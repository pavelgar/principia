package io.github.pavelgar.principia.skyline;

import java.util.Locale;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;
import net.minecraft.util.StringRepresentable;

/** Which of a segment's two rails, seen travelling along its curve from start to end. */
public enum RailSide implements StringRepresentable {
	LEFT(-1),
	RIGHT(1);

	public static final Codec<RailSide> CODEC = StringRepresentable.fromEnum(RailSide::values);
	public static final StreamCodec<ByteBuf, RailSide> STREAM_CODEC = ByteBufCodecs.idMapper(
			ByIdMap.continuous(Enum::ordinal, values(), ByIdMap.OutOfBoundsStrategy.ZERO), Enum::ordinal);

	/** How far the rail is from the centre line, in blocks, positive to the right. */
	final double offset;

	RailSide(double offset) {
		this.offset = offset;
	}

	public RailSide opposite() {
		return this == LEFT ? RIGHT : LEFT;
	}

	@Override
	public String getSerializedName() {
		return name().toLowerCase(Locale.ROOT);
	}
}
