package io.github.pavelgar.principia.skyline;

import java.util.EnumSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;

/**
 * A limit on the shape of a segment, each its own reason a placement can be refused. The values are starting points,
 * to be tuned in game.
 */
public enum SegmentLimit {
	/** The two anchors are the same cell. */
	SAME_CELL,
	/** The centre line is longer than {@link #MAX_LENGTH}. */
	TOO_LONG,
	/** The centre line is steeper than {@link #MAX_SLOPE_DEGREES} somewhere, not only at the anchors. */
	TOO_STEEP,
	/** The heading the segment arrives in is more than {@link #MAX_TURN_DEGREES} from the one it leaves in. */
	TURNS_TOO_FAR,
	/** Seen from above, the centre line bends tighter than {@link #MIN_RADIUS} somewhere. */
	TURN_TOO_TIGHT,
	/** The centre line bends tighter than {@link #MIN_RADIUS} in 3D somewhere: a crest, a dip or a turn. */
	BEND_TOO_SHARP;

	public static final double MAX_LENGTH = 32;
	public static final double MAX_SLOPE_DEGREES = 45;
	public static final int MAX_TURN_DEGREES = 90;
	/**
	 * The smallest radius of curvature of the centre line, in 3D and seen from above, which keeps the inner rail's at
	 * least 3. A climbing turn's radius in 3D is larger than seen from above, a crest's smaller, so both are checked.
	 */
	public static final double MIN_RADIUS = 4;

	/**
	 * A Bézier only approximates a circle: the quarter turn of radius 4 is tightest at 3.968, a climbing quarter turn
	 * of radius 4 seen from above at 3.947. The radius limit lets that dip through, or it refuses the very turns it is
	 * meant to allow.
	 */
	private static final double RADIUS_DIP = 0.02;
	/** Points along the curve the slope and the radius are checked at. */
	private static final int SAMPLES = 512;
	/** Room for rounding, so a segment exactly at a limit passes. */
	private static final double ROUNDING = 1e-9;

	static Set<SegmentLimit> brokenBy(Segment segment) {
		if (segment.offset().equals(Vec3i.ZERO)) {
			return EnumSet.of(SAME_CELL);
		}
		Set<SegmentLimit> broken = EnumSet.noneOf(SegmentLimit.class);
		Heading arriving = segment.to().leavingHeading().opposite();
		if (segment.from().leavingHeading().stepsTo(arriving) * 45 > MAX_TURN_DEGREES) {
			broken.add(TURNS_TOO_FAR);
		}
		SegmentCurve curve = segment.curve(BlockPos.ZERO);
		if (curve.length() > MAX_LENGTH + ROUNDING) {
			broken.add(TOO_LONG);
		}
		double maxSlope = Math.tan(Math.toRadians(MAX_SLOPE_DEGREES)) * (1 + ROUNDING);
		double minRadius = MIN_RADIUS * (1 - RADIUS_DIP);
		// Where the curve folds back along a straight line, v × a is zero on both sides of the fold, so the radius
		// reads as infinite. The direction reversing from one sample to the next gives the fold away. At a fold the
		// velocity passes through zero, so the comparison is with the last sample that moved.
		Vec3 moving = curve.velocity(0);
		Vec3 movingHorizontally = moving;
		for (int i = 0; i <= SAMPLES; i++) {
			double t = (double) i / SAMPLES;
			Vec3 velocity = curve.velocity(t);
			Vec3 acceleration = curve.acceleration(t);
			double horizontal = velocity.horizontalDistance();
			// Compared without dividing, so a vertical tangent counts as infinitely steep and tight. The radius of
			// curvature is |v|³ / |v × a|, and seen from above |v_h|³ / |v_x a_z - v_z a_x|.
			if (Math.abs(velocity.y) > maxSlope * horizontal) {
				broken.add(TOO_STEEP);
			}
			double horizontalBend = Math.abs(velocity.x * acceleration.z - velocity.z * acceleration.x);
			if (horizontalBend * minRadius > Math.pow(horizontal, 3)) {
				broken.add(TURN_TOO_TIGHT);
			}
			if (velocity.cross(acceleration).length() * minRadius > Math.pow(velocity.length(), 3)) {
				broken.add(BEND_TOO_SHARP);
			}
			if (velocity.lengthSqr() > 0) {
				if (velocity.dot(moving) < 0) {
					broken.add(BEND_TOO_SHARP);
				}
				moving = velocity;
			}
			if (horizontal > 0) {
				if (velocity.x * movingHorizontally.x + velocity.z * movingHorizontally.z < 0) {
					broken.add(TURN_TOO_TIGHT);
				}
				movingHorizontally = velocity;
			}
		}
		return broken;
	}
}
