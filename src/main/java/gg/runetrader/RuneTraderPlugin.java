package gg.runetrader;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@PluginDescriptor(
	name = "RuneTrader GE Sync",
	description = "Syncs your Grand Exchange offers to RuneTrader.gg in real time",
	tags = {"runetrader", "ge", "grand exchange", "flipping", "merchant"}
)
public class RuneTraderPlugin extends Plugin
{
	private static final String SYNC_URL = "https://www.runetrader.gg/api/sync-offers";
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
	private static final int MAX_QUEUE_SIZE = 50;
	// Debounce: coalesce rapid partial-fill ticks into one request per slot
	private static final long DEBOUNCE_MS = 1500;

	@Inject
	private Client client;

	@Inject
	private RuneTraderConfig config;

	@Inject
	private OkHttpClient okHttpClient;

	// One pending payload per slot, flushed after DEBOUNCE_MS of inactivity
	private final Map<Integer, String> pendingPayloads = new HashMap<>();
	private final Map<Integer, ScheduledFuture<?>> debounceTimers = new HashMap<>();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

	// Failed requests queued for retry on reconnect (capped to avoid unbounded growth)
	private final List<String> failedQueue = new ArrayList<>();

	@Override
	protected void startUp()
	{
		log.info("RuneTrader GE Sync started");
	}

	@Override
	protected void shutDown()
	{
		for (ScheduledFuture<?> timer : debounceTimers.values())
		{
			timer.cancel(false);
		}
		if (!pendingPayloads.isEmpty())
		{
			flushPending();
		}
		debounceTimers.clear();
		pendingPayloads.clear();
		scheduler.shutdown();
		log.info("RuneTrader GE Sync stopped");
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		if (!isConfigured()) return;

		int slot = event.getSlot();
		var offer = event.getOffer();
		var state = offer.getState();

		// Terminal states fire once and are semantically important — send immediately
		boolean isTerminal = (state == net.runelite.api.GrandExchangeOfferState.BOUGHT
			|| state == net.runelite.api.GrandExchangeOfferState.SOLD
			|| state == net.runelite.api.GrandExchangeOfferState.CANCELLED_BUY
			|| state == net.runelite.api.GrandExchangeOfferState.CANCELLED_SELL
			|| state == net.runelite.api.GrandExchangeOfferState.EMPTY);

		String json;
		if (state == net.runelite.api.GrandExchangeOfferState.EMPTY)
		{
			json = "{\"slot\":" + slot + ",\"status\":\"EMPTY\","
				+ "\"itemId\":0,\"itemName\":\"\",\"offerType\":\"BUY\","
				+ "\"offerPrice\":0,\"qtyTotal\":0,\"qtyFilled\":0,\"spent\":0}";
		}
		else
		{
			json = buildOfferJson(slot, offer);
		}

		if (isTerminal)
		{
			cancelDebounce(slot);
			pendingPayloads.remove(slot);
			sendSync("[" + json + "]");
		}
		else
		{
			// BUYING / SELLING: debounce rapid partial-fill ticks
			pendingPayloads.put(slot, json);
			cancelDebounce(slot);
			ScheduledFuture<?> timer = scheduler.schedule(() -> {
				String payload = pendingPayloads.remove(slot);
				debounceTimers.remove(slot);
				if (payload != null)
				{
					sendSync("[" + payload + "]");
				}
			}, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
			debounceTimers.put(slot, timer);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN && !failedQueue.isEmpty())
		{
			log.info("RuneTrader: flushing {} queued requests", failedQueue.size());
			List<String> toRetry = new ArrayList<>(failedQueue);
			failedQueue.clear();
			for (String body : toRetry)
			{
				sendSync(body);
			}
		}
	}

	private String buildOfferJson(int slot, net.runelite.api.GrandExchangeOffer offer)
	{
		String offerType = (offer.getState() == net.runelite.api.GrandExchangeOfferState.BUYING
			|| offer.getState() == net.runelite.api.GrandExchangeOfferState.BOUGHT
			|| offer.getState() == net.runelite.api.GrandExchangeOfferState.CANCELLED_BUY)
			? "BUY" : "SELL";
		String status   = mapState(offer.getState());
		String itemName = getItemName(offer.getItemId());

		int fillPrice = (offer.getQuantitySold() > 0)
			? (int)(offer.getSpent() / offer.getQuantitySold())
			: offer.getPrice();

		return "{"
			+ "\"slot\":" + slot + ","
			+ "\"itemId\":" + offer.getItemId() + ","
			+ "\"itemName\":" + jsonString(itemName) + ","
			+ "\"offerType\":" + jsonString(offerType) + ","
			+ "\"offerPrice\":" + fillPrice + ","
			+ "\"spent\":" + offer.getSpent() + ","
			+ "\"qtyTotal\":" + offer.getTotalQuantity() + ","
			+ "\"qtyFilled\":" + offer.getQuantitySold() + ","
			+ "\"status\":" + jsonString(status)
			+ "}";
	}

	private void sendSync(String jsonBody)
	{
		String apiKey = config.apiKey().trim();
		if (apiKey.isEmpty()) return;

		Request request = new Request.Builder()
			.url(SYNC_URL)
			.header("Authorization", "Bearer " + apiKey)
			.header("Content-Type", "application/json")
			.post(RequestBody.create(JSON, jsonBody))
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("RuneTrader sync failed, queuing for retry: {}", e.getMessage());
				if (failedQueue.size() < MAX_QUEUE_SIZE)
				{
					failedQueue.add(jsonBody);
				}
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					if (!response.isSuccessful())
					{
						log.warn("RuneTrader sync returned {}", response.code());
						if (response.code() >= 500 && failedQueue.size() < MAX_QUEUE_SIZE)
						{
							failedQueue.add(jsonBody);
						}
					}
				}
				finally
				{
					response.close();
				}
			}
		});
	}

	private void flushPending()
	{
		List<String> payloads = new ArrayList<>(pendingPayloads.values());
		pendingPayloads.clear();
		for (String p : payloads)
		{
			sendSync("[" + p + "]");
		}
	}

	private void cancelDebounce(int slot)
	{
		ScheduledFuture<?> existing = debounceTimers.remove(slot);
		if (existing != null)
		{
			existing.cancel(false);
		}
	}

	private String mapState(net.runelite.api.GrandExchangeOfferState state)
	{
		switch (state)
		{
			case BUYING:         return "BUYING";
			case BOUGHT:         return "BOUGHT";
			case SELLING:        return "SELLING";
			case SOLD:           return "SOLD";
			case CANCELLED_BUY:  return "CANCELLED_BUY";
			case CANCELLED_SELL: return "CANCELLED_SELL";
			default:             return "EMPTY";
		}
	}

	private String getItemName(int itemId)
	{
		var composition = client.getItemDefinition(itemId);
		return composition != null ? composition.getName() : "Unknown";
	}

	private boolean isConfigured()
	{
		String key = config.apiKey();
		return config.syncEnabled() && key != null && !key.trim().isEmpty() && key.startsWith("rt_");
	}

	private String jsonString(String value)
	{
		if (value == null) return "\"\"";
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	@Provides
	RuneTraderConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RuneTraderConfig.class);
	}
}
