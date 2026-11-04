package io.github.pavelgar.principia.skyline;

import io.github.pavelgar.principia.Principia;
import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * A client telling the server that its player has mined the rail they aim at: what the client targets, which the
 * server checks, as for {@link UseOnRailPayload}.
 */
public record BreakRailPayload(RailPoint target) implements CustomPacketPayload {
	public static final Type<BreakRailPayload> TYPE = new Type<>(Principia.id("break_rail"));
	public static final StreamCodec<ByteBuf, BreakRailPayload> STREAM_CODEC = RailPoint.STREAM_CODEC
			.map(BreakRailPayload::new, BreakRailPayload::target);

	@Override
	public Type<BreakRailPayload> type() {
		return TYPE;
	}
}
