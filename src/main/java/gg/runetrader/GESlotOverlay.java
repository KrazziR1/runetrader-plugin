package gg.runetrader;

import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.*;

public class GESlotOverlay extends Overlay
{
	private static final Color COLOR_PAUSED      = new Color(255, 170, 0, 220);
	private static final Color COLOR_AMBER       = new Color(243, 156, 18, 220);
	private static final Color COLOR_RED         = new Color(231, 76, 60, 220);
	private static final Color COLOR_COUNTDOWN   = new Color(200, 200, 200, 220);
	private static final Color COLOR_RESET       = new Color(46, 204, 113, 220);
	private static final Color BG                = new Color(0, 0, 0, 160);

	private final Client client;
	private final RuneTraderConfig config;
	private final RuneTraderDataStore dataStore;

	@Inject
	GESlotOverlay(Client client, RuneTraderConfig config, RuneTraderDataStore dataStore)
	{
		this.client    = client;
		this.config    = config;
		this.dataStore = dataStore;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (config.syncPaused() && config.showPausedOverlay())
		{
			renderPausedBanner(g);
		}

		for (int slot = 0; slot < 8; slot++)
		{
			Widget w = getSlotWidget(slot);
			if (w == null || w.isHidden()) continue;
			Rectangle bounds = w.getBounds();
			if (bounds == null) continue;

			SlotData sd = dataStore.getSlotData(slot);

			if (config.showBuyLimitCountdowns() && sd != null && sd.buyStartedAt != null)
			{
				renderCountdown(g, bounds, sd);
			}

			if (config.showDriftAlerts() && sd != null && sd.hasDrift()
				&& Math.abs(sd.driftPercent) >= config.driftThresholdPercent())
			{
				renderDriftBadge(g, bounds, sd);
			}
		}
		return null;
	}

	// ── Paused banner ──────────────────────────────────────────────────────────

	private void renderPausedBanner(Graphics2D g)
	{
		Widget ge = client.getWidget(WidgetInfo.GRAND_EXCHANGE_WINDOW_CONTAINER);
		if (ge == null || ge.isHidden()) return;
		Rectangle b = ge.getBounds();
		if (b == null) return;

		String text = "RT PAUSED";
		g.setFont(new Font("Arial", Font.BOLD, 11));
		FontMetrics fm = g.getFontMetrics();
		int tw = fm.stringWidth(text), th = fm.getHeight(), pad = 5;
		int x = b.x + b.width - tw - pad * 2 - 8;
		int y = b.y + 8;

		g.setColor(BG);
		g.fillRoundRect(x, y, tw + pad * 2, th + pad, 8, 8);
		g.setColor(COLOR_PAUSED);
		g.drawRoundRect(x, y, tw + pad * 2, th + pad, 8, 8);
		g.drawString(text, x + pad, y + th - 2);
	}

	// ── Buy limit countdown ────────────────────────────────────────────────────

	private void renderCountdown(Graphics2D g, Rectangle b, SlotData sd)
	{
		long remaining = sd.secondsUntilLimitReset();
		String label;
		Color color;

		if (remaining <= 0)
		{
			label = "READY";
			color = COLOR_RESET;
		}
		else
		{
			long h = remaining / 3600, m = (remaining % 3600) / 60;
			label = h > 0 ? h + "h " + String.format("%02d", m) + "m" : m + "m";
			color = remaining < 1800 ? COLOR_AMBER : COLOR_COUNTDOWN;
		}

		g.setFont(new Font("Arial", Font.PLAIN, 10));
		FontMetrics fm = g.getFontMetrics();
		int tw = fm.stringWidth(label), th = fm.getHeight(), pad = 3;
		int x = b.x + b.width - tw - pad * 2 - 2;
		int y = b.y + b.height - th - pad - 2;

		g.setColor(BG);
		g.fillRoundRect(x - 1, y - 1, tw + pad * 2 + 2, th + pad, 4, 4);
		g.setColor(color);
		g.drawString(label, x + pad, y + th - 2);
	}

	// ── Drift badge ────────────────────────────────────────────────────────────

	private void renderDriftBadge(Graphics2D g, Rectangle b, SlotData sd)
	{
		boolean severe  = Math.abs(sd.driftPercent) >= 5f;
		Color badgeColor = severe ? COLOR_RED : COLOR_AMBER;

		String driftLabel  = String.format("%.1f%% off", Math.abs(sd.driftPercent));
		String relistLabel = (config.showRelistPrice() && sd.relistPrice > 0)
			? "-> " + formatGP(sd.relistPrice) : null;

		g.setFont(new Font("Arial", Font.BOLD, 10));
		FontMetrics fm = g.getFontMetrics();
		int pad = 4, lh = fm.getHeight();
		int maxW = Math.max(fm.stringWidth(driftLabel),
			relistLabel != null ? fm.stringWidth(relistLabel) : 0);
		int boxW = maxW + pad * 2;
		int boxH = lh * (relistLabel != null ? 2 : 1) + pad * 2;
		int x = b.x + pad, y = b.y + pad;

		g.setColor(new Color(0, 0, 0, 180));
		g.fillRoundRect(x, y, boxW, boxH, 6, 6);
		g.setColor(badgeColor);
		g.drawRoundRect(x, y, boxW, boxH, 6, 6);
		g.drawString(driftLabel, x + pad, y + lh - 1);

		if (relistLabel != null)
		{
			g.setFont(new Font("Arial", Font.PLAIN, 10));
			g.setColor(new Color(badgeColor.getRed(), badgeColor.getGreen(), badgeColor.getBlue(), 190));
			g.drawString(relistLabel, x + pad, y + lh * 2 - 1);
		}

		if (severe)
		{
			g.setColor(new Color(231, 76, 60, 60));
			g.setStroke(new BasicStroke(2));
			g.drawRect(b.x, b.y, b.width, b.height);
		}
	}

	// ── Helpers ────────────────────────────────────────────────────────────────

	private Widget getSlotWidget(int slot)
	{
		// GE slots sit inside widget group 465 (GrandExchangeWindow)
		Widget ge = client.getWidget(465, 0);
		if (ge == null) return null;
		Widget[] children = ge.getChildren();
		if (children == null || slot >= children.length) return null;
		return children[slot];
	}

	private String formatGP(long gp)
	{
		if (gp >= 1_000_000) return String.format("%.1fM", gp / 1_000_000.0);
		if (gp >= 1_000)     return String.format("%.0fK", gp / 1_000.0);
		return String.valueOf(gp);
	}
}
