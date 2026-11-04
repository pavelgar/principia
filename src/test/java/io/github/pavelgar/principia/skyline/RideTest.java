package io.github.pavelgar.principia.skyline;

import static io.github.pavelgar.principia.skyline.SegmentCurveTest.assertVec;
import static io.github.pavelgar.principia.skyline.SegmentCurveTest.end;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/**
 * Riding by {@link Ride}'s law. The expected speeds are worked by hand from the law in {@link Ride}'s javadoc, in
 * blocks per tick: each tick 0.3 % drag, then gravity, 0.0245 times the fall of the rail per block along it, then
 * 0.0005 of rolling resistance, then the keys.
 */
class RideTest {
	private static final double EXACT = 1e-9;
	/** Where the first anchor of every track here is. Its point is (0.5, 64.9375, 0.5). */
	private static final BlockPos START = new BlockPos(0, 64, 0);
	private static final Vec3 EAST = new Vec3(1, 0, 0);
	private static final Vec3 WEST = new Vec3(-1, 0, 0);
	private static final Vec3 SOUTH = new Vec3(0, 0, 1);
	private static final Input NO_KEYS = Input.EMPTY;
	private static final Input W = keys(true, false, false, false);
	private static final Input S = keys(false, true, false, false);
	private static final Input W_AND_S = keys(true, true, false, false);
	private static final Input W_AND_D = keys(true, false, false, true);
	private static final Input W_SPRINTING = new Input(true, false, false, false, false, false, true);
	private static final Input SPRINTING = new Input(false, false, false, false, false, false, true);

	/**
	 * Holding W from rest: 0.05 the first tick, then each tick 0.997 v - 0.0005 + 0.05, so v = 16.5 - 16.45 · 0.997ⁿ⁻¹
	 * after n ticks: 0.48886 after 10, and the 11th tops it up to the motor's 0.5, where drag and rolling take 0.002 a
	 * tick and the motor puts it back.
	 */
	@Test
	void holdingWFromRestReachesTenBlocksASecondAndHoldsIt() {
		Rider rider = attach(lay(straight(30)), RailSide.RIGHT, 5);
		rider.hold(W);

		for (int tick = 1; tick <= 10; tick++) {
			rider.tick();
			assertEquals(16.5 - 16.45 * Math.pow(0.997, tick - 1), rider.speed(), EXACT, "the speed after " + tick);
		}
		for (int tick = 11; tick <= 20; tick++) {
			rider.tick();
			assertEquals(0.5, rider.speed(), EXACT, "the speed after " + tick);
		}
		// The 10 ticks sum to 2.70308, and 10 more of 0.5: 7.70308 further east. The right rail of an eastward segment
		// is its south one, z = 0.5 + 1, and runs 1 px below the anchors' tops.
		assertVec(new Vec3(0.5 + 5 + 7.703077, 64.9375, 1.5), rider.position(), 1e-6);
	}

	/**
	 * With no keys from 0.5, each tick v becomes 0.997 v - 0.0005: v + 1/6 = (2/3) 0.997ⁿ, which reaches 0 between
	 * tick 461 (v = 0.0002) and 462, where rolling resistance stops the rider and keeps them stopped.
	 */
	@Test
	void withNoKeysARiderCoastsToRestOnTheLevel() {
		Rider rider = atTopSpeed(lay(straight(150)));
		rider.hold(NO_KEYS);

		for (int tick = 1; tick <= 461; tick++) {
			rider.tick();
		}
		assertEquals(0.0002027, rider.speed(), 1e-7, "the speed after 461 ticks");
		rider.tick();
		assertEquals(0, rider.speed(), "the speed after 462 ticks");
		Vec3 stopped = rider.position();
		rider.tick();
		assertVec(stopped, rider.position(), EXACT);
	}

	/**
	 * S looking east, the way the rider rides: against their travel, a holding brake. Each tick v becomes 0.997 v -
	 * 0.0005 - 0.05: 0.448, 0.396, ..., 0.0376 after 9 ticks, and the 10th stops them. Then they stay, as long as S is
	 * held.
	 */
	@Test
	void sAgainstTheTravelStopsTheRiderAndHoldsThem() {
		Rider rider = atTopSpeed(lay(straight(30)));
		rider.hold(S);

		rider.tick();
		assertEquals(0.448, rider.speed(), EXACT, "the speed after a tick of S");
		for (int tick = 2; tick <= 9; tick++) {
			rider.tick();
		}
		assertEquals(0.037577, rider.speed(), 1e-6, "the speed after 9 ticks of S");
		rider.tick();
		assertEquals(0, rider.speed(), "the speed after 10 ticks of S");
		Vec3 stopped = rider.position();
		for (int tick = 1; tick <= 20; tick++) {
			rider.tick();
		}
		assertVec(stopped, rider.position(), EXACT);
	}

	/**
	 * Riding east at 0.5, the rider lets go of W for a tick, coasting to 0.498, and presses it again looking west: a
	 * throttle against the travel, which brakes through the stop and drives on west, at 0.05 a tick each way. East, v
	 * becomes 0.997 v - 0.0005 - 0.05: 0.446006 after a tick, down to 0.03563 after 9; the 10th takes it through 0 to
	 * 0.014977 west, and from there the motor speeds up as from rest, to 0.5 at the 20th.
	 */
	@Test
	void wAgainstTheTravelBrakesThroughTheStopAndDrivesOnTheOtherWay() {
		Rider rider = atTopSpeed(lay(straight(30)));
		rider.hold(NO_KEYS);
		rider.tick();
		rider.look(WEST);
		rider.hold(W);

		rider.tick();
		assertEquals(0.446006, rider.speed(), EXACT, "the speed east after a tick");
		for (int tick = 2; tick <= 9; tick++) {
			rider.tick();
		}
		assertEquals(0.03563, rider.speed(), 1e-6, "the speed east after 9 ticks");
		Vec3 ninth = rider.position();
		rider.tick();
		assertEquals(0.014977, rider.speed(), 1e-6, "the speed west after 10 ticks");
		assertEquals(ninth.x - 0.014977, rider.position().x, 1e-6, "where the 10th tick took the rider");
		for (int tick = 11; tick <= 20; tick++) {
			rider.tick();
		}
		assertEquals(0.5, rider.speed(), EXACT, "the speed west after 20 ticks");
	}

	/**
	 * On a 45° slope a rider at rest rolls down: gravity pulls them along it by 0.0245 · √½ = 0.017324 a tick, of
	 * which rolling resistance takes 0.0005. Each tick after, drag takes 0.3 % first: 0.016824, 0.033598, 0.050321.
	 */
	@Test
	void aRiderAtRestOnASlopeRollsDown() {
		Rider rider = attach(lay(slope(20)), RailSide.RIGHT, 10);
		Vec3 attached = rider.position();

		rider.tick();
		assertEquals(0.016824116, rider.speed(), 1e-9, "the speed after a tick");
		double along = 0.016824116 * Math.sqrt(0.5);
		assertVec(attached.add(-along, -along, 0), rider.position(), 1e-9);
		assertVec(new Vec3(-along, -along, 0), rider.ride().velocity(), 1e-9);
		rider.tick();
		assertEquals(0.033597760, rider.speed(), 1e-9, "the speed after 2 ticks");
		rider.tick();
		assertEquals(0.050321083, rider.speed(), 1e-9, "the speed after 3 ticks");
	}

	/**
	 * Riding with no keys into a climb (a bend up to 45°, then the slope), the rider slows, stops and rolls back. They
	 * reach the bend at about 0.453: from 0.5 at x = 8.7, 24.5 ticks of v + 1/6 falling by 0.997 a tick. Gravity alone
	 * would turn that into 0.453² / (2 · 0.0245) = 4.18 blocks up, 2 of them in the bend, so they turn back before
	 * x = 25.5 + 2.18. On the 45° slope each tick takes 0.3 % of the speed, then gravity's 0.0245 · √½ and rolling
	 * resistance's 0.0005: v becomes 0.997 v - 0.017824.
	 */
	@Test
	void withNoKeysARiderSlowsUpASlopeStopsAndRollsBack() {
		// The bend runs from x = 20.5 to the slope's foot at x = 25.5.
		Rider rider = atTopSpeed(lay(straight(20), upward(), slope(20)));
		rider.hold(NO_KEYS);

		double highest = 0;
		int onTheSlope = 0;
		for (int tick = 1; tick <= 200; tick++) {
			Vec3 before = rider.position();
			double speed = rider.speed();
			rider.tick();
			if (before.x > 25.5 && rider.position().x > before.x) {
				onTheSlope++;
				assertEquals(0.997 * speed - 0.017824116, rider.speed(), 1e-9, "a tick going up the slope");
			}
			highest = Math.max(highest, rider.position().x);
		}

		assertTrue(onTheSlope >= 3, "only " + onTheSlope + " ticks going up the slope");
		assertTrue(rider.position().x < 20, "the rider didn't roll back off the climb: at " + rider.position());
		assertTrue(highest < 25.5 + 2.18, "the rider climbed to x = " + highest);
	}

	/**
	 * W looking up a 45° slope from rest: gravity and rolling resistance pull back 0.017824 a tick, the motor pushes
	 * 0.05, up to its top speed on a 45° climb, 0.5 · (1 - ½ · √½ / sin 45°) = 0.25. From 0, v becomes 0.997 v -
	 * 0.017824 + 0.05 (at rest the first tick, gravity's 0.017324 less 0.0005 of rolling resistance against it):
	 * 0.033176, 0.065252, ..., 0.224196 after 7 ticks, and the 8th tops it up to 0.25, which holds.
	 */
	@Test
	void wUpASlopeSettlesAtFiveBlocksASecondFromRest() {
		Rider rider = attach(lay(slope(30)), RailSide.RIGHT, 2);
		rider.hold(W);

		rider.tick();
		assertEquals(0.033176, rider.speed(), 1e-6, "the speed after a tick");
		for (int tick = 2; tick <= 7; tick++) {
			rider.tick();
		}
		assertEquals(0.224196, rider.speed(), 1e-6, "the speed after 7 ticks");
		for (int tick = 8; tick <= 30; tick++) {
			rider.tick();
			assertEquals(0.25, rider.speed(), EXACT, "the speed after " + tick + " ticks");
		}
	}

	/**
	 * W looking east, down a 45° slope: the motor pushes up to 0.5 and gravity on, until the cap holds the rider at 1.0,
	 * about 30 blocks down. Through a dip and up the next slope, W does nothing above the climb's top speed, 0.25, so
	 * gravity slows the rider, and the motor holds them there.
	 */
	@Test
	void wDownASlopePassesTenBlocksASecondButNeverTwentyAndUpTheNextSettlesAtFive() {
		Rider rider = attach(lay(valley()), RailSide.RIGHT, 1);
		rider.hold(W);

		double fastest = 0;
		for (int tick = 1; tick <= 60; tick++) {
			rider.tick();
			fastest = Math.max(fastest, rider.speed());
		}
		assertEquals(1, fastest, EXACT, "the fastest down the slope");
		for (int tick = 1; tick <= 140; tick++) {
			rider.tick();
			assertTrue(rider.speed() <= 1, "over the cap at " + rider.speed());
		}
		// Climbing the far slope, x past 0.5 + 40 + 8.
		assertTrue(rider.position().x > 48.5, "the rider isn't up the far slope: at " + rider.position());
		for (int tick = 1; tick <= 10; tick++) {
			Vec3 before = rider.position();
			rider.tick();
			assertEquals(0.25, rider.speed(), EXACT, "the speed up the far slope");
			assertTrue(rider.position().y > before.y, "the rider isn't climbing");
		}
	}

	/**
	 * S looking east while riding down a 45° slope at the cap: up the slope, against the travel, a holding brake. Each
	 * tick v becomes 0.997 v + 0.017324 - 0.0005 - 0.05, 0.963824 the first; the brake beats gravity, stops the rider
	 * and holds them there while S is held.
	 */
	@Test
	void sGoingDownASlopeAtTwentyBlocksASecondStopsAndHoldsTheRider() {
		Rider rider = attach(lay(valley()), RailSide.RIGHT, 1);
		rider.hold(W);
		for (int tick = 1; tick <= 60 && rider.speed() < 1; tick++) {
			rider.tick();
		}
		assertEquals(1, rider.speed(), EXACT, "the speed down the slope");

		rider.hold(S);
		rider.tick();
		assertEquals(0.963824, rider.speed(), 1e-6, "the speed after a tick of S");
		for (int tick = 2; tick <= 40 && rider.speed() > 0; tick++) {
			rider.tick();
		}
		assertEquals(0, rider.speed(), "the speed after braking");
		Vec3 stopped = rider.position();
		assertTrue(stopped.x < 40.5, "the rider stopped past the slope, at " + stopped);
		for (int tick = 1; tick <= 20; tick++) {
			rider.tick();
		}
		assertVec(stopped, rider.position(), EXACT);
	}

	/**
	 * Gravity pulls a rider at rest along a slope of angle a by 0.0245 sin a a tick; static friction holds them on
	 * slopes up to 4°, where it pulls by 0.0245 sin 4° = 0.00170903. On 1 up in 15, 3.814°: 0.0245 / √226 =
	 * 0.00162972, held. A segment's curve runs from its lower anchor, so it rises there; in a dip between anchors level
	 * with each other it falls first, and 0.25 short of the bottom, about 0.25 / R = 2.5° for the bend's radius R = 8 /
	 * (2 sin 45°), the rider is held too. On 1 up in 14, 4.086°: 0.0245 / √197 = 0.00174555, which starts them rolling,
	 * and rolling resistance takes 0.0005 of it: 0.00124555.
	 */
	@Test
	void aRiderAtRestStaysOnSlopesUpToFourDegreesAndRollsOnSteeperOnes() {
		SegmentIndex dip = lay(dip(8));
		SegmentIndex.Held bend = dip.at(START, AnchorSide.FRONT);
		assertNotNull(bend, "no dip");
		double bottom = bend.curve().rail(RailSide.RIGHT).length() / 2;
		for (Rider held : List.of(attach(lay(gentle(15, 1)), RailSide.RIGHT, 7),
				attach(dip, RailSide.RIGHT, bottom - 0.25))) {
			Vec3 attached = held.position();
			for (int tick = 1; tick <= 100; tick++) {
				held.tick();
			}
			assertEquals(0, held.speed(), "the speed at " + attached);
			assertVec(attached, held.position(), EXACT);
		}

		Rider rolling = attach(lay(gentle(14, 1)), RailSide.RIGHT, 7);
		Vec3 before = rolling.position();
		rolling.tick();
		assertEquals(0.00124555, rolling.speed(), 1e-8, "the speed on 1 up in 14");
		assertTrue(rolling.position().x < before.x, "the rider didn't roll down");
	}

	/**
	 * Coasting up 1 in 16, 3.576°, a rider slows by gravity's 0.0245 / √257 = 0.00152827 a tick, rolling resistance's
	 * 0.0005 and drag. Where the speed runs out static friction holds them, gravity pulling by less than on 4°, where
	 * without it they would roll back down.
	 */
	@Test
	void aRiderCoastingUpAGentleSlopeStopsAndStaysThere() {
		Rider rider = attach(lay(gentle(32, 2)), RailSide.RIGHT, 2);
		Vec3 attached = rider.position();
		rider.hold(W);
		for (int tick = 1; tick <= 4; tick++) {
			rider.tick();
		}
		rider.hold(NO_KEYS);
		for (int tick = 1; tick <= 200 && rider.speed() > 0; tick++) {
			rider.tick();
		}

		assertEquals(0, rider.speed(), "the speed after coasting");
		Vec3 stopped = rider.position();
		assertTrue(stopped.x > attached.x + 1, "the rider stopped at " + stopped + ", from " + attached);
		for (int tick = 1; tick <= 100; tick++) {
			rider.tick();
		}
		assertVec(stopped, rider.position(), EXACT);
	}

	/**
	 * A rider let go 1 block down one side of a dip swings through it and up the other side, back and forth, losing
	 * speed to drag and rolling resistance, until they come to rest near the bottom: static friction holds them only
	 * where the rail is under 4°, within R (1 - cos 4°) = 0.0138 of the bottom of the bend, radius R = 8 / (2 sin 45°).
	 * The bend dips 4 (√2 - 1) below its anchors, ½ c (√2 - 1) for its chord c = 8 and handles of 2c / (3 (1 + cos
	 * 45°)).
	 */
	@Test
	void aSwingInADipComesToRestNearTheBottom() {
		Rider rider = attach(lay(descent(6), dip(8), slope(6)), RailSide.RIGHT, 1);

		for (int tick = 1; tick <= 6000; tick++) {
			rider.tick();
		}

		assertEquals(0, rider.speed(), "the speed after 6000 ticks");
		assertEquals(64.9375 - 6 - 4 * (Math.sqrt(2) - 1), rider.position().y, 0.0138, "the height at rest");
		Vec3 rest = rider.position();
		rider.tick();
		assertVec(rest, rider.position(), EXACT);
	}

	/** A rider who attaches is at rest, wherever they look, and stays so until a key moves them. */
	@Test
	void aRiderAttachesAtRestAndNoKeyMovesThem() {
		Rider rider = attach(lay(straight(30)), RailSide.RIGHT, 10);
		Vec3 attached = rider.position();

		for (int tick = 1; tick <= 20; tick++) {
			assertEquals(0, rider.tick().moved(), "how far the rider moved at tick " + tick);
		}

		assertEquals(0, rider.speed());
		assertVec(attached, rider.position(), EXACT);
	}

	/**
	 * W held round a U-turn, from riding east to riding west, while the rider looks east all along: the way it picked
	 * stays, so it drives them out of the turn west at 0.5, where looking east and holding W would have braked them.
	 * East 10, a quarter turn left of radius 4 to the north, another to the west, and 20 west; on the right rail, the
	 * outer one, radius 5, which allows √(0.05 · 5) = 0.5.
	 */
	@Test
	void wHeldRoundAUTurnKeepsItsWay() {
		Rider rider = attach(lay(straight(10), leftTurn(4), northToWest(4), westward(20)), RailSide.RIGHT, 2);
		rider.hold(W);

		for (int tick = 1; tick <= 60; tick++) {
			rider.tick();
		}

		// The westward straight runs from (10.5, _, -7.5); its right rail, the north one, at z = -8.5.
		assertTrue(rider.position().x < 9.5, "the rider isn't along the westward straight: " + rider.position());
		assertEquals(-8.5, rider.position().z, EXACT, "the rider isn't on the westward straight's north rail");
		Vec3 before = rider.position();
		rider.tick();
		assertEquals(before.x - 0.5, rider.position().x, EXACT, "the rider isn't going west at 0.5");
	}

	/**
	 * The way is picked when the keys change, not while they are held: riding east with W, turning to look west keeps
	 * the rider riding east at 0.5. Releasing W and pressing it again picks west, from the look: against the travel, so
	 * it brakes, 0.997 · 0.498 - 0.0005 - 0.05 = 0.446006 after a tick of coasting at 0.997 · 0.5 - 0.0005 = 0.498.
	 */
	@Test
	void pressingWAfreshPicksTheWayFromTheLook() {
		Rider rider = atTopSpeed(lay(straight(60)));
		rider.look(WEST);
		for (int tick = 1; tick <= 5; tick++) {
			Vec3 before = rider.position();
			rider.tick();
			assertEquals(before.x + 0.5, rider.position().x, EXACT, "the move east " + tick + " ticks looking west");
		}

		rider.hold(NO_KEYS);
		rider.tick();
		assertEquals(0.498, rider.speed(), EXACT, "the speed after a tick of coasting");
		rider.hold(W);
		rider.tick();
		assertEquals(0.446006, rider.speed(), EXACT, "the speed after pressing W again");
	}

	/**
	 * Adding D to W changes the keys, so they pick again: looking west, W and D are west and north, normalised, whose
	 * part along an eastward rail is -√½, so west, against the travel: 0.997 · 0.5 - 0.0005 - 0.05 = 0.448.
	 */
	@Test
	void addingDToWPicksTheWayAgain() {
		Rider rider = atTopSpeed(lay(straight(60)));
		rider.look(WEST);
		rider.tick();
		assertEquals(0.5, rider.speed(), EXACT, "the speed east looking west");

		rider.hold(W_AND_D);
		rider.tick();

		assertEquals(0.448, rider.speed(), EXACT, "the speed after adding D");
	}

	/** W and S together pick no way: the rider coasts, 0.997 · 0.5 - 0.0005 = 0.498. */
	@Test
	void wAndSTogetherCoast() {
		Rider rider = atTopSpeed(lay(straight(60)));
		rider.hold(W_AND_S);

		rider.tick();

		assertEquals(0.498, rider.speed(), EXACT);
	}

	/**
	 * W looking south, straight across an eastward rail, picks no way, and the rider stays at rest; once the look turns
	 * east with W still held, it picks east and the motor starts them, 0.05 in the tick.
	 */
	@Test
	void aPressStraightAcrossTheRailPicksOnceTheLookGivesAWay() {
		Rider rider = attach(lay(straight(30)), RailSide.RIGHT, 10);
		Vec3 attached = rider.position();
		rider.look(SOUTH);
		rider.hold(W);
		for (int tick = 1; tick <= 5; tick++) {
			rider.tick();
		}
		assertVec(attached, rider.position(), EXACT);

		rider.look(EAST);
		rider.tick();

		assertEquals(0.05, rider.speed(), EXACT);
		assertVec(attached.add(0.05, 0, 0), rider.position(), EXACT);
	}

	/**
	 * Braking ahead of an end, the rider stops exactly at the anchor without braking harder than 0.05 per tick: each
	 * move is at most 0.05 shorter than the one before, until the last lands on the anchor. From top speed on a long
	 * segment, and from the lower speeds a short one leaves room for. Up to top speed takes 3.20 blocks with W, and
	 * braking from it 0.45 + 0.40 + ... + 0.05 = 2.25.
	 */
	@Test
	void aRiderStopsExactlyAtAnEnd() {
		for (int length : new int[]{1, 2, 3, 4, 10, 30}) {
			Rider rider = attach(lay(straight(length)), RailSide.LEFT, 0);
			rider.hold(W);
			double topSpeed = 0;
			double lastMove = 0;
			for (int tick = 1; tick <= 200; tick++) {
				double before = rider.position().x;
				rider.tick();
				double move = rider.position().x - before;
				assertTrue(move >= lastMove - 0.05 - EXACT,
						"on " + length + " blocks the move fell from " + lastMove + " to " + move);
				assertTrue(rider.position().x <= 0.5 + length + EXACT, "on " + length + " blocks the rider overshot");
				topSpeed = Math.max(topSpeed, move);
				lastMove = move;
			}
			// The left rail of an eastward segment is its north one, z = 0.5 - 1.
			assertVec(new Vec3(0.5 + length, 64.9375, -0.5), rider.position(), EXACT);
			assertEquals(0, rider.speed(), "the speed at the end of " + length + " blocks");
			if (length >= 10) {
				assertEquals(0.5, topSpeed, EXACT, "the top speed on " + length + " blocks");
			} else {
				assertTrue(topSpeed < 0.5, "on " + length + " blocks the rider reached top speed");
			}
		}
	}

	/**
	 * Coasting at the cap down a 45° slope that levels out into the tightest turn, the rider brakes ahead for it while
	 * gravity still pulls, and is at its inner rail's √0.15 by the turn's start (see the level case below for the
	 * Bézier's leeway), coasting on through it no faster. The hook brakes at most 0.05 a tick: on the straight slope, a tick takes v to no less than
	 * 0.997 v + 0.017324 - 0.0005 - 0.05; levelling out and on the level, where gravity only adds, no less than 0.997 v
	 * - 0.0005 - 0.05.
	 */
	@Test
	void comingDownASlopeAtTheCapARiderBrakesToEnterTheTightestTurnAtItsSpeed() {
		// 40 down at 45°, a bend levelling out 12 east and 5 down, then the turn from x = 52.5 to z = 4.5.
		Rider rider = attach(lay(descent(40), levelling(), rightTurn(4), southward(30)), RailSide.RIGHT, 1);
		rider.hold(W);
		for (int tick = 1; tick <= 60 && rider.speed() < 1; tick++) {
			rider.tick();
		}
		assertEquals(1, rider.speed(), EXACT, "the speed down the slope");
		rider.hold(NO_KEYS);

		List<Double> inTurn = new ArrayList<>();
		for (int tick = 1; tick <= 80; tick++) {
			double before = rider.speed();
			double gravity = rider.position().x < 40.5 ? 0.017324 : 0;
			rider.tick();
			assertTrue(rider.speed() >= before * 0.997 + gravity - 0.0005 - 0.05 - 1e-6,
					"the speed fell from " + before + " to " + rider.speed());
			Vec3 at = rider.position();
			if (at.x > 52.5 + 1e-6 && at.z < 4.5 - 1e-6) {
				inTurn.add(rider.speed());
			}
		}

		assertTrue(inTurn.size() >= 5, "only " + inTurn.size() + " ticks in the turn");
		assertEquals(Math.sqrt(0.15), inTurn.getFirst(), 0.006, "the speed entering the turn");
		for (double speed : inTurn) {
			assertTrue(speed < Math.sqrt(0.15) + 0.006, "in the turn the rider went at " + speed);
		}
	}

	/**
	 * Coasting at the cap down a 45° slope into an end, the rider brakes ahead for it while gravity pulls, and stops
	 * exactly at the anchor, (40.5, 24.9375, 1.5), the last move under 0.05. The hook brakes at most 0.05 a tick: a tick
	 * takes v to no less than 0.997 v + 0.017324 - 0.0005 - 0.05. At the anchor they stay, gravity pulling them on past
	 * it.
	 */
	@Test
	void comingDownASlopeAtTheCapARiderStopsExactlyAtAnEnd() {
		Rider rider = attach(lay(descent(40)), RailSide.RIGHT, 1);
		rider.hold(W);
		for (int tick = 1; tick <= 60 && rider.speed() < 1; tick++) {
			rider.tick();
		}
		assertEquals(1, rider.speed(), EXACT, "the speed down the slope");
		rider.hold(NO_KEYS);

		Vec3 end = new Vec3(40.5, 64.9375 - 40, 1.5);
		double lastMove = 1;
		for (int tick = 1; tick <= 60 && rider.position().distanceTo(end) > EXACT; tick++) {
			double before = rider.speed();
			Vec3 from = rider.position();
			rider.tick();
			lastMove = rider.position().distanceTo(from);
			if (rider.position().distanceTo(end) > EXACT) {
				assertTrue(rider.speed() >= before * 0.997 + 0.017324 - 0.0005 - 0.05 - 1e-6,
						"the speed fell from " + before + " to " + rider.speed());
			}
		}
		assertVec(end, rider.position(), EXACT);
		assertTrue(lastMove < 0.05, "the rider came into the anchor at " + lastMove);
		assertEquals(0, rider.speed(), "the speed at the end");
		for (int tick = 1; tick <= 20; tick++) {
			rider.tick();
		}
		assertVec(end, rider.position(), EXACT);
	}

	/**
	 * Climbing into an end with W, the rider stops exactly at the anchor at the top; let go of W, gravity rolls them
	 * back down.
	 */
	@Test
	void aRiderStoppedAtTheTopOfAClimbRollsBackDown() {
		Rider rider = attach(lay(slope(4)), RailSide.RIGHT, 0);
		rider.hold(W);
		for (int tick = 1; tick <= 60; tick++) {
			rider.tick();
		}
		Vec3 top = new Vec3(4.5, 64.9375 + 4, 1.5);
		assertVec(top, rider.position(), EXACT);

		rider.hold(NO_KEYS);
		rider.tick();
		assertTrue(rider.position().y < top.y, "the rider didn't roll back down");
	}

	/**
	 * Holding W towards an end, the hook brakes at exactly the brake's rate: from the first move under 0.5, each is 0.05
	 * shorter than the one before, and the last lands on the anchor.
	 */
	@Test
	void aRiderBrakesForAnEndAtTheBrakesRate() {
		Rider rider = attach(lay(straight(30)), RailSide.LEFT, 0);
		rider.hold(W);
		List<Double> braking = new ArrayList<>();
		for (int tick = 1; tick <= 100; tick++) {
			double moved = rider.tick().moved();
			if (tick > 11 && moved < 0.5 - EXACT && moved > 0) {
				braking.add(moved);
			}
		}

		assertVec(new Vec3(30.5, 64.9375, -0.5), rider.position(), EXACT);
		assertTrue(braking.size() >= 9, "only " + braking.size() + " ticks of braking");
		for (int tick = 1; tick < braking.size(); tick++) {
			assertEquals(braking.get(tick - 1) - 0.05, braking.get(tick), EXACT, "braking tick " + tick);
		}
	}

	/**
	 * Down a rail steeper than the limits allow, which only placing checks: 60 down and 10 east between anchors
	 * pitched 45° down, near enough straight down in its middle. Coasting down it at the cap, the hook still sees the
	 * end in time to stop the rider exactly at it: braking against gravity straight down, 0.05 - 0.0245, takes 1 ·
	 * 1.0255 / (2 · 0.0255) = 20.1 blocks from the cap.
	 */
	@Test
	void downARailSteeperThanTheLimitsARiderStillStopsExactlyAtTheEnd() {
		Rider rider = attach(lay(new Segment(new Vec3i(10, -60, 0), end(Heading.EAST, -45, AnchorSide.FRONT),
				end(Heading.EAST, -45, AnchorSide.BACK))), RailSide.RIGHT, 0.5);
		double fastest = 0;
		double lastMove = 0;
		for (int tick = 1; tick <= 200; tick++) {
			Ride.Motion motion = rider.tick();
			fastest = Math.max(fastest, rider.speed());
			if (motion.moved() > 0) {
				lastMove = motion.moved();
			}
		}

		assertEquals(1, fastest, EXACT, "the fastest down the rail");
		assertVec(new Vec3(10.5, 64.9375 - 60, 1.5), rider.position(), EXACT);
		assertTrue(lastMove < 0.05, "the rider came into the anchor at " + lastMove);
	}

	/**
	 * Through a turn tightest well inside its segment, east to south 10 east and 4 south, holding W: radius 2.9 near
	 * its far end, 4.6 at it, on the inner rail 1 less. The hook brakes ahead for its tightest point, so at every tick
	 * the speed is no more than the rail there allows, v² κ ≤ 0.05, to within how much the turn tightens past the last
	 * of the points ⅛ block apart it is sampled at: on this turn, far tighter than the limits allow, about a twentieth.
	 * The same turn to the north is held from its far anchor, first in {@code BlockPos} order, so it is sampled
	 * walking back along its curve.
	 */
	@Test
	void aTurnTightestInsideItsSegmentIsTakenAtItsSpeedThroughout() {
		assertTakenAtItsSpeedThroughout(lay(straight(10), new Segment(new Vec3i(10, 0, 4),
				end(Heading.EAST, 0, AnchorSide.FRONT), end(Heading.SOUTH, 0, AnchorSide.BACK)), southward(20)),
				RailSide.RIGHT);
		assertTakenAtItsSpeedThroughout(lay(straight(10), new Segment(new Vec3i(10, 0, -4),
				end(Heading.EAST, 0, AnchorSide.FRONT), end(Heading.NORTH, 0, AnchorSide.BACK)), northward(20)),
				RailSide.LEFT);
	}

	private static void assertTakenAtItsSpeedThroughout(SegmentIndex index, RailSide inner) {
		Rider rider = attach(index, inner, 0);
		rider.hold(W);
		double tightest = 0;
		for (int tick = 1; tick <= 80; tick++) {
			rider.tick();
			double curvature = curvature(index, rider);
			tightest = Math.max(tightest, curvature);
			assertTrue(rider.speed() * rider.speed() * curvature <= 0.05 * 1.1, "at " + rider.position()
					+ " the rider went at " + rider.speed() + " where the radius is " + 1 / curvature);
		}
		assertTrue(tightest > 1 / 2.0, "the rider never reached the tightest part: radius " + 1 / tightest);
	}

	/**
	 * A turn that begins at an anchor, after a straight, is reached at its speed whatever the phase the rider arrives
	 * in. The tightest turn's inner rail has radius 3.088 at the anchor (see below), so √(0.05 · 3.088) ≈ 0.393 holds
	 * from the anchor on, where the straight allowed anything. Holding W at the motor's 0.5 from 64 starts 1/128 apart
	 * along the first half block, as far as a tick at top speed goes, at every tick v² κ ≤ 0.05, to within the
	 * sampling's 10 %. Braking by the distance to the anchor alone, a tick up to 0.05 over the turn's speed could end
	 * on the anchor itself: from 3/8 along, the rider would stand on it at 0.4375, 23 % over. The same turn to the
	 * north is held from its far anchor, so it is sampled walking back along its curve.
	 */
	@Test
	void aTurnBeginningAtAnAnchorIsReachedAtItsSpeedFromAnyStart() {
		assertReachedAtItsSpeed(lay(straight(10), rightTurn(4), southward(30)), RailSide.RIGHT);
		assertReachedAtItsSpeed(lay(straight(10), leftTurn(4), northward(30)), RailSide.LEFT);
	}

	private static void assertReachedAtItsSpeed(SegmentIndex index, RailSide inner) {
		for (int start = 0; start < 64; start++) {
			double along = start / 128.0;
			Rider rider = attach(index, inner, along);
			rider.hold(W);
			for (int tick = 1; tick <= 40; tick++) {
				rider.tick();
				double curvature = curvature(index, rider);
				assertTrue(rider.speed() * rider.speed() * curvature <= 0.05 * 1.1, "from " + along + " along, at "
						+ rider.position() + " the rider went at " + rider.speed() + " where the radius is "
						+ 1 / curvature);
			}
		}
	}

	/** How sharply the rail turns seen from above where the rider is. */
	private static double curvature(SegmentIndex index, Rider rider) {
		RailPoint at = rider.ride().point();
		SegmentIndex.Held segment = index.at(at.anchor(), at.side());
		assertNotNull(segment, "the rider's segment isn't held");
		return segment.curve().rail(at.rail()).horizontalCurvature(at.distance());
	}

	/**
	 * A press picks by its part along the rail: on a straight south, W looking east is straight across it and picks
	 * nothing; looking south-east, it picks south, and the motor starts the rider 0.05 that way.
	 */
	@Test
	void aPressPicksByItsPartAlongTheRail() {
		Rider rider = attach(lay(southward(20)), RailSide.RIGHT, 5);
		Vec3 attached = rider.position();
		rider.hold(W);
		rider.tick();
		assertVec(attached, rider.position(), EXACT);

		rider.look(new Vec3(Math.sqrt(0.5), 0, Math.sqrt(0.5)));
		rider.tick();
		assertVec(attached.add(0, 0, 0.05), rider.position(), EXACT);
	}

	/** Stopped at an end with W held, a rider who presses S, looking east, goes back west, as from rest. */
	@Test
	void aRiderStoppedAtAnEndGoesBackWhenAsked() {
		Rider rider = attach(lay(straight(3)), RailSide.LEFT, 0);
		rider.hold(W);
		for (int tick = 1; tick <= 40; tick++) {
			rider.tick();
		}
		rider.hold(S);
		rider.tick();

		assertEquals(0.05, rider.speed(), EXACT);
		assertVec(new Vec3(3.5 - 0.05, 64.9375, -0.5), rider.position(), EXACT);
	}

	/**
	 * Sideways acceleration on the inner rail of the tightest quarter turn, radius 3, is capped at 0.05 per tick²:
	 * v = √(0.05 · 3) ≈ 0.387. The Bézier only approximates the circle: its centre line's radius runs from 3.968 in the
	 * middle (SegmentLimit) to 4.088 at the ends, (2/3) (r - h) / h² for the handle h = 0.5523 r, so the inner rail's
	 * from 2.968 to 3.088, and the speed from √(0.05 · 2.968) ≈ 0.385 to √(0.05 · 3.088) ≈ 0.393. The same turn to the
	 * north is held from its far anchor, first in {@code BlockPos} order, so braking ahead walks back along its curve.
	 */
	@Test
	void onTheInnerRailOfTheTightestTurnTheSpeedSettlesAtTheTurnsLimit() {
		assertSettlesAtTheTurnsLimit(lay(straight(10), rightTurn(4), southward(30)), RailSide.RIGHT, 4.5);
		assertSettlesAtTheTurnsLimit(lay(straight(10), leftTurn(4), northward(30)), RailSide.LEFT, -3.5);
	}

	/** Rides W through the tightest turn after 10 east, to the turn's far anchor's z, on its inner rail. */
	private static void assertSettlesAtTheTurnsLimit(SegmentIndex index, RailSide inner, double turnEndZ) {
		Rider rider = attach(index, inner, 0);
		rider.hold(W);
		List<Double> inTurn = new ArrayList<>();
		for (int tick = 1; tick <= 60; tick++) {
			rider.tick();
			Vec3 at = rider.position();
			if (at.x > 10.5 + 1e-6 && Math.abs(at.z - 0.5) < Math.abs(turnEndZ - 0.5) - 1e-6) {
				inTurn.add(rider.speed());
			}
		}

		assertTrue(inTurn.size() >= 5, "only " + inTurn.size() + " ticks in the turn");
		// From top speed on the straight, slowed by the turn's start.
		assertEquals(Math.sqrt(0.15), inTurn.getFirst(), 0.006, "the speed entering the turn");
		for (double speed : inTurn) {
			assertEquals(Math.sqrt(0.15), speed, 0.006, "the speed in the turn");
		}
		assertEquals(0.5, rider.speed(), EXACT, "the speed back on the straight");
	}

	/**
	 * On the outer rail of the same turn, radius 5, the cap is √(0.05 · 5) = 0.5: full speed. The Bézier's tightest
	 * 4.968 caps it at √(0.05 · 4.968) ≈ 0.498.
	 */
	@Test
	void onTheOuterRailOfTheTightestTurnTheRiderKeepsFullSpeed() {
		Rider rider = attach(lay(straight(10), rightTurn(4), southward(30)), RailSide.LEFT, 0);
		rider.hold(W);
		double slowest = 0.5;
		for (int tick = 1; tick <= 60; tick++) {
			rider.tick();
			if (tick > 11) {
				slowest = Math.min(slowest, rider.speed());
			}
		}

		assertEquals(0.5, slowest, 0.003);
	}

	/**
	 * Sprinting with W boosts the motor's top speed to 0.75 on the level: from 0.5, v becomes 0.997 v - 0.0005 + 0.05,
	 * 0.548, 0.595856, ..., 0.738564 after 5 ticks, and the 6th tops it up to 0.75, which holds. Each tick the rider
	 * moves as far as their speed.
	 */
	@Test
	void sprintingWithWBoostsTheTopSpeedToFifteenBlocksASecond() {
		Rider rider = atTopSpeed(lay(straight(60)));
		rider.hold(W_SPRINTING);

		Ride.Motion first = rider.tick();
		assertTrue(first.boosting(), "the rider isn't boosting");
		assertEquals(0.548, rider.speed(), EXACT, "the speed after a tick");
		assertEquals(0.548, first.moved(), EXACT, "how far the rider moved in a tick");
		for (int tick = 2; tick <= 5; tick++) {
			rider.tick();
		}
		assertEquals(0.738564, rider.speed(), 1e-6, "the speed after 5 ticks");
		for (int tick = 6; tick <= 15; tick++) {
			rider.tick();
			assertEquals(0.75, rider.speed(), EXACT, "the speed after " + tick + " ticks");
		}
	}

	/**
	 * Up a 45° slope the boost's top speed falls as the motor's does, by half, to 0.375: from rest as without the
	 * boost, 0.033176, ..., 0.349644 after 11 ticks, and the 12th tops it up to 0.375.
	 */
	@Test
	void boostingUpASlopeSettlesAtSevenAndAHalfBlocksASecond() {
		Rider rider = attach(lay(slope(30)), RailSide.RIGHT, 2);
		rider.hold(W_SPRINTING);

		for (int tick = 1; tick <= 11; tick++) {
			rider.tick();
		}
		assertEquals(0.349644, rider.speed(), 1e-6, "the speed after 11 ticks");
		for (int tick = 12; tick <= 30; tick++) {
			rider.tick();
			assertEquals(0.375, rider.speed(), EXACT, "the speed after " + tick + " ticks");
		}
	}

	/**
	 * Without the food to sprint, sprinting with W doesn't boost: the rider keeps the motor's 0.5. Nor does sprinting
	 * with no throttle: S held against the travel, or no keys.
	 */
	@Test
	void onlyAFedRiderOnTheThrottleBoosts() {
		Rider hungry = atTopSpeed(lay(straight(60)));
		hungry.hungry();
		hungry.hold(W_SPRINTING);
		for (int tick = 1; tick <= 10; tick++) {
			assertFalse(hungry.tick().boosting(), "the hungry rider boosts");
			assertEquals(0.5, hungry.speed(), EXACT, "the hungry rider's speed");
		}

		Rider braking = atTopSpeed(lay(straight(60)));
		braking.hold(new Input(false, true, false, false, false, false, true));
		assertFalse(braking.tick().boosting(), "the rider boosts braking with S");
		Rider coasting = atTopSpeed(lay(straight(60)));
		coasting.hold(SPRINTING);
		assertFalse(coasting.tick().boosting(), "the rider boosts with no throttle");
	}

	/**
	 * Something in the rider's body's way beyond x = 8.5 stops them at once, just short of it: 10 ticks of W from 5
	 * blocks along take them to x = 5.5 + 2.70308, and their 11th move, 0.5, is cut to where the body still has room,
	 * found to within 0.5 / 2¹² of it. The speed they started the tick at, 16.5 - 16.45 · 0.997⁹ = 0.488857, is lost.
	 */
	@Test
	void aRiderStopsAtOnceJustShortOfSomethingInTheirWayLosingTheirSpeed() {
		SegmentIndex index = lay(straight(30));
		Ride ride = Ride.attach(index, new RailPoint(START, AnchorSide.FRONT, RailSide.RIGHT, 5));
		assertNotNull(ride, "no rail to attach to");
		Ride.Body wall = point -> point.x > 8.5;
		for (int tick = 1; tick <= 10; tick++) {
			assertEquals(0, ride.tick(index, controls(W, EAST), wall).lost(), "the speed lost at tick " + tick);
		}

		assertEquals(0.488857, ride.tick(index, controls(W, EAST), wall).lost(), 1e-6, "the speed lost at the wall");
		assertEquals(0, ride.speed(), "the speed against the wall");
		assertEquals(8.5, ride.position().x, 0.5 / 4096 + EXACT);
		assertTrue(ride.position().x <= 8.5, "the rider is in the wall at x = " + ride.position().x);
		// S, looking east: away from it.
		ride.tick(index, controls(S, EAST), wall);
		assertEquals(0.05, ride.speed(), EXACT);
	}

	/**
	 * Riding east at 0.5 with W, 0.79692 short of the anchor between two straights (x = 8.70308 + 0.5 + 0.5), the
	 * straight beyond it goes. The hook brakes as hard as it can, 0.05 below what the rider would coast at: 0.997 ·
	 * 0.5 - 0.0005 - 0.05 = 0.448, a loss of 0.052, leaving 0.34892, then 0.997 · 0.448 - 0.0505 = 0.396156, which
	 * takes the rider into the anchor, now an end, where they stop: the tick loses all of the 0.448 it began with.
	 */
	@Test
	void trackEndingCloserThanBrakingNeedsIsReachedAtSpeed() {
		SegmentIndex index = lay(straight(10), straight(10));
		Rider rider = atTopSpeed(index);
		rider.tick();
		rider.tick();
		assertEquals(0.5 + 5 + 3.20308 + 1, rider.position().x, 1e-5, "where the rider is when the track goes");
		index.put(START.east(10), List.of(straight(10).reversed()));
		index.remove(START.east(20));

		assertEquals(0.052, rider.tick().lost(), EXACT, "the speed lost braking");
		assertEquals(0.448, rider.speed(), EXACT, "the speed braking");
		Ride.Motion crash = rider.tick();
		assertEquals(0.448, crash.lost(), EXACT, "the speed lost at the end");
		assertEquals(0.79692 - 0.448, crash.moved(), 1e-5, "how far the rider got to the end");
		assertEquals(0, rider.speed(), "the speed at the end");
		assertEquals(10.5, rider.position().x, EXACT, "where the rider stopped");
	}

	/**
	 * A tick's loss is the speed it began with less the one it ends with, as an elytra crash counts it: holding S
	 * against the travel at about 0.33 into a wall just ahead, the rider loses all of it, though the brake had already
	 * taken it down to about 0.997 · 0.33 - 0.0005 - 0.05 = 0.2785 before the move.
	 */
	@Test
	void aTicksLossIsAllTheSpeedItBeganWith() {
		SegmentIndex index = lay(straight(60));
		Ride ride = Ride.attach(index, new RailPoint(START, AnchorSide.FRONT, RailSide.RIGHT, 5));
		assertNotNull(ride, "no rail to attach to");
		Ride.Body open = point -> false;
		while (ride.speed() < 0.33) {
			ride.tick(index, controls(W, EAST), open);
		}
		ride.tick(index, controls(NO_KEYS, EAST), open);
		while (ride.speed() > 0.33) {
			ride.tick(index, controls(NO_KEYS, EAST), open);
		}
		double speed = ride.speed();
		double at = ride.position().x;

		Ride.Motion crash = ride.tick(index, controls(S, EAST), point -> point.x > at + 0.1);

		assertEquals(speed, crash.lost(), EXACT);
		assertEquals(0, ride.speed());
	}

	/**
	 * The speed lost to a stop hurts as an elytra crash does, 10 per block a tick lost, less 3: 2 for 0.5, 7 for 1. No
	 * stop under 0.3, 6 blocks a second, hurts at all.
	 */
	@Test
	void theSpeedLostToAStopHurtsAsAnElytraCrash() {
		assertEquals(2, Ride.impactDamage(0.5), 1e-6);
		assertEquals(7, Ride.impactDamage(1), 1e-6);
		assertEquals(0, Ride.impactDamage(0.3));
		assertEquals(0, Ride.impactDamage(0.2));
		assertEquals(0, Ride.impactDamage(0));
	}

	/**
	 * Saved by {@link Ride#point()}, a ride resumes where it was, at rest. Named from the segment's far anchor, the same
	 * point is on the other rail, as far from that end.
	 */
	@Test
	void aRideResumesWhereItWasSavedAtRest() {
		SegmentIndex index = lay(straight(10));
		Rider rider = attach(index, RailSide.RIGHT, 2);
		rider.look(WEST);
		rider.hold(W);
		rider.tick();

		Ride resumed = Ride.resume(index, rider.ride().point());
		assertNotNull(resumed, "the ride didn't resume");
		assertVec(rider.position(), resumed.position(), EXACT);
		assertEquals(0, resumed.speed(), "the resumed speed");
		resumed.tick(index, controls(NO_KEYS, WEST), point -> false);
		assertVec(rider.position(), resumed.position(), EXACT);
		resumed.tick(index, controls(W, WEST), point -> false);
		assertVec(rider.position().add(-0.05, 0, 0), resumed.position(), EXACT);

		// 3 blocks from the east anchor on its right rail, seen from it: 7 from the west one on its left.
		Ride fromEast = Ride.resume(index, new RailPoint(START.east(10), AnchorSide.BACK, RailSide.RIGHT, 3));
		assertNotNull(fromEast, "the ride didn't resume from the east anchor");
		assertVec(new Vec3(0.5 + 7, 64.9375, -0.5), fromEast.position(), EXACT);
		fromEast.tick(index, controls(W, WEST), point -> false);
		assertVec(new Vec3(0.5 + 7 - 0.05, 64.9375, -0.5), fromEast.position(), EXACT);
	}

	/** A ride is on the track while the index holds its segment as it was, and not once its anchors have dropped it. */
	@Test
	void aRideIsOnTheTrackWhileTheIndexHoldsItsSegment() {
		SegmentIndex index = lay(straight(10));
		Ride ride = Ride.attach(index, new RailPoint(START, AnchorSide.FRONT, RailSide.RIGHT, 2));
		assertNotNull(ride, "no rail to attach to");
		assertTrue(ride.onTrack(index), "the ride is off its track");

		// The same anchors holding a segment of another shape: 10 east but leaving 10° up.
		index.put(START, List.of(new Segment(new Vec3i(10, 0, 0), end(Heading.EAST, 10, AnchorSide.FRONT),
				end(Heading.EAST, 0, AnchorSide.BACK))));
		index.remove(START.east(10));
		assertFalse(ride.onTrack(index), "the ride is on a segment that changed");
		index.remove(START);
		assertFalse(ride.onTrack(index), "the ride is on a segment that's gone");
	}

	/**
	 * East 10 from the start, then a quarter turn to the left, radius 8, to the north, then 10 north. The turn's far
	 * anchor (18, 64, -8) is first in block order, so the index holds the turn from it, running the other way round
	 * from the ride; the rider must stay on the south rail, which the turn bends round on the outside, radius 9, and W
	 * must keep driving them on, the way it picked seen along their travel.
	 *
	 * <p>With W from rest, 10 ticks cover 2.70308 blocks and each after 0.5 (no turn here slows the rider: the inner
	 * rail's radius 7 allows √0.35 ≈ 0.59): after 30 ticks, 12.70308. From 5 blocks along, that is 7.70308 into the
	 * turn, an angle of 7.70308 / 9 rad round its centre (10.5, _, 0.5 - 8).
	 */
	@Test
	void aRiderCrossesAnAnchorOntoASegmentHeldTheOtherWayAndStaysOnTheSameSide() {
		Rider rider = attach(lay(straight(10), leftTurn(8), northward(10)), RailSide.RIGHT, 5);
		rider.hold(W);

		for (int tick = 1; tick <= 30; tick++) {
			rider.tick();
		}

		double angle = 7.70308 / 9;
		assertVec(new Vec3(10.5 + 9 * Math.sin(angle), 64.9375, -7.5 + 9 * Math.cos(angle)), rider.position(), 0.02);
		assertEquals(0.5, rider.speed(), EXACT);
	}

	/** Landing at 0.5 east on a rail running east, parallel to the one jumped from, the rider keeps all of it. */
	@Test
	void landingOnAParallelRailKeepsTheWholeSpeed() {
		SegmentIndex index = lay(straight(30));

		Ride landed = land(index, RailSide.LEFT, 10, new Vec3(0.5, 0, 0));

		assertVec(new Vec3(0.5, 0, 0), landed.velocity(), EXACT);
	}

	/** Landing at 0.5 east on a rail running south, square to the velocity, the rider lands at rest. */
	@Test
	void landingOnARailAtRightAnglesLandsAtRest() {
		SegmentIndex index = lay(southward(30));

		Ride landed = land(index, RailSide.LEFT, 10, new Vec3(0.5, 0, 0));

		assertEquals(0, landed.speed(), EXACT);
	}

	/** Landing at 0.5 east on a rail running south-east, the rider keeps 0.5 · cos 45° = 0.353553 of it, along it. */
	@Test
	void landingOnARailAtFortyFiveDegreesKeepsThePartAlongIt() {
		SegmentIndex index = lay(new Segment(new Vec3i(20, 0, 20), end(Heading.SOUTH_EAST, 0, AnchorSide.FRONT),
				end(Heading.SOUTH_EAST, 0, AnchorSide.BACK)));

		Ride landed = land(index, RailSide.LEFT, 10, new Vec3(0.5, 0, 0));

		double kept = 0.5 * Math.sqrt(0.5);
		assertEquals(0.353553, landed.speed(), 1e-6);
		assertVec(new Vec3(kept * Math.sqrt(0.5), 0, kept * Math.sqrt(0.5)), landed.velocity(), EXACT);
	}

	/**
	 * Landing at 0.5 west on a rail running east, the rider goes on west, back towards the rail's start: with no keys,
	 * 0.997 · 0.5 - 0.0005 = 0.498 the next tick.
	 */
	@Test
	void theWayALandingRiderGoesIsTheWayTheirVelocityPointsAlongTheRail() {
		SegmentIndex index = lay(straight(30));
		Ride landed = land(index, RailSide.LEFT, 10, new Vec3(-0.5, 0, 0));
		Vec3 at = landed.position();

		assertVec(new Vec3(-0.5, 0, 0), landed.velocity(), EXACT);
		landed.tick(index, controls(NO_KEYS, EAST), point -> false);
		assertVec(at.add(-0.498, 0, 0), landed.position(), EXACT);
	}

	/**
	 * Landing at 0.5 a quarter of a block from an end, the rider lands at the most braking lets stop there: 2/15, for
	 * at 0.05 a tick, 2/15, 1/12 and 1/30 cover the 0.25 exactly. The cut costs nothing, and on to the end no tick
	 * loses enough to hurt.
	 */
	@Test
	void landingNearAnEndLandsAtTheSpeedBrakingStopsAtIt() {
		SegmentIndex index = lay(straight(10));

		Ride landed = land(index, RailSide.RIGHT, 9.75, new Vec3(0.5, 0, 0));

		assertEquals(2 / 15.0, landed.speed(), EXACT);
		for (int tick = 1; tick <= 4; tick++) {
			Ride.Motion motion = landed.tick(index, controls(NO_KEYS, EAST), point -> false);
			assertEquals(0, Ride.impactDamage(motion.lost()), "the damage at tick " + tick);
		}
		assertEquals(10.5, landed.position().x, EXACT, "where the rider stopped");
	}

	/**
	 * A rider 4 along the right rail of a straight east, its line at (4.5, 64.9375, 1.5), looks straight up from their
	 * eyes, 2.5 - 1.62 - 1/16 = 0.8175 below it. The ray passes through their own rail and on to the left rail of a
	 * track 2 higher and 2 further south, whose line is 2 above theirs: that is the rail they aim at.
	 */
	@Test
	void aRayThroughTheRidersOwnRailOverheadAimsAtTheRailBehindIt() {
		SegmentIndex index = lay(straight(10));
		BlockPos higher = START.offset(0, 2, 2);
		index.put(higher, List.of(straight(10)));
		index.put(higher.east(10), List.of(straight(10).reversed()));
		RailPoint riding = new RailPoint(START, AnchorSide.FRONT, RailSide.RIGHT, 4);
		Vec3 eye = new Vec3(4.5, 64.9375 - 0.8175, 1.5);

		RailAim.Hit hit = aimAlongTheLine(index, riding, eye, new Vec3(0, 1, 0));

		assertNotNull(hit, "no hit");
		assertRailPoint(new RailPoint(higher, AnchorSide.FRONT, RailSide.LEFT, 4), hit.railPoint());
		assertEquals(2.8175, hit.reach(), 1e-6);
	}

	/** The other rail of the rider's own track is no part of their line: they aim at it as at any other. */
	@Test
	void theOtherRailOfTheRidersTrackIsATarget() {
		SegmentIndex index = lay(straight(10));
		RailPoint riding = new RailPoint(START, AnchorSide.FRONT, RailSide.RIGHT, 4);

		RailAim.Hit hit = aimAlongTheLine(index, riding, new Vec3(4.5, 66, -0.5), new Vec3(0, -1, 0));

		assertNotNull(hit, "no hit");
		assertRailPoint(new RailPoint(START, AnchorSide.FRONT, RailSide.LEFT, 4), hit.railPoint());
	}

	/**
	 * East 10, then a quarter turn to the left, radius 8, to the north, which the index holds from its far anchor, the
	 * other way round from the ride. A rider 5 along the right rail of the straight reaches the turn's south rail 5
	 * blocks on, across the anchor: aiming straight down at it a block into the turn hits nothing, while the turn's
	 * north rail is a target. The 10 north beyond begins 5 + 9 π / 2 = 19.1 blocks on along the rider's rail, more than
	 * 8: there it is a target again.
	 */
	@Test
	void theRidersRailAcrossAnAnchorOntoASegmentHeldTheOtherWayRoundIsNoTarget() {
		SegmentIndex index = lay(straight(10), leftTurn(8), northward(10));
		RailPoint riding = new RailPoint(START, AnchorSide.FRONT, RailSide.RIGHT, 5);
		// The turn runs round (10.5, _, -7.5): its south rail with radius 9, its north one with radius 7.
		double angle = 1 / 9.0;
		Vec3 south = new Vec3(10.5 + 9 * Math.sin(angle), 64.9375, -7.5 + 9 * Math.cos(angle));
		Vec3 north = new Vec3(10.5 + 7 * Math.sin(angle), 64.9375, -7.5 + 7 * Math.cos(angle));
		Vec3 down = new Vec3(0, -1, 0);

		assertNull(aimAlongTheLine(index, riding, south.add(0, 1, 0), down), "the rider aims at their own rail");
		assertNotNull(aimAlongTheLine(index, riding, north.add(0, 1, 0), down), "the turn's other rail");
		// The east rail of the northward straight, from (19.5, _, -7.5), 2 blocks along it.
		assertNotNull(aimAlongTheLine(index, riding, new Vec3(19.5, 66, -9.5), down), "the rail beyond 8 blocks");
	}

	/**
	 * Two straights east: a rider 3 along the right rail of the second reaches the first's right rail 3 blocks back,
	 * across the anchor between them, and so doesn't aim at it.
	 */
	@Test
	void theRidersRailBehindThemIsNoTarget() {
		SegmentIndex index = lay(straight(10), straight(10));
		RailPoint riding = new RailPoint(START.east(10), AnchorSide.FRONT, RailSide.RIGHT, 3);

		assertNull(aimAlongTheLine(index, riding, new Vec3(5.5, 66, 1.5), new Vec3(0, -1, 0)), "the rail behind");
		assertNotNull(RailAim.aim(index, new Vec3(5.5, 66, 1.5), new Vec3(5.5, 58, 1.5), RailAim.HELD_RADIUS),
				"the rail behind, aimed at by anyone else");
	}

	/**
	 * Straights east of 10, 4 and 10: a rider 5 along the first reaches the second 5 blocks on and the third 9 blocks
	 * on, more than 8. The second's right rail is on their line, the third's is a target.
	 */
	@Test
	void theRidersLineEndsEightBlocksAlongTheTrack() {
		SegmentIndex index = lay(straight(10), straight(4), straight(10));
		RailPoint riding = new RailPoint(START, AnchorSide.FRONT, RailSide.RIGHT, 5);
		Vec3 down = new Vec3(0, -1, 0);

		assertNull(aimAlongTheLine(index, riding, new Vec3(12.5, 66, 1.5), down), "the second straight's rail");
		assertNotNull(aimAlongTheLine(index, riding, new Vec3(16.5, 66, 1.5), down), "the third straight's rail");
	}

	private static void assertRailPoint(RailPoint expected, RailPoint actual) {
		assertEquals(expected, new RailPoint(actual.anchor(), actual.side(), actual.rail(), expected.distance()),
				"the rail of " + actual);
		assertEquals(expected.distance(), actual.distance(), 1e-6, "the distance along the rail");
	}

	/** What a rider at a point aims at along a ray 8 long, their own line skipped. */
	private static RailAim.@Nullable Hit aimAlongTheLine(SegmentIndex index, RailPoint riding, Vec3 from,
			Vec3 direction) {
		return RailAim.aim(index, from, from.add(direction.scale(8)), RailAim.HELD_RADIUS,
				Ride.line(index, riding, 8));
	}

	/** A rider attached to the first segment of a track, as far along one of its rails, landed from a jump. */
	private static Ride land(SegmentIndex index, RailSide rail, double distance, Vec3 velocity) {
		Ride ride = Ride.attach(index, new RailPoint(START, AnchorSide.FRONT, rail, distance));
		assertNotNull(ride, "no rail to land on");
		ride.land(index, velocity);
		return ride;
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

	/** A rider attached to the first segment of a track, as far along one of its rails from {@link #START}. */
	private static Rider attach(SegmentIndex index, RailSide rail, double distance) {
		Ride ride = Ride.attach(index, new RailPoint(START, AnchorSide.FRONT, rail, distance));
		assertNotNull(ride, "no rail to attach to");
		return new Rider(ride, index);
	}

	/** A rider on the right rail of a track east, 5 blocks along, brought to 0.5 east by 11 ticks of W. */
	private static Rider atTopSpeed(SegmentIndex index) {
		Rider rider = attach(index, RailSide.RIGHT, 5);
		rider.hold(W);
		for (int tick = 1; tick <= 11; tick++) {
			rider.tick();
		}
		assertEquals(0.5, rider.speed(), EXACT, "the speed W brought the rider to");
		return rider;
	}

	/** A ride on a track with nothing in the rider's way, by the keys the rider holds and where they look. */
	private static final class Rider {
		private final Ride ride;
		private final SegmentIndex index;
		private Input keys = NO_KEYS;
		private Vec3 look = EAST;
		private boolean fed = true;

		Rider(Ride ride, SegmentIndex index) {
			this.ride = ride;
			this.index = index;
		}

		void hold(Input keys) {
			this.keys = keys;
		}

		/** Leaves the rider without the food to sprint. */
		void hungry() {
			fed = false;
		}

		/** Turns the rider's view to look along a horizontal unit vector. */
		void look(Vec3 look) {
			this.look = look;
		}

		Ride.Motion tick() {
			Ride.Controls controls = controls(keys, look);
			return ride.tick(index, new Ride.Controls(controls.keys(), controls.moveIntent(), fed), point -> false);
		}

		Ride ride() {
			return ride;
		}

		double speed() {
			return ride.speed();
		}

		Vec3 position() {
			return ride.position();
		}
	}

	/** A straight 45° slope up to the east, as long east as up. */
	private static Segment slope(int length) {
		return new Segment(new Vec3i(length, length, 0), end(Heading.EAST, 45, AnchorSide.FRONT),
				end(Heading.EAST, 45, AnchorSide.BACK));
	}

	/** A straight 45° slope down to the east, as long east as down. */
	private static Segment descent(int length) {
		return new Segment(new Vec3i(length, -length, 0), end(Heading.EAST, -45, AnchorSide.FRONT),
				end(Heading.EAST, -45, AnchorSide.BACK));
	}

	/** A bend from 45° down to 45° up, of a length east. */
	private static Segment dip(int length) {
		return new Segment(new Vec3i(length, 0, 0), end(Heading.EAST, -45, AnchorSide.FRONT),
				end(Heading.EAST, 45, AnchorSide.BACK));
	}

	/** A bend from level to 45° up, 5 east and 2 up. */
	private static Segment upward() {
		return new Segment(new Vec3i(5, 2, 0), end(Heading.EAST, 0, AnchorSide.FRONT),
				end(Heading.EAST, 45, AnchorSide.BACK));
	}

	/** A straight slope up to the east, so far east and up, pitched along its chord. */
	private static Segment gentle(int east, int up) {
		float pitch = (float) Math.toDegrees(Math.atan2(up, east));
		return new Segment(new Vec3i(east, up, 0), end(Heading.EAST, pitch, AnchorSide.FRONT),
				end(Heading.EAST, pitch, AnchorSide.BACK));
	}

	/** A bend from 45° down to level, 12 east and 5 down. */
	private static Segment levelling() {
		return new Segment(new Vec3i(12, -5, 0), end(Heading.EAST, -45, AnchorSide.FRONT),
				end(Heading.EAST, 0, AnchorSide.BACK));
	}

	/** 40 down at 45°, a dip of 8, and 40 up at 45°. */
	private static Segment[] valley() {
		return new Segment[]{descent(40), dip(8), slope(40)};
	}

	/** A level straight east of a length. */
	private static Segment straight(int length) {
		return new Segment(new Vec3i(length, 0, 0), end(Heading.EAST, 0, AnchorSide.FRONT),
				end(Heading.EAST, 0, AnchorSide.BACK));
	}

	/** A level quarter turn from east to south, of the radius between its anchors. */
	private static Segment rightTurn(int radius) {
		return new Segment(new Vec3i(radius, 0, radius), end(Heading.EAST, 0, AnchorSide.FRONT),
				end(Heading.SOUTH, 0, AnchorSide.BACK));
	}

	/** A level quarter turn from east to north, of the radius between its anchors. */
	private static Segment leftTurn(int radius) {
		return new Segment(new Vec3i(radius, 0, -radius), end(Heading.EAST, 0, AnchorSide.FRONT),
				end(Heading.NORTH, 0, AnchorSide.BACK));
	}

	/** A level quarter turn from north to west, of the radius between its anchors. */
	private static Segment northToWest(int radius) {
		return new Segment(new Vec3i(-radius, 0, -radius), end(Heading.NORTH, 0, AnchorSide.FRONT),
				end(Heading.WEST, 0, AnchorSide.BACK));
	}

	private static Segment southward(int length) {
		return new Segment(new Vec3i(0, 0, length), end(Heading.SOUTH, 0, AnchorSide.FRONT),
				end(Heading.SOUTH, 0, AnchorSide.BACK));
	}

	private static Segment northward(int length) {
		return new Segment(new Vec3i(0, 0, -length), end(Heading.NORTH, 0, AnchorSide.FRONT),
				end(Heading.NORTH, 0, AnchorSide.BACK));
	}

	private static Segment westward(int length) {
		return new Segment(new Vec3i(-length, 0, 0), end(Heading.WEST, 0, AnchorSide.FRONT),
				end(Heading.WEST, 0, AnchorSide.BACK));
	}

	/**
	 * An index holding a chain of segments laid from {@link #START}, each leaving the far anchor of the one before, as
	 * their anchors would hold them: each anchor every segment that ends at it.
	 */
	private static SegmentIndex lay(Segment... chain) {
		Map<BlockPos, List<Segment>> held = new HashMap<>();
		BlockPos anchor = START;
		for (Segment segment : chain) {
			BlockPos partner = anchor.offset(segment.offset());
			held.computeIfAbsent(anchor, ignored -> new ArrayList<>()).add(segment);
			held.computeIfAbsent(partner, ignored -> new ArrayList<>()).add(segment.reversed());
			anchor = partner;
		}
		SegmentIndex index = new SegmentIndex();
		held.forEach(index::put);
		return index;
	}
}
