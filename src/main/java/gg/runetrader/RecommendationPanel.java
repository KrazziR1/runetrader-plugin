package gg.runetrader;

import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.inject.Inject;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

public class RecommendationPanel extends PluginPanel
{
	private static final Color GOLD      = new Color(201, 168, 76);
	private static final Color BG        = new Color(14, 18, 24);
	private static final Color BG2       = new Color(22, 28, 38);
	private static final Color BG3       = new Color(30, 40, 55);
	private static final Color TEXT      = new Color(232, 232, 232);
	private static final Color TEXT_DIM  = new Color(120, 140, 160);
	private static final Color GREEN     = new Color(46, 204, 113);
	private static final Color AMBER     = new Color(243, 156, 18);

	private final RuneTraderPlugin plugin;
	private final RuneTraderConfig config;
	private final RuneTraderDataStore dataStore;

	private JLabel statusLabel;
	private JButton pauseBtn;
	private JPanel recContainer;

	@Inject
	RecommendationPanel(RuneTraderPlugin plugin, RuneTraderConfig config, RuneTraderDataStore dataStore)
	{
		super(false);
		this.plugin    = plugin;
		this.config    = config;
		this.dataStore = dataStore;

		setLayout(new BorderLayout(0, 8));
		setBackground(BG);
		setBorder(new EmptyBorder(10, 8, 10, 8));

		add(buildHeader(), BorderLayout.NORTH);
		add(buildRecContainer(), BorderLayout.CENTER);
		add(buildPauseButton(), BorderLayout.SOUTH);
	}

	// ── Header ─────────────────────────────────────────────────────────────────

	private JPanel buildHeader()
	{
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(BG);

		JLabel title = new JLabel("RuneTrader");
		title.setFont(FontManager.getRunescapeBoldFont().deriveFont(14f));
		title.setForeground(GOLD);

		statusLabel = new JLabel("● Live");
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(GREEN);

		header.add(title, BorderLayout.WEST);
		header.add(statusLabel, BorderLayout.EAST);
		return header;
	}

	// ── Pause button ────────────────────────────────────────────────────────────

	private JPanel buildPauseButton()
	{
		JPanel wrap = new JPanel(new BorderLayout(0, 4));
		wrap.setBackground(BG);

		pauseBtn = new JButton(config.syncPaused() ? "▶  Resume sync" : "⏸  Pause sync");
		pauseBtn.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD, 11f));
		styleButton(pauseBtn, config.syncPaused());
		pauseBtn.addActionListener(e -> plugin.toggleSyncPause());

		JLabel hint = new JLabel("Shift+P to toggle from anywhere");
		hint.setFont(FontManager.getRunescapeSmallFont().deriveFont(9f));
		hint.setForeground(TEXT_DIM);
		hint.setHorizontalAlignment(SwingConstants.CENTER);

		wrap.add(pauseBtn, BorderLayout.NORTH);
		wrap.add(hint, BorderLayout.SOUTH);
		return wrap;
	}

	// ── Recommendations ─────────────────────────────────────────────────────────

	private JPanel buildRecContainer()
	{
		recContainer = new JPanel();
		recContainer.setLayout(new BoxLayout(recContainer, BoxLayout.Y_AXIS));
		recContainer.setBackground(BG);

		JLabel lbl = new JLabel("Loading...");
		lbl.setFont(FontManager.getRunescapeSmallFont());
		lbl.setForeground(TEXT_DIM);
		recContainer.add(lbl);
		return recContainer;
	}

	// ── Public update methods ───────────────────────────────────────────────────

	public void updatePauseState(boolean paused)
	{
		SwingUtilities.invokeLater(() -> {
			statusLabel.setText(paused ? "⏸ Paused" : "● Live");
			statusLabel.setForeground(paused ? AMBER : GREEN);
			pauseBtn.setText(paused ? "▶  Resume sync" : "⏸  Pause sync");
			styleButton(pauseBtn, paused);
		});
	}

	public void updateRecommendations()
	{
		SwingUtilities.invokeLater(() -> {
			recContainer.removeAll();

			JLabel header = new JLabel("NEXT FLIPS");
			header.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD, 10f));
			header.setForeground(TEXT_DIM);
			header.setBorder(new EmptyBorder(0, 0, 6, 0));
			recContainer.add(header);

			List<FlipRecommendation> recs = dataStore.getRecommendations();
			if (recs.isEmpty())
			{
				JLabel empty = new JLabel("<html><center>No recommendations.<br>"
					+ "Set your picks prefs on<br>runetrader.gg</center></html>");
				empty.setFont(FontManager.getRunescapeSmallFont());
				empty.setForeground(TEXT_DIM);
				empty.setBorder(new EmptyBorder(8, 0, 0, 0));
				recContainer.add(empty);
			}
			else
			{
				int count = Math.min(recs.size(), config.recommendationCount());
				for (int i = 0; i < count; i++)
				{
					recContainer.add(buildCard(recs.get(i), i == 0));
					if (i < count - 1) recContainer.add(Box.createVerticalStrut(6));
				}
			}

			recContainer.revalidate();
			recContainer.repaint();
		});
	}

	// ── Card ───────────────────────────────────────────────────────────────────

	private JPanel buildCard(FlipRecommendation rec, boolean isTop)
	{
		JPanel card = new JPanel(new BorderLayout(0, 5));
		card.setBackground(BG2);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, isTop ? 2 : 1, 0, 0,
				isTop ? GOLD : new Color(50, 70, 90)),
			new EmptyBorder(8, 10, 8, 10)));

		// Name + score row
		JPanel top = new JPanel(new BorderLayout());
		top.setBackground(BG2);
		JLabel name = new JLabel(rec.itemName);
		name.setFont(FontManager.getRunescapeBoldFont().deriveFont(12f));
		name.setForeground(isTop ? GOLD : TEXT);
		JLabel score = new JLabel(rec.score + "/100");
		score.setFont(FontManager.getRunescapeSmallFont());
		score.setForeground(rec.score >= 70 ? GREEN : TEXT_DIM);
		top.add(name, BorderLayout.WEST);
		top.add(score, BorderLayout.EAST);

		// Stats grid
		JPanel stats = new JPanel(new GridLayout(2, 2, 4, 2));
		stats.setBackground(BG2);
		stats.add(stat("Margin",  rec.marginFormatted(), GREEN));
		stats.add(stat("ROI",     String.format("%.1f%%", rec.roi), TEXT));
		stats.add(stat("GP/flip", rec.gpPerFlipFormatted(), TEXT));
		stats.add(stat("Fill",    rec.estimatedFillTime(), TEXT_DIM));

		// Price chips
		JPanel prices = new JPanel(new GridLayout(1, 2, 6, 0));
		prices.setBackground(BG2);
		prices.setBorder(new EmptyBorder(4, 0, 0, 0));
		prices.add(priceChip("Buy at",  rec.suggestedBuyPrice,  rec));
		prices.add(priceChip("Sell at", rec.suggestedSellPrice, rec));

		JPanel bottom = new JPanel(new BorderLayout());
		bottom.setBackground(BG2);
		bottom.add(prices, BorderLayout.CENTER);
		if (rec.dataAgeMinutes > 10)
		{
			JLabel warn = new JLabel("Data " + rec.dataAgeMinutes + "m old");
			warn.setFont(FontManager.getRunescapeSmallFont());
			warn.setForeground(AMBER);
			warn.setBorder(new EmptyBorder(3, 0, 0, 0));
			bottom.add(warn, BorderLayout.SOUTH);
		}

		card.add(top,    BorderLayout.NORTH);
		card.add(stats,  BorderLayout.CENTER);
		card.add(bottom, BorderLayout.SOUTH);
		return card;
	}

	private JLabel stat(String label, String value, Color valueColor)
	{
		JLabel l = new JLabel("<html><font color='#789'>" + label + "</font> " + value + "</html>");
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(valueColor);
		return l;
	}

	private JPanel priceChip(String label, long price, FlipRecommendation rec)
	{
		JPanel chip = new JPanel(new BorderLayout(0, 2));
		chip.setBackground(BG3);
		chip.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(new Color(50, 70, 90)),
			new EmptyBorder(5, 7, 5, 7)));

		JLabel lbl = new JLabel(label);
		lbl.setFont(FontManager.getRunescapeSmallFont());
		lbl.setForeground(TEXT_DIM);

		JLabel val = new JLabel(String.format("%,d", price));
		val.setFont(FontManager.getRunescapeBoldFont().deriveFont(11f));
		val.setForeground(TEXT);

		chip.add(lbl, BorderLayout.NORTH);
		chip.add(val, BorderLayout.SOUTH);

		if (config.clickToFill())
		{
			chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			chip.setBackground(new Color(30, 45, 65));
			chip.setToolTipText("Click to fill GE field — you still confirm");
			chip.addMouseListener(new MouseAdapter()
			{
				@Override public void mouseEntered(MouseEvent e) { chip.setBackground(new Color(40, 60, 90)); chip.repaint(); }
				@Override public void mouseExited(MouseEvent e)  { chip.setBackground(new Color(30, 45, 65)); chip.repaint(); }
				@Override public void mouseClicked(MouseEvent e) { plugin.typeValueIntoGE(String.valueOf(price)); }
			});
		}

		return chip;
	}

	// ── Helpers ────────────────────────────────────────────────────────────────

	private void styleButton(JButton btn, boolean paused)
	{
		btn.setBackground(paused ? new Color(243, 156, 18, 30) : BG2);
		btn.setForeground(paused ? AMBER : TEXT_DIM);
		btn.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(paused ? new Color(243, 156, 18, 100) : new Color(60, 80, 100)),
			new EmptyBorder(7, 10, 7, 10)));
		btn.setFocusPainted(false);
		btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
	}
}
