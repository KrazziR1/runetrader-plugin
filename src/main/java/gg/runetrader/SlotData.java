package gg.runetrader;

import java.time.Instant;

public class SlotData
{
	public int slot;
	public int itemId;
	public String itemName;
	public long offerPrice;
	public String offerType; // "BUY" or "SELL"
	public Instant buyStartedAt;
	public long wikiPrice;
	public long relistPrice;
	public float driftPercent;

	public boolean hasDrift()
	{
		return Math.abs(driftPercent) >= 0.5f && wikiPrice > 0;
	}

	public boolean isLimitReady()
	{
		if (buyStartedAt == null) return false;
		return Instant.now().getEpochSecond() - buyStartedAt.getEpochSecond() >= 4 * 60 * 60;
	}

	public long secondsUntilLimitReset()
	{
		if (buyStartedAt == null) return 0;
		long elapsed = Instant.now().getEpochSecond() - buyStartedAt.getEpochSecond();
		return Math.max(0, (4 * 60 * 60) - elapsed);
	}
}
