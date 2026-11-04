package io.github.pavelgar.principia.skyline;

import java.util.function.BiPredicate;

import org.jspecify.annotations.Nullable;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Aiming at a rail. Rails aren't blocks, so what a player aims at is found by testing their look ray against the rails'
 * lines in the track's index: a ray that passes within the aim radius of a rail's line hits it where it passes
 * closest. Attaching a Sky-Hook and breaking rails aim with it, and so will placing them and jumping between them.
 */
public final class RailAim {
	/**
	 * The aim radius while a Sky-Line or a Sky-Hook is held, 2 px: about a 4 × 4 px box around the rail's 2 × 2 px bar.
	 * A starting value, to be tuned in game.
	 */
	public static final double HELD_RADIUS = 2 / 16.0;
	/** The aim radius while anything else is held, 1 px: the rail's own 2 × 2 px bar. */
	public static final double BAR_RADIUS = 1 / 16.0;
	/** The longest step along a rail near the ray: a closer approach between two steps strays from them by little. */
	private static final double FINE_STEP = 1 / 64.0;
	/** Golden-section steps that find the closest approach between two steps: to well under a millionth of a block. */
	private static final int REFINE_STEPS = 40;
	private static final double GOLDEN = (Math.sqrt(5) - 1) / 2;

	private RailAim() {
	}

	/**
	 * A rail a ray hits: the segment as the index holds it, which of its rails, how far along the rail it passes
	 * closest, the rail's point there, and how far that point is from the ray's start.
	 */
	public record Hit(SegmentIndex.Held segment, RailSide rail, double distance, Vec3 point, double reach) {
		/** The hit, named as a client sends it. */
		public RailPoint railPoint() {
			return new RailPoint(segment.anchor(), segment.segment().from().side(), rail, distance);
		}
	}

	/** The rail nearest to a ray's start that the ray, from one point to another, passes within a radius of. */
	public static @Nullable Hit aim(SegmentIndex index, Vec3 from, Vec3 to, double radius) {
		return aim(index, from, to, radius, (segment, rail) -> false);
	}

	/**
	 * The rail nearest to a ray's start that the ray, from one point to another, passes within a radius of, of those
	 * not skipped: a skipped rail, of a segment as the index holds it, hides none behind it.
	 */
	public static @Nullable Hit aim(SegmentIndex index, Vec3 from, Vec3 to, double radius,
			BiPredicate<SegmentIndex.Held, RailSide> skipped) {
		Hit nearest = null;
		for (SegmentIndex.Held segment : index.near(new AABB(from, to).inflate(radius))) {
			for (RailSide side : RailSide.values()) {
				if (skipped.test(segment, side)) {
					continue;
				}
				Hit hit = aim(segment, side, from, to, radius);
				if (hit != null && (nearest == null || hit.reach() < nearest.reach())) {
					nearest = hit;
				}
			}
		}
		return nearest;
	}

	private static @Nullable Hit aim(SegmentIndex.Held segment, RailSide side, Vec3 from, Vec3 to, double radius) {
		Rail rail = segment.curve().rail(side);
		double length = rail.length();
		Hit nearest = null;
		double distance = 0;
		while (distance <= length) {
			double gap = gap(rail.point(distance), from, to);
			if (gap > radius) {
				// A block along the rail moves its point at most a block, so it comes within the radius no sooner than
				// gap - radius further on.
				distance += Math.max(gap - radius, FINE_STEP);
				continue;
			}
			// Within the radius: step through, then find where it passes closest around the closest step.
			double closest = distance;
			double closestGap = gap;
			while (gap <= radius && distance < length) {
				distance = Math.min(distance + FINE_STEP, length);
				gap = gap(rail.point(distance), from, to);
				if (gap < closestGap) {
					closest = distance;
					closestGap = gap;
				}
			}
			double along = closestApproach(rail, from, to, Math.max(0, closest - FINE_STEP),
					Math.min(length, closest + FINE_STEP));
			Vec3 point = rail.point(along);
			Hit hit = new Hit(segment, side, along, point, point.distanceTo(from));
			if (nearest == null || hit.reach() < nearest.reach()) {
				nearest = hit;
			}
			distance += FINE_STEP;
		}
		return nearest;
	}

	/** Where along a stretch of rail its line passes closest to a ray, by golden-section search. */
	private static double closestApproach(Rail rail, Vec3 from, Vec3 to, double low, double high) {
		double a = low;
		double b = high;
		for (int i = 0; i < REFINE_STEPS; i++) {
			double c = b - GOLDEN * (b - a);
			double d = a + GOLDEN * (b - a);
			if (gap(rail.point(c), from, to) < gap(rail.point(d), from, to)) {
				b = d;
			} else {
				a = c;
			}
		}
		return (a + b) / 2;
	}

	/** The distance from a point to the ray from one point to another. */
	private static double gap(Vec3 point, Vec3 from, Vec3 to) {
		Vec3 ray = to.subtract(from);
		double length = ray.lengthSqr();
		double along = length == 0 ? 0 : Mth.clamp(point.subtract(from).dot(ray) / length, 0, 1);
		return from.add(ray.scale(along)).distanceTo(point);
	}
}
