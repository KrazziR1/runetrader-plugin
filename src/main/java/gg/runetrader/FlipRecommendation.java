package gg.runetrader;

public class FlipRecommendation
{
	public int itemId;
	public String itemName;
	public long suggestedBuyPrice;
	public long suggestedSellPrice;
	public int buyLimit;
	public int suggestedQty;
	public long margin;
	public double roi;
	public long gpPerFlip;
	public int volume;
	public int score;
	public int dataAgeMinutes;

	public String marginFormatted()
	{
		if (margin >= 1_000_000) return String.format("%.1fM", margin / 1_000_000.0);
		if (margin >= 1_000)     return String.format("%.0fK", margin / 1_000.0);
		return String.valueOf(margin);
	}

	public String gpPerFlipFormatted()
	{
		if (gpPerFlip >= 1_000_000) return String.format("%.1fM gp", gpPerFlip / 1_000_000.0);
		if (gpPerFlip >= 1_000)     return String.format("%.0fK gp", gpPerFlip / 1_000.0);
		return gpPerFlip + " gp";
	}

	public String estimatedFillTime()
	{
		if (volume >= 100_000) return "~5 min";
		if (volume >= 20_000)  return "~30 min";
		if (volume >= 5_000)   return "~2 hr";
		return "~4 hr";
	}
}
