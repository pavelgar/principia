package io.github.pavelgar.principia.skyline;

import static io.github.pavelgar.principia.skyline.SegmentCurveTest.assertVec;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

/**
 * A jump's flight, by the path in {@link Flight}'s javadoc: p(s) = A + s (B - A) + (0, 4 h s (1 - s), 0), h = d / 8,
 * over max(2, ⌈d / 0.8⌉) ticks, the hook at p(k / n) after k of them.
 */
class FlightTest {
	private static final double EXACT = 1e-9;
	private static final Vec3 A = new Vec3(0.5, 64.9375, 1.5);
	/** 8 east of {@link #A}, level: an 8-block jump. */
	private static final Vec3 EIGHT_EAST = A.add(8, 0, 0);
	/** 2 north of {@link #A}: the hop from a track's right rail to its left one. */
	private static final Vec3 HOP = A.add(0, 0, -2);
	private static final Vec3 TAKE_OFF = new Vec3(0.5, 0, 0);

	@Test
	void aFlightStartsAtTheTakeOffPointAndEndsAtTheLanding() {
		Flight flight = new Flight(A, EIGHT_EAST, TAKE_OFF);

		assertVec(A, flight.at(0), EXACT);
		assertVec(EIGHT_EAST, flight.at(flight.ticks()), EXACT);
	}

	/** Halfway through an 8-block jump, 5 of its 10 ticks, the hook is over the chord's midpoint by 8 / 8 = 1. */
	@Test
	void halfwayTheLiftIsAnEighthOfTheDistance() {
		Flight flight = new Flight(A, EIGHT_EAST, TAKE_OFF);

		assertVec(A.add(4, 1, 0), flight.at(5), EXACT);
	}

	/**
	 * A 2-block hop takes 3 ticks; after the first, a third of the way, the hook is a third of the way along the chord
	 * and lifted 4 · 0.25 · (1/3) · (2/3) = 2/9 ≈ 0.222.
	 */
	@Test
	void aThirdOfTheWayThroughAHopTheLiftIsTwoNinths() {
		Flight flight = new Flight(A, HOP, TAKE_OFF);

		assertVec(A.add(0, 2 / 9.0, -2 / 3.0), flight.at(1), EXACT);
	}

	/** A steady 0.8 a tick: ⌈8 / 0.8⌉ = 10 ticks, ⌈2 / 0.8⌉ = 3, and never fewer than 2, though 0.5 needs only 1. */
	@Test
	void aFlightTakesATickPerFourFifthsOfABlockAndAtLeastTwo() {
		assertEquals(10, new Flight(A, EIGHT_EAST, TAKE_OFF).ticks(), "8 blocks");
		assertEquals(3, new Flight(A, HOP, TAKE_OFF).ticks(), "2 blocks");
		assertEquals(2, new Flight(A, A.add(0, 0.3, 0.4), TAKE_OFF).ticks(), "0.5 blocks");
	}

	/**
	 * The hook's velocity after k ticks is its last move, p(k / n) - p((k - 1) / n): on the 2-block hop, (0, 2/9, -2/3)
	 * after the first tick, and (0, -2/9, -2/3) after the last, from 2/9 up two thirds of the way north down to the
	 * rail. Before the first, it is still the velocity it took off with.
	 */
	@Test
	void theVelocityIsTheHooksLastMove() {
		Flight flight = new Flight(A, HOP, TAKE_OFF);

		assertVec(TAKE_OFF, flight.velocity(0), EXACT);
		assertVec(new Vec3(0, 2 / 9.0, -2 / 3.0), flight.velocity(1), EXACT);
		assertVec(new Vec3(0, -2 / 9.0, -2 / 3.0), flight.velocity(3), EXACT);
	}
}
