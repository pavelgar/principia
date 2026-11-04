package io.github.pavelgar.principia.skyline;

import static io.github.pavelgar.principia.skyline.SegmentCurveTest.end;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;

/** What a segment costs in rails, and where its rails fall when it breaks. */
class RailDropsTest {
	private static final double EXACT = 1e-6;

	private static Segment segment(Vec3i offset, Heading heading, float pitch, Heading arriving,
			float arrivingPitch) {
		return new Segment(offset, end(heading, pitch, AnchorSide.FRONT),
				end(arriving, arrivingPitch, AnchorSide.BACK));
	}

	/** One rail per block of the centre line's length, rounded up. */
	@Test
	void aSegmentCostsOneRailPerBlockOfItsLengthRoundedUp() {
		// A straight 10 east: exactly 10 long.
		assertEquals(10, RailDrops.cost(segment(new Vec3i(10, 0, 0), Heading.EAST, 0, Heading.EAST, 0)));
		// A straight 10 east and 2 up, pitched atan(2 / 10) ≈ 11.31° at both ends: √104 ≈ 10.2 long.
		float pitch = (float) Math.toDegrees(Math.atan2(2, 10));
		assertEquals(11, RailDrops.cost(segment(new Vec3i(10, 2, 0), Heading.EAST, pitch, Heading.EAST, pitch)));
		// A quarter turn of radius 6: about 2π · 6 / 4 ≈ 9.42 long.
		assertEquals(10, RailDrops.cost(segment(new Vec3i(6, 0, 6), Heading.EAST, 0, Heading.SOUTH, 0)));
	}

	/**
	 * Laying a segment to a new anchor costs one more, for the anchor: a straight 10 east costs 11 to a new anchor and
	 * 10 to an existing one; one 10 east and 2 up, √104 ≈ 10.2 long, 12 and 11.
	 */
	@Test
	void aSegmentToANewAnchorCostsOneMoreForTheAnchor() {
		Segment straight = segment(new Vec3i(10, 0, 0), Heading.EAST, 0, Heading.EAST, 0);
		assertEquals(11, RailDrops.cost(straight, true));
		assertEquals(10, RailDrops.cost(straight, false));
		float pitch = (float) Math.toDegrees(Math.atan2(2, 10));
		Segment climbing = segment(new Vec3i(10, 2, 0), Heading.EAST, pitch, Heading.EAST, pitch);
		assertEquals(12, RailDrops.cost(climbing, true));
		assertEquals(11, RailDrops.cost(climbing, false));
	}

	/**
	 * The straight 10 east from an anchor at the origin runs along its centre line from x = 0.5 to 10.5, at y = 1 - 1/16
	 * and z = 0.5. Its 10 rails fall from the middles of its 10 one-block stretches: x = 1, 2, ..., 10.
	 */
	@Test
	void aSegmentsRailsFallFromEvenlyAlongItsCentreLine() {
		List<Vec3> points = RailDrops.points(BlockPos.ZERO,
				segment(new Vec3i(10, 0, 0), Heading.EAST, 0, Heading.EAST, 0));

		assertEquals(10, points.size());
		for (int i = 0; i < 10; i++) {
			Vec3 point = points.get(i);
			assertEquals(i + 1, point.x, EXACT, "the x of point " + i);
			assertEquals(0.9375, point.y, EXACT, "the y of point " + i);
			assertEquals(0.5, point.z, EXACT, "the z of point " + i);
		}
	}

	/**
	 * Along a quarter turn of radius 6, centred 6 south of the start, its 10 rails fall from points on the curve: each
	 * about 6 from the turn's centre (the Bézier strays from the circle by well under 0.01), and as far from the next
	 * as from the one before.
	 */
	@Test
	void aTurnsRailsFallFromEvenlyAlongTheTurn() {
		List<Vec3> points = RailDrops.points(BlockPos.ZERO,
				segment(new Vec3i(6, 0, 6), Heading.EAST, 0, Heading.SOUTH, 0));
		Vec3 centre = new Vec3(0.5, 0.9375, 6.5);

		assertEquals(10, points.size());
		for (Vec3 point : points) {
			assertEquals(6, point.distanceTo(centre), 0.01, "the radius at " + point);
		}
		double spacing = points.get(0).distanceTo(points.get(1));
		for (int i = 1; i < points.size() - 1; i++) {
			assertEquals(spacing, points.get(i).distanceTo(points.get(i + 1)), 0.01, "the spacing after point " + i);
		}
	}
}
