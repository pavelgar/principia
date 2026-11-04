package io.github.pavelgar.principia.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** The id of the teleport the server waits for a player's client to confirm, for test players to confirm it. */
@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerImplAccessor {
	@Accessor("awaitingTeleport")
	int principia$awaitingTeleport();
}
