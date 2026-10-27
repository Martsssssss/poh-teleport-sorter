package com.pohteleportsorter;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(PohTeleportSorterConfig.GROUP)
public interface PohTeleportSorterConfig extends Config
{
	// Kept from the plugin's first name so settings, pins and recent teleports saved before the rename carry over
	String GROUP = "portalnexussorter";
	String SORT_MODE = "sortMode";
	String JEWELLERY_MODE = "jewelleryBoxMode";
	String SHOW_JEWELLERY_SELECTOR = "showJewellerySelector";
	String WILD_ASTERISK = "wildAsterisk";
	String WILD_HIGHLIGHT = "wildHighlight";
	String SHOW_SEARCH = "showSearch";
	String RECENT_NEXUS = "recentNexus";
	String RECENT_JEWELLERY = "recentJewellery";
	String PINNED_NEXUS = "pinnedNexus";
	String PINNED_JEWELLERY = "pinnedJewellery";

	@ConfigSection(
		name = "Portal Nexus",
		description = "Portal Nexus teleport list",
		position = 1
	)
	String nexusSection = "nexus";

	@ConfigSection(
		name = "Ornate Jewellery Box",
		description = "Ornate jewellery box layout",
		position = 2
	)
	String jewellerySection = "jewellery";

	@ConfigItem(
		keyName = WILD_ASTERISK,
		name = "Wilderness asterisk",
		description = "Add a red * after teleports that land in the Wilderness",
		position = 0
	)
	default boolean wildAsterisk()
	{
		return false;
	}

	@ConfigItem(
		keyName = WILD_HIGHLIGHT,
		name = "Highlight Wilderness teleports",
		description = "Show the names of teleports that land in the Wilderness in red",
		position = 1
	)
	default boolean wildHighlight()
	{
		return false;
	}

	@ConfigItem(
		keyName = SHOW_SEARCH,
		name = "Search field",
		description = "Show a search field in the title bar of the Portal Nexus and the jewellery box. Takes effect next time a window is opened.",
		position = 2
	)
	default boolean showSearch()
	{
		return true;
	}

	@ConfigItem(
		keyName = SORT_MODE,
		name = "Sort by",
		description = "How the Portal Nexus teleport list is ordered. Changes apply straight away, even with the window open.",
		position = 0,
		section = nexusSection
	)
	default SortMode sortMode()
	{
		return SortMode.DEFAULT;
	}

	@ConfigItem(
		keyName = "showSelector",
		name = "Show buttons in nexus",
		description = "Show the sort buttons inside the Portal Nexus window. Turn off for the game's own window layout, and choose the order with Sort by above instead. The jewellery box has its own setting in the section below.",
		position = 1,
		section = nexusSection
	)
	default boolean showSelector()
	{
		return true;
	}

	@ConfigItem(
		keyName = JEWELLERY_MODE,
		name = "Layout",
		description = "How the ornate jewellery box shows its teleports. Changes apply straight away, even with the box open.",
		position = 0,
		section = jewellerySection
	)
	default JewelleryBoxMode jewelleryBoxMode()
	{
		return JewelleryBoxMode.DEFAULT;
	}

	@ConfigItem(
		keyName = SHOW_JEWELLERY_SELECTOR,
		name = "Show buttons in jewellery box",
		description = "Show the layout buttons along the bottom of the jewellery box. Turn off for a cleaner box, and choose the layout with Layout above instead. The Portal Nexus has its own setting in the section above.",
		position = 1,
		section = jewellerySection
	)
	default boolean showJewellerySelector()
	{
		return true;
	}

	// Pinned teleports: set by right-clicking a teleport and choosing Pin, not shown in the settings panel
	@ConfigItem(keyName = PINNED_NEXUS, name = "", description = "", hidden = true)
	default String pinnedNexus()
	{
		return "";
	}

	@ConfigItem(keyName = PINNED_JEWELLERY, name = "", description = "", hidden = true)
	default String pinnedJewellery()
	{
		return "";
	}

	// Recently used teleports, most recent first: recorded when you teleport, not shown in the settings panel
	@ConfigItem(keyName = RECENT_NEXUS, name = "", description = "", hidden = true)
	default String recentNexus()
	{
		return "";
	}

	@ConfigItem(keyName = RECENT_JEWELLERY, name = "", description = "", hidden = true)
	default String recentJewellery()
	{
		return "";
	}
}
