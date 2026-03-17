package gg.runetrader;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

@Singleton
public class WikiPriceClient
{
	private static final Logger log = Logger.getLogger(WikiPriceClient.class.getName());
	private static final String WIKI_URL   = "https://prices.runescape.wiki/api/v1/osrs/latest";
	private static final String USER_AGENT = "RuneTrader GE Sync Plugin - runetrader.gg";
	private static final long   CACHE_TTL  = 30_000L;

	private final OkHttpClient httpClient;
	private final Gson gson;

	private final Map<Integer, long[]> cache = new HashMap<>();
	private long lastFetchMs = 0;

	@Inject
	WikiPriceClient(OkHttpClient httpClient, Gson gson)
	{
		this.httpClient = httpClient;
		this.gson = gson;
	}

	/** Returns [high, low] for the given item ID, or null if unavailable. */
	public long[] getPrices(int itemId)
	{
		refreshIfStale();
		return cache.get(itemId);
	}

	/**
	 * Drift as a fraction — positive means our offer is behind the market.
	 * e.g. 0.03 = 3% off.
	 */
	public float getDriftPercent(int itemId, long offerPrice, boolean isBuy)
	{
		long[] prices = getPrices(itemId);
		if (prices == null || offerPrice <= 0) return 0f;
		long wikiPrice = isBuy ? prices[0] : prices[1];
		if (wikiPrice <= 0) return 0f;
		return isBuy
			? (float)(wikiPrice - offerPrice) / offerPrice
			: (float)(offerPrice - wikiPrice) / offerPrice;
	}

	/** Recommended relist price using beat-the-market logic. */
	public long getRelistPrice(int itemId, boolean isBuy)
	{
		long[] prices = getPrices(itemId);
		if (prices == null) return 0;
		long base = isBuy ? prices[0] : prices[1];
		long inc  = beatIncrement(base);
		return isBuy ? base + inc : Math.max(1, base - inc);
	}

	private long beatIncrement(long price)
	{
		if (price >= 10_000_000) return 10;
		if (price >= 1_000_000)  return 5;
		if (price >= 10_000)     return 3;
		return 2;
	}

	private void refreshIfStale()
	{
		if (System.currentTimeMillis() - lastFetchMs < CACHE_TTL) return;
		try
		{
			Request req = new Request.Builder()
				.url(WIKI_URL)
				.header("User-Agent", USER_AGENT)
				.build();
			try (Response res = httpClient.newCall(req).execute())
			{
				if (!res.isSuccessful() || res.body() == null) return;
				JsonObject root = gson.fromJson(res.body().string(), JsonObject.class);
				JsonObject data = root.getAsJsonObject("data");
				if (data == null) return;
				Map<Integer, long[]> fresh = new HashMap<>();
				for (String idStr : data.keySet())
				{
					try
					{
						int id = Integer.parseInt(idStr);
						JsonObject item = data.getAsJsonObject(idStr);
						long high = item.has("high") && !item.get("high").isJsonNull() ? item.get("high").getAsLong() : 0;
						long low  = item.has("low")  && !item.get("low").isJsonNull()  ? item.get("low").getAsLong()  : 0;
						fresh.put(id, new long[]{high, low});
					}
					catch (NumberFormatException ignored) {}
				}
				cache.clear();
				cache.putAll(fresh);
				lastFetchMs = System.currentTimeMillis();
			}
		}
		catch (IOException e) { log.warning("Wiki price fetch error: " + e.getMessage()); }
	}
}
