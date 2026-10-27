package com.pohteleportsorter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

/**
 * Pinned and recently used teleports, kept in the plugin's config so they survive restarts. Each list is
 * stored as newline-separated destination names: pins in the order pinned, recent use most recent first.
 *
 * RuneLite only writes settings to disk every five minutes and when it shuts down normally, so anything
 * changed just before RuneLite is closed some other way would be lost. {@link #saveIfChanged()} writes
 * them out straight away; the plugin calls it whenever the Portal Nexus or jewellery box closes.
 */
@Singleton
public class PinStore
{
	public enum Kind
	{
		NEXUS(PohTeleportSorterConfig.PINNED_NEXUS),
		JEWELLERY(PohTeleportSorterConfig.PINNED_JEWELLERY),
		RECENT_NEXUS(PohTeleportSorterConfig.RECENT_NEXUS),
		RECENT_JEWELLERY(PohTeleportSorterConfig.RECENT_JEWELLERY);

		private final String key;

		Kind(String key)
		{
			this.key = key;
		}
	}

	@Inject
	private ConfigManager configManager;

	@Inject
	private ScheduledExecutorService executor;

	private volatile boolean changed;

	/** Notes that a pin, recent use or chosen layout changed and should be saved. */
	public void markChanged()
	{
		changed = true;
	}

	/** Writes the settings to disk now (off the game thread) if anything changed since the last save. */
	public void saveIfChanged()
	{
		if (!changed)
		{
			return;
		}
		changed = false;
		executor.execute(configManager::sendConfig);
	}

	public List<String> list(Kind kind)
	{
		List<String> out = new ArrayList<>();
		String raw = configManager.getConfiguration(PohTeleportSorterConfig.GROUP, kind.key);
		if (raw == null)
		{
			return out;
		}
		for (String s : raw.split("\n"))
		{
			String t = s.trim();
			if (!t.isEmpty() && !contains(out, t))
			{
				out.add(t);
			}
		}
		return out;
	}

	public boolean isPinned(Kind kind, String name)
	{
		return name != null && contains(list(kind), name.trim());
	}

	/** Pins or unpins the destination, and returns whether it is now pinned. */
	public boolean toggle(Kind kind, String name)
	{
		List<String> pins = list(kind);
		String n = name.trim();
		boolean nowPinned;
		if (contains(pins, n))
		{
			pins.removeIf(p -> p.equalsIgnoreCase(n));
			nowPinned = false;
		}
		else
		{
			pins.add(n);
			nowPinned = true;
		}
		if (pins.isEmpty())
		{
			configManager.unsetConfiguration(PohTeleportSorterConfig.GROUP, kind.key);
		}
		else
		{
			configManager.setConfiguration(PohTeleportSorterConfig.GROUP, kind.key, String.join("\n", pins));
		}
		changed = true;
		return nowPinned;
	}

	/** Moves the destination to the front of a recently-used list, keeping at most {@code limit} names. */
	public void recordUse(Kind kind, String name, int limit)
	{
		if (name == null || name.trim().isEmpty())
		{
			return;
		}
		String n = name.trim();
		List<String> list = list(kind);
		if (!list.isEmpty() && list.get(0).equalsIgnoreCase(n))
		{
			return;
		}
		list.removeIf(p -> p.equalsIgnoreCase(n));
		list.add(0, n);
		while (list.size() > limit)
		{
			list.remove(list.size() - 1);
		}
		configManager.setConfiguration(PohTeleportSorterConfig.GROUP, kind.key, String.join("\n", list));
		changed = true;
	}

	/** Position in the list (0 = most recent / first pinned), or -1 if absent. */
	public static int indexOf(List<String> list, String name)
	{
		for (int i = 0; i < list.size(); i++)
		{
			if (list.get(i).equalsIgnoreCase(name))
			{
				return i;
			}
		}
		return -1;
	}

	private static boolean contains(List<String> pins, String name)
	{
		for (String p : pins)
		{
			if (p.equalsIgnoreCase(name))
			{
				return true;
			}
		}
		return false;
	}
}
