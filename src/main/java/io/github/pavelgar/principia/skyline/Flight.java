package io.github.pavelgar.principia.skyline;

import net.minecraft.world.phys.Vec3;

/**
 * A rider's flight as the hook carries them from one rail to another when they jump: from A, the point of the line of
 * the rail they ride, to B, the point of the target's, along p(s) = A + s (B - A) + (0, 4 h s (1 - s), 0), a straight
 * line with a lift in the middle of h = d / 8, d the distance from A to B. The hook pulls them along it at a steady
 * {@link #PULL}: after k of its n = max(2, ⌈d / {@link #PULL}⌉) ticks it is at p(k / n). Nothing changes their velocity
 * on the way, and blocks neither stop it nor keep them from taking off.
 *
 * <p>All are starting values, to be tuned in game.
 */
public final class Flight {
	/** How far the hook pulls a rider along the chord in a tick: 16 blocks a second. */
	private static final double PULL = 0.8;
	/** The fewest ticks a flight takes, so that even the shortest is seen to fly. */
	private static final int FEWEST_TICKS = 2;
	/** How high the path lifts in the middle, per block from A to B: 1 on an 8-block jump. */
	private static final double LIFT = 1 / 8.0;

	private final Vec3 from;
	private final Vec3 to;
	private final Vec3 takeOff;
	private final double lift;
	private final int ticks;

	/** A flight from a point of a rail's line to another, of a rider who took off at a velocity. */
	public Flight(Vec3 from, Vec3 to, Vec3 takeOff) {
		this.from = from;
		this.to = to;
		this.takeOff = takeOff;
		double distance = from.distanceTo(to);
		this.lift = LIFT * distance;
		this.ticks = Math.max(FEWEST_TICKS, (int) Math.ceil(distance / PULL));
	}

	/** How many ticks the flight takes. */
	public int ticks() {
		return ticks;
	}

	/** The velocity the rider took off at, which they land with. */
	public Vec3 takeOff() {
		return takeOff;
	}

	/** Where the hook's point is after so many ticks of the flight. */
	public Vec3 at(int tick) {
		return point((double) tick / ticks);
	}

	/**
	 * The hook's velocity after so many ticks of the flight: its last move, or before its first the velocity it took
	 * off at. A rider who lets go flies on at it.
	 */
	public Vec3 velocity(int tick) {
		return tick == 0 ? takeOff : at(tick).subtract(at(tick - 1));
	}

	/** The path's point a fraction of the way through the flight. */
	private Vec3 point(double s) {
		return from.add(to.subtract(from).scale(s)).add(0, 4 * lift * s * (1 - s), 0);
	}
}
