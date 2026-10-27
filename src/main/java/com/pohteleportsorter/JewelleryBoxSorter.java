package com.pohteleportsorter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.FontTypeFace;
import net.runelite.api.Point;
import net.runelite.api.WidgetNode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.widgets.ItemQuantityMode;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.util.Text;

/**
 * Ornate jewellery box (interface 590) layouts.
 *
 * The game's six jewellery sections stay alive the whole time, because each teleport's click handler
 * looks its own text widget up. In the list layouts those sections are hidden, the first section's layer
 * becomes the scrolling list, and every row runs the original widget's own click handler.
 */
@Slf4j
@Singleton
public class JewelleryBoxSorter
{
	// poh_jewellery_box_init, run by the server when the box opens; lays out and fills the six sections
	private static final int SCRIPT_JEWELLERY_BOX_INIT = 1685;

	private static final int[] SECTIONS = {
		InterfaceID.PohJewelleryBox.DUELING,
		InterfaceID.PohJewelleryBox.GAMING,
		InterfaceID.PohJewelleryBox.COMBAT,
		InterfaceID.PohJewelleryBox.SKILLS,
		InterfaceID.PohJewelleryBox.WEALTH,
		InterfaceID.PohJewelleryBox.GLORY,
	};

	// Same values the game uses: p11 orange teleports, white on hover, thinbox colours, scroll speeds
	private static final int TELE_COLOUR = 0xff981f;
	private static final int HOVER_COLOUR = 0xffffff;
	private static final int TITLE_LOCKED_COLOUR = 0x7f7f7f;
	private static final int SUFFIX_COLOUR = 0x9f9f9f;
	private static final int TITLE_SECTION_COLOUR = 0xffffff;
	private static final int THINBOX_OUTER = 0x0e0e0c;
	private static final int THINBOX_INNER = 0x474745;
	private static final int WHEEL_STEP = 45;
	private static final int ARROW_STEP = 4;

	// Radio style, identical to the Portal Nexus selector
	private static final int RADIO_OFF = SpriteID.RadioButtons._0;
	private static final int RADIO_OFF_HOVER = SpriteID.RadioButtons._4;
	private static final int RADIO_ON = SpriteID.RadioButtons._2;
	private static final int RADIO_ON_HOVER = SpriteID.RadioButtons._6;
	private static final int LABEL_COLOUR = 0xff981f;

	// Layout. The game uses 10px outer margins, 40px for the title bar and 5px gaps between sections.
	private static final int MARGIN = 10;
	private static final int TOP = 40;
	private static final int GAP = 5;
	private static final int STRIP_HEIGHT = 24;
	private static final int RESERVE = STRIP_HEIGHT + GAP;
	private static final int SCROLLBAR_WIDTH = 16;
	private static final int MAX_WAIT_FRAMES = 100;

	private static final int HEADER_HEIGHT = 26;
	private static final int LIST_TOP_PAD = 4;
	private static final int TELE_ROW_HEIGHT = 18;
	private static final int ALPHA_ROW_HEIGHT = 20;
	private static final int GROUP_GAP = 4;
	private static final int TEXT_INDENT = 46;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private PohTeleportSorterConfig config;

	@Inject
	private PinStore pins;

	@Inject
	private TeleportSearch search;

	@Inject
	private TeleportHotkeys hotkeys;

	// The game's own text for each original teleport, captured when the box opens, so Wilderness styling can
	// be applied to and removed from the default grid
	private final java.util.Map<Widget, String> originalTexts = new java.util.IdentityHashMap<>();
	// Originals currently showing the game's "Please wait..." (a teleport in progress), to record each use once
	private final java.util.Set<Widget> waiting = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
	private final List<Widget> pinIcons = new ArrayList<>();
	private static final int RECENT_LIMIT = 50;

	private static class Group
	{
		String title;
		int titleColour;
		int itemId;
		final List<Widget> teleports = new ArrayList<>();
	}

	private static class Row
	{
		final Widget mine;
		final Widget original;
		final Tele tele;
		String lastSeen;

		Row(Widget mine, Widget original, Tele tele)
		{
			this.mine = mine;
			this.original = original;
			this.tele = tele;
			this.lastSeen = original.getText();
		}
	}

	// Selector strip along the bottom of the box
	private boolean selectorShown;
	private final List<Widget> stripWidgets = new ArrayList<>();

	// Captured from the game's layout: every section's position and size, and every teleport's row,
	// so the grid can be put back exactly as the game drew it
	private final List<Group> groups = new ArrayList<>();
	private Widget[] duelingOriginals = new Widget[0];
	private final java.util.Map<Widget, int[]> gameGeometry = new java.util.IdentityHashMap<>();

	// The jewellery box title bar's clear band and left inner edge, from the game's steelborder script (title
	// text box at y 6) and measured against the game: the divider starts at y 27, the border ends at x 5
	private static final TeleportSearch.Band SEARCH_BAND = new TeleportSearch.Band(5, 27 + TitleBar.EXTRA, 5);
	private final TitleBar titleBar = new TitleBar();
	// How far the title bar pushed the content down (0 when it wasn't made taller)
	private int headerExtra;

	// Our widgets
	private final Widget[] radios = new Widget[JewelleryBoxMode.values().length];
	private final Widget[] labels = new Widget[JewelleryBoxMode.values().length];
	private final List<Widget> stripes = new ArrayList<>();
	private final List<Widget> icons = new ArrayList<>();
	private final List<Widget> texts = new ArrayList<>();
	private final List<Widget> suffixes = new ArrayList<>();
	private final List<Widget> listBackground = new ArrayList<>();
	private Widget track, dragMiddle, dragTop, dragBottom, arrowUp, arrowDown;
	private final List<Row> rows = new ArrayList<>();

	private boolean listActive;
	private int listHeight;
	private int dragGrab;

	// ---------------------------------------------------------------- lifecycle

	public void shutDown()
	{
		clientThread.invokeLater(() ->
		{
			if (client.getWidget(InterfaceID.PohJewelleryBox.UNIVERSE) != null)
			{
				// With no strip and no taller title bar, Default puts the game's own grid back
				selectorShown = false;
				headerExtra = 0;
				titleBar.restore();
				apply(JewelleryBoxMode.DEFAULT);
				hideAll(stripWidgets);
				// Back to exactly the game's own text and every teleport visible
				for (Group g : groups)
				{
					for (Widget orig : g.teleports)
					{
						String base = originalTexts.get(orig);
						String now = orig.getText();
						if (base != null && now != null && !now.contains("Please wait"))
						{
							orig.setText(base);
						}
						orig.setHidden(false);
					}
				}
			}
			clearState();
		});
	}

	private void clearState()
	{
		groups.clear();
		duelingOriginals = new Widget[0];
		gameGeometry.clear();
		titleBar.forget();
		headerExtra = 0;
		java.util.Arrays.fill(radios, null);
		java.util.Arrays.fill(labels, null);
		stripes.clear();
		icons.clear();
		texts.clear();
		suffixes.clear();
		listBackground.clear();
		track = dragMiddle = dragTop = dragBottom = arrowUp = arrowDown = null;
		rows.clear();
		listActive = false;
		selectorShown = false;
		stripWidgets.clear();
		originalTexts.clear();
		waiting.clear();
		pinIcons.clear();
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() != SCRIPT_JEWELLERY_BOX_INIT)
		{
			return;
		}
		// Never touch the box while the game is still opening it: changing it at that point can stop it
		// opening at all. Wait until the window is attached to the screen with a real size, then apply.
		final int[] frames = {0};
		clientThread.invokeLater(() ->
		{
			if (!isBoxReady())
			{
				// Give up quietly after a while; the box is then left exactly as the game drew it
				return ++frames[0] > MAX_WAIT_FRAMES;
			}
			try
			{
				afterLayout();
			}
			catch (Throwable t)
			{
				log.error("Jewellery box: failed to apply layout, leaving the default box", t);
				try
				{
					showDefault();
				}
				catch (Throwable ignored)
				{
				}
			}
			return true;
		});
	}

	private boolean isAttached()
	{
		for (WidgetNode node : client.getComponentTable())
		{
			if (node.getId() == InterfaceID.POH_JEWELLERY_BOX)
			{
				return true;
			}
		}
		return false;
	}

	/** True once interface 590 is attached to the screen and the game has given it a size. */
	private boolean isBoxReady()
	{
		if (!isAttached())
		{
			return false;
		}
		Widget universe = client.getWidget(InterfaceID.PohJewelleryBox.UNIVERSE);
		Widget dueling = client.getWidget(InterfaceID.PohJewelleryBox.DUELING);
		return universe != null && dueling != null
			&& !universe.isHidden()
			&& universe.getWidth() > 0 && universe.getHeight() > 0
			&& dueling.getHeight() > 0;
	}

	private void afterLayout()
	{
		clearState();
		Widget universe = client.getWidget(InterfaceID.PohJewelleryBox.UNIVERSE);
		if (universe == null)
		{
			return;
		}

		capture();
		if (config.showJewellerySelector())
		{
			buildStrip(universe);
			selectorShown = true;
		}
		if (config.showSearch() && search.isAvailable())
		{
			Widget frame = client.getWidget(InterfaceID.PohJewelleryBox.FRAME);
			extendTitleBar(frame);
			search.attach(universe, frame, SEARCH_BAND, () -> apply(config.jewelleryBoxMode()));
		}
		apply(config.jewelleryBoxMode());
		registerHotkeys();
	}

	/** Tells the custom hotkeys which teleports this box has, each used exactly as clicking it would. */
	private void registerHotkeys()
	{
		List<TeleportHotkeys.Target> targets = new ArrayList<>();
		for (Group g : groups)
		{
			for (Widget orig : g.teleports)
			{
				targets.add(new TeleportHotkeys.Target(sortName(orig), gameKey(orig), () ->
				{
					search.stopTyping();
					runOriginal(orig);
				}));
			}
		}
		hotkeys.setTargets(TeleportHotkeys.Kind.JEWELLERY, targets, this::redrawLabels);
	}

	/** Redraws every teleport's text, after a custom hotkey changes. */
	private void redrawLabels()
	{
		if (client.getWidget(InterfaceID.PohJewelleryBox.UNIVERSE) == null || groups.isEmpty())
		{
			return;
		}
		if (listActive)
		{
			showList(config.jewelleryBoxMode(), true);
		}
		else
		{
			showDefault();
		}
	}

	/** The game's own key for a teleport, from the text the game gave it ("<col=ccccff>1:</col> Ring of dueling"). */
	private String gameKey(Widget orig)
	{
		String base = Text.removeTags(originalTexts.getOrDefault(orig, ""));
		int colon = base.indexOf(':');
		return colon > 0 && colon < 4 ? base.substring(0, colon).trim() : "";
	}

	/**
	 * The text a teleport should show, before Wilderness styling: the game's own, with a custom hotkey in place of
	 * the game's key where one is set.
	 */
	private String baseText(Widget orig)
	{
		String base = originalTexts.getOrDefault(orig, orig.getText());
		if (!hotkeys.isEnabled())
		{
			return base;
		}
		return TeleportHotkeys.relabel(TeleportHotkeys.Kind.JEWELLERY, base,
			hotkeys.label(TeleportHotkeys.Kind.JEWELLERY, sortName(orig), gameKey(orig)));
	}

	/**
	 * Adds "Set hotkey" (and "Reset hotkey" when one is set) to the right-click menu of a teleport, in the grid
	 * and in the lists. Added only when the menu opens, below the game's own options.
	 */
	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (groups.isEmpty() || !hotkeys.isEnabled() || client.getWidget(InterfaceID.PohJewelleryBox.UNIVERSE) == null)
		{
			return;
		}
		Widget orig = null;
		for (MenuEntry me : event.getMenuEntries())
		{
			orig = teleportFor(me.getParam1(), me.getParam0());
			if (orig != null)
			{
				break;
			}
		}
		if (orig == null)
		{
			return;
		}
		final String name = sortName(orig);
		client.getMenu().createMenuEntry(1)
			.setOption("Set hotkey")
			.setTarget("<col=ff9040>" + name + "</col>")
			.setType(MenuAction.RUNELITE)
			.onClick(me ->
			{
				search.stopTyping();
				hotkeys.startCapture(TeleportHotkeys.Kind.JEWELLERY, name);
			});
		if (hotkeys.get(TeleportHotkeys.Kind.JEWELLERY, name) != null)
		{
			client.getMenu().createMenuEntry(1)
				.setOption("Reset hotkey")
				.setTarget("<col=ff9040>" + name + "</col>")
				.setType(MenuAction.RUNELITE)
				.onClick(me ->
				{
					hotkeys.reset(TeleportHotkeys.Kind.JEWELLERY, name);
					redrawLabels();
				});
		}
	}

	/** The game's teleport a menu entry is on: one of the grid's own teleports, or a list row standing in for one. */
	private Widget teleportFor(int componentId, int childIndex)
	{
		if (childIndex < 0)
		{
			return null;
		}
		if (listActive && componentId == InterfaceID.PohJewelleryBox.DUELING)
		{
			for (Row r : rows)
			{
				if (r.mine.getIndex() == childIndex && !r.mine.isHidden())
				{
					return r.original;
				}
			}
			return null;
		}
		if (listActive)
		{
			return null;
		}
		for (Group g : groups)
		{
			for (Widget orig : g.teleports)
			{
				if (orig.getId() == componentId && orig.getIndex() == childIndex)
				{
					return orig;
				}
			}
		}
		return null;
	}

	/**
	 * Makes the title bar {@link TitleBar#EXTRA} taller: the title and close button move down by half of that
	 * so they stay centred, and the divider (steelborder child 10) moves down by all of it. The sections and
	 * lists below start that much lower ({@link #headerExtra}).
	 */
	private void extendTitleBar(Widget frame)
	{
		Widget title = frame != null ? frame.getChild(1) : null;
		// steelborder puts the title at y 6; anything else means this window's bar was already moved
		if (title == null || titleBar.isExtended() || title.getOriginalY() != 6)
		{
			return;
		}
		int half = TitleBar.EXTRA / 2;
		titleBar.moveDown(title, half);
		titleBar.moveDown(frame.getChild(10), TitleBar.EXTRA);
		titleBar.moveDown(frame.getChild(11), half);
		headerExtra = TitleBar.EXTRA;
	}

	/** True when the default grid has to be laid out by the plugin, to leave room for the strip or title bar. */
	private boolean customGrid()
	{
		return selectorShown || headerExtra > 0;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!PohTeleportSorterConfig.GROUP.equals(event.getGroup())
			|| !(PohTeleportSorterConfig.JEWELLERY_MODE.equals(event.getKey())
				|| PohTeleportSorterConfig.SHOW_JEWELLERY_SELECTOR.equals(event.getKey())
				|| PohTeleportSorterConfig.WILD_ASTERISK.equals(event.getKey())
				|| PohTeleportSorterConfig.WILD_HIGHLIGHT.equals(event.getKey())))
		{
			return;
		}
		clientThread.invokeLater(() ->
		{
			try
			{
				Widget universe = client.getWidget(InterfaceID.PohJewelleryBox.UNIVERSE);
				if (universe == null || groups.isEmpty())
				{
					return;
				}
				if (PohTeleportSorterConfig.SHOW_JEWELLERY_SELECTOR.equals(event.getKey()))
				{
					if (config.showJewellerySelector() && stripWidgets.isEmpty())
					{
						buildStrip(universe);
					}
					selectorShown = config.showJewellerySelector();
				}
				apply(config.jewelleryBoxMode());
			}
			catch (Throwable t)
			{
				log.error("Jewellery box: failed to switch layout", t);
			}
		});
	}

	/**
	 * Every frame while the box is open: records a teleport as used the moment the game shows its
	 * "Please wait..." (so clicks, hotkeys and list rows all count), and mirrors the originals' text onto the
	 * list rows so "Please wait..." shows there too.
	 */
	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (groups.isEmpty())
		{
			return;
		}
		if (client.getWidget(InterfaceID.PohJewelleryBox.UNIVERSE) == null)
		{
			clearState();
			return;
		}

		for (Group g : groups)
		{
			for (Widget orig : g.teleports)
			{
				String t = orig.getText();
				boolean isWaiting = t != null && t.contains("Please wait");
				if (isWaiting && waiting.add(orig))
				{
					pins.recordUse(PinStore.Kind.RECENT_JEWELLERY, sortName(orig), RECENT_LIMIT);
				}
				else if (!isWaiting)
				{
					waiting.remove(orig);
					// After "Please wait..." the game puts its own text back; show the right key and styling again.
					// Only the game's own text is replaced, so nothing another plugin wrote is ever overwritten.
					String gameText = originalTexts.get(orig);
					if (t != null && t.equals(gameText))
					{
						String name = sortName(orig);
						String want = styled(baseText(orig), name, JewelleryTeleports.isWild(name));
						if (!t.equals(want))
						{
							orig.setText(want);
						}
					}
				}
			}
		}

		for (Row r : new ArrayList<>(rows))
		{
			String t = r.original.getText();
			if (t != null && !t.equals(r.lastSeen))
			{
				r.lastSeen = t;
				// The row shows the key this plugin would show, or, with Better Teleport Menu on, whatever it wrote
				String text = hotkeys.isEnabled() ? baseText(r.original) : t;
				r.mine.setText(t.contains("Please wait") ? t : styled(text, r.tele.name, r.tele.wild));
			}
		}
	}

	/** The text with the optional Wilderness styling applied. */
	private String styled(String text, String name, boolean wild)
	{
		return WildStyle.apply(text, name, wild, config.wildAsterisk(), config.wildHighlight());
	}

	// ---------------------------------------------------------------- capture

	private void capture()
	{
		for (int id : SECTIONS)
		{
			Widget layer = client.getWidget(id);
			Group g = new Group();
			g.title = "";
			g.titleColour = TITLE_LOCKED_COLOUR;
			if (layer != null && layer.getDynamicChildren() != null)
			{
				for (Widget c : layer.getDynamicChildren())
				{
					if (c == null)
					{
						continue;
					}
					if (c.getType() == WidgetType.TEXT && c.getOnOpListener() != null)
					{
						g.teleports.add(c);
						originalTexts.put(c, c.getText());
					}
					else if (c.getType() == WidgetType.TEXT && g.title.isEmpty())
					{
						g.title = Text.removeTags(c.getText());
						g.titleColour = c.getTextColor();
					}
					else if (c.getType() == WidgetType.GRAPHIC && c.getItemId() > 0)
					{
						g.itemId = c.getItemId();
					}
				}
			}
			groups.add(g);
		}

		Widget dueling = client.getWidget(InterfaceID.PohJewelleryBox.DUELING);
		if (dueling != null)
		{
			Widget[] kids = dueling.getDynamicChildren();
			duelingOriginals = kids != null ? kids.clone() : new Widget[0];
		}

		for (int id : SECTIONS)
		{
			Widget section = client.getWidget(id);
			if (section != null)
			{
				gameGeometry.put(section, new int[]{
					section.getOriginalX(), section.getOriginalY(), section.getOriginalWidth(), section.getOriginalHeight(),
					section.getXPositionMode(), section.getYPositionMode(), section.getWidthMode(), section.getHeightMode()});
			}
		}
		for (Group g : groups)
		{
			for (Widget t : g.teleports)
			{
				gameGeometry.put(t, new int[]{t.getOriginalY(), t.getOriginalHeight()});
			}
		}
	}

	/** Puts the six sections and their teleports back exactly where the game laid them out. */
	private void restoreGameGrid()
	{
		for (int id : SECTIONS)
		{
			Widget section = client.getWidget(id);
			int[] g = section != null ? gameGeometry.get(section) : null;
			if (g == null)
			{
				continue;
			}
			section.setOriginalX(g[0]);
			section.setOriginalY(g[1]);
			section.setOriginalWidth(g[2]);
			section.setOriginalHeight(g[3]);
			section.setXPositionMode(g[4]);
			section.setYPositionMode(g[5]);
			section.setWidthMode(g[6]);
			section.setHeightMode(g[7]);
		}
		for (Group grp : groups)
		{
			for (Widget t : grp.teleports)
			{
				int[] g = gameGeometry.get(t);
				if (g != null)
				{
					t.setOriginalY(g[0]);
					t.setOriginalHeight(g[1]);
				}
			}
		}
		for (int id : SECTIONS)
		{
			revalidateTree(client.getWidget(id));
		}
	}

	// ---------------------------------------------------------------- modes

	private void apply(JewelleryBoxMode mode)
	{
		if (mode == JewelleryBoxMode.DEFAULT)
		{
			showDefault();
		}
		else
		{
			showList(mode, false);
		}

		// The same strip of radios sits along the bottom in every layout, unless it's switched off in the settings
		for (Widget w : stripWidgets)
		{
			w.setHidden(!selectorShown);
		}
		refreshRadios(mode);
	}

	private void showDefault()
	{
		listActive = false;
		rows.clear();

		for (int i = 1; i < SECTIONS.length; i++)
		{
			Widget w = client.getWidget(SECTIONS[i]);
			if (w != null)
			{
				w.setHidden(false);
			}
		}

		if (customGrid())
		{
			layoutDefault();
		}
		else
		{
			restoreGameGrid();
		}

		Widget dueling = client.getWidget(InterfaceID.PohJewelleryBox.DUELING);
		if (dueling != null)
		{
			dueling.setScrollY(0);
			dueling.setScrollHeight(0);
			for (Widget w : duelingOriginals)
			{
				if (w != null)
				{
					w.setHidden(false);
				}
			}
			revalidateTree(dueling);
		}

		// Search on the default grid: teleports that don't match are hidden where they are, so the grid itself
		// never moves. Wilderness styling is applied to the game's own text.
		for (Group g : groups)
		{
			for (Widget orig : g.teleports)
			{
				String name = sortName(orig);
				orig.setHidden(!search.matches(name, g.title));
				String now = orig.getText();
				if (originalTexts.containsKey(orig) && now != null && !now.contains("Please wait"))
				{
					orig.setText(styled(baseText(orig), name, JewelleryTeleports.isWild(name)));
				}
			}
		}

		hideAll(stripes);
		hideAll(icons);
		hideAll(texts);
		hideAll(suffixes);
		hideAll(pinIcons);
		hideAll(listBackground);
		hide(track);
		hide(dragMiddle);
		hide(dragTop);
		hide(dragBottom);
		hide(arrowUp);
		hide(arrowDown);
	}

	/**
	 * The default grid, laid out with the game's own maths from poh_jewellery_box_init and
	 * poh_jewellerybox_getbuttonspacing, for the space left between the (possibly taller) title bar and the
	 * (possibly shown) selector strip: rows share that height in a 5:6:4 ratio, columns are (width - 25) / 2
	 * wide with 10px margins and 5px gaps, and each section's teleports are spaced
	 * min((section height - 10) / count, 25) apart, centred on the section. The strip then sits 5px under
	 * the bottom row, like the gaps between sections.
	 */
	private void layoutDefault()
	{
		Widget universe = client.getWidget(InterfaceID.PohJewelleryBox.UNIVERSE);
		if (universe == null)
		{
			return;
		}
		int top = TOP + headerExtra;
		int colW = (universe.getWidth() - 10 - 10 - 5) / 2;
		int avail = universe.getHeight() - top - 10 - 5 - 5 - (selectorShown ? RESERVE : 0);
		int r1 = 5 * avail / 15;
		int r2 = 6 * avail / 15;
		int r3 = 4 * avail / 15;

		int[][] geom = {
			{10, top, r1},
			{10 + colW + 5, top, r1},
			{10, top + r1 + 5, r2},
			{10 + colW + 5, top + r1 + 5, r2},
			{10, top + r1 + r2 + 5 + 5, r3},
			{10 + colW + 5, top + r1 + r2 + 5 + 5, r3},
		};

		for (int i = 0; i < SECTIONS.length; i++)
		{
			Widget section = client.getWidget(SECTIONS[i]);
			if (section == null)
			{
				continue;
			}
			section.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
			section.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
			section.setWidthMode(WidgetSizeMode.ABSOLUTE);
			section.setHeightMode(WidgetSizeMode.ABSOLUTE);
			section.setOriginalX(geom[i][0]);
			section.setOriginalY(geom[i][1]);
			section.setOriginalWidth(colW);
			section.setOriginalHeight(geom[i][2]);

			List<Widget> teles = i < groups.size() ? groups.get(i).teleports : new ArrayList<>();
			int n = Math.max(teles.size(), 1);
			int spacing = Math.min((geom[i][2] - 10) / n, 25);
			int y = -spacing * (n / 2);
			if (n % 2 == 0)
			{
				y += spacing / 2;
			}
			for (Widget t : teles)
			{
				t.setOriginalY(y);
				t.setOriginalHeight(spacing);
				y += spacing;
			}
			revalidateTree(section);
		}
	}

	// ---------------------------------------------------------------- list views

	/** One teleport as shown in the lists. */
	private static class Tele
	{
		final Widget orig;
		final String name;
		final int group;
		final String jewellery;
		final boolean wild;

		Tele(Widget orig, String name, int group, String jewellery)
		{
			this.orig = orig;
			this.name = name;
			this.group = group;
			this.jewellery = jewellery;
			this.wild = JewelleryTeleports.isWild(name);
		}
	}

	/** A titled block of rows: a jewellery, a region, or the pinned teleports. */
	private static class Section
	{
		final String title;
		final int colour;
		final int itemId;
		final boolean showOwner;
		final List<Tele> teles = new ArrayList<>();

		Section(String title, int colour, int itemId, boolean showOwner)
		{
			this.title = title;
			this.colour = colour;
			this.itemId = itemId;
			this.showOwner = showOwner;
		}
	}

	private void showList(JewelleryBoxMode mode, boolean keepScroll)
	{
		Widget universe = client.getWidget(InterfaceID.PohJewelleryBox.UNIVERSE);
		Widget frame = client.getWidget(InterfaceID.PohJewelleryBox.FRAME);
		Widget dueling = client.getWidget(InterfaceID.PohJewelleryBox.DUELING);
		if (universe == null || frame == null || dueling == null || groups.isEmpty())
		{
			return;
		}
		int previousScroll = keepScroll && listActive ? dueling.getScrollY() : 0;

		for (int i = 1; i < SECTIONS.length; i++)
		{
			Widget w = client.getWidget(SECTIONS[i]);
			if (w != null)
			{
				w.setHidden(true);
			}
		}
		for (Widget w : duelingOriginals)
		{
			if (w != null)
			{
				w.setHidden(true);
			}
		}

		int width = universe.getWidth();
		int height = universe.getHeight();
		int bottom = MARGIN + (selectorShown ? RESERVE : 0);
		int boxW = width - MARGIN * 2 - SCROLLBAR_WIDTH - 3;
		int boxTop = TOP + headerExtra;
		int boxH = height - boxTop - bottom;

		buildListBackground(frame, boxTop, boxW, boxH);

		// The first section's layer becomes the scrolling list, inset by the thinbox border
		dueling.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
		dueling.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
		dueling.setWidthMode(WidgetSizeMode.ABSOLUTE);
		dueling.setHeightMode(WidgetSizeMode.ABSOLUTE);
		dueling.setOriginalX(MARGIN + 2);
		dueling.setOriginalY(boxTop + 2);
		dueling.setOriginalWidth(boxW - 4);
		dueling.setOriginalHeight(boxH - 4);
		dueling.revalidate();
		listHeight = boxH - 4;

		allocatePools(dueling);
		rows.clear();
		List<Tele> all = matchingTeles();
		int contentHeight;
		if (all.isEmpty() && !search.query().isEmpty())
		{
			contentHeight = layoutNoMatches();
		}
		else if (mode == JewelleryBoxMode.ALPHABETICAL)
		{
			contentHeight = layoutSections(alphabeticalSections(all), false);
		}
		else if (mode == JewelleryBoxMode.RECENT)
		{
			contentHeight = layoutSections(recentSections(all), false);
		}
		else
		{
			contentHeight = layoutSections(jewellerySections(all), true);
		}

		dueling.setScrollHeight(Math.max(contentHeight, listHeight));
		dueling.setScrollY(Math.max(0, Math.min(previousScroll, Math.max(contentHeight - listHeight, 0))));
		dueling.setHasListener(true);
		dueling.setOnScrollWheelListener((JavaScriptCallback) ev -> scrollTo(dueling.getScrollY() + ev.getMouseY() * WHEEL_STEP));
		revalidateTree(dueling);
		dueling.revalidateScroll();

		buildScrollbar(universe, boxTop, boxH);
		listActive = true;
	}

	/** Every teleport the box has, in the game's order, filtered by the search text. */
	private List<Tele> matchingTeles()
	{
		List<Tele> out = new ArrayList<>();
		for (int gi = 0; gi < groups.size(); gi++)
		{
			Group g = groups.get(gi);
			for (Widget orig : g.teleports)
			{
				Tele t = new Tele(orig, sortName(orig), gi, g.title);
				if (search.matches(t.name, t.jewellery))
				{
					out.add(t);
				}
			}
		}
		return out;
	}

	/** Splits off the pinned teleports into their own section, keeping the order they were given in. */
	private List<Section> withPinnedFirst(List<Tele> ordered, List<Tele> rest)
	{
		List<String> pinned = pins.list(PinStore.Kind.JEWELLERY);
		Section pinSec = new Section("Pinned", TITLE_SECTION_COLOUR, 0, true);
		for (Tele t : ordered)
		{
			if (containsIgnoreCase(pinned, t.name))
			{
				pinSec.teles.add(t);
			}
			else
			{
				rest.add(t);
			}
		}
		List<Section> out = new ArrayList<>();
		if (!pinSec.teles.isEmpty())
		{
			out.add(pinSec);
		}
		return out;
	}

	private List<Section> jewellerySections(List<Tele> all)
	{
		List<Tele> rest = new ArrayList<>();
		List<Section> out = withPinnedFirst(all, rest);
		boolean filtering = !search.query().isEmpty();
		for (int gi = 0; gi < groups.size(); gi++)
		{
			Group g = groups.get(gi);
			Section sec = new Section(g.title, g.titleColour, g.itemId, false);
			for (Tele t : rest)
			{
				if (t.group == gi)
				{
					sec.teles.add(t);
				}
			}
			// A locked jewellery (no teleports at all) still shows its greyed heading, as in the game's grid.
			// Hide a heading only when its teleports are all pinned or filtered out.
			if (!sec.teles.isEmpty() || (g.teleports.isEmpty() && !filtering))
			{
				out.add(sec);
			}
		}
		return out;
	}

	/**
	 * Recently used: a Pinned heading if anything is pinned, then teleports you've used, most recent first,
	 * then everything else A to Z with no heading.
	 */
	private List<Section> recentSections(List<Tele> all)
	{
		List<String> recent = pins.list(PinStore.Kind.RECENT_JEWELLERY);
		List<Tele> ordered = new ArrayList<>(all);
		ordered.sort(Comparator.comparing(t -> t.name, String.CASE_INSENSITIVE_ORDER));
		List<Tele> rest = new ArrayList<>();
		List<Section> out = withPinnedFirst(ordered, rest);

		Section used = new Section("Recently used", TITLE_SECTION_COLOUR, 0, true);
		Section others = new Section(null, 0, 0, true);
		for (Tele t : rest)
		{
			(PinStore.indexOf(recent, t.name) >= 0 ? used : others).teles.add(t);
		}
		used.teles.sort(Comparator.comparingInt(t -> PinStore.indexOf(recent, t.name)));
		if (!used.teles.isEmpty())
		{
			out.add(used);
		}
		if (!others.teles.isEmpty())
		{
			out.add(others);
		}
		return out;
	}

	/** Alphabetical: every teleport A to Z with no heading, or, when something is pinned, Pinned then "A to Z". */
	private List<Section> alphabeticalSections(List<Tele> all)
	{
		List<Tele> ordered = new ArrayList<>(all);
		ordered.sort(Comparator.comparing(t -> t.name, String.CASE_INSENSITIVE_ORDER));
		List<Tele> rest = new ArrayList<>();
		List<Section> out = withPinnedFirst(ordered, rest);
		// With pins above, the rest gets its own heading so it reads as a separate list, as in Recently used
		Section flat = new Section(out.isEmpty() ? null : "A to Z", TITLE_SECTION_COLOUR, 0, true);
		flat.teles.addAll(rest);
		out.add(flat);
		return out;
	}

	/**
	 * Lays sections out in the list. With icons (By Jewellery), headings and rows line up after the
	 * jewellery icon; without (Alphabetical, Recently used), headings sit at the left and rows are indented.
	 * A section with no title is drawn as plain rows.
	 */
	private int layoutSections(List<Section> sections, boolean withIcons)
	{
		int headerX = withIcons ? TEXT_INDENT : 8;
		int y = LIST_TOP_PAD, stripe = 0, icon = 0, text = 0, suffix = 0, pin = 0;
		List<String> pinned = pins.list(PinStore.Kind.JEWELLERY);
		// When the list has headings (Pinned, Recently used), rows without a heading of their own carry straight
		// on from the block above: same indent, same row height, no gap, and the shading keeps alternating
		boolean anyTitled = sections.stream().anyMatch(sec -> sec.title != null);
		int titledRowX = withIcons ? TEXT_INDENT : 20;
		int n = 0;

		for (int si = 0; si < sections.size(); si++)
		{
			Section sec = sections.get(si);
			boolean hasTitle = sec.title != null;
			int rowX = hasTitle || anyTitled ? titledRowX : 8;
			int rowH = hasTitle || anyTitled ? TELE_ROW_HEIGHT : ALPHA_ROW_HEIGHT;
			if (hasTitle)
			{
				n = 0;
			}

			if (hasTitle)
			{
				if (withIcons && sec.itemId > 0)
				{
					Widget ic = icons.get(icon++);
					// 32px icon centred on the 26px title line, so the first teleport sits close under the name
					placeIn(ic, 4, y - 3, 36, 32);
					ic.setItemId(sec.itemId);
					ic.setItemQuantityMode(ItemQuantityMode.NEVER);
					ic.setBorderType(1);
					ic.setHidden(false);
					ic.revalidate();
				}

				Widget title = texts.get(text++);
				placeIn(title, headerX, y, -1, HEADER_HEIGHT);
				styleText(title, FontID.PLAIN_12, sec.colour);
				clearOps(title);
				title.setText(sec.title);
				title.setHidden(false);
				title.revalidate();
				y += HEADER_HEIGHT;
			}

			for (Tele t : sec.teles)
			{
				Widget s = stripes.get(stripe++);
				placeIn(s, 0, y, -1, rowH);
				shade(s, n++);

				Widget row = texts.get(text++);
				placeIn(row, rowX, y, -1, rowH);
				bindTeleport(row, t);

				// Pinned rows get the pin icon at the far right; the jewellery name (where shown) sits left of it
				boolean isPinned = PinStore.indexOf(pinned, t.name) >= 0;
				int rightGap = 8;
				if (isPinned)
				{
					Widget pw = pinIcons.get(pin++);
					pw.setXPositionMode(WidgetPositionMode.ABSOLUTE_RIGHT);
					pw.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
					pw.setWidthMode(WidgetSizeMode.ABSOLUTE);
					pw.setHeightMode(WidgetSizeMode.ABSOLUTE);
					pw.setOriginalX(6);
					pw.setOriginalY(y + (rowH - PinSprite.HEIGHT) / 2);
					pw.setOriginalWidth(PinSprite.WIDTH);
					pw.setOriginalHeight(PinSprite.HEIGHT);
					pw.setSpriteId(PinSprite.PIN.getSpriteId());
					pw.setHidden(false);
					pw.revalidate();
					rightGap = 6 + PinSprite.WIDTH + 6;
				}

				Widget sw = suffixes.get(suffix++);
				sw.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
				sw.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
				sw.setOriginalX(8);
				sw.setOriginalY(y);
				sw.setWidthMode(WidgetSizeMode.MINUS);
				sw.setOriginalWidth(8 + rightGap);
				sw.setHeightMode(WidgetSizeMode.ABSOLUTE);
				sw.setOriginalHeight(rowH);
				styleText(sw, FontID.PLAIN_11, SUFFIX_COLOUR);
				sw.setXTextAlignment(WidgetTextAlignment.RIGHT);
				sw.setText(sec.showOwner ? t.jewellery : "");
				sw.setHidden(!sec.showOwner);
				sw.revalidate();

				y += rowH;
			}
			boolean nextTitled = si + 1 < sections.size() && sections.get(si + 1).title != null;
			if (hasTitle && nextTitled)
			{
				y += GROUP_GAP;
			}
		}

		hideFrom(stripes, stripe);
		hideFrom(icons, icon);
		hideFrom(texts, text);
		hideFrom(suffixes, suffix);
		hideFrom(pinIcons, pin);
		return y;
	}

	private int layoutNoMatches()
	{
		Widget msg = texts.get(0);
		placeIn(msg, 8, LIST_TOP_PAD, -1, HEADER_HEIGHT);
		styleText(msg, FontID.PLAIN_12, SUFFIX_COLOUR);
		clearOps(msg);
		msg.setText("No teleports match \"" + Text.escapeJagex(search.query()) + "\"");
		msg.setHidden(false);
		msg.revalidate();
		hideAll(stripes);
		hideAll(icons);
		hideFrom(texts, 1);
		hideAll(suffixes);
		hideAll(pinIcons);
		return LIST_TOP_PAD + HEADER_HEIGHT;
	}

	private static boolean containsIgnoreCase(List<String> list, String s)
	{
		for (String x : list)
		{
			if (x.equalsIgnoreCase(s))
			{
				return true;
			}
		}
		return false;
	}

	/** The menu option the game gives each teleport is its plain name, without hotkey or strikethrough. */
	private static String sortName(Widget orig)
	{
		String[] actions = orig.getActions();
		if (actions != null && actions.length > 0 && actions[0] != null && !actions[0].isEmpty())
		{
			return Text.removeTags(actions[0]);
		}
		String t = Text.removeTags(orig.getText());
		int colon = t.indexOf(':');
		return (colon >= 0 && colon < 4 ? t.substring(colon + 1) : t).trim();
	}

	private void bindTeleport(Widget t, Tele tele)
	{
		Widget orig = tele.orig;
		styleText(t, FontID.PLAIN_11, TELE_COLOUR);
		String now = orig.getText();
		t.setText(now != null && now.contains("Please wait") ? now : styled(baseText(orig), tele.name, tele.wild));
		String[] actions = orig.getActions();
		String op = actions != null && actions.length > 0 && actions[0] != null ? actions[0] : "Teleport";
		clearOps(t);
		// First action is the game's own teleport (left-click); Pin/Unpin is the second, right-click only
		t.setAction(0, op);
		t.setAction(1, pins.isPinned(PinStore.Kind.JEWELLERY, tele.name) ? "Unpin" : "Pin");
		t.setName("");
		t.setHasListener(true);
		// A widget op event reports the action index plus one, as RuneLite's own plugins read it
		t.setOnOpListener((JavaScriptCallback) ev ->
		{
			if (ev.getOp() == 2)
			{
				pins.toggle(PinStore.Kind.JEWELLERY, tele.name);
				showList(config.jewelleryBoxMode(), true);
				refreshRadios(config.jewelleryBoxMode());
			}
			else
			{
				search.stopTyping();
				runOriginal(orig);
			}
		});
		t.setOnMouseOverListener((JavaScriptCallback) ev -> t.setTextColor(HOVER_COLOUR));
		t.setOnMouseLeaveListener((JavaScriptCallback) ev -> t.setTextColor(TELE_COLOUR));
		t.setHidden(false);
		t.revalidate();
		rows.add(new Row(t, orig, tele));
	}

	/**
	 * Runs the game's own click handler for the original teleport, exactly as clicking it in the grid would.
	 * Since July 2026 RuneLite blocks scripts run by plugins from sending anything to the server unless
	 * they're allowed to; this is one click for one teleport, allowed the same way RuneLite's own bank
	 * plugin runs the bank PIN buttons' click scripts.
	 */
	private void runOriginal(Widget orig)
	{
		Object[] listener = orig.getOnOpListener();
		if (listener == null)
		{
			return;
		}
		client.createScriptEventBuilder(listener)
			.setSource(orig)
			.setOp(1)
			.build()
			.setCanSendPackets(true)
			.run();
	}

	// ---------------------------------------------------------------- widget pools

	/** Creates every list widget once, in draw order: stripes under icons under text. */
	private void allocatePools(Widget layer)
	{
		int teleCount = 0;
		for (Group g : groups)
		{
			teleCount += g.teleports.size();
		}
		// Headings: one per jewellery (the most any layout uses), plus the Pinned heading
		int headings = Math.max(groups.size(), 2) + 1;
		grow(layer, stripes, WidgetType.RECTANGLE, teleCount);
		grow(layer, icons, WidgetType.GRAPHIC, groups.size());
		grow(layer, texts, WidgetType.TEXT, teleCount + headings);
		grow(layer, suffixes, WidgetType.TEXT, teleCount);
		grow(layer, pinIcons, WidgetType.GRAPHIC, teleCount);
	}

	private static void grow(Widget layer, List<Widget> pool, int type, int size)
	{
		while (pool.size() < size)
		{
			Widget w = layer.createChild(-1, type);
			w.setHidden(true);
			pool.add(w);
		}
	}

	private static void hideFrom(List<Widget> pool, int from)
	{
		for (int i = from; i < pool.size(); i++)
		{
			hide(pool.get(i));
		}
	}

	private static void hideAll(List<Widget> pool)
	{
		hideFrom(pool, 0);
	}

	private static void hide(Widget w)
	{
		if (w != null)
		{
			w.setHidden(true);
		}
	}

	private static void clearOps(Widget w)
	{
		w.setHasListener(false);
		w.setOnOpListener((Object[]) null);
		w.setOnMouseOverListener((Object[]) null);
		w.setOnMouseLeaveListener((Object[]) null);
		String[] a = w.getActions();
		if (a != null)
		{
			for (int i = 0; i < a.length; i++)
			{
				w.setAction(i, null);
			}
		}
	}

	private static void styleText(Widget t, int font, int colour)
	{
		t.setFontId(font);
		t.setTextColor(colour);
		t.setTextShadowed(true);
		t.setXTextAlignment(WidgetTextAlignment.LEFT);
		t.setYTextAlignment(WidgetTextAlignment.CENTER);
	}

	/** Row striping in the style of the game's lists: every other row lightly darkened. */
	private static void shade(Widget s, int n)
	{
		s.setFilled(true);
		s.setTextColor(0x000000);
		s.setOpacity(n % 2 == 0 ? 255 : 225);
		s.setHidden(false);
		s.revalidate();
	}

	/** Places a widget in the list. Width -1 means "to the right edge, less the same margin as x". */
	private static void placeIn(Widget w, int x, int y, int width, int height)
	{
		w.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
		w.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
		w.setOriginalX(x);
		w.setOriginalY(y);
		if (width < 0)
		{
			w.setWidthMode(WidgetSizeMode.MINUS);
			w.setOriginalWidth(x * 2);
		}
		else
		{
			w.setWidthMode(WidgetSizeMode.ABSOLUTE);
			w.setOriginalWidth(width);
		}
		w.setHeightMode(WidgetSizeMode.ABSOLUTE);
		w.setOriginalHeight(height);
	}

	// ---------------------------------------------------------------- list frame + scrollbar

	/**
	 * Backing and thinbox behind the list, drawn as part of the window frame so they sit underneath it.
	 * Same tradebacking and thinbox colours as the game's sections.
	 */
	private void buildListBackground(Widget frame, int boxTop, int boxW, int boxH)
	{
		if (listBackground.isEmpty())
		{
			Widget bg = frame.createChild(-1, WidgetType.GRAPHIC);
			bg.setSpriteId(SpriteID.TRADEBACKING_LIGHT);
			bg.setSpriteTiling(true);
			listBackground.add(bg);

			Widget outer = frame.createChild(-1, WidgetType.RECTANGLE);
			outer.setFilled(false);
			outer.setTextColor(THINBOX_OUTER);
			listBackground.add(outer);

			Widget inner = frame.createChild(-1, WidgetType.RECTANGLE);
			inner.setFilled(false);
			inner.setTextColor(THINBOX_INNER);
			listBackground.add(inner);
		}

		placeAbs(listBackground.get(0), MARGIN + 1, boxTop + 1, boxW - 2, boxH - 2);
		placeAbs(listBackground.get(1), MARGIN, boxTop, boxW, boxH);
		placeAbs(listBackground.get(2), MARGIN + 1, boxTop + 1, boxW - 2, boxH - 2);
		for (Widget w : listBackground)
		{
			w.setHidden(false);
			w.revalidate();
		}
	}

	/** The game's own vertical scrollbar, rebuilt with the same sprites and behaviour. */
	private void buildScrollbar(Widget universe, int boxTop, int boxH)
	{
		int x = universe.getWidth() - MARGIN - SCROLLBAR_WIDTH;
		if (track == null)
		{
			track = universe.createChild(-1, WidgetType.GRAPHIC);
			track.setSpriteId(SpriteID.ScrollbarDraggerV2._3);
			dragMiddle = universe.createChild(-1, WidgetType.GRAPHIC);
			dragMiddle.setSpriteId(SpriteID.ScrollbarDraggerV2._1);
			dragTop = universe.createChild(-1, WidgetType.GRAPHIC);
			dragTop.setSpriteId(SpriteID.ScrollbarDraggerV2._0);
			dragBottom = universe.createChild(-1, WidgetType.GRAPHIC);
			dragBottom.setSpriteId(SpriteID.ScrollbarDraggerV2._2);
			arrowUp = universe.createChild(-1, WidgetType.GRAPHIC);
			arrowUp.setSpriteId(SpriteID.ScrollbarV2.ARROW_UP);
			arrowDown = universe.createChild(-1, WidgetType.GRAPHIC);
			arrowDown.setSpriteId(SpriteID.ScrollbarV2.ARROW_DOWN);

			Widget dueling = client.getWidget(InterfaceID.PohJewelleryBox.DUELING);

			track.setHasListener(true);
			track.setNoClickThrough(true);
			track.setOnClickListener((JavaScriptCallback) ev -> jumpToMouse());
			track.setOnHoldListener((JavaScriptCallback) ev -> jumpToMouse());
			track.setOnScrollWheelListener((JavaScriptCallback) ev ->
			{
				if (dueling != null)
				{
					scrollTo(dueling.getScrollY() + ev.getMouseY() * WHEEL_STEP);
				}
			});

			for (Widget part : new Widget[]{dragMiddle, dragTop, dragBottom})
			{
				part.setHasListener(true);
				part.setNoClickThrough(true);
				part.setOnClickListener((JavaScriptCallback) ev -> grabDragger());
				part.setOnHoldListener((JavaScriptCallback) ev -> dragDragger());
			}

			arrowUp.setHasListener(true);
			arrowUp.setNoClickThrough(true);
			arrowUp.setOnHoldListener((JavaScriptCallback) ev ->
			{
				if (dueling != null)
				{
					scrollTo(dueling.getScrollY() - ARROW_STEP);
				}
			});
			arrowDown.setHasListener(true);
			arrowDown.setNoClickThrough(true);
			arrowDown.setOnHoldListener((JavaScriptCallback) ev ->
			{
				if (dueling != null)
				{
					scrollTo(dueling.getScrollY() + ARROW_STEP);
				}
			});
		}

		placeAbs(arrowUp, x, boxTop, SCROLLBAR_WIDTH, 16);
		placeAbs(arrowDown, x, boxTop + boxH - 16, SCROLLBAR_WIDTH, 16);
		placeAbs(track, x, boxTop + 16, SCROLLBAR_WIDTH, boxH - 32);
		for (Widget w : new Widget[]{track, arrowUp, arrowDown})
		{
			w.setHidden(false);
			w.revalidate();
		}
		updateDragger();
	}

	private int maxScroll()
	{
		Widget dueling = client.getWidget(InterfaceID.PohJewelleryBox.DUELING);
		return dueling == null ? 0 : Math.max(dueling.getScrollHeight() - listHeight, 0);
	}

	private int draggerHeight()
	{
		Widget dueling = client.getWidget(InterfaceID.PohJewelleryBox.DUELING);
		int trackH = track.getOriginalHeight();
		int scrollH = dueling == null ? 1 : Math.max(dueling.getScrollHeight(), 1);
		return Math.min(trackH, Math.max(10, trackH * listHeight / scrollH));
	}

	private void scrollTo(int y)
	{
		Widget dueling = client.getWidget(InterfaceID.PohJewelleryBox.DUELING);
		if (dueling == null || !listActive)
		{
			return;
		}
		dueling.setScrollY(Math.max(0, Math.min(y, maxScroll())));
		dueling.revalidateScroll();
		updateDragger();
	}

	private void updateDragger()
	{
		Widget dueling = client.getWidget(InterfaceID.PohJewelleryBox.DUELING);
		if (track == null || dueling == null)
		{
			return;
		}
		int x = track.getOriginalX();
		int trackTop = track.getOriginalY();
		int trackH = track.getOriginalHeight();
		int dh = draggerHeight();
		int max = Math.max(maxScroll(), 1);
		int top = trackTop + (trackH - dh) * dueling.getScrollY() / max;

		placeAbs(dragMiddle, x, top, SCROLLBAR_WIDTH, dh);
		placeAbs(dragTop, x, top, SCROLLBAR_WIDTH, 5);
		placeAbs(dragBottom, x, top + dh - 5, SCROLLBAR_WIDTH, 5);
		for (Widget w : new Widget[]{dragMiddle, dragTop, dragBottom})
		{
			w.setHidden(false);
			w.revalidate();
		}
	}

	private int mouseYInTrack()
	{
		Point mouse = client.getMouseCanvasPosition();
		Point at = track.getCanvasLocation();
		return mouse == null || at == null ? 0 : mouse.getY() - at.getY();
	}

	private void jumpToMouse()
	{
		int dh = draggerHeight();
		positionDragger(mouseYInTrack() - dh / 2);
	}

	private void grabDragger()
	{
		dragGrab = mouseYInTrack() - (dragMiddle.getOriginalY() - track.getOriginalY());
	}

	private void dragDragger()
	{
		positionDragger(mouseYInTrack() - dragGrab);
	}

	private void positionDragger(int draggerTopInTrack)
	{
		int range = Math.max(track.getOriginalHeight() - draggerHeight(), 1);
		scrollTo(draggerTopInTrack * maxScroll() / range);
	}

	// ---------------------------------------------------------------- selector strip

	/** A slim strip in the same style as the jewellery sections, holding the three layout radios. */
	private void buildStrip(Widget universe)
	{
		Widget bg = universe.createChild(-1, WidgetType.GRAPHIC);
		stripWidgets.add(bg);
		placeBottom(bg, MARGIN, STRIP_HEIGHT, 0);
		bg.setSpriteId(SpriteID.TRADEBACKING_LIGHT);
		bg.setSpriteTiling(true);
		bg.revalidate();

		Widget outer = universe.createChild(-1, WidgetType.RECTANGLE);
		stripWidgets.add(outer);
		placeBottom(outer, MARGIN, STRIP_HEIGHT, 0);
		outer.setFilled(false);
		outer.setTextColor(THINBOX_OUTER);
		outer.revalidate();

		Widget inner = universe.createChild(-1, WidgetType.RECTANGLE);
		stripWidgets.add(inner);
		placeBottom(inner, MARGIN + 1, STRIP_HEIGHT - 2, 1);
		inner.setFilled(false);
		inner.setTextColor(THINBOX_INNER);
		inner.revalidate();

		JewelleryBoxMode[] modes = JewelleryBoxMode.values();
		int innerW = universe.getWidth() - MARGIN * 2;
		int segment = innerW / modes.length;
		int radioBottom = MARGIN + (STRIP_HEIGHT - 17) / 2;
		int labelBottom = MARGIN + (STRIP_HEIGHT - 18) / 2;

		for (int i = 0; i < modes.length; i++)
		{
			JewelleryBoxMode mode = modes[i];

			Widget label = universe.createChild(-1, WidgetType.TEXT);
			label.setFontId(FontID.PLAIN_12);
			label.setText(mode.getLabel());
			int textW = measure(label, mode.getLabel());
			int groupW = 17 + 4 + textW;
			int gx = MARGIN + segment * i + (segment - groupW) / 2;

			Widget radio = universe.createChild(-1, WidgetType.GRAPHIC);
			radio.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
			radio.setYPositionMode(WidgetPositionMode.ABSOLUTE_BOTTOM);
			radio.setOriginalX(gx);
			radio.setOriginalY(radioBottom);
			radio.setOriginalWidth(17);
			radio.setOriginalHeight(17);
			radio.setSpriteId(RADIO_OFF);

			label.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
			label.setYPositionMode(WidgetPositionMode.ABSOLUTE_BOTTOM);
			label.setOriginalX(gx + 21);
			label.setOriginalY(labelBottom);
			label.setOriginalWidth(textW + 2);
			label.setOriginalHeight(18);
			label.setTextColor(LABEL_COLOUR);
			label.setTextShadowed(true);
			label.setXTextAlignment(WidgetTextAlignment.LEFT);
			label.setYTextAlignment(WidgetTextAlignment.CENTER);

			final int index = i;
			for (Widget w : new Widget[]{radio, label})
			{
				w.setHasListener(true);
				w.setAction(0, "Layout");
				w.setName("<col=ff9040>" + mode.getName() + "</col>");
				w.setOnOpListener((JavaScriptCallback) ev -> select(mode));
				w.setOnMouseOverListener((JavaScriptCallback) ev -> hover(index, true));
				w.setOnMouseLeaveListener((JavaScriptCallback) ev -> hover(index, false));
				w.revalidate();
			}

			radios[i] = radio;
			labels[i] = label;
			stripWidgets.add(radio);
			stripWidgets.add(label);
		}
	}

	private static int measure(Widget label, String text)
	{
		FontTypeFace font = label.getFont();
		return font != null ? font.getTextWidth(text) : text.length() * 6;
	}

	/** Full-width strip anchored to the bottom of the window, inset by the given amount on each side. */
	private static void placeBottom(Widget w, int sideMargin, int height, int inset)
	{
		w.setXPositionMode(WidgetPositionMode.ABSOLUTE_CENTER);
		w.setYPositionMode(WidgetPositionMode.ABSOLUTE_BOTTOM);
		w.setOriginalX(0);
		w.setOriginalY(MARGIN + inset);
		w.setWidthMode(WidgetSizeMode.MINUS);
		w.setOriginalWidth(sideMargin * 2);
		w.setHeightMode(WidgetSizeMode.ABSOLUTE);
		w.setOriginalHeight(height);
	}

	private static void placeAbs(Widget w, int x, int y, int width, int height)
	{
		w.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
		w.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
		w.setWidthMode(WidgetSizeMode.ABSOLUTE);
		w.setHeightMode(WidgetSizeMode.ABSOLUTE);
		w.setOriginalX(x);
		w.setOriginalY(y);
		w.setOriginalWidth(width);
		w.setOriginalHeight(height);
	}

	private void select(JewelleryBoxMode mode)
	{
		configManager.setConfiguration(PohTeleportSorterConfig.GROUP, PohTeleportSorterConfig.JEWELLERY_MODE, mode);
		pins.markChanged();
		try
		{
			apply(mode);
		}
		catch (Throwable t)
		{
			log.error("Jewellery box: failed to switch layout", t);
		}
	}

	private void hover(int index, boolean over)
	{
		Widget radio = radios[index];
		Widget label = labels[index];
		if (radio == null || label == null)
		{
			return;
		}
		boolean selected = config.jewelleryBoxMode().ordinal() == index;
		radio.setSpriteId(selected ? (over ? RADIO_ON_HOVER : RADIO_ON) : (over ? RADIO_OFF_HOVER : RADIO_OFF));
		label.setTextColor(over ? HOVER_COLOUR : LABEL_COLOUR);
	}

	private void refreshRadios(JewelleryBoxMode current)
	{
		for (int i = 0; i < radios.length; i++)
		{
			if (radios[i] != null)
			{
				radios[i].setSpriteId(current.ordinal() == i ? RADIO_ON : RADIO_OFF);
			}
		}
	}

	/**
	 * Revalidates a static layer, its dynamic children, and its static child layers.
	 * Dynamic children share their parent's id, so asking them for children would walk back up the tree.
	 */
	private static void revalidateTree(Widget w)
	{
		revalidateTree(w, 0);
	}

	private static void revalidateTree(Widget w, int depth)
	{
		if (w == null || depth > 8)
		{
			return;
		}
		w.revalidate();
		if (w.getIndex() != -1)
		{
			return; // dynamic child: no children of its own
		}
		Widget[] dynamic = w.getDynamicChildren();
		if (dynamic != null)
		{
			for (Widget k : dynamic)
			{
				if (k != null)
				{
					k.revalidate();
				}
			}
		}
		Widget[] statics = w.getStaticChildren();
		if (statics != null)
		{
			for (Widget k : statics)
			{
				revalidateTree(k, depth + 1);
			}
		}
	}
}
