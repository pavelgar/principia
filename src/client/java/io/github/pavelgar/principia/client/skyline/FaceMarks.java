package io.github.pavelgar.principia.client.skyline;

import java.util.ArrayList;
import java.util.List;

import io.github.pavelgar.principia.skyline.Placement;
import io.github.pavelgar.principia.skyline.TrackStart;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

/**
 * Marks on the block faces around the one a player aims at to lay track to a new anchor, showing which spots nearby
 * would work: a translucent square, green where a click would lay, red where it wouldn't, as Create marks the ground
 * around its track ends. Each is checked as a click on that face there would be.
 */
final class FaceMarks {
	/** How far the marks reach from the block aimed at, either way along the face: 5 × 5 blocks. */
	private static final int REACH = 2;
	/** Half a mark's side, 6 px, and how far it stands off its face, against z-fighting. */
	private static final double HALF_SIDE = 6 / 16.0;
	private static final double OFF_FACE = 0.01;
	/** How opaque a mark is: half. */
	private static final float OPACITY = 0.5F;

	/** What the marks were worked out for: they are again only when it changes. */
	private static @Nullable Key key;
	private static List<Mark> marks = List.of();

	private FaceMarks() {
	}

	private record Key(BlockPos pos, Direction face, TrackStart start) {
	}

	/** A spot nearby, and whether a click there would lay. */
	private record Mark(BlockHitResult click, boolean lays) {
	}

	/**
	 * The clicks the marks stand for: on the same face of each block in the 5 × 5 around the one aimed at, in the plane
	 * of its face, each at the face's middle.
	 */
	static List<BlockHitResult> clicks(BlockHitResult target) {
		Direction face = target.getDirection();
		List<BlockHitResult> clicks = new ArrayList<>((2 * REACH + 1) * (2 * REACH + 1));
		for (int u = -REACH; u <= REACH; u++) {
			for (int v = -REACH; v <= REACH; v++) {
				BlockPos pos = switch (face.getAxis()) {
					case X -> target.getBlockPos().offset(0, u, v);
					case Y -> target.getBlockPos().offset(u, 0, v);
					case Z -> target.getBlockPos().offset(u, v, 0);
				};
				clicks.add(new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5), face, pos, false));
			}
		}
		return clicks;
	}

	/**
	 * Called every tick while an anchor is selected: works the marks out again if the player aims at a new anchor's
	 * spot on another face or has selected another anchor, and clears them while the player aims at no new anchor's
	 * spot. Blocks with nothing to click, such as air, get no mark. Each shows what a plain click there would do: a
	 * heading chosen with the wheel holds only at the spot aimed at.
	 */
	static void update(Player player, InteractionHand hand, TrackStart start, @Nullable BlockHitResult target,
			Placement.Plan plan) {
		if (target == null || !(plan instanceof Placement.Plan.Laying laying) || !laying.toNewAnchor()) {
			clear();
			return;
		}
		Key now = new Key(target.getBlockPos(), target.getDirection(), start);
		if (now.equals(key)) {
			return;
		}
		key = now;
		Level level = player.level();
		List<Mark> found = new ArrayList<>();
		for (BlockHitResult click : clicks(target)) {
			if (!level.getBlockState(click.getBlockPos()).getShape(level, click.getBlockPos()).isEmpty()) {
				found.add(new Mark(click,
						Placement.plan(player, hand, start, click) instanceof Placement.Plan.Laying there
								&& there.why() == null));
			}
		}
		marks = found;
	}

	static void clear() {
		key = null;
		marks = List.of();
	}

	/** Draws each mark: a translucent square on its face, round the face's middle, seen from either side. */
	static void submit(LevelRenderContext context, Vec3 camera) {
		List<Mark> shown = marks;
		if (shown.isEmpty()) {
			return;
		}
		context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.debugQuads(),
				(pose, buffer) -> {
					for (Mark mark : shown) {
						Direction face = mark.click().getDirection();
						Vec3 middle = mark.click().getLocation().relative(face, OFF_FACE).subtract(camera);
						// Two steps along the face, square to each other.
						Vec3 u = face.getAxis() == Direction.Axis.X
								? new Vec3(0, HALF_SIDE, 0)
								: new Vec3(HALF_SIDE, 0, 0);
						Vec3 v = face.getAxis() == Direction.Axis.Z
								? new Vec3(0, HALF_SIDE, 0)
								: new Vec3(0, 0, HALF_SIDE);
						int color = ARGB.multiplyAlpha(mark.lays() ? TrackLaying.CAN : TrackLaying.CANT, OPACITY);
						for (Vec3 corner : new Vec3[]{middle.add(u).add(v), middle.subtract(u).add(v),
								middle.subtract(u).subtract(v), middle.add(u).subtract(v)}) {
							buffer.addVertex(pose, (float) corner.x, (float) corner.y, (float) corner.z)
									.setColor(color);
						}
					}
				});
	}
}
