package com.pohteleportsorter;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Getter;

/**
 * Every Portal Nexus destination, named exactly as the game labels it (enum 1377 / struct param 660).
 * Declaration order inside each spellbook follows that spellbook's in-game order.
 * The plain name is where an ancient teleport actually lands, used by the "Alphabetical (Ancient)" sort.
 */
@Getter
public enum NexusTeleport
{
	// Standard
	VARROCK("Varrock", Spellbook.STANDARD),
	GRAND_EXCHANGE("Grand Exchange", Spellbook.STANDARD),
	LUMBRIDGE("Lumbridge", Spellbook.STANDARD),
	FALADOR("Falador", Spellbook.STANDARD),
	CAMELOT("Camelot", Spellbook.STANDARD),
	SEERS_VILLAGE("Seers' Village", Spellbook.STANDARD),
	KOUREND_CASTLE("Kourend Castle", Spellbook.STANDARD),
	ARDOUGNE("Ardougne", Spellbook.STANDARD),
	CIVITAS("Civitas illa Fortis", Spellbook.STANDARD),
	WATCHTOWER("Watchtower", Spellbook.STANDARD),
	YANILLE("Yanille", Spellbook.STANDARD),
	TROLLHEIM("Trollheim", Spellbook.STANDARD),
	MARIM("Marim", Spellbook.STANDARD),
	BOAT("Boat", Spellbook.STANDARD),
	LAST_BOAT("Last Boat", Spellbook.STANDARD),

	// Ancient
	PADDEWWA("Paddewwa", Spellbook.ANCIENT, "Edgeville Dungeon"),
	SENNTISTEN("Senntisten", Spellbook.ANCIENT, "Digsite"),
	KHARYRLL("Kharyrll", Spellbook.ANCIENT, "Canifis"),
	LASSAR("Lassar", Spellbook.ANCIENT, "Ice Mountain"),
	DAREEYAK("Dareeyak", Spellbook.ANCIENT, "Ruins (west)"),
	CARRALLANGER("Carrallanger", Spellbook.ANCIENT, "Graveyard of Shadows"),
	ANNAKARL("Annakarl", Spellbook.ANCIENT, "Demonic Ruins"),
	GHORROCK("Ghorrock", Spellbook.ANCIENT, "Frozen Waste Plateau"),

	// Lunar
	LUNAR_ISLE("Lunar Isle", Spellbook.LUNAR),
	OURANIA("Ourania", Spellbook.LUNAR),
	WATERBIRTH("Waterbirth Island", Spellbook.LUNAR),
	BARBARIAN_OUTPOST("Barbarian Outpost", Spellbook.LUNAR),
	PORT_KHAZARD("Port Khazard", Spellbook.LUNAR),
	FISHING_GUILD("Fishing Guild", Spellbook.LUNAR),
	CATHERBY("Catherby", Spellbook.LUNAR),
	ICE_PLATEAU("Ice Plateau", Spellbook.LUNAR),

	// Arceuus
	ARCEUUS_LIBRARY("Arceuus Library", Spellbook.ARCEUUS),
	DRAYNOR_MANOR("Draynor Manor", Spellbook.ARCEUUS),
	BATTLEFRONT("Battlefront", Spellbook.ARCEUUS),
	MIND_ALTAR("Mind Altar", Spellbook.ARCEUUS),
	RESPAWN("Respawn", Spellbook.ARCEUUS),
	SALVE_GRAVEYARD("Salve Graveyard", Spellbook.ARCEUUS),
	FENKENSTRAINS_CASTLE("Fenken' Castle", Spellbook.ARCEUUS),
	WEST_ARDOUGNE("West Ardougne", Spellbook.ARCEUUS),
	HARMONY_ISLAND("Harmony Island", Spellbook.ARCEUUS),
	CEMETERY("Cemetery", Spellbook.ARCEUUS),
	BARROWS("Barrows", Spellbook.ARCEUUS),
	APE_ATOLL_DUNGEON("Ape Atoll Dungeon", Spellbook.ARCEUUS),

	// Basalt teleports, not spells
	TROLL_STRONGHOLD("Troll Stronghold", Spellbook.OTHER),
	WEISS("Weiss", Spellbook.OTHER);

	public enum Spellbook
	{
		STANDARD, ANCIENT, LUNAR, ARCEUUS, OTHER
	}

	private static final Map<String, NexusTeleport> BY_NAME = new HashMap<>();

	static
	{
		for (NexusTeleport t : values())
		{
			BY_NAME.put(t.gameName.toLowerCase(), t);
		}
	}

	private final String gameName;
	private final Spellbook spellbook;
	private final String plainName;

	NexusTeleport(String gameName, Spellbook spellbook)
	{
		this(gameName, spellbook, gameName);
	}

	NexusTeleport(String gameName, Spellbook spellbook, String plainName)
	{
		this.gameName = gameName;
		this.spellbook = spellbook;
		this.plainName = plainName;
	}

	/** Every nexus destination that lands in the Wilderness: Dareeyak, Carrallanger, Annakarl, Ghorrock, Ice Plateau, Cemetery. */
	public boolean isWild()
	{
		switch (this)
		{
			case DAREEYAK:
			case CARRALLANGER:
			case ANNAKARL:
			case GHORROCK:
			case ICE_PLATEAU:
			case CEMETERY:
				return true;
			default:
				return false;
		}
	}

	private static final Pattern BRACKETED = Pattern.compile("^(.*)\\s*\\(([^()]*)\\)$");

	/**
	 * Matches the name as shown in game. Handles plain names ("Kharyrll"), bracketed forms
	 * ("Canifis (Kharyll)") and spelling differences in the ancient spell names.
	 */
	public static NexusTeleport fromName(String name)
	{
		if (name == null)
		{
			return null;
		}
		String n = name.trim();
		NexusTeleport t = BY_NAME.get(n.toLowerCase());
		if (t != null)
		{
			return t;
		}

		Matcher m = BRACKETED.matcher(n);
		if (m.matches())
		{
			for (String part : new String[]{m.group(1), m.group(2)})
			{
				t = BY_NAME.get(part.trim().toLowerCase());
				if (t == null)
				{
					t = ancientByPrefix(part);
				}
				if (t != null)
				{
					return t;
				}
			}
		}
		return ancientByPrefix(n);
	}

	/** Ancient spell names are distinct in their first four letters, which survives spelling variants like Kharyll/Kharyrll. */
	public static NexusTeleport ancientByPrefix(String s)
	{
		String key = s.trim().toLowerCase().replaceAll("[^a-z]", "");
		if (key.length() < 4)
		{
			return null;
		}
		for (NexusTeleport t : values())
		{
			if (t.spellbook == Spellbook.ANCIENT && t.gameName.toLowerCase().startsWith(key.substring(0, 4)))
			{
				return t;
			}
		}
		return null;
	}

	/**
	 * For ancient teleports, the name with the spell first: "Canifis (Kharyll)" or "Kharyrll" becomes
	 * "Kharyll (Canifis)" / "Kharyrll (Canifis)". Returns the name unchanged for everything else.
	 */
	public static String spellFirst(String shown)
	{
		String n = shown.trim();
		Matcher m = BRACKETED.matcher(n);
		if (m.matches())
		{
			String place = m.group(1).trim();
			String spell = m.group(2).trim();
			if (ancientByPrefix(spell) != null)
			{
				return spell + " (" + place + ")";
			}
			if (ancientByPrefix(place) != null)
			{
				return n; // already spell first
			}
			return n;
		}
		NexusTeleport t = BY_NAME.get(n.toLowerCase());
		if (t != null && t.spellbook == Spellbook.ANCIENT)
		{
			return n + " (" + t.plainName + ")";
		}
		return n;
	}
}
