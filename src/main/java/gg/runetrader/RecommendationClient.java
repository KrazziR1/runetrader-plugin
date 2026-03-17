package gg.runetrader;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

@Singleton
public class RecommendationClient
{
	private static final Logger log = Logger.getLogger(RecommendationClient.class.getName());
	private static final String PICKS_URL  = "https://www.runetrader.gg/api/plugin/picks";
	private static final String USER_AGENT = "RuneTrader GE Sync Plugin - runetrader.gg";

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final RuneTraderConfig config;

	@Inject
	RecommendationClient(OkHttpClient httpClient, Gson gson, RuneTraderConfig config)
	{
		this.httpClient = httpClient;
		this.gson = gson;
		this.config = config;
	}

	public List<FlipRecommendation> fetchRecommendations()
	{
		List<FlipRecommendation> results = new ArrayList<>();
		String apiKey = config.apiKey().trim();
		if (apiKey.isEmpty()) return results;

		String url = PICKS_URL + "?limit=" + config.recommendationCount();
		Request req = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + apiKey)
			.header("User-Agent", USER_AGENT)
			.build();

		try (Response res = httpClient.newCall(req).execute())
		{
			if (!res.isSuccessful() || res.body() == null) return results;
			JsonObject root = gson.fromJson(res.body().string(), JsonObject.class);
			JsonArray picks = root.has("picks") ? root.getAsJsonArray("picks") : null;
			if (picks == null) return results;

			for (int i = 0; i < picks.size(); i++)
			{
				JsonObject o = picks.get(i).getAsJsonObject();
				FlipRecommendation r = new FlipRecommendation();
				r.itemId             = getInt(o, "id");
				r.itemName           = getString(o, "name");
				r.suggestedBuyPrice  = getLong(o, "suggestedBuyPrice");
				r.suggestedSellPrice = getLong(o, "suggestedSellPrice");
				r.buyLimit           = getInt(o, "buyLimit");
				r.suggestedQty       = getInt(o, "suggestedQty");
				r.margin             = getLong(o, "margin");
				r.roi                = getDouble(o, "roi");
				r.gpPerFlip          = getLong(o, "gpPerFlip");
				r.volume             = getInt(o, "volume");
				r.score              = getInt(o, "score");
				r.dataAgeMinutes     = getInt(o, "dataAgeMinutes");
				results.add(r);
			}
		}
		catch (IOException e) { log.warning("Picks fetch error: " + e.getMessage()); }
		return results;
	}

	private String getString(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
	private int    getInt(JsonObject o, String k)    { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt()    : 0; }
	private long   getLong(JsonObject o, String k)   { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong()   : 0L; }
	private double getDouble(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : 0.0; }
}
