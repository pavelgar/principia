package io.github.pavelgar.principia.skyline;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * A rail point a client sends of the rail its player aims at, as the server checks it before acting on it, for every
 * use of a rail (attaching a Sky-Hook, breaking a rail): the segment is still in the server's track, the player isn't
 * a spectator, the distance along the rail is a number, and the point is within reach of their eyes. It doesn't check where they look, as vanilla doesn't for
 * blocks.
 *
 * @param segment the segment as the anchor the point names stores it
 * @param point the point of the rail's line, worked out from the rail point on the server's own track
 */
public record RailTarget(Segment segment, Vec3 point) {
	/** How much further than its reach the server lets a target be, for lag: vanilla's buffer for blocks. */
	private static final double REACH_BUFFER = 1;

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
		if (point.distanceTo(player.getEyePosition()) > reach + REACH_BUFFER) {
			return null;
		}
		return new RailTarget(segment, point);
	}
}
