package com.pohteleportsorter;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;

/**
 * Whether the Better Teleport Menu plugin is running. That plugin handles these windows' hotkeys itself, from its
 * own key listener, and RuneLite gives keys to listeners in the order their plugins started, which can't be
 * controlled. So while it's on, this plugin's search and custom hotkeys step aside rather than fight it for keys.
 * This only reads whether the plugin is on; it never changes anything about it.
 */
@Singleton
public class BetterTeleportMenu
{
	private static final String CLASS_NAME = "BetterTeleportMenuPlugin";

	@Inject
	private PluginManager pluginManager;

	// Labels are redrawn every frame, so the answer is kept for a moment rather than looked up each time
	private static final long RECHECK_NANOS = 1_000_000_000L;
	private volatile boolean active;
	private volatile long checkedAt;
	private volatile boolean checked;

	public boolean isActive()
	{
		long now = System.nanoTime();
		if (!checked || now - checkedAt > RECHECK_NANOS)
		{
			active = lookUp();
			checkedAt = now;
			checked = true;
		}
		return active;
	}

	private boolean lookUp()
	{
		for (Plugin p : pluginManager.getPlugins())
		{
			if (CLASS_NAME.equals(p.getClass().getSimpleName()) && pluginManager.isPluginActive(p))
			{
				return true;
			}
		}
		return false;
	}
}
