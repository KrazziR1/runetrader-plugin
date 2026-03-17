package gg.runetrader;

import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class RuneTraderDataStore
{
	private final Map<Integer, SlotData> slots = new ConcurrentHashMap<>();
	private final List<FlipRecommendation> recommendations = new ArrayList<>();
	private final Object recLock = new Object();

	public void setSlotData(int slot, SlotData data)
	{
		if (data == null) slots.remove(slot);
		else slots.put(slot, data);
	}

	public SlotData getSlotData(int slot) { return slots.get(slot); }

	public Map<Integer, SlotData> getAllSlots() { return new HashMap<>(slots); }

	public void clearSlots() { slots.clear(); }

	public void setRecommendations(List<FlipRecommendation> recs)
	{
		synchronized (recLock)
		{
			recommendations.clear();
			if (recs != null) recommendations.addAll(recs);
		}
	}

	public List<FlipRecommendation> getRecommendations()
	{
		synchronized (recLock) { return new ArrayList<>(recommendations); }
	}
}
