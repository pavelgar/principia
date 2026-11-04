package io.github.pavelgar.principia.skyline;

import io.github.pavelgar.principia.Principia;
import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;

/**
 * A client telling the server that its player used the item in a hand on the rail they aim at: what the client
 * targets, which the server checks, as vanilla's use-item-on packet does for a block. The Sky-Hook attaches with it.
 */
public record UseOnRailPayload(InteractionHand hand, RailPoint target) implements CustomPacketPayload {
	public static final Type<UseOnRailPayload> TYPE = new Type<>(Principia.id("use_on_rail"));
	public static final StreamCodec<ByteBuf, UseOnRailPayload> STREAM_CODEC = StreamCodec.composite(
			InteractionHand.STREAM_CODEC, UseOnRailPayload::hand, RailPoint.STREAM_CODEC, UseOnRailPayload::target,
			UseOnRailPayload::new);

	@Override
	public Type<UseOnRailPayload> type() {
		return TYPE;
	}
}
