package io.github.pavelgar.principia.skyline;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A rail point a client sends of the rail its player aims at, as the server checks it before any use of a rail
 * (attaching a Sky-Hook, jumping to a rail, breaking a rail): the segment is still in the server's track, the player
 * isn't a spectator, the distance is a number, and the point is within reach of their eyes with no block between.
 * Vanilla checks no line of sight for blocks, but using a rail can move the player to it, through a wall without this.
 * Where they look isn't checked, as vanilla doesn't.
 *
 * @param segment the segment as the anchor the point names stores it
 * @param point the point of the rail's line, worked out from the rail point on the server's own track
 */
public record RailTarget(Segment segment, Vec3 point) {
	/** How much further than its reach the server lets a target be, for lag: vanilla's buffer for blocks. */
	private static final double REACH_BUFFER = 1;
	/**
	 * How far short of the rail point a block may stop the line from the eyes: twice the client's aim radius, as the
	 * client's look passes up to {@link RailAim#HELD_RADIUS} beside the point.
	 */
	private static final double SIGHT_SLACK = 2 * RailAim.HELD_RADIUS;

	/** The rail point a player's client sends, if it passes the checks, as far as the action can reach. */
	static @Nullable RailTarget check(ServerPlayer player, RailPoint target, double reach) {
		// A distance that isn't a number would read as the rail's start here and stay one along the ride: refused, as
		// vanilla refuses a move to a position that isn't one.
		if (player.isSpectator() || Double.isNaN(target.distance())) {
			return null;
		}
		SegmentIndex.Held held = Track.of(player.level()).index().at(target.anchor(), target.side());
		if (held == null) {
			return null;
		}
		Segment segment = held.anchor().equals(target.anchor()) ? held.segment() : held.segment().reversed();
		Vec3 point = segment.curve(target.anchor()).rail(target.rail()).point(target.distance());
		Vec3 eye = player.getEyePosition();
		if (point.distanceTo(eye) > reach + REACH_BUFFER) {
			return null;
		}
		// From the eyes now or a tick ago, as a moving client aims between the two; a tick ago only within reach, so
		// the line can't cross chunks the level would have to load.
		Vec3 before = player.getEyePosition(0);
		if (!inSight(player, eye, point)
				&& (point.distanceTo(before) > reach + REACH_BUFFER || !inSight(player, before, point))) {
			return null;
		}
		return new RailTarget(segment, point);
	}

	/** Whether no block's outline is on the line from the eyes to the point, short of {@link #SIGHT_SLACK}. */
	private static boolean inSight(ServerPlayer player, Vec3 eye, Vec3 point) {
		BlockHitResult hit = player.level()
				.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		return hit.getType() == HitResult.Type.MISS
				|| hit.getLocation().distanceTo(eye) >= eye.distanceTo(point) - SIGHT_SLACK;
	}
}
