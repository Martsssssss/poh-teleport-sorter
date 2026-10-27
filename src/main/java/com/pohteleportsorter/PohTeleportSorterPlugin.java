package com.pohteleportsorter;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.inject.Inject;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import java.awt.event.KeyEvent;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "POH Teleport Sorter",
	description = "Sort, pin, search and set your own hotkeys for the Portal Nexus and ornate jewellery box teleports",
	tags = {"poh", "portal", "nexus", "teleport", "sort", "house", "jewellery", "jewelry", "box", "pin", "search", "recent", "hotkey", "keybind"}
)
public class PohTeleportSorterPlugin extends Plugin implements KeyListener
{

	// telenexus_teleport_init (onload of interface 17) and the server-run list rebuild wrapper
	private static final int SCRIPT_TELENEXUS_TELEPORT_INIT = 2672;
	private static final int SCRIPT_TELENEXUS_LIST_REBUILD = 2673;
	// Finds a component's child and resumes it (cc_find + cc_resume_pausebutton), as the game's nexus hotkey does
	private static final int SCRIPT_RESUME_PAUSEBUTTON = 1437;
	// The game ignores its nexus hotkeys for 20 client cycles after one is used; custom hotkeys do the same
	private static final int HOTKEY_COOLDOWN = 20;

	// Matches telenexus_options: p12_full, orange 0xff981f, white on hover, text shadow
	private static final int TEXT_COLOUR = 0xff981f;
	private static final int TEXT_HOVER_COLOUR = 0xffffff;

	// radio_buttons frames as used by the Teleport/Scry box
	private static final int RADIO_OFF = SpriteID.RadioButtons._0;
	private static final int RADIO_OFF_HOVER = SpriteID.RadioButtons._4;
	private static final int RADIO_ON = SpriteID.RadioButtons._2;
	private static final int RADIO_ON_HOVER = SpriteID.RadioButtons._6;

	// Box geometry. Same right margin as the Teleport/Scry stonebox; both boxes are widened to this
	// width so "Alphabetical (Ancient)" fits. Row gap matches the 23px gap in the Teleport/Scry box.
	private static final int BOX_RIGHT = 10;
	private static final int BOX_TOP = 8;
	private static final int BOX_WIDTH = 172;
	private static final int ROW_STEP = 23;
	private static final int BOX_HEIGHT = 14 + 3 + ROW_STEP * (SortMode.values().length - 1) + 18 + 3;
	private static final int INNER_INSET_X = 10;
	private static final int INNER_INSET_Y = 7;

	// The nexus title bar's clear band and left inner edge, from the game's stoneborder script (title text
	// box at y 6, height 23) and measured against the game: the divider starts at y 29, the border ends at x 6
	private static final TeleportSearch.Band SEARCH_BAND = new TeleportSearch.Band(6, 29 + TitleBar.EXTRA, 6);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private PohTeleportSorterConfig config;

	@Inject
	private EventBus eventBus;

	@Inject
	private JewelleryBoxSorter jewelleryBoxSorter;

	@Inject
	private PinStore pins;

	@Inject
	private KeyManager keyManager;

	@Inject
	private TeleportSearch search;

	@Inject
	private TeleportHotkeys hotkeys;

	private int lastHotkeyCycle;

	@Inject
	private SpriteManager spriteManager;

	// The rows as last laid out, used to work out which teleport a right-click is on
	private final List<Entry> currentEntries = new ArrayList<>();

	private final List<Widget> boxWidgets = new ArrayList<>();
	private final Widget[] radioWidgets = new Widget[SortMode.values().length];
	private final Widget[] labelWidgets = new Widget[SortMode.values().length];

	private boolean modelHidden;
	private int originalScryX;
	private int originalScryY;
	private int originalScryHeight;
	private final TitleBar titleBar = new TitleBar();
	private int originalOptionsWidth = -1;

	// The game's own text for each row, so the ancient renaming can be undone when switching mode
	private final java.util.Map<Widget, String> originalTexts = new java.util.IdentityHashMap<>();

	@AllArgsConstructor
	private static class Entry
	{
		final Widget text;
		final Widget bg;
		final Widget icon;
		final int originalY;
		final String originalText;
		final String name;
		final NexusTeleport teleport;
		final int set; // 1 = main rows, 2 = the extra rows (Seers' Village, Grand Exchange, Yanille, Last Boat)
		final int index;
		final String hotkey;

		String pinKey()
		{
			return teleport != null ? teleport.getGameName() : name;
		}
	}

	@Provides
	PohTeleportSorterConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PohTeleportSorterConfig.class);
	}

	@Override
	protected void startUp()
	{
		spriteManager.addSpriteOverrides(PinSprite.values());
		eventBus.register(jewelleryBoxSorter);
		eventBus.register(search);
		eventBus.register(hotkeys);
		// The search is registered first, so while typing it consumes keys before the custom hotkeys, and those
		// consume the keys they use before the recorder of game hotkeys below
		keyManager.registerKeyListener(search);
		keyManager.registerKeyListener(hotkeys);
		keyManager.registerKeyListener(this);
		clientThread.invokeLater(() ->
		{
			if (client.getWidget(InterfaceID.TelenexusTeleport.CONTENT) != null)
			{
				setup(true);
			}
		});
	}

	@Override
	protected void shutDown()
	{
		eventBus.unregister(jewelleryBoxSorter);
		eventBus.unregister(search);
		eventBus.unregister(hotkeys);
		keyManager.unregisterKeyListener(search);
		keyManager.unregisterKeyListener(hotkeys);
		keyManager.unregisterKeyListener(this);
		hotkeys.clearTargets(TeleportHotkeys.Kind.NEXUS);
		hotkeys.clearTargets(TeleportHotkeys.Kind.JEWELLERY);
		jewelleryBoxSorter.shutDown();
		pins.saveIfChanged();
		clientThread.invokeLater(() ->
		{
			removeBox();
			restoreModel();
			restoreOptionsBox();
			search.detach();
			titleBar.restore();
			sortList(SortMode.DEFAULT);
			hidePinIcons();
			// Back to exactly the game's own text (no ancient renaming or Wilderness styling)
			for (Entry e : currentEntries)
			{
				e.text.setText(e.originalText);
			}
			clearState();
			hotkeys.clearTargets(TeleportHotkeys.Kind.NEXUS);
			spriteManager.removeSpriteOverrides(PinSprite.values());
		});
	}

	/** Saves pins, recent use and the chosen order/layout as soon as either window closes. */
	@Subscribe
	public void onWidgetClosed(net.runelite.api.events.WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.TELENEXUS_TELEPORT)
		{
			hotkeys.clearTargets(TeleportHotkeys.Kind.NEXUS);
			pins.saveIfChanged();
		}
		else if (event.getGroupId() == InterfaceID.POH_JEWELLERY_BOX)
		{
			hotkeys.clearTargets(TeleportHotkeys.Kind.JEWELLERY);
			pins.saveIfChanged();
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		int id = event.getScriptId();
		if (id == SCRIPT_TELENEXUS_TELEPORT_INIT)
		{
			// Fresh window: any widget references we held belong to the old one
			clearState();
			setup(true);
		}
		else if (id == SCRIPT_TELENEXUS_LIST_REBUILD)
		{
			// The game recreated the rows with its own text
			originalTexts.clear();
			hiddenBySearch.clear();
			setup(false);
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!PohTeleportSorterConfig.GROUP.equals(event.getGroup())
			|| !(PohTeleportSorterConfig.SORT_MODE.equals(event.getKey())
				|| "showSelector".equals(event.getKey())
				|| PohTeleportSorterConfig.WILD_ASTERISK.equals(event.getKey())
				|| PohTeleportSorterConfig.WILD_HIGHLIGHT.equals(event.getKey())))
		{
			return;
		}

		clientThread.invokeLater(() ->
		{
			if (client.getWidget(InterfaceID.TelenexusTeleport.CONTENT) == null)
			{
				return;
			}

			if ("showSelector".equals(event.getKey()))
			{
				removeBox();
				restoreModel();
				restoreOptionsBox();
				setup(false);
			}
			else
			{
				sortList(config.sortMode());
				refreshRadios();
			}
		});
	}

	private void setup(boolean freshLoad)
	{
		Widget universe = client.getWidget(InterfaceID.TelenexusTeleport.UNIVERSE);
		Widget frame = client.getWidget(InterfaceID.TelenexusTeleport.FRAME);
		boolean withSearch = freshLoad && config.showSearch() && search.isAvailable() && universe != null;
		// The title bar is made taller first, so everything below is laid out in the space that's left
		if (withSearch)
		{
			extendTitleBar(frame);
		}
		if (config.showSelector() && (freshLoad || boxWidgets.isEmpty()))
		{
			buildBox();
		}
		if (withSearch)
		{
			search.attach(universe, frame, SEARCH_BAND, () -> sortList(config.sortMode()));
		}
		sortList(config.sortMode());
		refreshRadios();
	}

	/**
	 * Makes the nexus title bar {@link TitleBar#EXTRA} taller: the title and close button move down by half
	 * of that so they stay centred, the divider (stoneborder children 10 to 12) moves down by all of it, and
	 * the window's content starts that much lower, keeping its bottom where it was.
	 */
	private void extendTitleBar(Widget frame)
	{
		Widget title = frame != null ? frame.getChild(1) : null;
		// stoneborder puts the title at y 6; anything else means this window's bar was already moved
		if (title == null || titleBar.isExtended() || title.getOriginalY() != 6)
		{
			return;
		}
		int half = TitleBar.EXTRA / 2;
		titleBar.moveDown(frame.getChild(1), half);
		for (int i = 10; i <= 12; i++)
		{
			titleBar.moveDown(frame.getChild(i), TitleBar.EXTRA);
		}
		titleBar.moveDown(frame.getChild(13), half);
		Widget content = client.getWidget(InterfaceID.TelenexusTeleport.CONTENT);
		titleBar.moveTopDown(content, TitleBar.EXTRA);
		revalidateTree(content);
	}

	private void clearState()
	{
		boxWidgets.clear();
		java.util.Arrays.fill(radioWidgets, null);
		java.util.Arrays.fill(labelWidgets, null);
		modelHidden = false;
		originalOptionsWidth = -1;
		// A new window: whatever was moved belonged to the old one
		titleBar.forget();
		originalTexts.clear();
		currentEntries.clear();
		hiddenBySearch.clear();
		waitingRows.clear();
		pinIcons.clear();
		pinIconsUsed = 0;
	}

	// ---------------------------------------------------------------- sorting

	private void sortList(SortMode mode)
	{
		List<Entry> entries = new ArrayList<>();
		collect(entries, 1, InterfaceID.TelenexusTeleport.TEXT1, InterfaceID.TelenexusTeleport.ROWS1, InterfaceID.TelenexusTeleport.GFX1);
		collect(entries, 2, InterfaceID.TelenexusTeleport.EXTRAS, InterfaceID.TelenexusTeleport.ROWS2, InterfaceID.TelenexusTeleport.GFX2);
		currentEntries.clear();
		currentEntries.addAll(entries);
		if (entries.isEmpty())
		{
			return;
		}

		List<TeleportHotkeys.Target> targets = new ArrayList<>();
		for (Entry e : entries)
		{
			targets.add(new TeleportHotkeys.Target(e.pinKey(), e.hotkey, () -> useByHotkey(e)));
		}
		hotkeys.setTargets(TeleportHotkeys.Kind.NEXUS, targets, () ->
		{
			if (client.getWidget(InterfaceID.TelenexusTeleport.CONTENT) != null)
			{
				sortList(config.sortMode());
			}
		});

		// The game's own order is the order it laid the rows out in
		entries.sort(Comparator.comparingInt(e -> e.originalY));

		List<Entry> sorted = new ArrayList<>(entries);
		switch (mode)
		{
			case ALPHABETICAL:
				sorted.sort(Comparator.comparing(e -> e.name, String.CASE_INSENSITIVE_ORDER));
				break;
			case ALPHABETICAL_ANCIENT:
				// Ancients read "Kharyll (Canifis)" and sort under the spell name; everything else as normal
				sorted.sort(Comparator.comparing(e -> NexusTeleport.spellFirst(e.name), String.CASE_INSENSITIVE_ORDER));
				break;
			case SPELLBOOK:
				sorted.sort(Comparator
					.comparingInt((Entry e) -> e.teleport != null ? e.teleport.getSpellbook().ordinal() : NexusTeleport.Spellbook.OTHER.ordinal())
					.thenComparingInt(e -> e.teleport != null ? e.teleport.ordinal() : Integer.MAX_VALUE));
				break;
			case RECENT:
			{
				// Most recently used first; never-used teleports follow in the game's order
				List<String> recent = pins.list(PinStore.Kind.RECENT_NEXUS);
				sorted.sort(Comparator.comparingInt(e ->
				{
					int i = PinStore.indexOf(recent, e.pinKey());
					return i < 0 ? Integer.MAX_VALUE : i;
				}));
				break;
			}
			default:
				break;
		}

		// Pinned teleports go to the top in every mode, keeping the mode's order among themselves.
		// List.sort is stable, so everything else keeps the order it was just given.
		List<String> pinned = pins.list(PinStore.Kind.NEXUS);
		sorted.sort(Comparator.comparingInt(e -> PinStore.indexOf(pinned, e.pinKey()) >= 0 ? 0 : 1));

		boolean asterisk = config.wildAsterisk();
		boolean highlight = config.wildHighlight();
		boolean customKeys = hotkeys.isEnabled();
		hidePinIcons();
		hiddenBySearch.clear();

		// Rows are stacked from the top in the new order, each at its own height, as the game stacks them.
		// Rows that don't match the search are hidden and take no space.
		int y = 0;
		int visible = 0;
		for (Entry e : sorted)
		{
			boolean match = search.matches(e.name, NexusTeleport.spellFirst(e.name),
				e.teleport != null ? e.teleport.getPlainName() : null);
			e.text.setHidden(!match);
			e.bg.setHidden(!match);
			if (e.icon != null)
			{
				e.icon.setHidden(!match);
			}
			if (!match)
			{
				hiddenBySearch.add(e.text);
				continue;
			}

			// The key shown at the front of the row: a custom hotkey in place of the game's, where one is set
			String shown = customKeys
				? TeleportHotkeys.relabel(TeleportHotkeys.Kind.NEXUS, e.originalText,
					hotkeys.label(TeleportHotkeys.Kind.NEXUS, e.pinKey(), e.hotkey))
				: e.originalText;
			String shownName = e.name;
			if (mode == SortMode.ALPHABETICAL_ANCIENT)
			{
				String swapped = NexusTeleport.spellFirst(e.name);
				int at = shown.lastIndexOf(e.name);
				if (!swapped.equals(e.name) && at >= 0)
				{
					shown = shown.substring(0, at) + swapped + shown.substring(at + e.name.length());
					shownName = swapped;
				}
			}
			shown = WildStyle.apply(shown, shownName, e.teleport != null && e.teleport.isWild(), asterisk, highlight);
			e.text.setText(shown);
			e.text.setOriginalY(y);
			e.bg.setOriginalY(y);
			if (e.icon != null)
			{
				e.icon.setOriginalY(y);
			}

			// Re-stripe so the alternating row shading stays in step with the new order
			if (visible % 2 == 0)
			{
				e.bg.setTextColor(0xffffff);
				e.bg.setOpacity(255);
			}
			else
			{
				e.bg.setTextColor(0x000000);
				e.bg.setOpacity(225);
			}

			e.text.revalidate();
			e.bg.revalidate();
			if (e.icon != null)
			{
				e.icon.revalidate();
			}

			int rowH = e.bg.getOriginalHeight();
			if (PinStore.indexOf(pinned, e.pinKey()) >= 0)
			{
				showPinIcon(y, rowH);
			}
			y += rowH;
			visible++;
		}
		updateScroll(y);
	}

	/**
	 * Sets the list's scroll size the way the game's own build script does (scroll only when the rows are
	 * taller than the list), then has the game's scrollbar_resize script (RuneLite's UPDATE_SCROLLBAR)
	 * redraw the scrollbar. The script runs a moment later, outside whatever script or event is running now.
	 */
	private void updateScroll(int contentHeight)
	{
		Widget list = client.getWidget(InterfaceID.TelenexusTeleport.LIST_1);
		Widget scrolling = client.getWidget(InterfaceID.TelenexusTeleport.SCROLLING1);
		if (list == null || scrolling == null)
		{
			return;
		}
		if (contentHeight > list.getHeight())
		{
			scrolling.setScrollHeight(contentHeight);
			int max = Math.max(contentHeight - scrolling.getHeight(), 0);
			scrolling.setScrollY(Math.min(scrolling.getScrollY(), max));
		}
		else
		{
			scrolling.setScrollHeight(0);
			scrolling.setScrollY(0);
		}
		scrolling.revalidateScroll();
		clientThread.invokeLater(() ->
		{
			Widget sc = client.getWidget(InterfaceID.TelenexusTeleport.SCROLLING1);
			if (sc != null && client.getWidget(InterfaceID.TelenexusTeleport.SCROLLBAR1) != null)
			{
				client.runScript(ScriptID.UPDATE_SCROLLBAR, InterfaceID.TelenexusTeleport.SCROLLBAR1,
					InterfaceID.TelenexusTeleport.SCROLLING1, sc.getScrollY());
			}
		});
	}

	// ---------------------------------------------------------------- pin icons

	/** Pin icons, drawn in the list's scrolling layer so they scroll with the rows, at each pinned row's right end. */
	private final List<Widget> pinIcons = new ArrayList<>();
	private int pinIconsUsed;

	private void hidePinIcons()
	{
		pinIconsUsed = 0;
		for (Widget w : pinIcons)
		{
			w.setHidden(true);
		}
	}

	private void showPinIcon(int rowY, int rowH)
	{
		Widget scrolling = client.getWidget(InterfaceID.TelenexusTeleport.SCROLLING1);
		if (scrolling == null)
		{
			return;
		}
		Widget icon;
		if (pinIconsUsed < pinIcons.size())
		{
			icon = pinIcons.get(pinIconsUsed);
		}
		else
		{
			icon = scrolling.createChild(-1, WidgetType.GRAPHIC);
			icon.setSpriteId(PinSprite.PIN.getSpriteId());
			pinIcons.add(icon);
		}
		pinIconsUsed++;
		icon.setXPositionMode(WidgetPositionMode.ABSOLUTE_RIGHT);
		icon.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
		icon.setWidthMode(WidgetSizeMode.ABSOLUTE);
		icon.setHeightMode(WidgetSizeMode.ABSOLUTE);
		icon.setOriginalX(6);
		icon.setOriginalY(rowY + (rowH - PinSprite.HEIGHT) / 2);
		icon.setOriginalWidth(PinSprite.WIDTH);
		icon.setOriginalHeight(PinSprite.HEIGHT);
		icon.setHidden(false);
		icon.revalidate();
	}

	// ---------------------------------------------------------------- pinning and recent use

	private static final int[] SET1_LAYERS = {
		InterfaceID.TelenexusTeleport.TEXT1, InterfaceID.TelenexusTeleport.ROWS1,
		InterfaceID.TelenexusTeleport.GFX1, InterfaceID.TelenexusTeleport.KEY_LISTENERS,
	};
	private static final int[] SET2_LAYERS = {
		InterfaceID.TelenexusTeleport.EXTRAS, InterfaceID.TelenexusTeleport.ROWS2,
		InterfaceID.TelenexusTeleport.GFX2, InterfaceID.TelenexusTeleport.EXTRA_KEY_LISTENERS,
	};
	private static final int RECENT_LIMIT = 50;

	/**
	 * Adds "Pin" or "Unpin" to the right-click menu of a nexus teleport row. It goes just above Cancel and
	 * is only added when the menu is opened, so the left-click option is always the game's own.
	 */
	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (currentEntries.isEmpty() || client.getWidget(InterfaceID.TelenexusTeleport.CONTENT) == null)
		{
			return;
		}
		Entry row = null;
		for (MenuEntry me : event.getMenuEntries())
		{
			row = rowFor(me.getParam1(), me.getParam0());
			if (row != null)
			{
				break;
			}
		}
		if (row == null)
		{
			return;
		}

		final Entry target = row;
		boolean isPinned = pins.isPinned(PinStore.Kind.NEXUS, target.pinKey());
		client.getMenu().createMenuEntry(1)
			.setOption(isPinned ? "Unpin" : "Pin")
			.setTarget("<col=ff9040>" + target.name + "</col>")
			.setType(MenuAction.RUNELITE)
			.onClick(me ->
			{
				pins.toggle(PinStore.Kind.NEXUS, target.pinKey());
				sortList(config.sortMode());
			});

		if (hotkeys.isEnabled())
		{
			client.getMenu().createMenuEntry(1)
				.setOption("Set hotkey")
				.setTarget("<col=ff9040>" + target.name + "</col>")
				.setType(MenuAction.RUNELITE)
				.onClick(me ->
				{
					search.stopTyping();
					hotkeys.startCapture(TeleportHotkeys.Kind.NEXUS, target.pinKey());
				});
			if (hotkeys.get(TeleportHotkeys.Kind.NEXUS, target.pinKey()) != null)
			{
				client.getMenu().createMenuEntry(1)
					.setOption("Reset hotkey")
					.setTarget("<col=ff9040>" + target.name + "</col>")
					.setType(MenuAction.RUNELITE)
					.onClick(me ->
					{
						hotkeys.reset(TeleportHotkeys.Kind.NEXUS, target.pinKey());
						sortList(config.sortMode());
					});
			}
		}
	}

	/**
	 * Uses a teleport by its custom hotkey, exactly as the game's own hotkey does: resumes the row's key
	 * listener component. In scry mode that scries, as the game's hotkey would. Allowed to reach the server in
	 * the same way as Better Teleport Menu and RuneLite's own bank plugin run game scripts.
	 */
	private void useByHotkey(Entry e)
	{
		if (client.getGameCycle() < lastHotkeyCycle + HOTKEY_COOLDOWN)
		{
			return;
		}
		int layer = e.set == 1 ? InterfaceID.TelenexusTeleport.KEY_LISTENERS : InterfaceID.TelenexusTeleport.EXTRA_KEY_LISTENERS;
		Widget listeners = client.getWidget(layer);
		if (listeners == null || listeners.getChild(e.index) == null)
		{
			return;
		}
		lastHotkeyCycle = client.getGameCycle();
		search.stopTyping();
		client.createScriptEventBuilder(SCRIPT_RESUME_PAUSEBUTTON, layer, e.index)
			.build()
			.setCanSendPackets(true)
			.run();
		recordUse(e);
	}

	/** Records a teleport clicked in the nexus list (not in scry mode, which only looks). */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (event.getMenuAction() == MenuAction.RUNELITE || currentEntries.isEmpty()
			|| client.getWidget(InterfaceID.TelenexusTeleport.CONTENT) == null)
		{
			return;
		}
		Entry row = rowFor(event.getParam1(), event.getParam0());
		if (row != null)
		{
			search.stopTyping();
			recordUse(row);
		}
	}

	// Rows showing "Please wait...", so each use is recorded once
	private final java.util.Set<Widget> waitingRows = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

	/**
	 * Records a teleport used through Better Teleport Menu's hotkeys, which never reach this plugin: that plugin
	 * writes "Please wait..." on the row it's teleporting with.
	 */
	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (currentEntries.isEmpty())
		{
			if (!waitingRows.isEmpty())
			{
				waitingRows.clear();
			}
			return;
		}
		for (Entry e : currentEntries)
		{
			String t = e.text.getText();
			if (t != null && t.contains("Please wait"))
			{
				if (waitingRows.add(e.text))
				{
					recordUse(e);
				}
			}
			else
			{
				waitingRows.remove(e.text);
			}
		}
	}

	/** Records a teleport used by its hotkey. Search typing never reaches here (the search consumes it first). */
	@Override
	public void keyTyped(KeyEvent e)
	{
		char c = e.getKeyChar();
		if (c == KeyEvent.CHAR_UNDEFINED || Character.isISOControl(c))
		{
			return;
		}
		clientThread.invoke(() ->
		{
			if (client.getWidget(InterfaceID.TelenexusTeleport.CONTENT) == null)
			{
				return;
			}
			for (Entry row : currentEntries)
			{
				if (!row.hotkey.isEmpty() && row.hotkey.equalsIgnoreCase(String.valueOf(c)))
				{
					recordUse(row);
					return;
				}
			}
		});
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
	}

	private void recordUse(Entry row)
	{
		if (client.getVarbitValue(VarbitID.POH_NEXUS_TELE_SCRY_MODE) == 1)
		{
			return;
		}
		pins.recordUse(PinStore.Kind.RECENT_NEXUS, row.pinKey(), RECENT_LIMIT);
	}

	/** The row a menu entry belongs to, from the entry's component and child index. */
	private Entry rowFor(int componentId, int childIndex)
	{
		int set = 0;
		for (int id : SET1_LAYERS)
		{
			if (id == componentId)
			{
				set = 1;
			}
		}
		for (int id : SET2_LAYERS)
		{
			if (id == componentId)
			{
				set = 2;
			}
		}
		if (set == 0)
		{
			return null;
		}
		for (Entry e : currentEntries)
		{
			if (e.set == set && e.index == childIndex)
			{
				return e;
			}
		}
		return null;
	}

	// Rows this plugin hid for the search, so they aren't mistaken for rows the game hid itself
	private final java.util.Set<Widget> hiddenBySearch = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

	private void collect(List<Entry> out, int set, int textId, int bgId, int iconId)
	{
		Widget textLayer = client.getWidget(textId);
		Widget bgLayer = client.getWidget(bgId);
		Widget iconLayer = client.getWidget(iconId);
		if (textLayer == null || bgLayer == null)
		{
			return;
		}

		Widget[] texts = textLayer.getDynamicChildren();
		Widget[] bgs = bgLayer.getDynamicChildren();
		Widget[] icons = iconLayer != null ? iconLayer.getDynamicChildren() : new Widget[0];

		for (int i = 0; i < texts.length && i < bgs.length; i++)
		{
			Widget text = texts[i];
			Widget bg = bgs[i];
			if (text == null || bg == null)
			{
				continue;
			}
			// The game hides extra rows you can't use yet; skip those, but not rows hidden by the search
			if ((text.isSelfHidden() || bg.isSelfHidden()) && !hiddenBySearch.contains(text))
			{
				continue;
			}

			String original = originalTexts.computeIfAbsent(text, Widget::getText);

			// "<col=ffffff>C</col> :  Camelot" -> hotkey "C", name "Camelot"
			String raw = Text.removeTags(original);
			int colon = raw.indexOf(':');
			String name = (colon >= 0 ? raw.substring(colon + 1) : raw).trim();
			String hotkey = colon > 0 ? raw.substring(0, colon).trim() : "";
			Widget icon = i < icons.length ? icons[i] : null;

			out.add(new Entry(text, bg, icon, bg.getOriginalY(), original, name, NexusTeleport.fromName(name), set, i, hotkey));
		}
	}

	// ---------------------------------------------------------------- selector box

	private void buildBox()
	{
		Widget content = client.getWidget(InterfaceID.TelenexusTeleport.CONTENT);
		if (content == null)
		{
			return;
		}

		removeBox();

		// Stonebox borders, same sprites and offsets as the game's stonebox(…, 0)
		border(content, SpriteID.V2StoneBorders._18, 0, 0, 32, 32);
		border(content, SpriteID.V2StoneBorders._19, BOX_WIDTH - 32, 0, 32, 32);
		border(content, SpriteID.V2StoneBorders._20, 0, BOX_HEIGHT - 32, 32, 32);
		border(content, SpriteID.V2StoneBorders._21, BOX_WIDTH - 32, BOX_HEIGHT - 32, 32, 32);
		border(content, SpriteID.V2StoneBorders._8, 32, -14, BOX_WIDTH - 64, 32);
		border(content, SpriteID.V2StoneBorders._8, 32, BOX_HEIGHT - 20, BOX_WIDTH - 64, 32);
		border(content, SpriteID.V2StoneBorders._13, -14, 32, 32, BOX_HEIGHT - 64);
		border(content, SpriteID.V2StoneBorders._13, BOX_WIDTH - 20, 32, 32, BOX_HEIGHT - 64);

		int innerWidth = BOX_WIDTH - INNER_INSET_X * 2;
		SortMode[] modes = SortMode.values();
		for (int i = 0; i < modes.length; i++)
		{
			SortMode mode = modes[i];
			int rowY = INNER_INSET_Y + 3 + ROW_STEP * i;

			Widget radio = content.createChild(-1, WidgetType.GRAPHIC);
			place(radio, INNER_INSET_X + 5, rowY - 1, 17, 17);
			radio.setSpriteId(RADIO_OFF);
			radio.revalidate();
			boxWidgets.add(radio);
			radioWidgets[i] = radio;

			Widget label = content.createChild(-1, WidgetType.TEXT);
			// Centred in the space right of the radio, so long labels never run into it
			place(label, INNER_INSET_X + 24, rowY, innerWidth - 24, 18);
			label.setText(mode.getLabel());
			label.setFontId(FontID.PLAIN_12);
			label.setTextColor(TEXT_COLOUR);
			label.setTextShadowed(true);
			label.setXTextAlignment(WidgetTextAlignment.CENTER);
			label.setYTextAlignment(WidgetTextAlignment.CENTER);
			// Both the circle and its label select the order, as the game's own Teleport/Scry buttons do
			final int index = i;
			for (Widget w : new Widget[]{radio, label})
			{
				w.setHasListener(true);
				w.setAction(0, "Sort");
				w.setName("<col=ff9040>" + mode.getName() + "</col>");
				w.setOnOpListener((JavaScriptCallback) ev -> select(mode));
				w.setOnMouseOverListener((JavaScriptCallback) ev -> hover(index, true));
				w.setOnMouseLeaveListener((JavaScriptCallback) ev -> hover(index, false));
				w.revalidate();
			}
			boxWidgets.add(label);
			labelWidgets[i] = label;
		}

		widenOptionsBox();
		hideModel();
	}

	/** Widens the game's Teleport/Scry box to match the sort box. Its borders and rows size relative to it. */
	private void widenOptionsBox()
	{
		Widget frame = client.getWidget(InterfaceID.TelenexusTeleport.OPTIONS_FRAME);
		if (frame == null || originalOptionsWidth != -1)
		{
			return;
		}
		originalOptionsWidth = frame.getOriginalWidth();
		frame.setOriginalWidth(BOX_WIDTH);
		revalidateTree(frame);
	}

	private void restoreOptionsBox()
	{
		if (originalOptionsWidth == -1)
		{
			return;
		}
		Widget frame = client.getWidget(InterfaceID.TelenexusTeleport.OPTIONS_FRAME);
		if (frame != null)
		{
			frame.setOriginalWidth(originalOptionsWidth);
			revalidateTree(frame);
		}
		originalOptionsWidth = -1;
	}

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
			return;
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

	private void border(Widget parent, int sprite, int x, int y, int w, int h)
	{
		Widget g = parent.createChild(-1, WidgetType.GRAPHIC);
		place(g, x, y, w, h);
		g.setSpriteId(sprite);
		g.setSpriteTiling(true);
		g.revalidate();
		boxWidgets.add(g);
	}

	/**
	 * Positions a widget by its offset from the box's top-left, anchored to the content layer's right edge
	 * so it lines up with the Teleport/Scry box regardless of the window's width.
	 */
	private static void place(Widget w, int x, int y, int width, int height)
	{
		w.setXPositionMode(WidgetPositionMode.ABSOLUTE_RIGHT);
		w.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
		w.setOriginalX(BOX_RIGHT + BOX_WIDTH - (x + width));
		w.setOriginalY(BOX_TOP + y);
		w.setWidthMode(WidgetSizeMode.ABSOLUTE);
		w.setHeightMode(WidgetSizeMode.ABSOLUTE);
		w.setOriginalWidth(width);
		w.setOriginalHeight(height);
	}

	private void select(SortMode mode)
	{
		configManager.setConfiguration(PohTeleportSorterConfig.GROUP, PohTeleportSorterConfig.SORT_MODE, mode);
		pins.markChanged();
		// Apply straight away rather than waiting for the config event round trip
		sortList(mode);
		refreshRadios(mode);
	}

	private void hover(int index, boolean over)
	{
		Widget radio = radioWidgets[index];
		Widget label = labelWidgets[index];
		if (radio == null || label == null)
		{
			return;
		}
		boolean selected = config.sortMode().ordinal() == index;
		radio.setSpriteId(selected ? (over ? RADIO_ON_HOVER : RADIO_ON) : (over ? RADIO_OFF_HOVER : RADIO_OFF));
		label.setTextColor(over ? TEXT_HOVER_COLOUR : TEXT_COLOUR);
	}

	private void refreshRadios()
	{
		refreshRadios(config.sortMode());
	}

	private void refreshRadios(SortMode current)
	{
		for (int i = 0; i < radioWidgets.length; i++)
		{
			if (radioWidgets[i] != null)
			{
				radioWidgets[i].setSpriteId(current.ordinal() == i ? RADIO_ON : RADIO_OFF);
			}
		}
	}

	private void removeBox()
	{
		for (Widget w : boxWidgets)
		{
			w.setHidden(true);
			w.setHasListener(false);
		}
		boxWidgets.clear();
		java.util.Arrays.fill(radioWidgets, null);
		java.util.Arrays.fill(labelWidgets, null);
	}

	// ---------------------------------------------------------------- nexus model

	/**
	 * Hides the portal model while the sort box is shown, so the two boxes stack cleanly. The game's
	 * "Click here to scry house portal." text, shown in scry mode where the model was, is centred in the
	 * space between the two boxes instead.
	 */
	private void hideModel()
	{
		Widget model = client.getWidget(InterfaceID.TelenexusTeleport.NEXUS_MODEL);
		if (model == null || modelHidden)
		{
			return;
		}
		modelHidden = true;
		model.setHidden(true);

		Widget scry = client.getWidget(InterfaceID.TelenexusTeleport.SCRY_PORTAL);
		Widget content = client.getWidget(InterfaceID.TelenexusTeleport.CONTENT);
		Widget options = client.getWidget(InterfaceID.TelenexusTeleport.OPTIONS_FRAME);
		if (scry != null && content != null && options != null)
		{
			originalScryX = scry.getOriginalX();
			originalScryY = scry.getOriginalY();
			originalScryHeight = scry.getOriginalHeight();
			int top = BOX_TOP + BOX_HEIGHT;
			// The Teleport/Scry box sits its own y (10) above the content's bottom edge
			int bottom = content.getHeight() - options.getOriginalY() - options.getHeight();
			// Centred under the two boxes, which share the same right margin and width
			int boxCentre = content.getWidth() - BOX_RIGHT - BOX_WIDTH / 2;
			scry.setOriginalX(boxCentre - scry.getWidth() / 2);
			scry.setOriginalY(top);
			scry.setOriginalHeight(Math.max(20, bottom - top));
			scry.revalidate();
		}
	}

	private void restoreModel()
	{
		if (!modelHidden)
		{
			return;
		}
		Widget model = client.getWidget(InterfaceID.TelenexusTeleport.NEXUS_MODEL);
		if (model != null)
		{
			model.setHidden(false);
		}
		Widget scry = client.getWidget(InterfaceID.TelenexusTeleport.SCRY_PORTAL);
		if (scry != null)
		{
			scry.setOriginalX(originalScryX);
			scry.setOriginalY(originalScryY);
			scry.setOriginalHeight(originalScryHeight);
			scry.revalidate();
		}
		modelHidden = false;
	}
}
