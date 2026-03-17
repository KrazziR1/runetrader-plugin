package gg.runetrader;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.ImageUtil;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Instant;
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
	private static final String SYNC_URL       = "https://www.runetrader.gg/api/sync-offers";
	private static final String PAUSE_URL      = "https://www.runetrader.gg/api/sync-pause";
	private static final String PICKS_URL      = "https://www.runetrader.gg/api/plugin/picks";
	private static final MediaType JSON        = MediaType.get("application/json; charset=utf-8");
	private static final int MAX_QUEUE_SIZE    = 50;
	private static final long DEBOUNCE_MS      = 1500;

	// ── Injected ──────────────────────────────────────────────────────────────

	@Inject private Client client;
	@Inject private RuneTraderConfig config;
	@Inject private ConfigManager configManager;
	@Inject private OkHttpClient okHttpClient;
	@Inject private KeyManager keyManager;
	@Inject private OverlayManager overlayManager;
	@Inject private ClientToolbar clientToolbar;

	@Inject private RuneTraderDataStore dataStore;
	@Inject private WikiPriceClient wikiPriceClient;
	@Inject private RecommendationClient recommendationClient;
	@Inject private GESlotOverlay geSlotOverlay;
	@Inject private RecommendationPanel panel;

	// ── State ─────────────────────────────────────────────────────────────────

	private final Map<Integer, String> pendingPayloads  = new HashMap<>();
	private final Map<Integer, ScheduledFuture<?>> debounceTimers = new HashMap<>();
	private final List<String> failedQueue              = new ArrayList<>();
	private final ScheduledExecutorService scheduler    = Executors.newScheduledThreadPool(2);

	private NavigationButton navButton;
	private ScheduledFuture<?> wikiPollFuture;
	private ScheduledFuture<?> recPollFuture;
	private ScheduledFuture<?> autoResumeFuture;

	// ── Keybind ───────────────────────────────────────────────────────────────

	private final HotkeyListener pauseHotkeyListener = new HotkeyListener(() -> config.pauseKeybind())
	{
		@Override
		public void hotkeyPressed()
		{
			toggleSyncPause();
		}
	};

	// ── Lifecycle ─────────────────────────────────────────────────────────────

	@Override
	protected void startUp()
	{
		overlayManager.add(geSlotOverlay);
		keyManager.registerKeyListener(pauseHotkeyListener);

		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/icon.png");
		navButton = NavigationButton.builder()
			.tooltip("RuneTrader")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		startWikiPoll();
		startRecommendationPoll();

		log.info("RuneTrader GE Sync started");
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(geSlotOverlay);
		keyManager.unregisterKeyListener(pauseHotkeyListener);
		clientToolbar.removeNavigation(navButton);

		for (ScheduledFuture<?> timer : debounceTimers.values())
		{
			timer.cancel(false);
		}
		if (!pendingPayloads.isEmpty())
		{
			flushPending();
		}

		scheduler.shutdownNow();
		dataStore.clearSlots();
		log.info("RuneTrader GE Sync stopped");
	}

	@Provides
	RuneTraderConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RuneTraderConfig.class);
	}

	// ── GE offer events ───────────────────────────────────────────────────────

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		if (!isConfigured()) return;

		// Skip sync entirely when paused — this is the core pause feature
		if (config.syncPaused()) return;

		int slot = event.getSlot();
		GrandExchangeOffer offer = event.getOffer();
		GrandExchangeOfferState state = offer.getState();

		boolean isTerminal = (state == GrandExchangeOfferState.BOUGHT
			|| state == GrandExchangeOfferState.SOLD
			|| state == GrandExchangeOfferState.CANCELLED_BUY
			|| state == GrandExchangeOfferState.CANCELLED_SELL
			|| state == GrandExchangeOfferState.EMPTY);

		// Update local slot data for overlays regardless of sync state
		updateSlotData(slot, offer);

		String json;
		if (state == GrandExchangeOfferState.EMPTY)
		{
			dataStore.setSlotData(slot, null);
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
			pendingPayloads.put(slot, json);
			cancelDebounce(slot);
			ScheduledFuture<?> timer = scheduler.schedule(() -> {
				String payload = pendingPayloads.remove(slot);
				debounceTimers.remove(slot);
				if (payload != null) sendSync("[" + payload + "]");
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
			for (String body : toRetry) sendSync(body);
		}
	}

	// ── Pause / resume ────────────────────────────────────────────────────────

	/**
	 * Toggles sync pause. Called from keybind and panel button.
	 */
	public void toggleSyncPause()
	{
		boolean nowPaused = !config.syncPaused();
		configManager.setConfiguration("runetrader", "syncPaused", nowPaused);

		if (nowPaused)
		{
			sendChatMessage("[RuneTrader] GE sync paused — activity not tracked. Shift+P to resume.");
			scheduleAutoResume();
		}
		else
		{
			cancelAutoResume();
			sendChatMessage("[RuneTrader] GE sync resumed.");
		}

		// Tell the website so it can show/hide the paused banner
		scheduler.execute(() -> sendPauseState(nowPaused));
		panel.updatePauseState(nowPaused);
	}

	private void scheduleAutoResume()
	{
		cancelAutoResume();
		int minutes = config.autoResumeMinutes();
		if (minutes <= 0) return;

		autoResumeFuture = scheduler.schedule(() -> {
			if (config.syncPaused())
			{
				log.info("RuneTrader: auto-resuming after {} min", minutes);
				toggleSyncPause();
			}
		}, minutes, TimeUnit.MINUTES);
	}

	private void cancelAutoResume()
	{
		if (autoResumeFuture != null && !autoResumeFuture.isDone())
		{
			autoResumeFuture.cancel(false);
			autoResumeFuture = null;
		}
	}

	// ── Click-to-fill ─────────────────────────────────────────────────────────

	/**
	 * Types a price/quantity value into the focused GE input field.
	 * Uses Robot keystrokes — player still clicks Confirm themselves.
	 * Only active when config.clickToFill() is true.
	 */
	public void typeValueIntoGE(String value)
	{
		if (!config.clickToFill()) return;

		scheduler.execute(() ->
		{
			try
			{
				java.awt.Robot robot = new java.awt.Robot();
				// Select all existing text first
				robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);
				robot.keyPress(java.awt.event.KeyEvent.VK_A);
				robot.keyRelease(java.awt.event.KeyEvent.VK_A);
				robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);
				Thread.sleep(30);

				// Type each digit
				for (char c : value.toCharArray())
				{
					int keyCode = java.awt.event.KeyEvent.getExtendedKeyCodeForChar(c);
					robot.keyPress(keyCode);
					robot.keyRelease(keyCode);
					Thread.sleep(10);
				}
			}
			catch (Exception e)
			{
				log.warn("typeValueIntoGE error: {}", e.getMessage());
			}
		});
	}

	// ── Slot data for overlays ────────────────────────────────────────────────

	private void updateSlotData(int slot, GrandExchangeOffer offer)
	{
		if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY)
		{
			dataStore.setSlotData(slot, null);
			return;
		}

		SlotData slotData = new SlotData();
		slotData.slot      = slot;
		slotData.itemId    = offer.getItemId();
		slotData.itemName  = getItemName(offer.getItemId());
		slotData.offerPrice = offer.getPrice();
		slotData.offerType  = isBuyOffer(offer.getState()) ? "BUY" : "SELL";

		// Preserve buyStartedAt — only set it when offer first placed (qty filled = 0)
		SlotData existing = dataStore.getSlotData(slot);
		if (isBuyOffer(offer.getState()) && offer.getQuantitySold() == 0)
		{
			slotData.buyStartedAt = Instant.now();
		}
		else if (existing != null)
		{
			slotData.buyStartedAt = existing.buyStartedAt;
		}

		// Calculate drift from Wiki prices
		long[] wikiPrices = wikiPriceClient.getPrices(offer.getItemId());
		if (wikiPrices != null)
		{
			boolean isBuy = isBuyOffer(offer.getState());
			slotData.wikiPrice    = isBuy ? wikiPrices[0] : wikiPrices[1];
			slotData.driftPercent = wikiPriceClient.getDriftPercent(
				offer.getItemId(), offer.getPrice(), isBuy) * 100f;
			slotData.relistPrice  = wikiPriceClient.getRelistPrice(
				offer.getItemId(), isBuy);
		}

		dataStore.setSlotData(slot, slotData);
	}

	// ── Background polls ──────────────────────────────────────────────────────

	private void startWikiPoll()
	{
		wikiPollFuture = scheduler.scheduleAtFixedRate(() -> {
			try
			{
				// Refresh drift on all active slots
				dataStore.getAllSlots().forEach((slot, sd) -> {
					if (sd.itemId <= 0 || sd.offerPrice <= 0) return;
					long[] prices = wikiPriceClient.getPrices(sd.itemId);
					if (prices == null) return;

					boolean isBuy = "BUY".equals(sd.offerType);
					sd.wikiPrice    = isBuy ? prices[0] : prices[1];
					sd.driftPercent = wikiPriceClient.getDriftPercent(
						sd.itemId, sd.offerPrice, isBuy) * 100f;
					sd.relistPrice  = wikiPriceClient.getRelistPrice(sd.itemId, isBuy);
					dataStore.setSlotData(slot, sd);
				});
			}
			catch (Exception e) { log.warn("Wiki poll error", e); }
		}, 0, 30, TimeUnit.SECONDS);
	}

	private void startRecommendationPoll()
	{
		recPollFuture = scheduler.scheduleAtFixedRate(() -> {
			if (!config.showRecommendationPanel() || !isConfigured()) return;
			try
			{
				List<FlipRecommendation> recs = recommendationClient.fetchRecommendations();
				dataStore.setRecommendations(recs);
				panel.updateRecommendations();
			}
			catch (Exception e) { log.warn("Recommendation poll error", e); }
		}, 0, 60, TimeUnit.SECONDS);
	}

	// ── HTTP ──────────────────────────────────────────────────────────────────

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
				log.warn("RuneTrader sync failed, queuing: {}", e.getMessage());
				if (failedQueue.size() < MAX_QUEUE_SIZE) failedQueue.add(jsonBody);
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
							failedQueue.add(jsonBody);
					}
				}
				finally { response.close(); }
			}
		});
	}

	/**
	 * Notifies the website that pause state changed so the banner updates.
	 */
	private void sendPauseState(boolean paused)
	{
		String apiKey = config.apiKey().trim();
		if (apiKey.isEmpty()) return;

		String body = "{\"paused\":" + paused + "}";
		Request request = new Request.Builder()
			.url(PAUSE_URL)
			.header("Authorization", "Bearer " + apiKey)
			.header("Content-Type", "application/json")
			.post(RequestBody.create(JSON, body))
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("RuneTrader pause sync failed: {}", e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				response.close();
			}
		});
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private String buildOfferJson(int slot, GrandExchangeOffer offer)
	{
		String offerType = isBuyOffer(offer.getState()) ? "BUY" : "SELL";
		String status    = mapState(offer.getState());
		String itemName  = getItemName(offer.getItemId());

		// Feature 5: actual fill price via getSpent() / getQuantitySold()
		int fillPrice = (offer.getQuantitySold() > 0)
			? (int) (offer.getSpent() / offer.getQuantitySold())
			: offer.getPrice();

		return "{"
			+ "\"slot\":"      + slot + ","
			+ "\"itemId\":"    + offer.getItemId() + ","
			+ "\"itemName\":"  + jsonString(itemName) + ","
			+ "\"offerType\":" + jsonString(offerType) + ","
			+ "\"offerPrice\":" + offer.getPrice() + ","
			+ "\"fillPrice\":"  + fillPrice + ","
			+ "\"spent\":"      + offer.getSpent() + ","
			+ "\"qtyTotal\":"   + offer.getTotalQuantity() + ","
			+ "\"qtyFilled\":"  + offer.getQuantitySold() + ","
			+ "\"status\":"     + jsonString(status)
			+ "}";
	}

	private void flushPending()
	{
		List<String> payloads = new ArrayList<>(pendingPayloads.values());
		pendingPayloads.clear();
		for (String p : payloads) sendSync("[" + p + "]");
	}

	private void cancelDebounce(int slot)
	{
		ScheduledFuture<?> existing = debounceTimers.remove(slot);
		if (existing != null) existing.cancel(false);
	}

	private boolean isBuyOffer(GrandExchangeOfferState state)
	{
		return state == GrandExchangeOfferState.BUYING
			|| state == GrandExchangeOfferState.BOUGHT
			|| state == GrandExchangeOfferState.CANCELLED_BUY;
	}

	private String mapState(GrandExchangeOfferState state)
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
		var def = client.getItemDefinition(itemId);
		return def != null ? def.getName() : "Unknown";
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

	private void sendChatMessage(String message)
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null);
	}
}
