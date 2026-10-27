package com.pohteleportsorter;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.client.game.SpriteOverride;

/** The plugin's own sprites, registered with RuneLite's SpriteManager. Negative ids are reserved for plugins. */
@Getter
@RequiredArgsConstructor
public enum PinSprite implements SpriteOverride
{
	PIN(-27140, "pin.png");

	private final int spriteId;
	private final String fileName;

	static final int WIDTH = 11;
	static final int HEIGHT = 13;
}
