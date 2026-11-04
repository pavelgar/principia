package io.github.pavelgar.principia.skyline;

import static io.github.pavelgar.principia.skyline.SegmentCurveTest.end;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.core.Vec3i;

/**
 * Each limit rejects a segment just past it with its own reason and accepts one inside it. Every segment starts at
 * the origin, leaving along the first tangent, and arrives along the second (the partner's back side).
 */
class SegmentLimitTest {
	private static Set<SegmentLimit> broken(Vec3i offset, Heading heading, float pitch, Heading arriving,
			float arrivingPitch) {
		return new Segment(offset, end(heading, pitch, AnchorSide.FRONT),
				end(arriving, arrivingPitch, AnchorSide.BACK)).brokenLimits();
	}

	@Test
	void theAnchorsMustBeDifferentCells() {
		assertEquals(Set.of(SegmentLimit.SAME_CELL), broken(Vec3i.ZERO, Heading.EAST, 0, Heading.EAST, 0));
	}

	@Test
	void theCentreLineIsAtMost32BlocksLong() {
		// Straight lines along x: exactly as long as the anchors are apart.
		assertEquals(Set.of(), broken(new Vec3i(32, 0, 0), Heading.EAST, 0, Heading.EAST, 0));
		assertEquals(Set.of(SegmentLimit.TOO_LONG), broken(new Vec3i(33, 0, 0), Heading.EAST, 0, Heading.EAST, 0));
	}

	@Test
	void theSlopeIsAtMost45DegreesEverywhere() {
		// A straight line 8 east and 8 up, pitched 45° at both ends, is exactly 45° all along.
		assertEquals(Set.of(), broken(new Vec3i(8, 8, 0), Heading.EAST, 45, Heading.EAST, 45));
		// Both ends at 40°, but the chord climbs 14 over 14 (45°): the middle has to be steeper than the chord.
		assertEquals(Set.of(SegmentLimit.TOO_STEEP), broken(new Vec3i(14, 14, 0), Heading.EAST, 40, Heading.EAST, 40));
	}

	@Test
	void theHeadingTurnsAtMost90Degrees() {
		// East into south: 90°, along a quarter circle of radius 6.
		assertEquals(Set.of(), broken(new Vec3i(6, 0, 6), Heading.EAST, 0, Heading.SOUTH, 0));
		// East into south-west: 135°, its tightest radius well over 4 (anchors 4 east and 10 south apart).
		assertEquals(Set.of(SegmentLimit.TURNS_TOO_FAR),
				broken(new Vec3i(4, 0, 10), Heading.EAST, 0, Heading.SOUTH_WEST, 0));
		// The same turns to the left: east into north, and east into north-west.
		assertEquals(Set.of(), broken(new Vec3i(6, 0, -6), Heading.EAST, 0, Heading.NORTH, 0));
		assertEquals(Set.of(SegmentLimit.TURNS_TOO_FAR),
				broken(new Vec3i(4, 0, -10), Heading.EAST, 0, Heading.NORTH_WEST, 0));
	}

	/**
	 * A Bézier only approximates a circle: this quarter turn is tightest about 1 % under radius 4, and the turn the
	 * radius limit is meant to allow must pass.
	 */
	@Test
	void aQuarterTurnWithAnchorsFourApartOnEachAxisIsAccepted() {
		assertEquals(Set.of(), broken(new Vec3i(4, 0, 4), Heading.EAST, 0, Heading.SOUTH, 0));
	}

	/**
	 * Climbing quarter turns, 30° up at both ends: seen from above, quarter circles of radius 3 and 4. Climbing as
	 * steeply as they turn, their radius in 3D is larger: a helix of radius r and slope α has 3D radius r / cos²α,
	 * here about 3 / cos²(32.5°) ≈ 4.2 and 4 / cos²(32.5°) ≈ 5.6. Only the horizontal limit holds the first back.
	 */
	@Test
	void theRadiusSeenFromAboveIsAtLeast4() {
		assertEquals(Set.of(), broken(new Vec3i(4, 4, 4), Heading.EAST, 30, Heading.SOUTH, 30));
		assertEquals(Set.of(SegmentLimit.TURN_TOO_TIGHT),
				broken(new Vec3i(3, 3, 3), Heading.EAST, 30, Heading.SOUTH, 30));
	}

	/**
	 * The partner 10 blocks behind, both facing east: every control point is on the x axis, so the curve runs east,
	 * stops, runs back west past the partner, stops and turns east again. Two hairpins of radius 0, though the curve
	 * never leaves a straight line, where a bend is measured as zero.
	 */
	@Test
	void aSegmentThatFoldsBackOnItselfBendsTooSharply() {
		assertEquals(Set.of(SegmentLimit.TURN_TOO_TIGHT, SegmentLimit.BEND_TOO_SHARP),
				broken(new Vec3i(-10, 0, 0), Heading.EAST, 0, Heading.EAST, 0));
		// The same along a diagonal: the partner 5 south-west, both facing north-east.
		assertEquals(Set.of(SegmentLimit.TURN_TOO_TIGHT, SegmentLimit.BEND_TOO_SHARP),
				broken(new Vec3i(-5, 0, 5), Heading.NORTH_EAST, 0, Heading.NORTH_EAST, 0));
	}

	/**
	 * Crests 5 blocks long, straight seen from above. A circular arc leaving up at φ and arriving down at φ over a chord
	 * of 5 has radius 5 / (2 sin φ): 4.36 for 35°, 3.89 for 40°.
	 */
	@Test
	void theRadiusIn3DIsAtLeast4() {
		assertEquals(Set.of(), broken(new Vec3i(5, 0, 0), Heading.EAST, 35, Heading.EAST, -35));
		assertEquals(Set.of(SegmentLimit.BEND_TOO_SHARP),
				broken(new Vec3i(5, 0, 0), Heading.EAST, 40, Heading.EAST, -40));
	}
}
