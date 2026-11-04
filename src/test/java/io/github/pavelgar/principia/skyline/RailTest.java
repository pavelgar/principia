package io.github.pavelgar.principia.skyline;

import static io.github.pavelgar.principia.skyline.SegmentCurveTest.assertVec;
import static io.github.pavelgar.principia.skyline.SegmentCurveTest.end;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;

class RailTest {
	/** Right of north-east is south-east: one block that way is (1/√2, 0, 1/√2). */
	private static final Vec3 SOUTH_EAST_BLOCK = new Vec3(Math.sqrt(0.5), 0, Math.sqrt(0.5));

	@Test
	void onADiagonalTheRailsRunOneBlockToEachSideOfTheCentreLine() {
		SegmentCurve curve = new Segment(new Vec3i(5, 0, -5), end(Heading.NORTH_EAST, 0, AnchorSide.FRONT),
				end(Heading.NORTH_EAST, 0, AnchorSide.BACK)).curve(BlockPos.ZERO);

		// Starting at the anchor point (0.5, 0.9375, 0.5).
		assertVec(new Vec3(1.2071067812, 0.9375, 1.2071067812), curve.rail(RailSide.RIGHT).point(0), 1e-9);
		assertVec(new Vec3(-0.2071067812, 0.9375, -0.2071067812), curve.rail(RailSide.LEFT).point(0), 1e-9);
		for (double d = 0; d <= curve.length(); d += 0.5) {
			assertVec(curve.point(d).add(SOUTH_EAST_BLOCK), curve.rail(RailSide.RIGHT).point(d), 1e-9);
			assertVec(curve.point(d).subtract(SOUTH_EAST_BLOCK), curve.rail(RailSide.LEFT).point(d), 1e-9);
		}
	}

	@Test
	void onAClimbingDiagonalTheRailsStayLevelWithTheCentreLine() {
		// Pitched along the chord, so the segment is straight: 3 up over a horizontal run of 5√2.
		float pitch = (float) Math.toDegrees(Math.atan2(3, 5 * Math.sqrt(2)));
		SegmentCurve curve = new Segment(new Vec3i(5, 3, -5), end(Heading.NORTH_EAST, pitch, AnchorSide.FRONT),
				end(Heading.NORTH_EAST, pitch, AnchorSide.BACK)).curve(BlockPos.ZERO);

		for (double d = 0; d <= curve.length(); d += 0.5) {
			assertVec(curve.point(d).add(SOUTH_EAST_BLOCK), curve.rail(RailSide.RIGHT).point(d), 1e-6);
			assertVec(curve.point(d).subtract(SOUTH_EAST_BLOCK), curve.rail(RailSide.LEFT).point(d), 1e-6);
		}
	}

	@Test
	void onAQuarterTurnEachRailIsItsOwnQuarterCircle() {
		SegmentCurve turn = SegmentCurveTest.quarterTurn();

		// Turning right with radius 4, the right rail runs on radius 3 and the left on 5: 2π · 3 / 4 and 2π · 5 / 4.
		assertEquals(1.5 * Math.PI, turn.rail(RailSide.RIGHT).length(), 1.5 * Math.PI * 0.001);
		assertEquals(2.5 * Math.PI, turn.rail(RailSide.LEFT).length(), 2.5 * Math.PI * 0.001);
		// Both end level with the anchor points, one block to the side: south of the start, west of the end (4.5, _, 4.5).
		assertVec(new Vec3(0.5, 0.9375, 1.5), turn.rail(RailSide.RIGHT).point(0), 1e-9);
		assertVec(new Vec3(3.5, 0.9375, 4.5), turn.rail(RailSide.RIGHT).point(10), 1e-9);
		assertVec(new Vec3(5.5, 0.9375, 4.5), turn.rail(RailSide.LEFT).point(10), 1e-9);
	}
}
