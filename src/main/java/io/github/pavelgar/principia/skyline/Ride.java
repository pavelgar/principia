package io.github.pavelgar.principia.skyline;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BiPredicate;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/**
 * A rider's place on the track and the law they move along it by, tick by tick: what a Sky-Hook does on a rail. The
 * rider's keys are a throttle and a brake along the rail, as on a coaster with a motor; with none they coast, drag and
 * rolling resistance slowly taking their speed while gravity speeds them downhill and slows them uphill, and static
 * friction holds them at rest on slopes up to 4°. The hook
 * brakes ahead of turns and ends so the rider enters a turn at the speed it allows and
 * stops exactly at an end, braking for them no harder than a rider's brake, and goes on across anchors onto the next
 * segment, on the rail on the same side.
 *
 * <p>One tick, in order: drag takes {@link #DRAG}; gravity pulls along the rail by {@link #GRAVITY} times the fall of
 * the rail per block along it; rolling resistance takes {@link #ROLLING_RESISTANCE}, never past rest; static friction
 * holds a rider at rest, or coming to rest, where gravity pulls by no more than {@link #STATIC_FRICTION}; the keys push,
 * brake or hold; the limits cap the speed at {@link #MAX_SPEED} and brake ahead of turns and ends;
 * and the rider moves, stopping short of a block in their body's way or at an end. Such a stop can lose the rider far
 * more speed in a tick than braking does, which hurts: see {@link #impactDamage}.
 *
 * <p>The keys pick a way along the rail when they change: the way the move intent (the keys turned by the player's
 * yaw) points along the rail. While they stay the same, the way stays, whatever the look does; a press that picks no
 * way (straight across the rail, or W and S together) picks once the look gives one. S picking the way against the
 * rider's travel is a holding brake: it stops them at {@link #BRAKE} a tick and holds them at rest while the keys stay
 * the same. Every other press is a throttle: against the travel it brakes at {@link #BRAKE} a tick through the stop and
 * pushes on the other way; along it, or from rest, it pushes at {@link #PUSH} a tick up to the motor's top speed,
 * {@link #TOP_SPEED} on the level and downhill and less on a climb, and does nothing above it. Sprinting on the
 * throttle, with the food to sprint, boosts the top speed to {@link #BOOST_SPEED}.
 *
 * <p>The place is kept on the segment as the track's index holds it, from its anchor first in {@code BlockPos} order,
 * so every tick uses the curve the index built. The speed and the way the keys picked are signed along that curve:
 * positive towards its end.
 */
public final class Ride {
	/** Gravity, 9.8 m/s² with a block a metre, in blocks per tick². */
	static final double GRAVITY = 0.0245;
	/** What a tick of drag leaves of the speed: vanilla's ridden minecart's, 6 % a second. */
	static final double DRAG = 0.997;
	/**
	 * How much rolling resistance takes off the speed in a tick, in blocks per tick², never past rest: a rider coasting
	 * on the level stops, about 23 s from 10 blocks/s. Gravity beats it on slopes over 1.17°, so a rider rolls on down
	 * slopes gentler than {@link #STATIC_FRICTION} would have started them on.
	 */
	static final double ROLLING_RESISTANCE = 0.0005;
	/**
	 * The most gravity's pull along the rail that static friction withstands, in blocks per tick²: its pull on a 4°
	 * slope, 0.0245 · sin 4° = 0.0017. A rider at rest, or coming to rest, stays so on slopes up to 4°.
	 */
	static final double STATIC_FRICTION = GRAVITY * Math.sin(Math.toRadians(4));
	/** How much the motor adds to the speed in a tick, in blocks per tick²: 10 blocks/s from rest in half a second. */
	static final double PUSH = 0.05;
	/**
	 * How much braking takes off the speed in a tick, the keys' brake and the hook's alike, in blocks per tick². It
	 * beats gravity on the steepest slope the limits allow, 0.0245 · sin 45° = 0.0173, so a rider can stop and hold
	 * anywhere.
	 */
	static final double BRAKE = 0.05;
	/**
	 * The motor's top speed on the level and downhill, 10 blocks per second. On a climb it falls as on a chain lift, by
	 * half at 45°, the steepest the limits allow, where the motor still beats gravity: see {@link #climbing}.
	 */
	static final double TOP_SPEED = 0.5;
	/** The motor's top speed boosted by sprinting, 15 blocks per second, and as much less on a climb. */
	static final double BOOST_SPEED = 0.75;
	/** The most a rider ever goes, 20 blocks per second: the hook never moves more than a block a tick. */
	static final double MAX_SPEED = 1;
	/**
	 * The most sideways acceleration on a rail seen from above, v² / r with r the rail's horizontal radius, in blocks
	 * per tick²: 20 blocks/s², which slows a rider only on the inner rail of the tightest turns. To be tuned in game.
	 */
	static final double MAX_SIDEWAYS_ACCELERATION = 0.05;
	/**
	 * The least braking slows a rider by in a tick: against gravity on a rail straight down, steeper than any the
	 * limits allow. It bounds how far ahead turns and ends can matter.
	 */
	private static final double LEAST_BRAKING = BRAKE - GRAVITY;
	/**
	 * The step the rail ahead is sampled at for turns and slopes, from the start of each segment's curve, so every tick
	 * plans against the same points. A turn's radius changes little over it.
	 */
	private static final double SAMPLE = 1 / 8.0;
	/** A move intent with less than this along the rail is straight across it. */
	private static final double ACROSS = 1e-3;
	/** A move that ends this close to an anchor ends at it, so a rider braking for an end stops exactly there. */
	private static final double SNAP = 1e-9;
	/** The most speed a rider loses to a stop unhurt, 6 blocks a second: an elytra crash's. */
	private static final double HARMLESS_IMPACT = 0.3;
	/** Halvings that find the point just short of a block in the rider's way: to well under a thousandth of a block. */
	private static final int OBSTACLE_STEPS = 12;

	private Place place;
	private double speed;
	/** The movement keys held last tick, to tell when they change. */
	private Input held = Input.EMPTY;
	/** The way along the curve the keys picked: 1, -1, or 0 for none yet. */
	private int picked;
	/** Whether the keys are a holding brake rather than a throttle. */
	private boolean holding;

	private Ride(Place place) {
		this.place = place;
	}

	/** A rider attaching to a rail at a point, at rest. Null if the index holds no such segment. */
	public static @Nullable Ride attach(SegmentIndex index, RailPoint point) {
		Walk at = Walk.of(index, point);
		return at == null ? null : new Ride(at.place());
	}

	/** A ride resumed, at rest, where {@link #point()} left it. Null if the index holds no such segment. */
	public static @Nullable Ride resume(SegmentIndex index, RailPoint point) {
		return attach(index, point);
	}

	/**
	 * A rider just attached here landing from a jump at a velocity: they ride on at its part along the rail, the way it
	 * points along it, limited as each tick's speed is to the cap and to what lets the hook brake for the turns and
	 * ends ahead. The cut is no crash, whatever its size: the rider couldn't see those turns from below the rail. As
	 * the ride is new, its first tick takes the keys held as a fresh press.
	 */
	public void land(SegmentIndex index, Vec3 velocity) {
		double along = velocity.dot(place.tangent());
		int direction = (int) Math.signum(along);
		speed = direction == 0
				? 0
				: direction * Math.min(Math.abs(along), limit(index, new Walk(place, direction), Math.abs(along)));
	}

	/**
	 * A rider's own line from their point: their rail on their segment, and on each segment it reaches within a
	 * distance along the track either way, across anchors as riding crosses them, on the same side seen along the
	 * travel. Each such rail, of a segment as the index holds it, is on the line whole. A rider never jumps to it: going
	 * along their own rail is the motor's job. Empty if the index holds no such segment.
	 */
	public static BiPredicate<SegmentIndex.Held, RailSide> line(SegmentIndex index, RailPoint point, double within) {
		Walk at = Walk.of(index, point);
		Set<HeldRail> rails = new HashSet<>();
		if (at != null) {
			for (int direction : new int[]{1, -1}) {
				Walk walk = new Walk(at.place(), direction);
				double left = within;
				while (true) {
					rails.add(new HeldRail(walk.place().segment(), walk.place().rail()));
					double room = walk.direction() > 0
							? walk.place().line().length() - walk.place().distance()
							: walk.place().distance();
					Walk across = room < left ? walk.across(index) : null;
					if (across == null) {
						break;
					}
					left -= room;
					walk = across;
				}
			}
		}
		return (segment, rail) -> rails.contains(new HeldRail(segment, rail));
	}

	/** Where the rider is: the anchor first in {@code BlockPos} order of their segment names it. */
	public RailPoint point() {
		SegmentIndex.Held segment = place.segment();
		return new RailPoint(segment.anchor(), segment.segment().from().side(), place.rail(), place.distance());
	}

	/** The point of the rail's line the rider hangs from. */
	public Vec3 position() {
		return place.point();
	}

	/** How fast the rider goes along the rail, in blocks per tick. */
	public double speed() {
		return Math.abs(speed);
	}

	/** The rider's velocity, in blocks per tick: their speed along the rail's direction of travel. */
	public Vec3 velocity() {
		return place.tangent().scale(speed);
	}

	/** Whether the rider is on one of some rails, of segments as the index holds them: on a {@link #line}, say. */
	public boolean isOn(BiPredicate<SegmentIndex.Held, RailSide> rails) {
		return rails.test(place.segment(), place.rail());
	}

	/** Whether the index still holds the rider's segment as it was. */
	public boolean onTrack(SegmentIndex index) {
		SegmentIndex.Held segment = place.segment();
		return segment.equals(index.at(segment.anchor(), segment.segment().from().side()));
	}

	/**
	 * One tick of riding, by the law in this class's javadoc: the keys held change the speed, within the limits of the
	 * rail ahead, and the rider moves on, stopping at once short of a block in their way.
	 */
	public Motion tick(SegmentIndex index, Controls controls, Body body) {
		double before = Math.abs(speed);
		pick(controls);
		Vec3 tangent = place.tangent();
		speed *= DRAG;
		double pull = -GRAVITY * tangent.y;
		double rolled = towardsRest(speed + pull, ROLLING_RESISTANCE);
		// At rest, or coming to rest this tick, static friction holds the rider where gravity pulls by no more than it.
		speed = rolled * speed <= 0 && Math.abs(pull) <= STATIC_FRICTION ? 0 : rolled;
		double coasting = speed;
		boolean boosting = picked != 0 && !holding && controls.keys().sprint() && controls.canSprint();
		if (holding) {
			speed = towardsRest(speed, BRAKE);
		} else if (picked != 0) {
			double top = (boosting ? BOOST_SPEED : TOP_SPEED) * climbing(picked * tangent.y);
			speed += picked * Mth.clamp(top - picked * speed, 0, PUSH);
		}
		speed = Mth.clamp(speed, -MAX_SPEED, MAX_SPEED);
		int direction = (int) Math.signum(speed);
		if (direction == 0) {
			return new Motion(0, before, boosting);
		}
		Walk from = new Walk(place, direction);
		double wanted = Math.abs(speed);
		// The hook brakes no harder than its brake, below what the rider would coast at: it cuts the motor first.
		double limited = Math.min(wanted, Math.max(limit(index, from, wanted), direction * coasting - BRAKE));
		Step step = from.forward(index, limited);
		if (body.blockedAt(step.walk().place().point()) && !body.blockedAt(place.point())) {
			// Stopped as by a wall: at the furthest point of the move the body still has room at.
			double free = 0;
			double blocked = step.moved();
			for (int i = 0; i < OBSTACLE_STEPS; i++) {
				double middle = (free + blocked) / 2;
				if (body.blockedAt(from.forward(index, middle).walk().place().point())) {
					blocked = middle;
				} else {
					free = middle;
				}
			}
			moveTo(from.forward(index, free), direction);
			speed = 0;
			return new Motion(free, before, boosting);
		}
		moveTo(step, direction);
		if (step.ended()) {
			speed = 0;
		}
		return new Motion(step.moved(), Math.max(0, before - Math.abs(speed)), boosting);
	}

	/**
	 * The damage a rider takes for the speed a tick lost them, as an elytra crash deals it: 10 per block a tick, less 3,
	 * so 1 heart at 10 blocks a second and 3.5 at 20. None up to {@link #HARMLESS_IMPACT}, which no braking loses.
	 */
	public static float impactDamage(double speedLost) {
		return speedLost > HARMLESS_IMPACT ? (float) (10 * speedLost - 3) : 0;
	}

	/**
	 * The latch: when the movement keys change, they pick the way again, and until a press picks one it picks as soon
	 * as the move intent gives one. A press picks whether it holds or drives as it picks, by the travel then.
	 */
	private void pick(Controls controls) {
		Input keys = controls.keys();
		Input movement = new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), false, false, false);
		if (!movement.equals(held)) {
			held = movement;
			picked = 0;
			holding = false;
		}
		if (picked == 0 && !movement.equals(Input.EMPTY)) {
			picked = pickedDirection(controls.moveIntent(), place.tangent());
			holding = picked != 0 && movement.backward() && !movement.forward() && picked * speed < 0;
		}
	}

	/**
	 * What is left of the motor's top speed driving a way along the rail that rises by so much per block along it: all
	 * of it on the level and downhill, and on a climb less by half the rise over sin 45°, so half at 45°.
	 */
	private static double climbing(double rise) {
		return rise > 0 ? 1 - rise / (2 * Math.sin(Math.PI / 4)) : 1;
	}

	/** A speed less an amount, but no further than rest. */
	private static double towardsRest(double speed, double amount) {
		return Math.signum(speed) * Math.max(0, Math.abs(speed) - amount);
	}

	private void moveTo(Step step, int direction) {
		Walk walk = step.walk();
		place = walk.place();
		speed = walk.direction() * step.moved();
		// The way the keys picked stays the same seen along the travel, across a segment held the other way round.
		if (picked != 0) {
			picked = picked == direction ? walk.direction() : -walk.direction();
		}
	}

	/**
	 * The fastest the rider may go this tick heading one way: no faster than lets them, braking at {@link #BRAKE} per
	 * tick, be within each turn ahead no faster than it allows, and stop exactly at an end. Gravity helps or fights the
	 * brake on the way: for each point ahead it is taken at its strongest pull along the travel anywhere before it,
	 * which on a slope of one steepness is exact, and drag and rolling resistance, which only help, are left out. It
	 * looks ahead until nothing further could slow the rider below what they want and the limit so far: not even an
	 * end, braking against gravity straight down.
	 *
	 * <p>The rail between two points it is sampled at is taken to turn as sharply as the sharper of them, so each
	 * point's turn speed holds from the point before it, and from where the rider is for the first point ahead. Where a
	 * turn begins at an anchor, the rail's radius changes there all at once; held only from that point on, the turn
	 * would let a tick up to a brake's worth faster than it end on the point itself, leaving the rider on the turn
	 * faster than it allows.
	 */
	private static double limit(SegmentIndex index, Walk from, double wanted) {
		double limit = MAX_SPEED;
		double pull = Double.NEGATIVE_INFINITY;
		Walk walk = from;
		double ahead = 0;
		// How far ahead the point before this one is, where this one's turn speed holds from.
		double before = 0;
		while (true) {
			pull = Math.max(pull, walk.pull());
			limit = Math.min(limit, fastestSlowingTo(turnSpeed(walk.place().curvature()), before, BRAKE - pull));
			if (fastestSlowingTo(0, ahead, LEAST_BRAKING) >= Math.min(wanted, limit)) {
				return limit;
			}
			Step step = walk.forward(index, walk.toNextSample());
			before = ahead;
			ahead += step.moved();
			walk = step.walk();
			if (step.ended()) {
				pull = Math.max(pull, walk.pull());
				return Math.min(limit, fastestSlowingTo(0, ahead, BRAKE - pull));
			}
		}
	}

	/**
	 * The fastest a rider may go on a rail that turns this sharply seen from above, v² κ ≤ the most sideways: infinite
	 * where it runs straight. {@link #limit} keeps it to the cap.
	 */
	static double turnSpeed(double curvature) {
		return Math.sqrt(MAX_SIDEWAYS_ACCELERATION / curvature);
	}

	/**
	 * The fastest a rider may go now so that, slowing by a each tick, every tick they are still faster than a speed
	 * ends at most a distance further on. The rider moves by each tick's new speed: from v, the ticks faster than the
	 * target s are the first k, k = ⌈(v - s) / a⌉, and cover k v - a k (k - 1) / 2. Each tick it is taken, slowing by a
	 * keeps within the next tick's, so braking by it is smooth.
	 *
	 * <p>To rest (s = 0), the ticks cover exactly the distance: a rider braking by it stops exactly there.
	 */
	static double fastestSlowingTo(double speed, double within, double slowing) {
		double fastest = speed;
		for (int ticks = 1; fastest < MAX_SPEED; ticks++) {
			double fastestForTicks = Math.min(speed + ticks * slowing,
					(within + slowing * ticks * (ticks - 1) / 2) / ticks);
			if (!(fastestForTicks > speed + (ticks - 1) * slowing)) {
				break;
			}
			fastest = fastestForTicks;
		}
		return fastest;
	}

	/**
	 * The way along a rail, 1 or -1, that a move intent picks: the way its horizontal part along the rail's tangent
	 * points. 0 for no input, or input straight across the rail.
	 */
	private static int pickedDirection(Vec3 moveIntent, Vec3 tangent) {
		double along = moveIntent.x * tangent.x + moveIntent.z * tangent.z;
		if (Math.abs(along) < ACROSS) {
			return 0;
		}
		return along > 0 ? 1 : -1;
	}

	/**
	 * What came of a tick of riding: how far the rider moved along the rail, the speed they lost, from the start of the
	 * tick to its end as an elytra crash counts it, in blocks per tick, and whether they were boosting.
	 */
	public record Motion(double moved, double lost, boolean boosting) {
	}

	/**
	 * What a rider does in a tick: the keys they hold, as {@code ServerPlayer.getLastClientInput()} gives them, their
	 * move intent, those keys turned by the player's yaw, as {@code ServerPlayer.getLastClientMoveIntent()} gives it,
	 * and whether they have the food to sprint, as vanilla requires of sprinting.
	 */
	public record Controls(Input keys, Vec3 moveIntent, boolean canSprint) {
	}

	/** Whether a rider's body hanging from a point of a rail's line would be in a block. */
	@FunctionalInterface
	public interface Body {
		boolean blockedAt(Vec3 railPoint);
	}

	/** One rail of a segment as the index holds it. */
	private record HeldRail(SegmentIndex.Held segment, RailSide rail) {
	}

	/** A point on one rail of a segment as the index holds it. */
	private record Place(SegmentIndex.Held segment, RailSide rail, double distance) {
		Rail line() {
			return segment.curve().rail(rail);
		}

		Vec3 point() {
			return line().point(distance);
		}

		/** The unit vector the rail runs along here, towards the end of the segment's curve. */
		Vec3 tangent() {
			return line().direction(distance);
		}

		double curvature() {
			return line().horizontalCurvature(distance);
		}
	}

	/** A place and the way along its segment's curve a rider goes from it, 1 towards its end or -1. */
	private record Walk(Place place, int direction) {
		/**
		 * A rail point as a place on the segment as the index holds it, heading away from the point's anchor. Null if
		 * the index holds no such segment.
		 */
		static @Nullable Walk of(SegmentIndex index, RailPoint point) {
			SegmentIndex.Held segment = index.at(point.anchor(), point.side());
			if (segment == null) {
				return null;
			}
			if (segment.anchor().equals(point.anchor())) {
				return new Walk(new Place(segment, point.rail(), clamped(segment, point.rail(), point.distance())), 1);
			}
			// Seen from the other anchor the rails swap sides, and distances run from the other end.
			RailSide rail = point.rail().opposite();
			double length = segment.curve().rail(rail).length();
			return new Walk(new Place(segment, rail, clamped(segment, rail, length - point.distance())), -1);
		}

		private static double clamped(SegmentIndex.Held segment, RailSide rail, double distance) {
			return Mth.clamp(distance, 0, segment.curve().rail(rail).length());
		}

		/** Further along by a distance, across anchors onto the segments beyond, as far as an end. */
		Step forward(SegmentIndex index, double distance) {
			Walk walk = this;
			double left = distance;
			while (true) {
				Place at = walk.place();
				double length = at.line().length();
				double room = walk.direction() > 0 ? length - at.distance() : at.distance();
				if (left < room - SNAP) {
					return new Step(walk.at(at.distance() + walk.direction() * left), distance, false);
				}
				Walk across = walk.across(index);
				if (across == null) {
					return new Step(walk.at(walk.direction() > 0 ? length : 0), distance - Math.max(0, left - room),
							true);
				}
				left = Math.max(0, left - room);
				walk = across;
			}
		}

		private Walk at(double distance) {
			return new Walk(new Place(place.segment(), place.rail(), distance), direction);
		}

		/**
		 * How far on the next point is that the rail is sampled at: the next multiple of {@link #SAMPLE} along the
		 * segment's curve the way the walk goes, or its end. Points within a millionth of a step count as passed, so
		 * each step gets on.
		 */
		double toNextSample() {
			double steps = direction * place.distance() / SAMPLE;
			double next = direction * (Math.floor(steps + 1e-6) + 1) * SAMPLE;
			return Math.abs(Mth.clamp(next, 0, place.line().length()) - place.distance());
		}

		/** How much gravity adds in a tick to the speed of a rider going this way from here. */
		double pull() {
			return -GRAVITY * direction * place.tangent().y;
		}

		/**
		 * At the anchor the walk heads to, onto the segment on the anchor's other side, if there is one: on its rail on
		 * the same side seen along the travel, where the two rails meet.
		 */
		private @Nullable Walk across(SegmentIndex index) {
			SegmentIndex.Held held = place.segment();
			BlockPos anchor = direction > 0 ? held.anchor().offset(held.segment().offset()) : held.anchor();
			AnchorSide arrivedOn = direction > 0 ? held.segment().to().side() : held.segment().from().side();
			SegmentIndex.Held next = index.at(anchor, arrivedOn.opposite());
			if (next == null) {
				return null;
			}
			int nextDirection = next.anchor().equals(anchor) ? 1 : -1;
			RailSide travelSide = direction > 0 ? place.rail() : place.rail().opposite();
			RailSide rail = nextDirection > 0 ? travelSide : travelSide.opposite();
			double start = nextDirection > 0 ? 0 : next.curve().rail(rail).length();
			return new Walk(new Place(next, rail, start), nextDirection);
		}
	}

	/**
	 * Where a walk got to, how far it went, and whether it stopped at an end: an anchor with no segment on its far side.
	 */
	private record Step(Walk walk, double moved, boolean ended) {
	}
}
