package com.pohteleportsorter;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum JewelleryBoxMode
{
	DEFAULT("Default", "Default"),
	BY_JEWELLERY("By jewellery", "By Jewellery"),
	ALPHABETICAL("Alphabetical", "Alphabetical"),
	RECENT("Recently used", "Recently Used");

	private final String name;
	private final String label;

	@Override
	public String toString()
	{
		return name;
	}
}
