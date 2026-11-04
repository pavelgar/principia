package io.github.pavelgar.principia.skyline;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;

/**
 * The direction of a Sky-Line through an anchor: a heading and a pitch, in degrees, positive upwards (the opposite of
 * an entity's x rotation). The line leaves the anchor along it on the anchor's front side.
 */
public record AnchorTangent(Heading heading, float pitch) {
	public static final float MAX_PITCH = 45;

	public static final Codec<AnchorTangent> CODEC = RecordCodecBuilder.create(instance -> instance
			.group(Heading.CODEC.fieldOf("heading").forGetter(AnchorTangent::heading),
					Codec.floatRange(-MAX_PITCH, MAX_PITCH).fieldOf("pitch").forGetter(AnchorTangent::pitch))
			.apply(instance, AnchorTangent::new));
	public static final StreamCodec<ByteBuf, AnchorTangent> STREAM_CODEC = StreamCodec.composite(
			Heading.STREAM_CODEC, AnchorTangent::heading, ByteBufCodecs.FLOAT, AnchorTangent::pitch,
			AnchorTangent::new);

	public AnchorTangent {
		if (!(Math.abs(pitch) <= MAX_PITCH)) {
			throw new IllegalArgumentException("pitch " + pitch + " outside [-" + MAX_PITCH + ", " + MAX_PITCH + "]");
		}
	}

	/** This tangent as a unit vector. */
	public Vec3 direction() {
		double radians = Math.toRadians(pitch);
		double horizontal = Math.cos(radians);
		return new Vec3(heading.x() * horizontal, Math.sin(radians), heading.z() * horizontal);
	}
}
