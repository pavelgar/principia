package io.github.pavelgar.principia.skyline;

/**
 * The server's count of a player mining a rail, as vanilla's {@code ServerPlayerGameMode} counts mining a block: the
 * server ticks since the tick they started on, that one included, times how much of the anchor block they mine a tick
 * now. A client's break is taken at once from {@link #ON_REQUEST} of the mining on, for lag; an earlier one is delayed,
 * and taken on the first server tick the count reaches {@link #DELAYED}.
 *
 * @param startTick the server tick the player started mining on
 */
record MiningTime(int startTick) {
	/** How much of its mining a break the client asks for must have, by the server's count: vanilla's 0.7. */
	static final float ON_REQUEST = 0.7F;
	/** How much of its mining a delayed break must have to be taken. */
	static final float DELAYED = 1;

	/** How much of the mining is done on a server tick, at a speed of so much a tick. */
	float progress(float perTick, int now) {
		return perTick * (now - startTick + 1);
	}

	/** Whether a break the client asks for on a server tick is taken then. */
	boolean breaksOnRequest(float perTick, int now) {
		return progress(perTick, now) >= ON_REQUEST;
	}

	/** Whether a delayed break is taken on a server tick. */
	boolean delayedBreaks(float perTick, int now) {
		return progress(perTick, now) >= DELAYED;
	}
}
