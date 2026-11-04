package io.github.pavelgar.principia.skyline;

import static io.github.pavelgar.principia.skyline.SegmentCurveTest.assertVec;
import static io.github.pavelgar.principia.skyline.SegmentCurveTest.end;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;

class RailAimTest {
	private static final double PX = 1 / 16.0;
	private static final double EXACT = 1e-6;
	private static final BlockPos WEST = new BlockPos(0, 64, 0);
	/**
	 * 10 east from {@link #WEST}, level: the rails' lines run 1 px below the anchors' tops, y = 64.9375, from x = 0.5 to
	 * 10.5, the left (north) one at z = -0.5 and the right (south) one at z = 1.5.
	 */
	private static final Segment EASTWARD = new Segment(new Vec3i(10, 0, 0), end(Heading.EAST, 0, AnchorSide.FRONT),
			end(Heading.EAST, 0, AnchorSide.BACK));
	private static final double RAIL_Y = 64.9375;
	private static final double SOUTH_RAIL_Z = 1.5;

	@Test
	void aRayPassingWithinTheAimRadiusHitsTheRailWhereItPassesClosest() {
		// Straight down at x = 4.5, 1.5 px south of the south rail's line: 4 blocks along it from its start at x = 0.5.
		Vec3 eye = new Vec3(4.5, 67, SOUTH_RAIL_Z + 1.5 * PX);

		RailAim.Hit hit = RailAim.aim(index(), eye, eye.add(0, -4, 0), 2 * PX);

		assertNotNull(hit, "no hit");
		assertEquals(RailSide.RIGHT, hit.rail());
		assertEquals(4, hit.distance(), EXACT);
		assertVec(new Vec3(4.5, RAIL_Y, SOUTH_RAIL_Z), hit.point(), EXACT);
		assertEquals(new RailPoint(WEST, AnchorSide.FRONT, RailSide.RIGHT, hit.distance()), hit.railPoint());
	}

	@Test
	void aRayPassingOutsideTheAimRadiusMisses() {
		Vec3 eye = new Vec3(4.5, 67, SOUTH_RAIL_Z + 2.5 * PX);

		assertNull(RailAim.aim(index(), eye, eye.add(0, -4, 0), 2 * PX));
	}

	/**
	 * Holding anything but a Sky-Line or a Sky-Hook, a player aims only at the rail's own 2 × 2 px bar, a 1 px radius;
	 * holding one, about a 4 × 4 px box, a 2 px radius. A ray 0.5 px from the rail's line hits it either way, one
	 * 1.5 px from it only with the wider radius.
	 */
	@Test
	void aPlayerAimsAtTheBarItselfUnlessHoldingASkyLineOrSkyHook() {
		Vec3 close = new Vec3(4.5, 67, SOUTH_RAIL_Z + 0.5 * PX);
		Vec3 near = new Vec3(4.5, 67, SOUTH_RAIL_Z + 1.5 * PX);

		assertNotNull(RailAim.aim(index(), close, close.add(0, -4, 0), RailAim.BAR_RADIUS), "0.5 px, bare");
		assertNotNull(RailAim.aim(index(), close, close.add(0, -4, 0), RailAim.HELD_RADIUS), "0.5 px, held");
		assertNull(RailAim.aim(index(), near, near.add(0, -4, 0), RailAim.BAR_RADIUS), "1.5 px, bare");
		assertNotNull(RailAim.aim(index(), near, near.add(0, -4, 0), RailAim.HELD_RADIUS), "1.5 px, held");
	}

	@Test
	void aRayEndingShortOfTheRailMisses() {
		// Straight down onto the rail's line, but ending half a block above it.
		Vec3 eye = new Vec3(4.5, 67, SOUTH_RAIL_Z);

		assertNull(RailAim.aim(index(), eye, new Vec3(4.5, RAIL_Y + 0.5, SOUTH_RAIL_Z), 2 * PX));
	}

	@Test
	void aSlantingRayHitsTheRailWhereItCrossesIt() {
		// Down and north at 45° through (6.25, 64.9375, 1.5): 5.75 blocks along the rail.
		Vec3 eye = new Vec3(6.25, RAIL_Y + 2, SOUTH_RAIL_Z + 2);

		RailAim.Hit hit = RailAim.aim(index(), eye, eye.add(0, -4, -4), 2 * PX);

		assertNotNull(hit, "no hit");
		assertEquals(5.75, hit.distance(), EXACT);
		assertEquals(2 * Math.sqrt(2), hit.reach(), EXACT);
	}

	/**
	 * A ray running down along the south rail, from 0.1 above its line at x = 2.5 to on it at x = 6.5, is within the aim
	 * radius all the way: it hits where it comes closest, at its end, 6 blocks along, not where it first comes within.
	 */
	@Test
	void aRayRunningAlongARailHitsWhereItComesClosest() {
		Vec3 eye = new Vec3(2.5, RAIL_Y + 0.1, SOUTH_RAIL_Z);

		RailAim.Hit hit = RailAim.aim(index(), eye, new Vec3(6.5, RAIL_Y, SOUTH_RAIL_Z), 2 * PX);

		assertNotNull(hit, "no hit");
		assertEquals(6, hit.distance(), EXACT);
	}

	/**
	 * On the tightest quarter turn from (0, 64, 0), east to south, the inner rail runs round (0.5, _, 4.5) with radius 3.
	 * A ray at its height along the chord between its points 10° and 80° round, from half a block before the first,
	 * crosses it twice: it hits the first crossing, 0.5 away and 3 · 10° = 0.524 blocks along the rail. The Bézier
	 * strays from the circle by thousandths.
	 */
	@Test
	void aRayCrossingARailTwiceHitsItWhereItCrossesFirst() {
		SegmentIndex index = new SegmentIndex();
		index.put(WEST, List.of(new Segment(new Vec3i(4, 0, 4), end(Heading.EAST, 0, AnchorSide.FRONT),
				end(Heading.SOUTH, 0, AnchorSide.BACK))));
		double first = Math.toRadians(-80);
		double second = Math.toRadians(-10);
		Vec3 entering = new Vec3(0.5 + 3 * Math.cos(first), RAIL_Y, 4.5 + 3 * Math.sin(first));
		Vec3 leaving = new Vec3(0.5 + 3 * Math.cos(second), RAIL_Y, 4.5 + 3 * Math.sin(second));
		Vec3 along = leaving.subtract(entering).normalize();

		RailAim.Hit hit = RailAim.aim(index, entering.subtract(along.scale(0.5)), leaving.add(along.scale(0.5)),
				2 * PX);

		assertNotNull(hit, "no hit");
		assertEquals(RailSide.RIGHT, hit.rail());
		assertEquals(0.5, hit.reach(), 0.01);
		assertEquals(3 * Math.toRadians(10), hit.distance(), 0.01);
	}

	@Test
	void ofTwoRailsAlongTheRayTheNearerIsHit() {
		// North at the rails' height from z = 3: through the south rail 1.5 blocks on, the north one 3.5 blocks on.
		Vec3 eye = new Vec3(5.5, RAIL_Y, 3);

		RailAim.Hit hit = RailAim.aim(index(), eye, eye.add(0, 0, -5), 2 * PX);

		assertNotNull(hit, "no hit");
		assertEquals(RailSide.RIGHT, hit.rail());
		assertEquals(1.5, hit.reach(), EXACT);
	}

	private static SegmentIndex index() {
		SegmentIndex index = new SegmentIndex();
		index.put(WEST, List.of(EASTWARD));
		return index;
	}
}
