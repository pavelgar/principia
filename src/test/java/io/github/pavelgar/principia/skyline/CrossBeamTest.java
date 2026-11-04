package io.github.pavelgar.principia.skyline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

/** The outline of an anchor's whole cross-beam: one box, the beam's bar as the anchor's models draw it. */
class CrossBeamTest {
	private static final double PX = 1 / 16.0;
	private static final double EXACT = 1e-9;
	private static final double HALF = Math.sqrt(0.5);

	/** The corners of the straight model's element, from (-9, 14, 7) to (25, 16, 9) px: an anchor heading south. */
	private static List<Vec3> straightElement() {
		List<Vec3> corners = new ArrayList<>();
		for (double x : new double[]{-9 * PX, 25 * PX}) {
			for (double y : new double[]{14 * PX, 16 * PX}) {
				for (double z : new double[]{7 * PX, 9 * PX}) {
					corners.add(new Vec3(x, y, z));
				}
			}
		}
		return corners;
	}

	@Test
	void aStraightBeamIsOutlinedAsItsModelsElement() {
		assertSameCorners(straightElement(), CrossBeam.corners(Heading.SOUTH));
		assertSameCorners(straightElement(), CrossBeam.corners(Heading.NORTH));
	}

	/**
	 * The diagonal model turns the same element 45° about the cell's centre, so it runs from north-east to south-west:
	 * x along the element becomes (√½, -√½), z across it (√½, √½).
	 */
	@Test
	void aDiagonalBeamIsOutlinedAsItsModelsTurnedElement() {
		List<Vec3> turned = new ArrayList<>();
		for (Vec3 corner : straightElement()) {
			double x = corner.x - 0.5;
			double z = corner.z - 0.5;
			turned.add(new Vec3(0.5 + HALF * x + HALF * z, corner.y, 0.5 - HALF * x + HALF * z));
		}

		assertSameCorners(turned, CrossBeam.corners(Heading.SOUTH_EAST));
	}

	/** Corner i and corner j share an edge when their indices differ in one bit: 12 edges, 4 of 34 px and 8 of 2. */
	@Test
	void cornersDifferingInOneBitShareAnEdge() {
		List<Vec3> corners = CrossBeam.corners(Heading.SOUTH_EAST);
		List<Double> lengths = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			for (int bit = 1; bit < 8; bit <<= 1) {
				if ((i & bit) == 0) {
					lengths.add(corners.get(i).distanceTo(corners.get(i | bit)) / PX);
				}
			}
		}
		assertEquals(12, lengths.size());
		assertEquals(4, lengths.stream().filter(length -> Math.abs(length - 34) < 1e-6).count());
		assertEquals(8, lengths.stream().filter(length -> Math.abs(length - 2) < 1e-6).count());
	}

	private static void assertSameCorners(List<Vec3> expected, List<Vec3> actual) {
		assertEquals(expected.size(), actual.size());
		for (Vec3 corner : expected) {
			assertTrue(actual.stream().anyMatch(other -> other.distanceTo(corner) < EXACT),
					"no corner at " + corner + " in " + actual);
		}
	}
}
