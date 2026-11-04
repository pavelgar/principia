package io.github.pavelgar.principia.skyline;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A point on a rail, named by its segment as one of the segment's anchors stores it: that anchor, the side of it the
 * segment leaves on, the rail's side seen along the segment from that anchor, and the distance along the rail from it.
 * It names the segment without its shape, which whoever reads it looks up in its own track. What a client sends of the
 * rail it aims at, and what a rider's hook saves of where it hangs.
 */
public record RailPoint(BlockPos anchor, AnchorSide side, RailSide rail, double distance) {
	public static final Codec<RailPoint> CODEC = RecordCodecBuilder.create(instance -> instance
			.group(BlockPos.CODEC.fieldOf("anchor").forGetter(RailPoint::anchor),
					AnchorSide.CODEC.fieldOf("side").forGetter(RailPoint::side),
					RailSide.CODEC.fieldOf("rail").forGetter(RailPoint::rail),
					Codec.DOUBLE.fieldOf("distance").forGetter(RailPoint::distance))
			.apply(instance, RailPoint::new));
	public static final StreamCodec<ByteBuf, RailPoint> STREAM_CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC,
			RailPoint::anchor, AnchorSide.STREAM_CODEC, RailPoint::side, RailSide.STREAM_CODEC, RailPoint::rail,
			ByteBufCodecs.DOUBLE, RailPoint::distance, RailPoint::new);
}
