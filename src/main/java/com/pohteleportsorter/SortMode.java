package com.pohteleportsorter;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SortMode
{
	DEFAULT("Default", "Default"),
	ALPHABETICAL("Alphabetical", "Alphabetical"),
	ALPHABETICAL_ANCIENT("Alphabetical (ancient)", "Alphabetical (Ancient)"),
	SPELLBOOK("Spellbook type", "Spellbook"),
	RECENT("Recently used", "Recently Used");

	private final String name;
	private final String label;

	@Override
	public String toString()
	{
		return name;
	}
}
