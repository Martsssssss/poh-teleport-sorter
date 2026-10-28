package com.pohteleportsorter;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.events.ClientTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;

/**
 * Custom hotkeys for the Portal Nexus and jewellery box teleports.
 *
 * Right-click a teleport and choose "Set hotkey", then press a key (a letter, number or symbol; Esc cancels).
 * "Reset hotkey" gives the teleport the game's key back.
 *
 * The plugin never presses or runs anything itself. Each teleport's hotkey is the game's own key listener, which
 * checks the typed character against the one or two characters it was given (upper and lower case). Setting a
 * hotkey just changes those characters: the game then handles the key press and the teleport exactly as it
 * always does. A teleport whose game key now belongs to another teleport is given no key at all.
 *
 * The only key this class ever takes is the one pressed while choosing a hotkey, so it can't also reach the game.
 * While Better Teleport Menu is on, all of this steps aside (see {@link BetterTeleportMenu}), and the game's
 * listeners are left exactly as they were.
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

	// The open window and how to redraw it after a hotkey changes
	private volatile Kind openKind;
	private volatile Runnable redraw;

	// Waiting for the key to bind to this teleport
	private volatile Kind captureKind;
	private volatile String captureName;

	// The key press that chose a hotkey: its typed and released events are consumed too
	private volatile boolean swallowTyped;

	// The game's own listeners as they were before this plugin changed them, so they can be put back exactly
	private final Map<Widget, Object[]> originalKeyListeners = new IdentityHashMap<>();
	private final Map<Widget, Object[]> originalOpListeners = new IdentityHashMap<>();

	/** Called on the client thread whenever a window lays its teleports out. */
	void setWindow(Kind kind, Runnable redrawWindow)
	{
		if (openKind != kind)
		{
			forgetListeners();
		}
		openKind = kind;
		redraw = redrawWindow;
	}

	/** Called when a window closes: its widgets are gone, so nothing needs putting back. */
	void clearWindow(Kind kind)
	{
		if (openKind == kind)
		{
			openKind = null;
			redraw = null;
			forgetListeners();
		}
		if (captureKind == kind)
		{
			captureKind = null;
			captureName = null;
		}
	}

	/** Forgets a window as soon as it's gone, however it closed. */
	@Subscribe
	public void onClientTick(ClientTick event)
	{
		Kind kind = openKind;
		if (kind != null && !isOpen(kind))
		{
			clearWindow(kind);
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
	private final Map<Kind, Map<String, Character>> cachedMap = new EnumMap<>(Kind.class);

	/** The custom hotkeys for a window, by teleport name (lower-case characters), in the order they were set. */
	synchronized Map<String, Character> view(Kind kind)
	{
		String raw = configManager.getConfiguration(PohTeleportSorterConfig.GROUP, kind.key);
		Map<String, Character> map = cachedMap.get(kind);
		if (map == null || !Objects.equals(raw, cachedRaw.get(kind)))
		{
			map = Collections.unmodifiableMap(parse(raw));
			cachedRaw.put(kind, raw);
			cachedMap.put(kind, map);
		}
		return map;
	}

	boolean hasAny(Kind kind)
	{
		return !view(kind).isEmpty();
	}

	private Map<String, Character> all(Kind kind)
	{
		return new LinkedHashMap<>(view(kind));
	}

	/**
	 * Reads the stored hotkeys: one "name, tab, character" per line. Lines saved by earlier test builds as "name,
	 * tab, key code, tab, modifiers" are read too where they were a plain letter or number; anything else is dropped.
	 */
	static Map<String, Character> parse(String raw)
	{
		Map<String, Character> out = new LinkedHashMap<>();
		if (raw == null)
		{
			return out;
		}
		for (String line : raw.split("\n"))
		{
			String[] parts = line.split("\t", -1);
			if (parts.length < 2 || parts[0].trim().isEmpty())
			{
				continue;
			}
			Character c = null;
			if (parts.length == 2 && parts[1].length() == 1)
			{
				c = parts[1].charAt(0);
			}
			else if (parts.length == 3)
			{
				try
				{
					int code = Integer.parseInt(parts[1]);
					int mods = Integer.parseInt(parts[2]);
					String text = KeyEvent.getKeyText(code);
					if (mods == 0 && text.length() == 1)
					{
						c = text.charAt(0);
					}
				}
				catch (NumberFormatException ignored)
				{
					// A damaged line is skipped
				}
			}
			// Each key belongs to one teleport only; a repeat (from an older save) is dropped
			if (c != null && isUsable(c) && !out.containsValue(Character.toLowerCase(c)))
			{
				out.put(parts[0].trim(), Character.toLowerCase(c));
			}
		}
		return out;
	}

	/** A key the game can match: a single visible character. */
	static boolean isUsable(char c)
	{
		return c != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(c) && !Character.isWhitespace(c);
	}

	Character get(Kind kind, String name)
	{
		return find(view(kind), name);
	}

	/** Binds the key to the teleport, taking it off any other teleport that had it. */
	private void set(Kind kind, String name, char key)
	{
		char k = Character.toLowerCase(key);
		Map<String, Character> map = all(kind);
		map.values().removeIf(v -> v == k);
		removeName(map, name);
		map.put(name.trim(), k);
		save(kind, map);
	}

	void reset(Kind kind, String name)
	{
		Map<String, Character> map = all(kind);
		removeName(map, name);
		save(kind, map);
	}

	private void save(Kind kind, Map<String, Character> map)
	{
		if (map.isEmpty())
		{
			configManager.unsetConfiguration(PohTeleportSorterConfig.GROUP, kind.key);
		}
		else
		{
			StringBuilder sb = new StringBuilder();
			for (Map.Entry<String, Character> e : map.entrySet())
			{
				if (sb.length() > 0)
				{
					sb.append('\n');
				}
				sb.append(e.getKey()).append('\t').append(e.getValue());
			}
			configManager.setConfiguration(PohTeleportSorterConfig.GROUP, kind.key, sb.toString());
		}
		pins.markChanged();
	}

	private static Character find(Map<String, Character> map, String name)
	{
		for (Map.Entry<String, Character> e : map.entrySet())
		{
			if (e.getKey().equalsIgnoreCase(name.trim()))
			{
				return e.getValue();
			}
		}
		return null;
	}

	private static void removeName(Map<String, Character> map, String name)
	{
		map.keySet().removeIf(k -> k.equalsIgnoreCase(name.trim()));
	}

	// ---------------------------------------------------------------- keys and labels

	/**
	 * The key a teleport answers to: its custom hotkey if it has one, otherwise the game's own key, unless another
	 * teleport's custom hotkey has taken that key. Upper case, as the game shows its letters; empty for no key.
	 */
	String effectiveKey(Kind kind, String name, String gameKey)
	{
		String game = gameKey == null ? "" : gameKey;
		Map<String, Character> map = view(kind);
		Character own = find(map, name);
		if (own != null)
		{
			return String.valueOf(own).toUpperCase();
		}
		if (game.length() == 1 && map.containsValue(Character.toLowerCase(game.charAt(0))))
		{
			return "";
		}
		return game;
	}

	/**
	 * The key to show next to a teleport: "Press a key" while one is being chosen for it, otherwise the key it
	 * answers to. With Better Teleport Menu on, the game's own key.
	 */
	String label(Kind kind, String name, String gameKey)
	{
		if (!isEnabled())
		{
			return gameKey == null ? "" : gameKey;
		}
		if (kind == captureKind && captureName != null && captureName.equalsIgnoreCase(name.trim()))
		{
			return PROMPT;
		}
		return effectiveKey(kind, name, gameKey);
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

	// ---------------------------------------------------------------- the game's listeners

	/**
	 * Changes arguments of one of the game's listeners, but only on a listener that is the expected game script
	 * with the expected shape. The version first seen is kept, so it can be put back exactly.
	 *
	 * @param values position in the listener's arguments, then the value to put there, repeated
	 * @return false if the listener isn't the expected one, in which case it is left alone
	 */
	boolean setArguments(Widget w, boolean keyListener, int scriptId, int length, Object... values)
	{
		if (w == null)
		{
			return false;
		}
		Object[] now = keyListener ? w.getOnKeyListener() : w.getOnOpListener();
		if (now == null || now.length != length || !Objects.equals(now[0], scriptId))
		{
			return false;
		}
		// Each changed argument must already be there with the same type (a string stays a string)
		for (int i = 0; i < values.length; i += 2)
		{
			int at = (Integer) values[i];
			if (now[at] == null || values[i + 1] == null || now[at].getClass() != values[i + 1].getClass())
			{
				return false;
			}
		}
		Object[] changed = now.clone();
		boolean differs = false;
		for (int i = 0; i < values.length; i += 2)
		{
			int at = (Integer) values[i];
			if (!Objects.equals(changed[at], values[i + 1]))
			{
				changed[at] = values[i + 1];
				differs = true;
			}
		}
		if (!differs)
		{
			return true;
		}
		(keyListener ? originalKeyListeners : originalOpListeners).putIfAbsent(w, now.clone());
		set(w, keyListener, changed);
		return true;
	}

	/**
	 * Gives a widget a key listener copied from another of the game's own, for a teleport the game gave no key
	 * (only its first 35 get one). The copy is removed again by {@link #restoreListeners()}.
	 */
	void addListener(Widget w, Object[] copy)
	{
		if (w == null || copy == null)
		{
			return;
		}
		originalKeyListeners.putIfAbsent(w, new Object[0]);
		w.setOnKeyListener(copy);
	}

	/** Puts every listener this plugin changed back as the game made it. Called on the client thread. */
	void restoreListeners()
	{
		for (Map.Entry<Widget, Object[]> e : originalKeyListeners.entrySet())
		{
			set(e.getKey(), true, e.getValue().length == 0 ? null : e.getValue());
		}
		for (Map.Entry<Widget, Object[]> e : originalOpListeners.entrySet())
		{
			set(e.getKey(), false, e.getValue());
		}
		forgetListeners();
	}

	private void forgetListeners()
	{
		originalKeyListeners.clear();
		originalOpListeners.clear();
	}

	private static void set(Widget w, boolean keyListener, Object[] args)
	{
		if (keyListener)
		{
			w.setOnKeyListener(args);
		}
		else
		{
			w.setOnOpListener(args);
		}
	}

	/** Whether a listener is the expected game script with the expected number of arguments. */
	static boolean isExpectedShape(Object[] args, int scriptId, int length)
	{
		return args != null && args.length == length && Objects.equals(args[0], scriptId);
	}

	/** The listener's characters for a key: upper case, then lower case (empty when the same, as the game does). */
	static String[] characters(String key)
	{
		String upper = key == null ? "" : key.toUpperCase();
		String lower = key == null ? "" : key.toLowerCase();
		return new String[]{upper, lower.equals(upper) ? "" : lower};
	}

	// ---------------------------------------------------------------- choosing a key

	/** Starts waiting for the key to bind to this teleport. Called on the client thread. */
	void startCapture(Kind kind, String name)
	{
		captureKind = kind;
		captureName = name.trim();
		runRedraw();
	}

	private void finishCapture(Character key)
	{
		Kind kind = captureKind;
		String name = captureName;
		captureKind = null;
		captureName = null;
		if (kind != null && name != null && key != null)
		{
			set(kind, name, key);
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

	/**
	 * While a hotkey is being chosen, the next key is taken for it: Esc cancels, a letter, number or symbol is
	 * chosen, and anything else (Ctrl or Alt combinations, F-keys, arrows) is ignored while waiting for another.
	 * The choice is made from the typed character, as the game matches it, so Shift+1 chooses "!".
	 */
	@Override
	public void keyPressed(KeyEvent e)
	{
		swallowTyped = false;
		if (captureKind == null)
		{
			return;
		}
		if (e.getKeyCode() == KeyEvent.VK_SHIFT)
		{
			return;
		}
		e.consume();
		if (e.getKeyCode() == KeyEvent.VK_ESCAPE)
		{
			swallowTyped = true;
			finishCapture(null);
			return;
		}
		int mods = e.getModifiersEx() & (InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK);
		char c = e.getKeyChar();
		if (mods != 0 || !isUsable(c))
		{
			// A key the game can't match: keep waiting
			return;
		}
		swallowTyped = true;
		finishCapture(c);
	}

	@Override
	public void keyTyped(KeyEvent e)
	{
		if (swallowTyped || captureKind != null)
		{
			e.consume();
		}
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
		if (swallowTyped)
		{
			e.consume();
			swallowTyped = false;
		}
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
