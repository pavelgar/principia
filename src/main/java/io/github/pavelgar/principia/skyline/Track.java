package io.github.pavelgar.principia.skyline;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.level.Level;

/**
 * The Sky-Line track of one level, on a server or a client: the index of its loaded anchors' segments and, on a
 * server, the anchors loaded in it this tick, whose segments are checked against their partners as it ends.
 */
public final class Track {
	private final SegmentIndex index = new SegmentIndex();
	private final List<AnchorBlockEntity> loadedThisTick = new ArrayList<>();

	/** The track of a level. */
	public static Track of(Level level) {
		return SkyLineHooks.track(level);
	}

	/** The segments of the loaded anchors, for what asks about rails at a place. */
	public SegmentIndex index() {
		return index;
	}

	void checkAtTickEnd(AnchorBlockEntity anchor) {
		loadedThisTick.add(anchor);
	}

	/** Called as each server tick of the level ends. */
	void tickEnded() {
		if (!loadedThisTick.isEmpty()) {
			// Checking can make a partner's entity, which then waits for the next tick's end.
			List<AnchorBlockEntity> loaded = List.copyOf(loadedThisTick);
			loadedThisTick.clear();
			for (AnchorBlockEntity anchor : loaded) {
				anchor.checkPartners();
			}
		}
	}
}
