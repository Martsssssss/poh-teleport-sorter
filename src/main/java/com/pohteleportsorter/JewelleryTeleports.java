package com.pohteleportsorter;

/** Ornate jewellery box destinations that land in the Wilderness, named as poh_jewellery_box_init names them. */
final class JewelleryTeleports
{
	private JewelleryTeleports()
	{
	}

	/**
	 * Ferox Enclave is the only one. Corporeal Beast is not: the teleport arrives inside the lair, and the
	 * lair itself is not in the Wilderness (only its entrance is).
	 */
	static boolean isWild(String name)
	{
		return name != null && name.trim().equalsIgnoreCase("Ferox Enclave");
	}
}
