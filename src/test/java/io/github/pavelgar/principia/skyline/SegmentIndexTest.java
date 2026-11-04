package io.github.pavelgar.principia.skyline;

import static io.github.pavelgar.principia.skyline.SegmentCurveTest.end;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

class SegmentIndexTest {
	private static final BlockPos WEST_ANCHOR = new BlockPos(100, 64, 200);
	private static final BlockPos EAST_ANCHOR = WEST_ANCHOR.east(10);
	/** 10 east from {@link #WEST_ANCHOR} to {@link #EAST_ANCHOR}, level, as the west anchor stores it. */
	private static final Segment EASTWARD = new Segment(new Vec3i(10, 0, 0), end(Heading.EAST, 0, AnchorSide.FRONT),
			end(Heading.EAST, 0, AnchorSide.BACK));
	/** {@link #EASTWARD} as the index gives it: as the west anchor, first in {@code BlockPos} order, stores it. */
	private static final List<SegmentIndex.Held> FOUND = List.of(new SegmentIndex.Held(WEST_ANCHOR, EASTWARD));
	/**
	 * Around the middle of the south rail. The rails of {@link #EASTWARD} run 1 block either side of the centre line,
	 * z = 200.5 ± 1, from x = 100.5 to 110.5, their bars 2 px square around a line 1 px below the anchors' tops.
	 */
	private static final AABB ON_SOUTH_RAIL = new AABB(105, 64.5, 201, 106, 66, 202);

	@Test
	void aSegmentOneLoadedAnchorHoldsIsFound() {
		SegmentIndex index = new SegmentIndex();
		index.put(WEST_ANCHOR, List.of(EASTWARD));

		assertEquals(FOUND, index.near(ON_SOUTH_RAIL));
	}

	@Test
	void aSegmentBothAnchorsHoldIsFoundOnce() {
		SegmentIndex index = new SegmentIndex();
		index.put(WEST_ANCHOR, List.of(EASTWARD));
		index.put(EAST_ANCHOR, List.of(EASTWARD.reversed()));

		assertEquals(FOUND, index.near(ON_SOUTH_RAIL));
	}

	@Test
	void aSegmentOnlyItsEastAnchorHoldsIsFoundAsTheWestOneWouldStoreIt() {
		SegmentIndex index = new SegmentIndex();
		index.put(EAST_ANCHOR, List.of(EASTWARD.reversed()));

		assertEquals(FOUND, index.near(ON_SOUTH_RAIL));
	}

	@Test
	void aSegmentStaysWhileEitherAnchorHoldsIt() {
		SegmentIndex index = new SegmentIndex();
		index.put(WEST_ANCHOR, List.of(EASTWARD));
		index.put(EAST_ANCHOR, List.of(EASTWARD.reversed()));

		index.remove(WEST_ANCHOR);
		assertEquals(FOUND, index.near(ON_SOUTH_RAIL));
		index.remove(EAST_ANCHOR);
		assertEquals(List.of(), index.near(ON_SOUTH_RAIL));
	}

	/**
	 * Each segment's curve is built once, as it enters the index, and every search hands out that one, from the anchor
	 * the segment is found as: {@link #EASTWARD}'s starts at the west anchor's point, (100.5, 64.9375, 200.5).
	 */
	@Test
	void everySearchHandsOutTheCurveTheIndexBuilt() {
		SegmentIndex index = new SegmentIndex();
		index.put(WEST_ANCHOR, List.of(EASTWARD));
		index.put(EAST_ANCHOR, List.of(EASTWARD.reversed()));

		SegmentCurve curve = index.near(ON_SOUTH_RAIL).get(0).curve();

		assertSame(curve, index.near(ON_SOUTH_RAIL.move(2, 0, 0)).get(0).curve());
		SegmentCurveTest.assertVec(new Vec3(100.5, 64.9375, 200.5), curve.point(0), 1e-9);
	}

	/** A segment the index finds is equal to one named by the same anchor and segment, and to no other. */
	@Test
	void aFoundSegmentEqualsOneNamedByItsAnchorAndSegment() {
		SegmentIndex index = new SegmentIndex();
		index.put(WEST_ANCHOR, List.of(EASTWARD));

		SegmentIndex.Held found = index.near(ON_SOUTH_RAIL).get(0);

		assertEquals(WEST_ANCHOR, found.anchor());
		assertEquals(EASTWARD, found.segment());
		SegmentIndex.Held named = new SegmentIndex.Held(WEST_ANCHOR, EASTWARD);
		assertEquals(named, found);
		assertEquals(named.hashCode(), found.hashCode());
		// The same segment as the east anchor stores it, and a segment from the west anchor going the other way.
		assertNotEquals(new SegmentIndex.Held(EAST_ANCHOR, EASTWARD.reversed()), found);
		assertNotEquals(new SegmentIndex.Held(WEST_ANCHOR, EASTWARD.reversed()), found);
	}

	@Test
	void anAnchorsSegmentsReplaceThoseItHeldBefore() {
		SegmentIndex index = new SegmentIndex();
		index.put(WEST_ANCHOR, List.of(EASTWARD));

		index.put(WEST_ANCHOR, List.of());

		assertEquals(List.of(), index.near(ON_SOUTH_RAIL));
	}

	@Test
	void removingAnAnchorLeavesTheSegmentsOfOthers() {
		BlockPos elsewhere = WEST_ANCHOR.south(5);
		SegmentIndex index = new SegmentIndex();
		index.put(WEST_ANCHOR, List.of(EASTWARD));
		index.put(elsewhere, List.of(EASTWARD));

		index.remove(elsewhere);

		assertEquals(FOUND, index.near(ON_SOUTH_RAIL));
	}

	@Test
	void anAreaInTheSegmentsSectionButBesideItsRailsFindsNothing() {
		SegmentIndex index = new SegmentIndex();
		index.put(WEST_ANCHOR, List.of(EASTWARD));

		// The south rail's bar reaches z = 201.5 + 1/16; its section runs on to z = 208.
		assertEquals(List.of(), index.near(new AABB(105, 64, 201.6, 106, 65, 202)));
	}

	@Test
	void anAreaAcrossSectionBordersFindsTheSegmentOnce() {
		SegmentIndex index = new SegmentIndex();
		index.put(WEST_ANCHOR, List.of(EASTWARD));

		// The area spans the borders between sections x = 5, 6 and 7 at x = 96 and 112, y = 3 and 4 at y = 64, and
		// z = 12 and 13 at z = 208, and holds both rails whole.
		assertEquals(FOUND, index.near(new AABB(95, 63, 195, 112.5, 66, 209)));
	}

	/** 40 east from x = 100, in section x = 6, to x = 140, in section 8: its middle crosses section 7 alone. */
	@Test
	void aSegmentIsFoundInASectionOnlyItsMiddleCrosses() {
		Segment long40 = new Segment(new Vec3i(40, 0, 0), end(Heading.EAST, 0, AnchorSide.FRONT),
				end(Heading.EAST, 0, AnchorSide.BACK));
		SegmentIndex index = new SegmentIndex();
		index.put(WEST_ANCHOR, List.of(long40));

		assertEquals(List.of(new SegmentIndex.Held(WEST_ANCHOR, long40)), index.near(ON_SOUTH_RAIL.move(15, 0, 0)));
	}

	@Test
	void theSegmentsEndingAtAnAnchorAreThoseTheOtherEndsHold() {
		SegmentIndex index = new SegmentIndex();
		index.put(WEST_ANCHOR, List.of(EASTWARD));

		assertEquals(List.of(new SegmentIndex.Held(WEST_ANCHOR, EASTWARD)), index.endingAt(EAST_ANCHOR));
		// The east anchor holds nothing, so nothing that ends at the west one is held at its other end.
		assertEquals(List.of(), index.endingAt(WEST_ANCHOR));

		index.put(EAST_ANCHOR, List.of(EASTWARD.reversed()));

		assertEquals(List.of(new SegmentIndex.Held(EAST_ANCHOR, EASTWARD.reversed())), index.endingAt(WEST_ANCHOR));
		assertEquals(List.of(new SegmentIndex.Held(WEST_ANCHOR, EASTWARD)), index.endingAt(EAST_ANCHOR));
	}

	@Test
	void aSegmentThatOnlyPassesAnAnchorDoesntEndThere() {
		SegmentIndex index = new SegmentIndex();
		index.put(WEST_ANCHOR, List.of(EASTWARD));

		// On the centre line, halfway between the anchors.
		assertEquals(List.of(), index.endingAt(WEST_ANCHOR.east(5)));
	}

	@Test
	void aSegmentBothAnchorsHoldIsDrawnFromTheFirstInBlockPosOrder() {
		SegmentIndex index = new SegmentIndex();
		index.put(WEST_ANCHOR, List.of(EASTWARD));
		index.put(EAST_ANCHOR, List.of(EASTWARD.reversed()));

		// Same y and z: the west anchor, at the lower x, comes first.
		assertEquals(FOUND, index.drawnFrom(WEST_ANCHOR));
		assertEquals(List.of(), index.drawnFrom(EAST_ANCHOR));
	}

	@Test
	void aSegmentOnlyItsLaterAnchorHoldsIsDrawnFromThatOne() {
		SegmentIndex index = new SegmentIndex();
		index.put(WEST_ANCHOR, List.of(EASTWARD));
		index.put(EAST_ANCHOR, List.of(EASTWARD.reversed()));

		index.remove(WEST_ANCHOR);

		assertEquals(List.of(), index.drawnFrom(WEST_ANCHOR));
		assertEquals(FOUND, index.drawnFrom(EAST_ANCHOR));
	}

	/** Positions are ordered by y before x: the lower anchor draws, though it is further east. */
	@Test
	void theAnchorThatDrawsIsFirstByHeightBeforeEastWest() {
		BlockPos lowEast = new BlockPos(110, 64, 200);
		BlockPos highWest = new BlockPos(100, 70, 200);
		Segment climbingWest = new Segment(new Vec3i(-10, 6, 0), end(Heading.WEST, 0, AnchorSide.FRONT),
				end(Heading.WEST, 0, AnchorSide.BACK));
		SegmentIndex index = new SegmentIndex();
		index.put(lowEast, List.of(climbingWest));
		index.put(highWest, List.of(climbingWest.reversed()));

		assertEquals(List.of(new SegmentIndex.Held(lowEast, climbingWest)), index.drawnFrom(lowEast));
		assertEquals(List.of(), index.drawnFrom(highWest));
	}

	@Test
	void anAnchorDrawsEachOfItsSegmentsItComesFirstFor() {
		BlockPos farEast = EAST_ANCHOR.east(10);
		SegmentIndex index = new SegmentIndex();
		index.put(WEST_ANCHOR, List.of(EASTWARD));
		index.put(EAST_ANCHOR, List.of(EASTWARD.reversed(), EASTWARD));
		index.put(farEast, List.of(EASTWARD.reversed()));

		// The middle anchor comes after the west one and before the far east one.
		assertEquals(List.of(new SegmentIndex.Held(EAST_ANCHOR, EASTWARD)), index.drawnFrom(EAST_ANCHOR));
	}

	@Test
	void anAnchorThatHoldsNothingDrawsNothing() {
		SegmentIndex index = new SegmentIndex();
		index.put(EAST_ANCHOR, List.of(EASTWARD.reversed()));

		assertEquals(List.of(), index.drawnFrom(WEST_ANCHOR));
	}

	@Test
	void aDrawnSegmentComesWithTheCurveTheIndexBuilt() {
		SegmentIndex index = new SegmentIndex();
		index.put(WEST_ANCHOR, List.of(EASTWARD));

		assertSame(index.near(ON_SOUTH_RAIL).get(0).curve(), index.drawnFrom(WEST_ANCHOR).get(0).curve());
	}

	@Test
	void aSegmentOutsideAnyWorldsHeightIsFound() {
		// Anchors far above any build height, as a curve's crest can be: sections are not limited to a world's.
		BlockPos high = new BlockPos(-30, 4000, -30);
		SegmentIndex index = new SegmentIndex();
		index.put(high, List.of(EASTWARD));

		assertEquals(List.of(new SegmentIndex.Held(high, EASTWARD)),
				index.near(ON_SOUTH_RAIL.move(-130, 4000 - 64, -230)));
	}
}
