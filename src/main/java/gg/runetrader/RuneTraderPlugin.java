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
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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

	@Inject
	private Client client;

	@Inject
	private RuneTraderConfig config;

	@Inject
	private OkHttpClient okHttpClient;

	// Failed requests are queued here and retried on reconnect
	private final List<String> failedQueue = new ArrayList<>();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

	@Override
	protected void startUp()
	{
		log.info("RuneTrader GE Sync started");
	}

	@Override
	protected void shutDown()
	{
		scheduler.shutdown();
		log.info("RuneTrader GE Sync stopped");
	}

	// ── Listen for GE offer changes ──────────────────────────
	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		if (!isConfigured()) return;

		var offer = event.getOffer();

		// Skip EMPTY slots — nothing to sync
		if (offer.getState() == net.runelite.api.GrandExchangeOfferState.EMPTY) return;

		String json = buildOfferJson(event.getSlot(), offer);
		sendSync("[" + json + "]");
	}

	// ── Flush failed queue when player logs back in ──────────
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

	// ── Build JSON for a single offer ────────────────────────
	private String buildOfferJson(int slot, net.runelite.api.GrandExchangeOffer offer)
	{
		// Map RuneLite's GrandExchangeOfferState to our API's expected values
		String offerType  = (offer.getState() == net.runelite.api.GrandExchangeOfferState.BUYING
			|| offer.getState() == net.runelite.api.GrandExchangeOfferState.BOUGHT
			|| offer.getState() == net.runelite.api.GrandExchangeOfferState.CANCELLED_BUY)
			? "BUY" : "SELL";
		String status     = mapState(offer.getState());
		String itemName   = getItemName(offer.getItemId());

		return "{"
			+ "\"slot\":"       + slot                      + ","
			+ "\"itemId\":"     + offer.getItemId()          + ","
			+ "\"itemName\":"   + jsonString(itemName)       + ","
			+ "\"offerType\":" + jsonString(offerType)      + ","
			+ "\"offerPrice\":" + offer.getPrice()           + ","
			+ "\"qtyTotal\":"   + offer.getTotalQuantity()   + ","
			+ "\"qtyFilled\":"  + offer.getQuantitySold()    + ","
			+ "\"status\":"     + jsonString(status)
			+ "}";
	}

	// ── Send to RuneTrader API ────────────────────────────────
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
				failedQueue.add(jsonBody);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				if (!response.isSuccessful())
				{
					log.warn("RuneTrader sync returned {}: queuing for retry", response.code());
					// Don't retry 401 (bad key) — would loop forever
					if (response.code() != 401)
					{
						failedQueue.add(jsonBody);
					}
				}
				response.close();
			}
		});
	}

	// ── Map GrandExchangeOfferState to our API status strings ─
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

	// ── Get item name from client cache ──────────────────────
	private String getItemName(int itemId)
	{
		var composition = client.getItemDefinition(itemId);
		return composition != null ? composition.getName() : "Unknown";
	}

	// ── Helpers ───────────────────────────────────────────────
	private boolean isConfigured()
	{
		String key = config.apiKey();
		return key != null && !key.trim().isEmpty() && key.startsWith("rt_");
	}

	private String jsonString(String value)
	{
		return "\"" + value.replace("\"", "\\\"") + "\"";
	}

	@Provides
	RuneTraderConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RuneTraderConfig.class);
	}
}
