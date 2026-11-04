package io.github.pavelgar.principia.skyline;

import net.minecraft.world.phys.Vec3;

/**
 * One rail of a segment: the line the middle of its bar runs along, the centre line offset one block to the side,
 * horizontally and square to the centre line's heading. There is no banking, so both rails are always level with
 * each other. Each rail is its own lane, with its own length, and positions along it are distances along it.
 */
public final class Rail {
	/** Half the width and height of a rail's bar, which is 2 px square: 1 px. */
	public static final double HALF_BAR = 1 / 16.0;

	private final SegmentCurve centre;
	private final double offset;
	private final ArcLengthTable table;

	Rail(SegmentCurve centre, RailSide side) {
		this.centre = centre;
		this.offset = side.offset;
		this.table = new ArcLengthTable(t -> velocityAt(t).length());
	}

	public double length() {
		return table.length();
	}

	/** The point at a distance along the rail, clamped to its ends. */
	public Vec3 point(double distance) {
		return pointAt(table.parameterAt(distance));
	}

	/**
	 * The unit vector the rail runs along at a distance along it, from its start towards its end, clamped to its ends.
	 */
	public Vec3 direction(double distance) {
		return velocityAt(table.parameterAt(distance)).normalize();
	}

	/**
	 * How sharply the rail turns seen from above at a distance along it: the reciprocal of its horizontal radius there,
	 * 0 where it runs straight. The rail is the centre line offset sideways, so seen from above it runs round the same
	 * centre, its radius the centre line's less its offset towards the inside of the turn.
	 */
	public double horizontalCurvature(double distance) {
		double centre = this.centre.horizontalCurvature(table.parameterAt(distance));
		return Math.abs(centre / (1 - offset * centre));
	}

	/**
	 * The horizontal unit vector square to the right of the rail at a distance along it, clamped to its ends: the way
	 * the right side of its bar faces, as the bar has horizontal and vertical sides.
	 */
	public Vec3 side(double distance) {
		return centre.rightAt(table.parameterAt(distance));
	}

	/** The parameter t of the curve at a distance along this rail, clamped to its ends. */
	double parameterAt(double distance) {
		return table.parameterAt(distance);
	}

	SegmentCurve centre() {
		return centre;
	}

	/** The rail's point beside the centre line's point at parameter t. */
	Vec3 pointAt(double t) {
		return centre.pointAt(t).add(centre.rightAt(t).scale(offset));
	}

	Vec3 velocityAt(double t) {
		return centre.velocity(t).add(centre.rightVelocity(t).scale(offset));
	}
}
