package io.github.pavelgar.principia.skyline;

import static io.github.pavelgar.principia.skyline.SegmentCurveTest.assertVec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.DoubleFunction;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.InRange;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What holds for every segment's curve, fuzzed: see {@link FuzzedTrack} for the segments. Anchors go anywhere in a
 * world, out to the world border and over the build height.
 */
class SegmentCurveFuzzTest {
	/** Points along the curves compared, evenly spaced by distance. */
	private static final int SAMPLES = 64;

	/**
	 * Both anchors hold a segment, each as seen from itself, and whichever builds the curve, it is the same line run
	 * the other way: the same points, the same length, and the same limits broken, so a segment placed from one end
	 * could as well have been placed from the other.
	 */
	@FuzzTest(maxDuration = "1m")
	void eitherAnchorBuildsTheSameCurve(@NotNull FuzzedTrack.Offset offset, @NotNull FuzzedTrack.End from,
			@NotNull FuzzedTrack.End to, @InRange(min = -30_000_000, max = 30_000_000) int x,
			@InRange(min = -64, max = 319) int y, @InRange(min = -30_000_000, max = 30_000_000) int z) {
		Segment segment = FuzzedTrack.any(offset, from, to);
		if (segment == null) {
			return;
		}
		FuzzedTrack.checking(segment, () -> {
			BlockPos anchor = new BlockPos(x, y, z);
			SegmentCurve curve = segment.curve(anchor);
			SegmentCurve back = segment.reversed().curve(anchor.offset(segment.offset()));

			double length = curve.length();
			assertEquals(length, back.length(), 1e-9 * length, "the length seen from the other anchor");
			for (int i = 0; i <= SAMPLES; i++) {
				double distance = length * i / SAMPLES;
				assertVec(curve.point(distance), back.point(length - distance), 1e-6);
			}
			assertEquals(segment.brokenLimits(), segment.reversed().brokenLimits(),
					"the limits seen from the other anchor");
		});
	}

	/**
	 * Seen from the other anchor, the rails swap sides and run from the other end, which is how riding and aiming map a
	 * rail point named from either anchor onto the curve the index built.
	 */
	@FuzzTest(maxDuration = "1m")
	void eitherAnchorBuildsTheSameRails(@NotNull FuzzedTrack.End from, @NotNull FuzzedTrack.Shape shape,
			@InRange(min = -30_000_000, max = 30_000_000) int x, @InRange(min = -64, max = 319) int y,
			@InRange(min = -30_000_000, max = 30_000_000) int z) {
		Segment segment = FuzzedTrack.placeable(from, shape);
		if (segment == null) {
			return;
		}
		FuzzedTrack.checking(segment, () -> {
			BlockPos anchor = new BlockPos(x, y, z);
			SegmentCurve curve = segment.curve(anchor);
			SegmentCurve back = segment.reversed().curve(anchor.offset(segment.offset()));

			for (RailSide side : RailSide.values()) {
				Rail rail = curve.rail(side);
				Rail other = back.rail(side.opposite());
				double length = rail.length();
				assertEquals(length, other.length(), 1e-9 * length, () -> "the length of the " + side + " rail");
				for (int i = 0; i <= SAMPLES; i++) {
					double distance = length * i / SAMPLES;
					assertVec(rail.point(distance), other.point(length - distance), 1e-6);
				}
			}
		});
	}

	/** The box the index files a segment under holds both rails' bars whole, whatever the segment's shape. */
	@FuzzTest(maxDuration = "1m")
	void railBoundsHoldBothRails(@NotNull FuzzedTrack.Offset offset, @NotNull FuzzedTrack.End from,
			@NotNull FuzzedTrack.End to) {
		Segment segment = FuzzedTrack.any(offset, from, to);
		if (segment == null) {
			return;
		}
		FuzzedTrack.checking(segment, () -> {
			SegmentCurve curve = segment.curve(BlockPos.ZERO);
			// The bar reaches half its width either side of the rail's line.
			AABB bounds = curve.railBounds().deflate(Rail.HALF_BAR - 1e-9);

			for (RailSide side : RailSide.values()) {
				Rail rail = curve.rail(side);
				for (int i = 0; i <= SAMPLES; i++) {
					Vec3 point = rail.point(rail.length() * i / SAMPLES);
					assertTrue(point.x >= bounds.minX && point.x <= bounds.maxX && point.y >= bounds.minY
							&& point.y <= bounds.maxY && point.z >= bounds.minZ && point.z <= bounds.maxZ,
							() -> "the " + side + " rail's " + point + " is outside " + curve.railBounds());
				}
			}
		});
	}

	/**
	 * A segment that breaks no limit keeps to them everywhere, not only at the 512 points the check samples: slope at
	 * most 45°, and a radius of at least 4 in 3D and seen from above, less the 2 % a Bézier's circle dips below it
	 * ({@link SegmentLimit#MIN_RADIUS}).
	 */
	@FuzzTest(maxDuration = "1m")
	void aPlaceableSegmentKeepsToTheLimitsBetweenTheSamples(@NotNull FuzzedTrack.End from,
			@NotNull FuzzedTrack.Shape shape) {
		Segment segment = FuzzedTrack.placeable(from, shape);
		if (segment == null) {
			return;
		}
		FuzzedTrack.checking(segment, () -> {
			SegmentCurve curve = segment.curve(BlockPos.ZERO);
			double minRadius = SegmentLimit.MIN_RADIUS * 0.98;
			// Four times as fine as the check's samples, and between them.
			int samples = 4 * 512;

			for (int i = 0; i < samples; i++) {
				double t = (i + 0.5) / samples;
				Vec3 velocity = curve.velocity(t);
				Vec3 acceleration = curve.acceleration(t);
				double horizontal = velocity.horizontalDistance();
				assertTrue(Math.abs(velocity.y) <= horizontal * (1 + 1e-9), () -> "steeper than 45° at t = " + t);
				double fromAbove = horizontal * horizontal * horizontal
						/ Math.abs(velocity.x * acceleration.z - velocity.z * acceleration.x);
				assertTrue(fromAbove >= minRadius, () -> "radius " + fromAbove + " seen from above at t = " + t);
				double inSpace = Math.pow(velocity.length(), 3) / velocity.cross(acceleration).length();
				assertTrue(inSpace >= minRadius, () -> "radius " + inSpace + " in 3D at t = " + t);
			}
		});
	}

	/**
	 * Positions along a curve and its rails are distances along them: points an eighth of a block apart, the step
	 * riding plans by, are an eighth of a block apart in a straight line, to within the thousandth of a block the arc
	 * length table is good to, as the curve bends too gently over an eighth for its chord to fall shorter.
	 */
	@FuzzTest(maxDuration = "1m")
	void distancesAlongAPlaceableSegmentAreDistances(@NotNull FuzzedTrack.End from, @NotNull FuzzedTrack.Shape shape) {
		Segment segment = FuzzedTrack.placeable(from, shape);
		if (segment == null) {
			return;
		}
		FuzzedTrack.checking(segment, () -> {
			SegmentCurve curve = segment.curve(BlockPos.ZERO);
			double step = 1 / 8.0;

			assertStepsApart(curve.length(), curve::point, step, "the centre line");
			for (RailSide side : RailSide.values()) {
				Rail rail = curve.rail(side);
				assertStepsApart(rail.length(), rail::point, step, "the " + side + " rail");
			}
		});
	}

	private static void assertStepsApart(double length, DoubleFunction<Vec3> point, double step, String line) {
		for (double distance = 0; distance + step <= length; distance += step) {
			double apart = point.apply(distance + step).distanceTo(point.apply(distance));
			double at = distance;
			assertEquals(step, apart, 1e-3, () -> "on " + line + " " + length + " long, from " + at);
		}
	}
}
