package io.github.pavelgar.principia.skyline;

import java.util.Locale;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;
import net.minecraft.util.StringRepresentable;

/**
 * A side of a Sky-Line anchor. The segment on its front side leaves the anchor along the anchor's tangent, the one on
 * its back side along the reverse, so a line runs on through the anchor without a kink.
 */
public enum AnchorSide implements StringRepresentable {
	FRONT,
	BACK;

	public static final Codec<AnchorSide> CODEC = StringRepresentable.fromEnum(AnchorSide::values);
	public static final StreamCodec<ByteBuf, AnchorSide> STREAM_CODEC = ByteBufCodecs.idMapper(
			ByIdMap.continuous(Enum::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO), Enum::ordinal);

	public AnchorSide opposite() {
		return this == FRONT ? BACK : FRONT;
	}

	@Override
	public String getSerializedName() {
		return name().toLowerCase(Locale.ROOT);
	}
}
