package io.github.pavelgar.principia.skyline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.core.Vec3i;

/** A player's facing as an anchor's heading, and the cells an anchor's cross-beam reaches into. */
class HeadingTest {
	/**
	 * Yaw is 0° facing south and grows clockwise seen from above: 90° west, 180° north, -90° east. Each heading takes
	 * the 45° around it, so south runs to 22.5° and south-west starts past it.
	 */
	@Test
	void aYawSnapsToTheNearestHeading() {
		assertEquals(Heading.SOUTH, Heading.fromYaw(0));
		assertEquals(Heading.SOUTH, Heading.fromYaw(22));
		assertEquals(Heading.SOUTH_WEST, Heading.fromYaw(23));
		assertEquals(Heading.EAST, Heading.fromYaw(-90));
		assertEquals(Heading.NORTH, Heading.fromYaw(180));
		assertEquals(Heading.NORTH, Heading.fromYaw(-180));
		assertEquals(Heading.SOUTH, Heading.fromYaw(360));
		assertEquals(Heading.SOUTH_EAST, Heading.fromYaw(-50));
	}

	/**
	 * The beam lies square to the heading and reaches 9 px into a cell on each side. Heading south it runs along x,
	 * into the cells west and east; heading south-east it runs from north-east to south-west, through the corners of
	 * the anchor's cell, into the cells at those corners.
	 */
	@Test
	void aCrossBeamEndsInTheCellsSquareToItsHeading() {
		assertEquals(Set.of(new Vec3i(-1, 0, 0), new Vec3i(1, 0, 0)), Set.copyOf(CrossBeam.ends(Heading.SOUTH)));
		assertEquals(Set.of(new Vec3i(0, 0, -1), new Vec3i(0, 0, 1)), Set.copyOf(CrossBeam.ends(Heading.EAST)));
		assertEquals(Set.of(new Vec3i(1, 0, -1), new Vec3i(-1, 0, 1)),
				Set.copyOf(CrossBeam.ends(Heading.SOUTH_EAST)));
		assertEquals(Set.of(new Vec3i(-1, 0, -1), new Vec3i(1, 0, 1)),
				Set.copyOf(CrossBeam.ends(Heading.NORTH_EAST)));
	}
}
