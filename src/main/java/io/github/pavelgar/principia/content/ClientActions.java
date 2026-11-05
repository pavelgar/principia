package io.github.pavelgar.principia.content;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * The checks every action a client asks the server for passes before its feature acts on it, as vanilla's own packets
 * for using and breaking blocks do: the player is alive, their client has loaded the level, and they aren't between a
 * teleport and their client confirming it. A feature's own checks (reach, sight, protection) come after, in the
 * feature. Every serverbound payload of the mod is registered with {@link #register}, which asks them first.
 */
public final class ClientActions {
	private ClientActions() {
	}

	/** What a feature does with a payload a player's client sent, once {@link #accept} passed. */
	@FunctionalInterface
	public interface Handler<T extends CustomPacketPayload> {
		void handle(ServerPlayer player, T payload);
	}

	/**
	 * Registers a serverbound payload with Fabric API, and its handler, called on the server thread for each payload a
	 * player's client sends that the server {@link #accept}s.
	 */
	public static <T extends CustomPacketPayload> void register(CustomPacketPayload.Type<T> type,
			StreamCodec<? super RegistryFriendlyByteBuf, T> codec, Handler<T> handler) {
		PayloadTypeRegistry.serverboundPlay().register(type, codec);
		ServerPlayNetworking.registerGlobalReceiver(type, (payload, context) -> {
			if (accept(context.player())) {
				handler.handle(context.player(), payload);
			}
		});
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
