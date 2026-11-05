package io.github.pavelgar.principia.skyline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The server's count of a player mining a rail, at a tenth of the anchor a tick: ten ticks to mine it whole. */
class MiningTimeTest {
	private static final float TENTH = 0.1F;
	private static final MiningTime FROM_100 = new MiningTime(100);

	/** The tick mining starts on counts: a tenth is mined on it, and the whole on the tenth tick, tick 109. */
	@Test
	void theStartsTickCounts() {
		assertEquals(TENTH, FROM_100.progress(TENTH, 100), 1e-6);
		assertEquals(1, FROM_100.progress(TENTH, 109), 1e-6);
	}

	/** A break the client asks for is taken from 0.7 of the mining on, the seventh tick, and not a tick before. */
	@Test
	void aBreakAskedForIsTakenFromSevenTenthsOn() {
		assertFalse(FROM_100.breaksOnRequest(TENTH, 105));
		assertTrue(FROM_100.breaksOnRequest(TENTH, 106));
	}

	/** A delayed break is taken once the whole mining is done, the tenth tick, and not a tick before. */
	@Test
	void aDelayedBreakIsTakenOnceTheWholeMiningIsDone() {
		assertFalse(FROM_100.delayedBreaks(TENTH, 108));
		assertTrue(FROM_100.delayedBreaks(TENTH, 109));
	}

	/** The count is at the speed the player mines at now: mining twice as fast from the start halves it. */
	@Test
	void theCountIsAtTheSpeedOfNow() {
		assertFalse(FROM_100.delayedBreaks(TENTH, 103));
		assertTrue(FROM_100.delayedBreaks(2 * TENTH, 104));
	}
}
