package io.github.pavelgar.principia.skyline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

class SegmentCurveTest {
	private static final double EXACT = 1e-9;

	static Segment.End end(Heading heading, float pitch, AnchorSide side) {
		return new Segment.End(new AnchorTangent(heading, pitch), side);
	}

	static void assertVec(Vec3 expected, Vec3 actual, double delta) {
		assertEquals(expected.x, actual.x, delta, () -> "x of " + actual);
		assertEquals(expected.y, actual.y, delta, () -> "y of " + actual);
		assertEquals(expected.z, actual.z, delta, () -> "z of " + actual);
	}

	/**
	 * Anchor (10, 64, -3) front side, tangent east and 30° up; partner 8 east, 3 up and 4 south of it, back side,
	 * tangent south and 10° down.
	 */
	private static final SegmentCurve PITCHED = new Segment(new Vec3i(8, 3, 4),
			end(Heading.EAST, 30, AnchorSide.FRONT), end(Heading.SOUTH, -10, AnchorSide.BACK))
			.curve(new BlockPos(10, 64, -3));

	@Test
	void startsAndEndsOnTheAnchorPoints() {
		// The cell's horizontal centre, 1 px below its top: (10 + 0.5, 64 + 1 - 1/16, -3 + 0.5).
		assertVec(new Vec3(10.5, 64.9375, -2.5), PITCHED.point(0), EXACT);
		// Partner cell (18, 67, 1).
		assertVec(new Vec3(18.5, 67.9375, 1.5), PITCHED.point(PITCHED.length()), EXACT);
	}

	@Test
	void leavesAlongTheFrontTangentAndArrivesAlongTheBackSideOne() {
		// East and 30° up: (cos 30°, sin 30°, 0).
		assertVec(new Vec3(0.8660254038, 0.5, 0), PITCHED.direction(0), EXACT);
		// The back side's segment arrives along the tangent, as the line goes on through the anchor to its front side:
		// south and 10° down, (0, sin -10°, cos -10°).
		assertVec(new Vec3(0, -0.1736481777, 0.9848077530), PITCHED.direction(PITCHED.length()), EXACT);
	}

	@Test
	void leavesAlongTheReverseOnTheBackSideAndArrivesAgainstTheFrontSideTangent() {
		SegmentCurve curve = new Segment(new Vec3i(6, -3, 9), end(Heading.NORTH_WEST, 20, AnchorSide.BACK),
				end(Heading.NORTH, 15, AnchorSide.FRONT)).curve(BlockPos.ZERO);

		// Reverse of north-west and 20° up: south-east and 20° down, (cos 20° / √2, -sin 20°, cos 20° / √2).
		assertVec(new Vec3(0.6644630243, -0.3420201433, 0.6644630243), curve.direction(0), EXACT);
		// The partner's front side leaves north and 15° up, so the segment arrives south and 15° down:
		// (0, -sin 15°, cos 15°).
		assertVec(new Vec3(0, -0.2588190451, 0.9659258263), curve.direction(curve.length()), EXACT);
	}

	@Test
	void aStraightSegmentIsExactlyAsLongAsTheDistanceBetweenItsAnchors() {
		SegmentCurve alongX = new Segment(new Vec3i(10, 0, 0), end(Heading.EAST, 0, AnchorSide.FRONT),
				end(Heading.EAST, 0, AnchorSide.BACK)).curve(BlockPos.ZERO);
		SegmentCurve diagonal = new Segment(new Vec3i(6, 0, -6), end(Heading.NORTH_EAST, 0, AnchorSide.FRONT),
				end(Heading.NORTH_EAST, 0, AnchorSide.BACK)).curve(BlockPos.ZERO);

		assertEquals(10, alongX.length(), EXACT);
		assertEquals(6 * Math.sqrt(2), diagonal.length(), EXACT);
		// Evenly parametrised: 2.5 blocks along is 2.5 blocks from the start.
		assertVec(new Vec3(3, 0.9375, 0.5), alongX.point(2.5), EXACT);
	}

	@Test
	void aQuarterTurnOfRadiusFourIsAsLongAsAQuarterCircle() {
		SegmentCurve turn = quarterTurn();

		// A quarter of a circle of radius 4: 2π · 4 / 4 = 2π.
		assertEquals(2 * Math.PI, turn.length(), 2 * Math.PI * 0.001);
	}

	@Test
	void pointsAreSpacedByDistanceAlongTheCurve() {
		SegmentCurve turn = quarterTurn();

		// A chord of 0.25 blocks of arc on radius 4 is 2 · 4 · sin(0.25 / 8) ≈ 0.24996.
		for (double d = 0; d + 0.25 <= turn.length(); d += 0.25) {
			assertEquals(0.25, turn.point(d).distanceTo(turn.point(d + 0.25)), 0.001, "at " + d);
		}
	}

	/** East from the origin, turning right into south at (4, 0, 4): a quarter of a circle of radius 4. */
	static SegmentCurve quarterTurn() {
		return new Segment(new Vec3i(4, 0, 4), end(Heading.EAST, 0, AnchorSide.FRONT),
				end(Heading.SOUTH, 0, AnchorSide.BACK)).curve(BlockPos.ZERO);
	}

	/**
	 * Across a straight rail along an axis the rail bounds are exact. Level from the anchor point (100.5, 64.9375,
	 * 200.5) to 10 east, the rails run at z = 200.5 ± 1 and their bars reach 1 px further, to z = 200.5 ± 17/16, and
	 * 1 px above and below y = 64.9375. Along the rails the bounds reach 17/16 past each anchor point, as far as a rail
	 * can reach sideways: from x = 100.5 - 1.0625 to 110.5 + 1.0625.
	 */
	@Test
	void theRailBoundsOfAStraightSegmentAlongAnAxisAreExactAcrossIt() {
		SegmentCurve curve = new Segment(new Vec3i(10, 0, 0), end(Heading.EAST, 0, AnchorSide.FRONT),
				end(Heading.EAST, 0, AnchorSide.BACK)).curve(new BlockPos(100, 64, 200));

		assertEquals(new AABB(99.4375, 64.875, 199.4375, 111.5625, 65, 201.5625), curve.railBounds());
	}

	/**
	 * Both rails' bars lie inside the rail bounds: each rail's line, 1 px to either side across it and 1 px up and
	 * down, every 1/64 block along it and at its end. The bounds reach exactly as high and low as the bars, and to the sides at most
	 * a rail's reach past them, 17/16 blocks. Among the shapes, a turn whose partner is 1 east and 6 south, leaving east and arriving south,
	 * swings out to about x = 1.35 before it comes back to x = 1 (its handles are 2.375 long), and a crest and a dip
	 * between level anchors rise above and sink below both anchors.
	 */
	@Test
	void theRailBoundsHoldBothRailsBarsAndLittleMore() {
		List<SegmentCurve> curves = List.of(PITCHED,
				new Segment(new Vec3i(1, 0, 6), end(Heading.EAST, 0, AnchorSide.FRONT),
						end(Heading.SOUTH, 0, AnchorSide.BACK)).curve(BlockPos.ZERO),
				new Segment(new Vec3i(5, 0, 0), end(Heading.EAST, 35, AnchorSide.FRONT),
						end(Heading.EAST, -35, AnchorSide.BACK)).curve(BlockPos.ZERO),
				new Segment(new Vec3i(-5, 0, 3), end(Heading.WEST, -35, AnchorSide.FRONT),
						end(Heading.WEST, 30, AnchorSide.BACK)).curve(BlockPos.ZERO),
				new Segment(new Vec3i(4, 4, 4), end(Heading.EAST, 30, AnchorSide.FRONT),
						end(Heading.SOUTH, 30, AnchorSide.BACK)).curve(BlockPos.ZERO),
				new Segment(new Vec3i(8, 0, -8), end(Heading.NORTH_EAST, 0, AnchorSide.FRONT),
						end(Heading.NORTH_EAST, 0, AnchorSide.BACK)).curve(BlockPos.ZERO));
		for (SegmentCurve curve : curves) {
			AABB bounds = curve.railBounds();
			// Starts at the centre line's start, which lies between the rails' starts.
			AABB bars = new AABB(curve.point(0), curve.point(0));
			for (RailSide side : RailSide.values()) {
				Rail rail = curve.rail(side);
				int steps = (int) Math.ceil(rail.length() * 64);
				for (int step = 0; step <= steps; step++) {
					double distance = Math.min(step / 64.0, rail.length());
					Vec3 point = rail.point(distance);
					Vec3 across = curve.rightAt(rail.parameterAt(distance)).scale(Rail.HALF_BAR);
					for (int a = -1; a <= 1; a += 2) {
						for (int b = -1; b <= 1; b += 2) {
							Vec3 corner = point.add(across.scale(a)).add(0, b * Rail.HALF_BAR, 0);
							assertTrue(holds(bounds, corner), () -> bounds + " misses " + corner);
							bars = bars.minmax(new AABB(corner, corner));
						}
					}
				}
			}
			// Sampled every 1/64 block, a crest's top is missed by far less than a thousandth of a block.
			AABB sampled = bars;
			assertEquals(sampled.minY, bounds.minY, 1e-3, () -> "bottom of " + bounds);
			assertEquals(sampled.maxY, bounds.maxY, 1e-3, () -> "top of " + bounds);
			double reach = 17 / 16.0 + 1e-3;
			AABB widened = sampled.inflate(reach, 0, reach);
			assertTrue(widened.minX <= bounds.minX && bounds.maxX <= widened.maxX && widened.minZ <= bounds.minZ
					&& bounds.maxZ <= widened.maxZ, () -> bounds + " reaches more than 17/16 past " + sampled);
		}
	}

	private static boolean holds(AABB box, Vec3 point) {
		return box.minX - EXACT <= point.x && point.x <= box.maxX + EXACT && box.minY - EXACT <= point.y
				&& point.y <= box.maxY + EXACT && box.minZ - EXACT <= point.z && point.z <= box.maxZ + EXACT;
	}
}
