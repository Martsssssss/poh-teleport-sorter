package com.pohteleportsorter;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.EnumMap;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.events.ClientTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Keybind;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;

/**
 * Custom hotkeys for the Portal Nexus and jewellery box teleports.
 *
 * Right-click a teleport and choose "Set hotkey", then press a key (Shift, Ctrl and Alt combinations work; Esc
 * cancels). The row then shows that key in place of the game's. "Reset hotkey" gives it the game's key back.
 *
 * With no custom hotkeys set for a window, nothing here touches its keys: the game's own hotkeys run as normal.
 * Once a window has custom hotkeys, a key press that matches one is consumed (so the game never sees it) and
 * runs that teleport the same way the game's own hotkey or click would. A teleport's old game key is consumed
 * and ignored, so each teleport answers to exactly the key shown next to it. Any other key reaches the game.
 *
 * While Better Teleport Menu is on, all of this steps aside (see {@link BetterTeleportMenu}).
 */
@Singleton
public class TeleportHotkeys implements KeyListener
{
	public enum Kind
	{
		NEXUS(PohTeleportSorterConfig.HOTKEYS_NEXUS),
		JEWELLERY(PohTeleportSorterConfig.HOTKEYS_JEWELLERY);

		private final String key;

		Kind(String key)
		{
			this.key = key;
		}
	}

	/** One teleport in the open window: its name, the game's own key for it (or ""), and how to use it. */
	static final class Target
	{
		final String name;
		final String gameKey;
		final Runnable trigger;

		Target(String name, String gameKey, Runnable trigger)
		{
			this.name = name;
			this.gameKey = gameKey == null ? "" : gameKey;
			this.trigger = trigger;
		}
	}

	/** Shown in place of the key while waiting for the key to bind. */
	static final String PROMPT = "Press a key";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private PinStore pins;

	@Inject
	private BetterTeleportMenu betterTeleportMenu;

	// The teleports in whichever window is open (only one can be), read by the key listener on the input thread
	private volatile Kind openKind;
	private volatile List<Target> targets = Collections.emptyList();
	// Redraws the open window's labels after a hotkey changes
	private volatile Runnable redraw;

	// Waiting for the key to bind to this teleport
	private volatile Kind captureKind;
	private volatile String captureName;

	// The key press just consumed: its typed event is consumed too, so no stray character reaches the game
	private volatile boolean swallowTyped;

	/** Called on the client thread whenever a window lays its teleports out. */
	void setTargets(Kind kind, List<Target> list, Runnable redrawWindow)
	{
		openKind = kind;
		targets = Collections.unmodifiableList(new ArrayList<>(list));
		redraw = redrawWindow;
	}

	/** Called when a window closes. */
	void clearTargets(Kind kind)
	{
		if (openKind == kind)
		{
			openKind = null;
			targets = Collections.emptyList();
			redraw = null;
		}
		if (captureKind == kind)
		{
			captureKind = null;
			captureName = null;
		}
	}

	/** Forgets a window's teleports as soon as it's gone, however it closed, so no key is ever taken without one open. */
	@Subscribe
	public void onClientTick(ClientTick event)
	{
		Kind kind = openKind;
		if (kind != null && !isOpen(kind))
		{
			clearTargets(kind);
		}
	}

	/** Custom hotkeys work (and can be set) unless Better Teleport Menu is on. */
	boolean isEnabled()
	{
		return !betterTeleportMenu.isActive();
	}

	// ---------------------------------------------------------------- stored hotkeys

	// The last stored text read for each window and what it parsed to, so labels drawn every frame don't re-parse
	private final Map<Kind, String> cachedRaw = new EnumMap<>(Kind.class);
	private final Map<Kind, Map<String, Keybind>> cachedMap = new EnumMap<>(Kind.class);

	/** The custom hotkeys for a window, by teleport name, in the order they were set. Read-only. */
	synchronized Map<String, Keybind> view(Kind kind)
	{
		String raw = configManager.getConfiguration(PohTeleportSorterConfig.GROUP, kind.key);
		Map<String, Keybind> map = cachedMap.get(kind);
		if (map == null || !Objects.equals(raw, cachedRaw.get(kind)))
		{
			map = Collections.unmodifiableMap(parse(raw));
			cachedRaw.put(kind, raw);
			cachedMap.put(kind, map);
		}
		return map;
	}

	/** A copy of the custom hotkeys for a window, to change and save. */
	private Map<String, Keybind> all(Kind kind)
	{
		return new LinkedHashMap<>(view(kind));
	}

	private static Map<String, Keybind> parse(String raw)
	{
		Map<String, Keybind> out = new LinkedHashMap<>();
		if (raw == null)
		{
			return out;
		}
		for (String line : raw.split("\n"))
		{
			String[] parts = line.split("\t");
			if (parts.length != 3 || parts[0].trim().isEmpty())
			{
				continue;
			}
			try
			{
				Keybind k = new Keybind(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
				if (k.getKeyCode() != KeyEvent.VK_UNDEFINED)
				{
					out.put(parts[0].trim(), k);
				}
			}
			catch (NumberFormatException ignored)
			{
				// A damaged line is skipped
			}
		}
		return out;
	}

	Keybind get(Kind kind, String name)
	{
		return find(view(kind), name);
	}

	/** Binds the key to the teleport, taking it off any other teleport that had it. */
	private void set(Kind kind, String name, Keybind key)
	{
		Map<String, Keybind> map = all(kind);
		map.values().removeIf(key::equals);
		removeName(map, name);
		map.put(name.trim(), key);
		save(kind, map);
	}

	void reset(Kind kind, String name)
	{
		Map<String, Keybind> map = all(kind);
		removeName(map, name);
		save(kind, map);
	}

	private void save(Kind kind, Map<String, Keybind> map)
	{
		if (map.isEmpty())
		{
			configManager.unsetConfiguration(PohTeleportSorterConfig.GROUP, kind.key);
		}
		else
		{
			StringBuilder sb = new StringBuilder();
			for (Map.Entry<String, Keybind> e : map.entrySet())
			{
				if (sb.length() > 0)
				{
					sb.append('\n');
				}
				sb.append(e.getKey()).append('\t').append(e.getValue().getKeyCode()).append('\t').append(e.getValue().getModifiers());
			}
			configManager.setConfiguration(PohTeleportSorterConfig.GROUP, kind.key, sb.toString());
		}
		pins.markChanged();
	}

	private static Keybind find(Map<String, Keybind> map, String name)
	{
		for (Map.Entry<String, Keybind> e : map.entrySet())
		{
			if (e.getKey().equalsIgnoreCase(name.trim()))
			{
				return e.getValue();
			}
		}
		return null;
	}

	private static void removeName(Map<String, Keybind> map, String name)
	{
		map.keySet().removeIf(k -> k.equalsIgnoreCase(name.trim()));
	}

	// ---------------------------------------------------------------- labels

	/**
	 * The key to show next to a teleport: "Press a key" while one is being chosen for it, its custom hotkey if it
	 * has one, otherwise the game's own key, unless another teleport's custom hotkey has taken that key. Empty
	 * for no key. With Better Teleport Menu on, always the game's own key (that plugin shows its own).
	 */
	String label(Kind kind, String name, String gameKey)
	{
		String game = gameKey == null ? "" : gameKey;
		if (!isEnabled())
		{
			return game;
		}
		if (kind == captureKind && captureName != null && captureName.equalsIgnoreCase(name.trim()))
		{
			return PROMPT;
		}
		Map<String, Keybind> map = view(kind);
		Keybind own = find(map, name);
		if (own != null)
		{
			return own.toString();
		}
		if (!game.isEmpty())
		{
			for (Keybind k : map.values())
			{
				if (takesGameKey(k, game))
				{
					return "";
				}
			}
		}
		return game;
	}

	/** Whether pressing the custom hotkey is the same as pressing that game key, so the game key now means it. */
	private static boolean takesGameKey(Keybind k, String gameKey)
	{
		return k.getModifiers() == 0 && KeyEvent.getKeyText(k.getKeyCode()).equalsIgnoreCase(gameKey);
	}

	/**
	 * Swaps the key at the front of the game's row text for another. The nexus writes rows as
	 * "<col=ffffff>C</col> :  Camelot" and the jewellery box as "<col=ccccff>1:</col> Ring of dueling"; rows past
	 * the game's 35 keys have no key at all. An empty key leaves just the name.
	 */
	static String relabel(Kind kind, String gameText, String key)
	{
		if (gameText == null)
		{
			return null;
		}
		String rest = gameText;
		String sep = kind == Kind.NEXUS ? "</col> :  " : "</col> ";
		String open = kind == Kind.NEXUS ? "<col=ffffff>" : "<col=ccccff>";
		if (gameText.startsWith(open))
		{
			int at = gameText.indexOf(sep);
			if (at > 0)
			{
				rest = gameText.substring(at + sep.length());
			}
		}
		if (key == null || key.isEmpty())
		{
			return rest;
		}
		return kind == Kind.NEXUS
			? open + key + sep + rest
			: open + key + ":" + sep + rest;
	}

	// ---------------------------------------------------------------- choosing a key

	/** Starts waiting for the key to bind to this teleport. Called on the client thread. */
	void startCapture(Kind kind, String name)
	{
		captureKind = kind;
		captureName = name.trim();
		runRedraw();
	}

	private void finishCapture(KeyEvent e)
	{
		Kind kind = captureKind;
		String name = captureName;
		captureKind = null;
		captureName = null;
		if (kind != null && name != null && e.getKeyCode() != KeyEvent.VK_ESCAPE)
		{
			set(kind, name, new Keybind(e));
		}
		clientThread.invoke(this::runRedraw);
	}

	private void runRedraw()
	{
		Runnable r = redraw;
		if (r != null)
		{
			r.run();
		}
	}

	// ---------------------------------------------------------------- keys

	@Override
	public void keyPressed(KeyEvent e)
	{
		swallowTyped = false;
		if (captureKind != null)
		{
			// Shift, Ctrl and Alt on their own just build up the combination
			if (Keybind.getModifierForKeyCode(e.getKeyCode()) != null || e.getKeyCode() == KeyEvent.VK_UNDEFINED)
			{
				return;
			}
			e.consume();
			swallowTyped = true;
			finishCapture(e);
			return;
		}

		Kind kind = openKind;
		List<Target> list = targets;
		if (kind == null || list.isEmpty() || !isEnabled())
		{
			return;
		}
		Map<String, Keybind> map = view(kind);
		if (map.isEmpty())
		{
			// No custom hotkeys for this window: the game's own run untouched
			return;
		}

		for (Target t : list)
		{
			Keybind k = find(map, t.name);
			if (k != null && k.matches(e))
			{
				e.consume();
				swallowTyped = true;
				clientThread.invoke(() ->
				{
					if (isOpen(kind))
					{
						t.trigger.run();
					}
				});
				return;
			}
		}

		// A teleport's old game key no longer runs it: the key shown next to each teleport is the one that works
		char c = e.getKeyChar();
		if (c == KeyEvent.CHAR_UNDEFINED || Character.isISOControl(c))
		{
			return;
		}
		String typed = String.valueOf(c);
		for (Target t : list)
		{
			if (t.gameKey.equalsIgnoreCase(typed) && find(map, t.name) != null)
			{
				e.consume();
				swallowTyped = true;
				return;
			}
		}
	}

	@Override
	public void keyTyped(KeyEvent e)
	{
		if (swallowTyped)
		{
			swallowTyped = false;
			e.consume();
		}
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
		swallowTyped = false;
	}

	@Override
	public void focusLost()
	{
		swallowTyped = false;
	}

	private boolean isOpen(Kind kind)
	{
		return kind == Kind.NEXUS
			? client.getWidget(InterfaceID.TelenexusTeleport.CONTENT) != null
			: client.getWidget(InterfaceID.PohJewelleryBox.UNIVERSE) != null;
	}
}
