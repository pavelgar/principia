package io.github.pavelgar.principia.skyline;

import java.util.Optional;

import io.github.pavelgar.principia.Principia;
import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A client telling the server that its player, with an anchor selected and a Sky-Line in a hand, clicked a block to lay
 * track to: the block as vanilla's use-item-on packet has it, and the heading the player chose for a new anchor there,
 * if they did. The server checks it all again ({@link Placement#lay}).
 */
public record LayTrackPayload(InteractionHand hand, BlockHitResult hit, Optional<Heading> chosen)
		implements
			CustomPacketPayload {
	public static final Type<LayTrackPayload> TYPE = new Type<>(Principia.id("lay_track"));
	public static final StreamCodec<ByteBuf, LayTrackPayload> STREAM_CODEC = StreamCodec.composite(
			InteractionHand.STREAM_CODEC, LayTrackPayload::hand, BlockHitResult.STREAM_CODEC, LayTrackPayload::hit,
			ByteBufCodecs.optional(Heading.STREAM_CODEC), LayTrackPayload::chosen, LayTrackPayload::new);

	@Override
	public Type<LayTrackPayload> type() {
		return TYPE;
	}
}
