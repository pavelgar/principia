package io.github.pavelgar.principia.content;

import net.minecraft.server.level.ServerPlayer;

/**
 * The checks every action a client asks the server for passes before its feature acts on it, as vanilla's own packets
 * for using and breaking blocks do: the player is alive, their client has loaded the level, and they aren't between a
 * teleport and their client confirming it. A feature's own checks (reach, sight, protection) come after, in the
 * feature.
 *
 * <p>Every serverbound payload handler of the mod starts with {@code if (!ClientActions.accept(player)) return;}.
 */
public final class ClientActions {
	private ClientActions() {
	}

	/** Whether the server acts on what the player's client asks for now. */
	public static boolean mayAct(ServerPlayer player) {
		return player.isAlive() && player.connection.hasClientLoaded()
				&& player.connection.awaitingPositionFromClient == null && !player.isChangingDimension();
	}

	/**
	 * {@link #mayAct}, and if so counts the request as the player's activity, as vanilla does for using a block, so
	 * they aren't kicked for idling while they build.
	 */
	public static boolean accept(ServerPlayer player) {
		if (!mayAct(player)) {
			return false;
		}
		player.resetLastActionTime();
		return true;
	}
}
