package io.github.pavelgar.principia.skyline;

import io.github.pavelgar.principia.Principia;
import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * A client telling the server that its player has started mining the rail they aim at, as vanilla's client does for a
 * block, so the server can time the break that follows ({@link RailBreaking#startMining}).
 */
public record StartMiningRailPayload(RailPoint target) implements CustomPacketPayload {
	public static final Type<StartMiningRailPayload> TYPE = new Type<>(Principia.id("start_mining_rail"));
	public static final StreamCodec<ByteBuf, StartMiningRailPayload> STREAM_CODEC = RailPoint.STREAM_CODEC
			.map(StartMiningRailPayload::new, StartMiningRailPayload::target);

	@Override
	public Type<StartMiningRailPayload> type() {
		return TYPE;
	}
}
