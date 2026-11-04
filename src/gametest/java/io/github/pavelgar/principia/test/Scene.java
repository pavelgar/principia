package io.github.pavelgar.principia.test;

import java.util.List;
import java.util.Locale;

import io.github.pavelgar.principia.skyline.Heading;

import net.minecraft.core.BlockPos;

/**
 * A scene for a playtest, built beside the player by {@code /playtest <scene>} or by {@code scripts/play.sh <scene>} as
 * they join: track laid as a player could lay it, ready to try. Each is built from the player's feet, its first anchor
 * {@link #ORIGIN} from them, and puts the player where the playtest starts. Add one for each change a player should
 * try, and name it in the PR's Try it.
 */
enum Scene {
	/**
	 * Five lines east, each starting 32 blocks south of the one before, anchors 3 above the feet: a quarter turn left
	 * with its anchors 4 apart (the tightest), the same 8 apart, two 45° turns left, a 45° turn left then right (an
	 * S-bend), and a hill up 4 over 12 between level anchors. Each has 16 of straight before and after.
	 */
	LINES("five lines east: a tight and a wide turn, two 45° turns, an S-bend and a hill", -2, -3, 0, -90) {
		@Override
		List<Line> lines(BlockPos origin) {
			return List.of(
					Line.from(origin, Heading.EAST).to(16, 0, 0, Heading.EAST).to(4, 0, -4, Heading.NORTH)
							.to(0, 0, -16, Heading.NORTH),
					Line.from(origin.south(32), Heading.EAST).to(16, 0, 0, Heading.EAST).to(8, 0, -8, Heading.NORTH)
							.to(0, 0, -16, Heading.NORTH),
					Line.from(origin.south(64), Heading.EAST).to(16, 0, 0, Heading.EAST)
							.to(5, 0, -2, Heading.NORTH_EAST).to(2, 0, -5, Heading.NORTH).to(0, 0, -16, Heading.NORTH),
					Line.from(origin.south(96), Heading.EAST).to(16, 0, 0, Heading.EAST)
							.to(5, 0, -2, Heading.NORTH_EAST).to(5, 0, -2, Heading.EAST).to(16, 0, 0, Heading.EAST),
					Line.from(origin.south(128), Heading.EAST).to(16, 0, 0, Heading.EAST).to(12, 4, 0, Heading.EAST)
							.to(16, 0, 0, Heading.EAST));
		}
	},
	/**
	 * Three straights east, 32 long, to jump between while riding: two level, 6 apart, and a third 3 higher and 6
	 * further south. All are within the Sky-Hook's jump reach of the next.
	 */
	JUMPS("three straights to jump between: two level, 6 apart, and one 3 higher", -2, -3, 0, -90) {
		@Override
		List<Line> lines(BlockPos origin) {
			return List.of(Line.from(origin, Heading.EAST).to(32, 0, 0, Heading.EAST),
					Line.from(origin.south(6), Heading.EAST).to(32, 0, 0, Heading.EAST),
					Line.from(origin.offset(0, 3, 12), Heading.EAST).to(32, 0, 0, Heading.EAST));
		}
	},
	/**
	 * A long coast downhill into turns: south down 6 over 22 and 10 over 22 between level anchors, a gentle S-bend
	 * 3 east over 15, a quarter turn east beginning at an anchor, radius 8, and 12 east to the end. The first anchor is
	 * 19 above the feet, the last 3; the player starts flying beside the first.
	 */
	DESCENT("a long coast downhill into an S-bend and a turn that begins at an anchor", -2, -2, -2, 0) {
		@Override
		BlockPos origin(BlockPos feet) {
			return feet.above(19);
		}

		@Override
		List<Line> lines(BlockPos origin) {
			return List.of(Line.from(origin, Heading.SOUTH).to(0, -6, 22, Heading.SOUTH).to(0, -10, 22, Heading.SOUTH)
					.to(3, 0, 15, Heading.SOUTH).to(8, 0, 8, Heading.EAST).to(12, 0, 0, Heading.EAST));
		}
	};

	/** Where a scene's first anchor is from the player's feet, unless the scene says otherwise. */
	private static final int ORIGIN = 3;

	final String about;
	/** Where the player starts, in blocks from the first anchor's cell, standing in the middle of it. */
	final int startX;
	final int startY;
	final int startZ;
	/** The way the player looks at the start, as an entity's yaw: south 0°, east -90°. */
	final float yaw;

	Scene(String about, int startX, int startY, int startZ, float yaw) {
		this.about = about;
		this.startX = startX;
		this.startY = startY;
		this.startZ = startZ;
		this.yaw = yaw;
	}

	/** The scene's lines, from its first anchor. */
	abstract List<Line> lines(BlockPos origin);

	/** The scene's first anchor, from the player's feet. */
	BlockPos origin(BlockPos feet) {
		return feet.above(ORIGIN);
	}

	/** The name {@code /playtest} and {@code scripts/play.sh} know the scene by. */
	String id() {
		return name().toLowerCase(Locale.ROOT);
	}
}
