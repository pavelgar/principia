package io.github.pavelgar.principia.skyline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.minecraft.core.Vec3i;

/** How a new anchor lies: which of its sides a segment leaves it on, and its pitch. */
class PlacementTest {
	/**
	 * Rule A: a new anchor's heading only fixes its line, and the segment leaves it on the side that points more toward
	 * the other anchor. An end 10 blocks east of the start, heading west, points its front back at the start; heading
	 * east, its back.
	 */
	@Test
	void aSegmentLeavesANewAnchorOnTheSideTowardTheOtherAnchor() {
		Vec3i toStart = new Vec3i(-10, 0, 0);
		assertEquals(AnchorSide.FRONT, Placement.sideToward(Heading.WEST, toStart));
		assertEquals(AnchorSide.BACK, Placement.sideToward(Heading.EAST, toStart));
		// Square to the heading, neither side points more toward it: the front.
		assertEquals(AnchorSide.FRONT, Placement.sideToward(Heading.NORTH, toStart));
		// A diagonal heading: south-east points away from a start to the north-west.
		assertEquals(AnchorSide.BACK, Placement.sideToward(Heading.SOUTH_EAST, new Vec3i(-3, 2, -5)));
	}

	/**
	 * A new anchor's pitch is the slope of the chord to the other anchor, seen along its heading: 5 up over 10 across
	 * is atan(0.5) ≈ 26.57°, climbing along a heading toward it and falling along the opposite one. Past 45° it is held
	 * at 45°.
	 */
	@Test
	void aNewAnchorIsPitchedAlongTheChordToTheOtherAnchor() {
		Vec3i up = new Vec3i(10, 5, 0);
		assertEquals(26.565, Placement.pitchToward(Heading.EAST, up), 1e-3);
		assertEquals(-26.565, Placement.pitchToward(Heading.WEST, up), 1e-3);
		assertEquals(0, Placement.pitchToward(Heading.EAST, new Vec3i(10, 0, 3)), 1e-6);
		// Level, seen backwards: exactly 0, not -0, which a tangent would tell apart.
		assertEquals(new AnchorTangent(Heading.WEST, 0),
				new AnchorTangent(Heading.WEST, Placement.pitchToward(Heading.WEST, new Vec3i(10, 0, 0))));
		// 6 up over 4 across is 56.3°, held at 45°.
		assertEquals(45, Placement.pitchToward(Heading.EAST, new Vec3i(4, 6, 0)), 1e-6);
		assertEquals(-45, Placement.pitchToward(Heading.EAST, new Vec3i(4, -6, 0)), 1e-6);
		// Square to the heading, 5 up and 3 south of an anchor heading east, the chord is vertical seen along it: 45°.
		assertEquals(45, Placement.pitchToward(Heading.EAST, new Vec3i(0, 5, 3)), 1e-6);
		// On a diagonal, 3 up over 4 east and 4 south: across, along the heading, is 4√2 ≈ 5.66, so atan(3 / 5.66).
		assertEquals(27.94, Placement.pitchToward(Heading.SOUTH_EAST, new Vec3i(4, 3, 4)), 1e-2);
	}

	/**
	 * A new anchor heads the way a circle leaving the start along its track would run through it, snapped to 45°: the
	 * start's direction mirrored in the chord. Leaving east:
	 * <ul>
	 * <li>10 ahead, or 10 ahead and 3 up, or straight up: on east;</li>
	 * <li>6 ahead and 6 south, a quarter circle: south; 6 north, north;</li>
	 * <li>8 ahead and 2 south: the chord is atan(2 / 8) ≈ 14.0° off, the circle turns twice that, 28.1°, nearer 45°
	 * than 0°: south-east;</li>
	 * <li>10 ahead and 1 south: 5.7° off, turning 11.4°, nearer 0°: east;</li>
	 * <li>6 south and none ahead, a half circle: west.</li>
	 * </ul>
	 * Leaving north-east, 5 east and 5 north is straight on: north-east.
	 */
	@Test
	void aNewAnchorHeadsTheWayACircleFromTheStartRunsThroughIt() {
		assertEquals(Heading.EAST, Placement.arcHeading(Heading.EAST, new Vec3i(10, 0, 0)));
		assertEquals(Heading.EAST, Placement.arcHeading(Heading.EAST, new Vec3i(10, 3, 0)));
		assertEquals(Heading.EAST, Placement.arcHeading(Heading.EAST, new Vec3i(0, 4, 0)));
		assertEquals(Heading.SOUTH, Placement.arcHeading(Heading.EAST, new Vec3i(6, 0, 6)));
		assertEquals(Heading.NORTH, Placement.arcHeading(Heading.EAST, new Vec3i(6, 0, -6)));
		assertEquals(Heading.SOUTH_EAST, Placement.arcHeading(Heading.EAST, new Vec3i(8, 0, 2)));
		assertEquals(Heading.EAST, Placement.arcHeading(Heading.EAST, new Vec3i(10, 0, 1)));
		assertEquals(Heading.WEST, Placement.arcHeading(Heading.EAST, new Vec3i(0, 0, 6)));
		assertEquals(Heading.NORTH_EAST, Placement.arcHeading(Heading.NORTH_EAST, new Vec3i(5, 0, -5)));
	}

	/**
	 * A new anchor takes the arc's heading if that works, and otherwise, of the other headings that work, the one whose
	 * segment bends least sharply, however far it turns from the arc's; with none working, the arc's, to show why.
	 */
	@Test
	void aNewAnchorFallsBackToTheGentlestHeadingThatWorks() {
		assertEquals(Heading.SOUTH_EAST, Placement.pickHeading(Heading.SOUTH_EAST, heading -> true, heading -> 1));
		assertEquals(Heading.WEST, Placement.pickHeading(Heading.SOUTH_EAST,
				heading -> heading == Heading.WEST || heading == Heading.NORTH,
				heading -> heading == Heading.WEST ? 0.1 : 0.2));
		assertEquals(Heading.SOUTH_EAST, Placement.pickHeading(Heading.SOUTH_EAST, heading -> false, heading -> 0));
	}

	/**
	 * Leaving east, a new anchor 4 ahead and 23 north is on a chord 80° off: the arc turns 160°, nearest west, and its
	 * segment, and those of its neighbours north-west and south-west, break the limits. Heading north, two steps from
	 * west, a long easy left turn keeps them, so it is the one taken.
	 */
	@Test
	void aNewAnchorFarToTheSideTurnsAsFarAsItMust() {
		Vec3i offset = new Vec3i(4, 0, -23);
		Segment.End leaving = new Segment.End(new AnchorTangent(Heading.EAST, 0), AnchorSide.FRONT);
		assertEquals(Heading.WEST, Placement.arcHeading(Heading.EAST, offset));

		Heading picked = Placement.pickHeading(Placement.arcHeading(Heading.EAST, offset),
				heading -> newAnchor(offset, leaving, heading).brokenLimits().isEmpty(), heading -> 0);

		assertEquals(Heading.NORTH, picked);
	}

	private static Segment newAnchor(Vec3i offset, Segment.End leaving, Heading heading) {
		Vec3i back = offset.multiply(-1);
		return new Segment(offset, leaving,
				new Segment.End(new AnchorTangent(heading, Placement.pitchToward(heading, back)), AnchorSide.BACK));
	}

	/**
	 * A lone anchor, holding no segment, takes the pitch of the chord to the other anchor whatever its pitch was: 5 up
	 * over 10 across is atan(0.5) ≈ 26.57°, climbing along a heading toward it (here east, leaving by the front) and
	 * falling along the opposite one (west, leaving by the back, which points east). An anchor holding a segment keeps
	 * its tangent, 10° up east, which the segment it holds leaves along. A side given is kept, rule A or not.
	 */
	@Test
	void aLoneAnchorTakesThePitchOfTheChordAndOneHoldingASegmentKeepsItsTangent() {
		Vec3i up = new Vec3i(10, 5, 0);
		Segment.End east = Placement.end(new AnchorTangent(Heading.EAST, -20), false, Optional.empty(), up);
		Segment.End west = Placement.end(new AnchorTangent(Heading.WEST, 0), false, Optional.empty(), up);
		Segment.End held = Placement.end(new AnchorTangent(Heading.EAST, 10), true, Optional.empty(), up);
		Segment.End given = Placement.end(new AnchorTangent(Heading.EAST, 10), true, Optional.of(AnchorSide.BACK), up);

		assertEquals(Heading.EAST, east.tangent().heading());
		assertEquals(26.565, east.tangent().pitch(), 1e-3);
		assertEquals(AnchorSide.FRONT, east.side());
		assertEquals(Heading.WEST, west.tangent().heading());
		assertEquals(-26.565, west.tangent().pitch(), 1e-3);
		assertEquals(AnchorSide.BACK, west.side());
		assertEquals(new Segment.End(new AnchorTangent(Heading.EAST, 10), AnchorSide.FRONT), held);
		assertEquals(new Segment.End(new AnchorTangent(Heading.EAST, 10), AnchorSide.BACK), given);
	}
}
