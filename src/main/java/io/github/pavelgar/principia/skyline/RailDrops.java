package io.github.pavelgar.principia.skyline;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/**
 * The rails track is laid with: a segment costs one per block of its centre line's length, rounded up, and when it
 * breaks they fall from evenly along its centre line, one from the middle of each of its stretches, as Create spreads
 * a broken curve's items along it. An anchor costs one more.
 */
public final class RailDrops {
	/** Room for rounding, so that a straight exactly 10 blocks long costs 10. */
	private static final double ROUNDING = 1e-6;
	/** How far a rail falls from beside its point, either way along x and z: as far as vanilla pops a block's drops. */
	private static final double SPREAD = 0.25;

	private RailDrops() {
	}

	/** The rails a segment's own length costs to lay, and drops when it breaks. */
	public static int cost(Segment segment) {
		return cost(segment.curve(BlockPos.ZERO).length());
	}

	/** The rails laying a segment takes: its length's, and one for the anchor at its far end if that is new. */
	public static int cost(Segment segment, boolean toNewAnchor) {
		return cost(segment) + (toNewAnchor ? 1 : 0);
	}

	private static int cost(double length) {
		return (int) Math.ceil(length - ROUNDING);
	}

	/** The points of a segment's centre line its rails fall from, with this anchor at a position. */
	static List<Vec3> points(BlockPos anchor, Segment segment) {
		SegmentCurve curve = segment.curve(anchor);
		double length = curve.length();
		int count = cost(length);
		List<Vec3> points = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			points.add(curve.point(length * (i + 0.5) / count));
		}
		return points;
	}

	/**
	 * Drops a segment's rails along it, each a little to the side of its point, unless the level's rules keep blocks
	 * from dropping anything.
	 */
	static void drop(ServerLevel level, BlockPos anchor, Segment segment) {
		drop(level, points(anchor, segment));
	}

	/** Drops an anchor's own rail, as {@link #drop(ServerLevel, BlockPos, Segment)} drops a segment's, from its point. */
	static void drop(ServerLevel level, BlockPos anchor) {
		drop(level, List.of(SegmentCurve.anchorPoint(anchor)));
	}

	private static void drop(ServerLevel level, List<Vec3> points) {
		if (!level.getGameRules().get(GameRules.BLOCK_DROPS)) {
			return;
		}
		RandomSource random = level.getRandom();
		double halfHeight = EntityTypes.ITEM.getHeight() / 2.0;
		for (Vec3 point : points) {
			ItemEntity rail = new ItemEntity(level, point.x + Mth.nextDouble(random, -SPREAD, SPREAD),
					point.y - halfHeight, point.z + Mth.nextDouble(random, -SPREAD, SPREAD),
					new ItemStack(SkyLine.RAIL));
			rail.setDefaultPickUpDelay();
			level.addFreshEntity(rail);
		}
	}
}
