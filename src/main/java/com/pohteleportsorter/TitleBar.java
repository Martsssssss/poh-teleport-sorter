package com.pohteleportsorter;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetSizeMode;

/**
 * Makes a window's title bar a little taller, so the search field has room around it, and puts every
 * widget it moved back exactly where the game had it.
 *
 * The title bars are drawn by the game's stoneborder (Portal Nexus) and steelborder (jewellery box)
 * scripts as children of the window's frame layer: child 1 is the title text, the divider under the bar
 * follows the border pieces, and the close button comes after it.
 */
final class TitleBar
{
	/** How much taller the title bar gets, in interface pixels. */
	static final int EXTRA = 6;

	private static final class Saved
	{
		final Widget widget;
		final int y;
		final int height;

		Saved(Widget widget)
		{
			this.widget = widget;
			this.y = widget.getOriginalY();
			this.height = widget.getOriginalHeight();
		}
	}

	private final List<Saved> saved = new ArrayList<>();

	/** Moves a widget down. Missing widgets (a window without a close button, say) are skipped. */
	void moveDown(Widget w, int dy)
	{
		if (w == null)
		{
			return;
		}
		saved.add(new Saved(w));
		w.setOriginalY(w.getOriginalY() + dy);
		w.revalidate();
	}

	/**
	 * Moves the top of a layer down while keeping its bottom where it was. Works for layers sized
	 * "parent minus N" (it adds to N) and for fixed-height layers (it takes from the height).
	 */
	void moveTopDown(Widget w, int dy)
	{
		if (w == null)
		{
			return;
		}
		saved.add(new Saved(w));
		w.setOriginalY(w.getOriginalY() + dy);
		if (w.getHeightMode() == WidgetSizeMode.MINUS)
		{
			w.setOriginalHeight(w.getOriginalHeight() + dy);
		}
		else
		{
			w.setOriginalHeight(Math.max(0, w.getOriginalHeight() - dy));
		}
		w.revalidate();
	}

	boolean isExtended()
	{
		return !saved.isEmpty();
	}

	/** Puts everything back as the game drew it. */
	void restore()
	{
		for (int i = saved.size() - 1; i >= 0; i--)
		{
			Saved s = saved.get(i);
			s.widget.setOriginalY(s.y);
			s.widget.setOriginalHeight(s.height);
			s.widget.revalidate();
		}
		saved.clear();
	}

	/** Forgets what was moved, for when the window has closed and its widgets are gone. */
	void forget()
	{
		saved.clear();
	}
}
