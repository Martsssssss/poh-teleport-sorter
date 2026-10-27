package com.pohteleportsorter;

import java.awt.event.KeyEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.FontTypeFace;
import net.runelite.api.events.ClientTick;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;
import net.runelite.client.util.Text;

/**
 * The search field shown in the title bar of the Portal Nexus and the jewellery box, left of the title.
 *
 * While the field is being typed in, every key goes to it: the key is consumed, so the window's own
 * hotkeys don't fire, and neither do the shortcuts of any plugin whose key listener runs after this one.
 *
 * Better Teleport Menu runs these windows' hotkeys itself, from its own key listener. RuneLite gives keys
 * to listeners in the order their plugins started, which can't be controlled, so it could see the keys
 * typed into the search first and teleport. The search is therefore left out while that plugin is on.
 *
 * Enter finishes typing but keeps the results, so the next key press is a normal hotkey again; Esc clears
 * the search; Ctrl+A selects the whole search, so the next key replaces it and Backspace or Delete clears
 * it. Only one of the two windows can be open at a time, so one field is shared between them.
 */
@Singleton
public class TeleportSearch implements KeyListener
{
	/** Where the field goes: the title bar's clear band (top to bottom) and its left inner edge. */
	static final class Band
	{
		final int top;
		final int bottom;
		final int left;

		Band(int top, int bottom, int left)
		{
			this.top = top;
			this.bottom = bottom;
			this.left = left;
		}
	}

	private static final int HEIGHT = 20;
	private static final int MAX_WIDTH = 170;
	private static final int MIN_WIDTH = 100;
	// Equal space either side of the field: from the border's inner edge, and before the title text
	private static final int SIDE_GAP = 12;
	private static final int MAX_LENGTH = 24;

	private static final int TEXT_COLOUR = 0xffffff;
	private static final int HINT_COLOUR = 0x9f9f9f;
	private static final int CLEAR_COLOUR = 0xff981f;
	private static final int HOVER_COLOUR = 0xffffff;
	private static final int THINBOX_OUTER = 0x0e0e0c;
	private static final int THINBOX_INNER = 0x474745;
	// The same selection colour as RuneLite's own chatbox text input
	private static final int SELECTION_COLOUR = 0x113399;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private BetterTeleportMenu betterTeleportMenu;

	// Written on the AWT thread by the key dispatcher, read on the client thread
	private volatile boolean typing;
	private volatile boolean allSelected;
	private volatile String query = "";
	// The key that ended typing (Enter/Esc): its remaining typed/released events are swallowed too
	private volatile int swallowKeyCode = -1;

	private int rootId = -1;
	private Runnable onChange;
	private Widget text;
	private Widget selection;
	private Widget clear;
	private int textX;
	private int textY;
	private final java.util.List<Widget> widgets = new java.util.ArrayList<>();

	/**
	 * Shows the field in a window's title bar: centred vertically in the bar's clear band, and centred
	 * horizontally in the space between the border and the title text. {@code frame} is the layer holding
	 * the window's border and title (the title is its child 1). {@code onChange} re-filters the window.
	 */
	void attach(Widget root, Widget frame, Band band, Runnable onChange)
	{
		typing = false;
		allSelected = false;
		query = "";
		this.rootId = root.getId();
		this.onChange = onChange;

		int span = titleLeft(frame) - band.left;
		int width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, span - SIDE_GAP * 2));
		int x = band.left + Math.max(SIDE_GAP / 2, (span - width) / 2);
		int y = band.top + (band.bottom - band.top - HEIGHT) / 2;

		Widget bg = root.createChild(-1, WidgetType.GRAPHIC);
		bg.setSpriteId(SpriteID.TRADEBACKING_DARK);
		bg.setSpriteTiling(true);
		place(bg, x + 1, y + 1, width - 2, HEIGHT - 2);

		Widget outer = root.createChild(-1, WidgetType.RECTANGLE);
		outer.setFilled(false);
		outer.setTextColor(THINBOX_OUTER);
		place(outer, x, y, width, HEIGHT);

		Widget inner = root.createChild(-1, WidgetType.RECTANGLE);
		inner.setFilled(false);
		inner.setTextColor(THINBOX_INNER);
		place(inner, x + 1, y + 1, width - 2, HEIGHT - 2);

		// Drawn under the text, sized to it when everything is selected
		textX = x + 5;
		textY = y;
		selection = root.createChild(-1, WidgetType.RECTANGLE);
		selection.setFilled(true);
		selection.setTextColor(SELECTION_COLOUR);
		place(selection, textX - 1, y + 4, 1, HEIGHT - 8);
		selection.setHidden(true);

		text = root.createChild(-1, WidgetType.TEXT);
		text.setFontId(FontID.PLAIN_11);
		text.setTextShadowed(true);
		text.setXTextAlignment(WidgetTextAlignment.LEFT);
		text.setYTextAlignment(WidgetTextAlignment.CENTER);
		place(text, textX, y, width - 20, HEIGHT);
		text.setHasListener(true);
		text.setAction(0, "Search");
		text.setName("");
		text.setOnOpListener((JavaScriptCallback) ev -> startTyping());

		clear = root.createChild(-1, WidgetType.TEXT);
		clear.setFontId(FontID.PLAIN_11);
		clear.setTextShadowed(true);
		clear.setTextColor(CLEAR_COLOUR);
		clear.setXTextAlignment(WidgetTextAlignment.CENTER);
		clear.setYTextAlignment(WidgetTextAlignment.CENTER);
		clear.setText("x");
		place(clear, x + width - 15, y, 12, HEIGHT);
		clear.setHasListener(true);
		clear.setAction(0, "Clear");
		clear.setName("<col=ff9040>Search</col>");
		clear.setOnOpListener((JavaScriptCallback) ev -> clearSearch());
		clear.setOnMouseOverListener((JavaScriptCallback) ev -> clear.setTextColor(HOVER_COLOUR));
		clear.setOnMouseLeaveListener((JavaScriptCallback) ev -> clear.setTextColor(CLEAR_COLOUR));

		widgets.clear();
		for (Widget w : new Widget[]{bg, outer, inner, selection, text, clear})
		{
			w.revalidate();
			widgets.add(w);
		}
		refresh();
	}

	/** Left edge of the title's text, worked out from its font, as the game centres it in its box. */
	private static int titleLeft(Widget frame)
	{
		Widget title = frame != null ? frame.getChild(1) : null;
		if (title == null || title.getFont() == null || title.getText() == null)
		{
			return MIN_WIDTH + SIDE_GAP * 2 + 26;
		}
		int textW = title.getFont().getTextWidth(Text.removeTags(title.getText()));
		return title.getRelativeX() + (title.getWidth() - textW) / 2;
	}

	/** Ends the search. When the window is still open (plugin turned off), the field is hidden too. */
	void detach()
	{
		swallowKeyCode = -1;
		if (rootId != -1 && client.getWidget(rootId) != null)
		{
			for (Widget w : widgets)
			{
				w.setHidden(true);
				w.setHasListener(false);
			}
		}
		widgets.clear();
		typing = false;
		allSelected = false;
		query = "";
		rootId = -1;
		onChange = null;
		text = null;
		selection = null;
		clear = null;
	}

	/** The search text, lower case and trimmed; empty when not searching. */
	String query()
	{
		return query.trim().toLowerCase();
	}

	boolean matches(String... fields)
	{
		String q = query();
		if (q.isEmpty())
		{
			return true;
		}
		for (String f : fields)
		{
			if (f != null && f.toLowerCase().contains(q))
			{
				return true;
			}
		}
		return false;
	}

	/** Called when a teleport is clicked: typing ends, results stay. */
	void stopTyping()
	{
		if (typing)
		{
			typing = false;
			allSelected = false;
			refresh();
		}
	}

	private void startTyping()
	{
		typing = true;
		allSelected = false;
		refresh();
	}

	private void clearSearch()
	{
		typing = false;
		allSelected = false;
		query = "";
		changed();
	}

	private void changed()
	{
		refresh();
		if (onChange != null)
		{
			onChange.run();
		}
	}

	private void refresh()
	{
		if (text == null)
		{
			return;
		}
		String q = query;
		boolean selected = typing && allSelected && !q.isEmpty();
		if (selected)
		{
			text.setText(Text.escapeJagex(q));
			text.setTextColor(TEXT_COLOUR);
		}
		else if (typing)
		{
			text.setText(Text.escapeJagex(q) + "|");
			text.setTextColor(TEXT_COLOUR);
		}
		else if (q.isEmpty())
		{
			text.setText("Search");
			text.setTextColor(HINT_COLOUR);
		}
		else
		{
			text.setText(Text.escapeJagex(q));
			text.setTextColor(TEXT_COLOUR);
		}

		if (selection != null)
		{
			FontTypeFace font = text.getFont();
			int w = font != null ? font.getTextWidth(q) : q.length() * 6;
			selection.setOriginalX(textX - 1);
			selection.setOriginalY(textY + 4);
			selection.setOriginalWidth(w + 2);
			selection.setHidden(!selected);
			selection.revalidate();
		}
		if (clear != null)
		{
			clear.setHidden(q.isEmpty());
		}
	}

	/** Ends the search when its window closes. */
	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (rootId != -1 && client.getWidget(rootId) == null)
		{
			detach();
		}
	}

	/**
	 * Whether the search field can be shown: not while Better Teleport Menu is on, since its hotkeys could
	 * fire while typing. Checked each time a window opens.
	 */
	boolean isAvailable()
	{
		return !betterTeleportMenu.isActive();
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		take(e);
	}

	@Override
	public void keyTyped(KeyEvent e)
	{
		take(e);
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
		take(e);
	}

	/** Consumes the key, so the game and later listeners never see it, if it belongs to the search. */
	private void take(KeyEvent e)
	{
		if (handle(e))
		{
			e.consume();
		}
	}

	/** Returns true if the key belongs to the search field. */
	private boolean handle(KeyEvent e)
	{
		int code = e.getKeyCode();
		if (!typing)
		{
			// Swallow what's left of the key that ended typing, so an Enter can't fall through to the chatbox
			if (swallowKeyCode != -1)
			{
				if (e.getID() == KeyEvent.KEY_RELEASED && code == swallowKeyCode)
				{
					swallowKeyCode = -1;
					return true;
				}
				if (e.getID() == KeyEvent.KEY_TYPED)
				{
					return true;
				}
			}
			return false;
		}

		switch (e.getID())
		{
			case KeyEvent.KEY_PRESSED:
				if (code == KeyEvent.VK_A && e.isControlDown() && !e.isAltDown())
				{
					allSelected = !query.isEmpty();
					clientThread.invoke(this::refresh);
				}
				else if (code == KeyEvent.VK_ENTER)
				{
					// Keep the results; the next key press is a normal hotkey again
					typing = false;
					allSelected = false;
					swallowKeyCode = code;
					clientThread.invoke(this::refresh);
				}
				else if (code == KeyEvent.VK_ESCAPE)
				{
					typing = false;
					allSelected = false;
					query = "";
					swallowKeyCode = code;
					clientThread.invoke(this::changed);
				}
				else if (code == KeyEvent.VK_BACK_SPACE || code == KeyEvent.VK_DELETE)
				{
					if (allSelected)
					{
						allSelected = false;
						query = "";
						clientThread.invoke(this::changed);
					}
					else if (code == KeyEvent.VK_BACK_SPACE && !query.isEmpty())
					{
						query = query.substring(0, query.length() - 1);
						clientThread.invoke(this::changed);
					}
				}
				else if (allSelected && isNavigationKey(code))
				{
					// Arrow keys, Home and End drop the selection, as in any text box
					allSelected = false;
					clientThread.invoke(this::refresh);
				}
				break;
			case KeyEvent.KEY_TYPED:
			{
				char c = e.getKeyChar();
				if (c != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(c))
				{
					if (allSelected)
					{
						// Typing over a selection replaces it
						allSelected = false;
						query = String.valueOf(c);
						clientThread.invoke(this::changed);
					}
					else if (query.length() < MAX_LENGTH)
					{
						query = query + c;
						clientThread.invoke(this::changed);
					}
				}
				break;
			}
			default:
				break;
		}
		// While typing, every key belongs to the search field
		return true;
	}

	private static boolean isNavigationKey(int code)
	{
		switch (code)
		{
			case KeyEvent.VK_LEFT:
			case KeyEvent.VK_RIGHT:
			case KeyEvent.VK_UP:
			case KeyEvent.VK_DOWN:
			case KeyEvent.VK_HOME:
			case KeyEvent.VK_END:
				return true;
			default:
				return false;
		}
	}

	private static void place(Widget w, int x, int y, int width, int height)
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
}
