package io.github.pavelgar.principia.skyline;

import java.util.Arrays;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The centre line of a Sky-Line segment: a cubic Bézier curve from one anchor's point, leaving along that anchor's
 * tangent, to the other's, arriving along its tangent. Positions along it are given as distances from its start.
 */
public final class SegmentCurve {
	/**
	 * How far below the top of its cell an anchor's point is. The rails' tops are 1 px above the centre line, so they
	 * end flush with the top of the cell row.
	 */
	static final double ANCHOR_DEPTH = 1 / 16.0;

	private final Vec3 start;
	private final Vec3 end;
	// The curve as a polynomial relative to its start, point(t) = start + t (a + t (b + t c)). Relative, so that a
	// coordinate every control point shares (a straight line along an axis) comes out exact.
	private final double ax, ay, az, bx, by, bz, cx, cy, cz;
	// The heading the curve starts in, for the rails' sides where the curve runs straight up or down (only a curve
	// that breaks the slope limit does).
	private final double startHeadingX, startHeadingZ;
	private final ArcLengthTable table;
	private final Rail left;
	private final Rail right;

	/**
	 * @param leaving the unit vector the curve leaves {@code start} along
	 * @param endLeaving the unit vector the curve leaves {@code end} along, were it run backwards
	 */
	SegmentCurve(Vec3 start, Vec3 leaving, Vec3 end, Vec3 endLeaving) {
		this.start = start;
		this.end = end;
		Vec3 chord = end.subtract(start);
		// θ is the angle between the directions of travel at the two ends; at the end that's -endLeaving. A handle
		// length of h = (2/3) c tan(θ/4) / sin(θ/2) for a chord c makes a symmetric turn follow a circular arc to
		// within 0.03 % of its radius. Rewritten as 2c / (3 (1 + cos(θ/2))), it is c/3 at θ = 0 (a straight line,
		// evenly parametrised) without a special case.
		double cosTheta = Mth.clamp(-leaving.dot(endLeaving), -1, 1);
		double cosHalfTheta = Math.sqrt((1 + cosTheta) / 2);
		double handle = 2 * chord.length() / (3 * (1 + cosHalfTheta));
		Vec3 p1 = leaving.scale(handle);
		Vec3 p2 = chord.add(endLeaving.scale(handle));
		ax = 3 * p1.x;
		ay = 3 * p1.y;
		az = 3 * p1.z;
		bx = 3 * (p2.x - 2 * p1.x);
		by = 3 * (p2.y - 2 * p1.y);
		bz = 3 * (p2.z - 2 * p1.z);
		cx = chord.x + 3 * (p1.x - p2.x);
		cy = chord.y + 3 * (p1.y - p2.y);
		cz = chord.z + 3 * (p1.z - p2.z);
		double horizontal = Math.sqrt(leaving.x * leaving.x + leaving.z * leaving.z);
		startHeadingX = leaving.x / horizontal;
		startHeadingZ = leaving.z / horizontal;
		table = new ArcLengthTable(t -> velocity(t).length());
		left = new Rail(this, RailSide.LEFT);
		right = new Rail(this, RailSide.RIGHT);
	}

	/** The point the centre line passes through at an anchor: its cell's horizontal centre, 1 px below its top. */
	public static Vec3 anchorPoint(BlockPos anchor) {
		return new Vec3(anchor.getX() + 0.5, anchor.getY() + 1 - ANCHOR_DEPTH, anchor.getZ() + 0.5);
	}

	/** The length of the centre line. */
	public double length() {
		return table.length();
	}

	/** The point at a distance along the centre line, clamped to its ends. */
	public Vec3 point(double distance) {
		return pointAt(table.parameterAt(distance));
	}

	/** The unit vector the centre line runs along at a distance along it. */
	public Vec3 direction(double distance) {
		return velocity(table.parameterAt(distance)).normalize();
	}

	public Rail rail(RailSide side) {
		return side == RailSide.LEFT ? left : right;
	}

	Vec3 pointAt(double t) {
		if (t >= 1) {
			return end; // exactly, which the polynomial is only to within rounding
		}
		return new Vec3(start.x + t * (ax + t * (bx + t * cx)), start.y + t * (ay + t * (by + t * cy)),
				start.z + t * (az + t * (bz + t * cz)));
	}

	/** The derivative of the curve by its parameter t. */
	Vec3 velocity(double t) {
		return new Vec3(ax + t * (2 * bx + 3 * t * cx), ay + t * (2 * by + 3 * t * cy),
				az + t * (2 * bz + 3 * t * cz));
	}

	/** The second derivative of the curve by its parameter t. */
	Vec3 acceleration(double t) {
		return new Vec3(2 * bx + 6 * t * cx, 2 * by + 6 * t * cy, 2 * bz + 6 * t * cz);
	}

	/**
	 * A box around both rails' bars: the box of the centre line, from its ends and the points where it turns back along
	 * an axis, widened by a rail's offset and half its bar to the sides, and by half the bar up and down. The rails are
	 * level with the centre line, so it is exact up and down; to the sides, exact across a rail that runs along an axis,
	 * and elsewhere at most a rail's reach, 17/16 blocks, wider. Cheap: no samples along the curve.
	 */
	public AABB railBounds() {
		double minX = Math.min(start.x, end.x);
		double minY = Math.min(start.y, end.y);
		double minZ = Math.min(start.z, end.z);
		double maxX = Math.max(start.x, end.x);
		double maxY = Math.max(start.y, end.y);
		double maxZ = Math.max(start.z, end.z);
		for (double[] turns : List.of(stationaryParameters(ax, bx, cx), levelParameters(),
				stationaryParameters(az, bz, cz))) {
			for (double t : turns) {
				Vec3 point = pointAt(t);
				minX = Math.min(minX, point.x);
				minY = Math.min(minY, point.y);
				minZ = Math.min(minZ, point.z);
				maxX = Math.max(maxX, point.x);
				maxY = Math.max(maxY, point.y);
				maxZ = Math.max(maxZ, point.z);
			}
		}
		double side = RailSide.RIGHT.offset + Rail.HALF_BAR;
		double half = Rail.HALF_BAR;
		return new AABB(minX - side, minY - half, minZ - side, maxX + side, maxY + half, maxZ + side);
	}

	/** The parameters t strictly between 0 and 1 where the curve is level (a crest or a dip): none, one or two. */
	double[] levelParameters() {
		return stationaryParameters(ay, by, cy);
	}

	/**
	 * The parameters t strictly between 0 and 1 where one coordinate of the curve, start + t (a + t (b + t c)), stops
	 * and turns back: none, one or two.
	 */
	private static double[] stationaryParameters(double linear, double quadratic, double cubic) {
		// Roots of the coordinate's derivative, linear + 2 quadratic t + 3 cubic t².
		double a = 3 * cubic;
		double b = 2 * quadratic;
		double c = linear;
		double discriminant = b * b - 4 * a * c;
		if (discriminant < 0) {
			return new double[0];
		}
		// (-b ± √D) / 2a loses the root near -c/b to cancellation where 4ac is small beside b², as on a nearly
		// symmetric curve, by enough to move a turning point the rails' box is built from. q = -(b + sign(b) √D) / 2
		// cancels nothing, and the roots are q / a and c / q; with a = 0 the first is infinite and the second the
		// linear root. q is 0 only where b and c are both 0, with no turning point strictly inside.
		double q = -(b + Math.copySign(Math.sqrt(discriminant), b)) / 2;
		if (q == 0) {
			return new double[0];
		}
		return Arrays.stream(new double[]{q / a, c / q}).filter(t -> t > 0 && t < 1).toArray();
	}

	/**
	 * How sharply the curve turns seen from above at parameter t: the reciprocal of its radius there, positive turning
	 * right, 0 where it runs straight or straight up or down.
	 */
	double horizontalCurvature(double t) {
		Vec3 velocity = velocity(t);
		double horizontal = velocity.horizontalDistance();
		if (horizontal < 1e-9) {
			return 0;
		}
		Vec3 acceleration = acceleration(t);
		return (velocity.x * acceleration.z - velocity.z * acceleration.x) / (horizontal * horizontal * horizontal);
	}

	/** The horizontal unit vector square to the right of the curve's heading at parameter t. */
	Vec3 rightAt(double t) {
		Vec3 velocity = velocity(t);
		double horizontal = velocity.horizontalDistance();
		if (horizontal < 1e-9) {
			return new Vec3(-startHeadingZ, 0, startHeadingX);
		}
		return new Vec3(-velocity.z / horizontal, 0, velocity.x / horizontal);
	}

	/** The derivative of {@link #rightAt} by t: how fast the rails swing round as the heading turns. */
	Vec3 rightVelocity(double t) {
		Vec3 velocity = velocity(t);
		double horizontal = velocity.horizontalDistance();
		if (horizontal < 1e-9) {
			return Vec3.ZERO;
		}
		// The heading u = (x', z') / |(x', z')| turns at u' = (q - u (u · q)) / |(x', z')|, q = (x'', z'').
		Vec3 acceleration = acceleration(t);
		double ux = velocity.x / horizontal;
		double uz = velocity.z / horizontal;
		double along = ux * acceleration.x + uz * acceleration.z;
		double turnX = (acceleration.x - ux * along) / horizontal;
		double turnZ = (acceleration.z - uz * along) / horizontal;
		return new Vec3(-turnZ, 0, turnX);
	}
}
