package gg.runetrader;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("runetrader")
public interface RuneTraderConfig extends Config
{
	@ConfigItem(
		keyName = "apiKey",
		name = "API Key",
		description = "Your RuneTrader.gg API key. Generate one at runetrader.gg in Settings.",
		secret = true,
		position = 1
	)
	default String apiKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "syncEnabled",
		name = "Enable Sync",
		description = "Toggle GE syncing on or off without removing your API key.",
		position = 2
	)
	default boolean syncEnabled()
	{
		return true;
	}
}
