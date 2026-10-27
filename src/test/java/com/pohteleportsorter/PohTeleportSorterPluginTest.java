package com.pohteleportsorter;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class PohTeleportSorterPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(PohTeleportSorterPlugin.class);
		RuneLite.main(args);
	}
}
