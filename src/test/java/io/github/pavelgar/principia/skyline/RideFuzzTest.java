package io.github.pavelgar.principia.skyline;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.DoubleInRange;
import com.code_intelligence.jazzer.mutation.annotation.InRange;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import com.code_intelligence.jazzer.mutation.annotation.WithSize;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/**
 * What holds for every ride, fuzzed: placeable track of one to three segments (see {@link FuzzedTrack}), a rider
 * attached anywhere on it at rest, pressing keys while looking ways, with nothing in their way.
 */
class RideFuzzTest {
	private static final BlockPos START = new BlockPos(0, 64, 0);

	enum Keys {
		NONE,
		W,
		S,
		W_AND_S,
		A,
		W_AND_D,
		W_SPRINTING;

		Input input() {
			return switch (this) {
				case NONE -> Input.EMPTY;
				case W -> keys(true, false, false, false);
				case S -> keys(false, true, false, false);
				case W_AND_S -> keys(true, true, false, false);
				case A -> keys(false, false, true, false);
				case W_AND_D -> keys(true, false, false, true);
				case W_SPRINTING -> new Input(true, false, false, false, false, false, true);
			};
		}
	}

	/** Keys held for some ticks while looking a way: a yaw in degrees, south 0° and west 90°, as an entity's. */
	record Press(@NotNull Keys keys, @DoubleInRange(min = 0, max = 360, allowNaN = false) double yaw,
			@InRange(min = 1, max = 40) int ticks) {
	}

	/**
	 * Tick by tick, whatever the keys: the rider stays on the track and between its ends, never goes faster than
	 * {@link Ride#MAX_SPEED}, moves no further in a tick than that, and, as the hook brakes ahead of turns, takes them
	 * within {@link Ride#MAX_SIDEWAYS_ACCELERATION}. That last is to within the 10 % the hook's planning on samples an
	 * eighth of a block apart can miss a turn's tightest point by, as in {@code RideTest}.
	 */
	@FuzzTest(maxDuration = "1m")
	void aRiderKeepsToTheRailTheSpeedCapAndTheTurns(@NotNull FuzzedTrack.End from,
			@NotNull @WithSize(min = 1, max = 3) List<FuzzedTrack.@NotNull Shape> shapes, @NotNull RailSide rail,
			@DoubleInRange(min = 0, max = 1, allowNaN = false) double along,
			@NotNull @WithSize(min = 1, max = 8) List<@NotNull Press> presses) {
		List<Segment> track = FuzzedTrack.placeableChain(from, shapes);
		if (track == null) {
			return;
		}
		SegmentIndex index = FuzzedTrack.lay(START, track);
		Segment first = track.getFirst();
		double length = first.curve(START).rail(rail).length();
		Ride ride = Ride.attach(index, new RailPoint(START, first.from().side(), rail, along * length));
		assertNotNull(ride, "no rail to attach to");
		FuzzedTrack.checking(track + ", " + rail + " rail at " + along + ", " + presses,
				() -> ride(index, ride, presses));
	}

	/** Rides by the presses, checking the ride after each tick. */
	private static void ride(SegmentIndex index, Ride ride, List<Press> presses) {
		int tick = 0;
		for (Press press : presses) {
			double radians = Math.toRadians(press.yaw());
			Ride.Controls controls = controls(press.keys().input(), new Vec3(-Math.sin(radians), 0, Math.cos(radians)));
			for (int i = 0; i < press.ticks(); i++) {
				tick++;
				int at = tick;
				Vec3 before = ride.position();
				Ride.Motion motion = ride.tick(index, controls, point -> false);

				assertTrue(ride.onTrack(index), () -> "off the track after tick " + at);
				RailPoint point = ride.point();
				SegmentIndex.Held held = index.at(point.anchor(), point.side());
				assertNotNull(held, "the rider's segment isn't held");
				Rail line = held.curve().rail(point.rail());
				assertTrue(point.distance() >= 0 && point.distance() <= line.length(),
						() -> "past an end after tick " + at + ": " + point.distance() + " along " + line.length());
				assertTrue(ride.speed() <= Ride.MAX_SPEED, () -> "at " + ride.speed() + " after tick " + at);
				assertTrue(motion.moved() <= Ride.MAX_SPEED + 1e-9, () -> "moved " + motion.moved() + " in tick " + at);
				// No further in a straight line than along the rail, but for the thousandth of a block each end's point
				// may be off by along the rail's arc length table.
				double moved = ride.position().distanceTo(before);
				assertTrue(moved <= motion.moved() + 2e-3,
						() -> "went " + moved + " in tick " + at + ", but the motion says " + motion.moved());
				double curvature = line.horizontalCurvature(point.distance());
				double sideways = ride.speed() * ride.speed() * curvature;
				assertTrue(sideways <= Ride.MAX_SIDEWAYS_ACCELERATION * 1.1, () -> "sideways acceleration " + sideways
						+ " after tick " + at + " at " + ride.speed() + " on a radius of " + 1 / curvature);
			}
		}
	}

	private static Input keys(boolean forward, boolean backward, boolean left, boolean right) {
		return new Input(forward, backward, left, right, false, false, false);
	}

	/**
	 * Keys held while looking along a horizontal unit vector, with the move intent they make, as {@code
	 * ServerPlayer.getLastClientMoveIntent()} turns them by the player's yaw: W along the look, A to its left.
	 */
	private static Ride.Controls controls(Input keys, Vec3 look) {
		double forward = (keys.forward() ? 1 : 0) - (keys.backward() ? 1 : 0);
		double left = (keys.left() ? 1 : 0) - (keys.right() ? 1 : 0);
		double length = Math.sqrt(forward * forward + left * left);
		if (length > 1) {
			forward /= length;
			left /= length;
		}
		Vec3 intent = new Vec3(forward * look.x + left * look.z, 0, forward * look.z - left * look.x);
		return new Ride.Controls(keys, intent, true);
	}
}
