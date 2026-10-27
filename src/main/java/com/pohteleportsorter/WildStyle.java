package com.pohteleportsorter;

/** Optional styling for teleports that land in the Wilderness. Display only. */
final class WildStyle
{
	private static final String RED = "<col=ff0000>";

	private WildStyle()
	{
	}

	/**
	 * Styles a row's text. {@code name} is the destination name as it appears in {@code text};
	 * highlight turns that name red, asterisk adds a red * after it.
	 */
	static String apply(String text, String name, boolean wild, boolean asterisk, boolean highlight)
	{
		if (!wild || text == null || name == null || name.isEmpty())
		{
			return text;
		}
		String out = text;
		if (highlight)
		{
			int at = out.lastIndexOf(name);
			if (at >= 0)
			{
				out = out.substring(0, at) + RED + name + "</col>" + out.substring(at + name.length());
			}
		}
		if (asterisk)
		{
			out = out + RED + "*</col>";
		}
		return out;
	}
}
