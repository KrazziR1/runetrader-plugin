package gg.runetrader;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

@ConfigGroup("runetrader")
public interface RuneTraderConfig extends Config
{
	@ConfigSection(
		name = "Account",
		description = "RuneTrader account settings",
		position = 0
	)
	String accountSection = "account";

	@ConfigSection(
		name = "Sync",
		description = "GE sync behaviour",
		position = 1
	)
	String syncSection = "sync";

	@ConfigSection(
		name = "Overlays",
		description = "In-game overlays and display",
		position = 2
	)
	String overlaySection = "overlays";

	@ConfigSection(
		name = "Recommendations",
		description = "Next flip recommendation panel",
		position = 3
	)
	String recommendationSection = "recommendations";

	@ConfigSection(
		name = "Keybinds",
		description = "Keyboard shortcuts",
		position = 4
	)
	String keybindSection = "keybinds";

	// ── Account ───────────────────────────────────────────────────────────────

	@ConfigItem(
		keyName = "apiKey",
		name = "API Key",
		description = "Your RuneTrader.gg API key. Generate one at runetrader.gg in Settings.",
		secret = true,
		section = accountSection,
		position = 0
	)
	default String apiKey()
	{
		return "";
	}

	// ── Sync ──────────────────────────────────────────────────────────────────

	@ConfigItem(
		keyName = "syncEnabled",
		name = "Enable sync",
		description = "Toggle GE syncing on or off without removing your API key.",
		section = syncSection,
		position = 0
	)
	default boolean syncEnabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncPaused",
		name = "Pause sync",
		description = "Stops all GE data from being sent to RuneTrader. Use when buying items for personal use.",
		section = syncSection,
		position = 1
	)
	default boolean syncPaused()
	{
		return false;
	}

	@ConfigItem(
		keyName = "syncPaused",
		name = "Pause sync",
		description = "Stops all GE data from being sent to RuneTrader.",
		section = syncSection,
		position = 1
	)
	void setSyncPaused(boolean paused);

	@ConfigItem(
		keyName = "autoResumeMinutes",
		name = "Auto-resume after (minutes)",
		description = "Automatically resume sync after this many minutes. Set to 0 to disable.",
		section = syncSection,
		position = 2
	)
	default int autoResumeMinutes()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "showPausedOverlay",
		name = "Show paused reminder on GE screen",
		description = "Shows RT PAUSED text on the GE interface when sync is paused.",
		section = syncSection,
		position = 3
	)
	default boolean showPausedOverlay()
	{
		return true;
	}

	// ── Overlays ──────────────────────────────────────────────────────────────

	@ConfigItem(
		keyName = "showBuyLimitCountdowns",
		name = "Buy limit countdowns",
		description = "Shows a countdown to when each slot's 4-hour buy limit resets.",
		section = overlaySection,
		position = 0
	)
	default boolean showBuyLimitCountdowns()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showDriftAlerts",
		name = "Drift alerts",
		description = "Highlights slots where your offer price has drifted from the current market price.",
		section = overlaySection,
		position = 1
	)
	default boolean showDriftAlerts()
	{
		return true;
	}

	@ConfigItem(
		keyName = "driftThresholdPercent",
		name = "Drift alert threshold (%)",
		description = "Minimum drift percentage before showing a warning. Default 3%.",
		section = overlaySection,
		position = 2
	)
	default int driftThresholdPercent()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "showRelistPrice",
		name = "Show relist price on drifted slots",
		description = "Displays the recommended relist price on drifted slots.",
		section = overlaySection,
		position = 3
	)
	default boolean showRelistPrice()
	{
		return true;
	}

	// ── Recommendations ───────────────────────────────────────────────────────

	@ConfigItem(
		keyName = "showRecommendationPanel",
		name = "Show recommendation panel",
		description = "Shows the RuneTrader next-flip recommendation panel.",
		section = recommendationSection,
		position = 0
	)
	default boolean showRecommendationPanel()
	{
		return true;
	}

	@ConfigItem(
		keyName = "clickToFill",
		name = "Click-to-fill prices and quantities",
		description = "Clicking a suggested price types it into the GE field. You still click Confirm yourself.",
		section = recommendationSection,
		position = 1
	)
	default boolean clickToFill()
	{
		return false;
	}

	@ConfigItem(
		keyName = "recommendationCount",
		name = "Number of recommendations",
		description = "How many flip recommendations to show (1-5).",
		section = recommendationSection,
		position = 2
	)
	default int recommendationCount()
	{
		return 3;
	}

	// ── Keybinds ──────────────────────────────────────────────────────────────

	@ConfigItem(
		keyName = "pauseKeybind",
		name = "Toggle sync pause",
		description = "Hotkey to toggle GE sync pause on/off. Default: Shift+P",
		section = keybindSection,
		position = 0
	)
	default Keybind pauseKeybind()
	{
		return new Keybind(KeyEvent.VK_P, InputEvent.SHIFT_DOWN_MASK);
	}
}
