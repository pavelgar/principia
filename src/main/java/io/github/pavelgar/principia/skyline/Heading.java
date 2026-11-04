package io.github.pavelgar.principia.skyline;

import java.util.Locale;
import java.util.function.IntFunction;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;

import net.minecraft.core.Vec3i;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;
import net.minecraft.util.StringRepresentable;

/**
 * One of the eight horizontal headings a Sky-Line anchor can face, 45° apart. They are in the order of an entity's
 * yaw: south is 0°, and each next one is 45° further clockwise seen from above.
 */
public enum Heading implements StringRepresentable {
	SOUTH(0, 0, 1),
	SOUTH_WEST(1, -1, 1),
	WEST(2, -1, 0),
	NORTH_WEST(3, -1, -1),
	NORTH(4, 0, -1),
	NORTH_EAST(5, 1, -1),
	EAST(6, 1, 0),
	SOUTH_EAST(7, 1, 1);

	public static final Codec<Heading> CODEC = StringRepresentable.fromEnum(Heading::values);
	private static final IntFunction<Heading> BY_ID = ByIdMap.continuous(heading -> heading.id, values(),
			ByIdMap.OutOfBoundsStrategy.WRAP);
	public static final StreamCodec<ByteBuf, Heading> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID,
			heading -> heading.id);

	/** The yaw of this heading in 45° steps. */
	private final int id;
	private final int stepX;
	private final int stepZ;
	private final double x;
	private final double z;

	Heading(int id, int x, int z) {
		this.id = id;
		this.stepX = x;
		this.stepZ = z;
		double length = Math.sqrt(x * x + z * z);
		this.x = x / length;
		this.z = z / length;
	}

	/** The heading nearest to an entity's yaw, in degrees: south is 0°, west 90°. */
	public static Heading fromYaw(float yaw) {
		return BY_ID.apply(Math.round(yaw / 45));
	}

	/** The x component of this heading as a unit vector. */
	public double x() {
		return x;
	}

	/** The z component of this heading as a unit vector. */
	public double z() {
		return z;
	}

	/** The offset to the next cell this way: one step along x, z or both. */
	public Vec3i step() {
		return new Vec3i(stepX, 0, stepZ);
	}

	/** This heading turned by a number of 45° steps, clockwise seen from above. */
	public Heading turned(int steps) {
		return BY_ID.apply(id + steps);
	}

	public Heading opposite() {
		return BY_ID.apply(id + 4);
	}

	/** How far this heading is from another, in 45° steps: 0 to 4. */
	public int stepsTo(Heading other) {
		int steps = Math.floorMod(other.id - id, 8);
		return Math.min(steps, 8 - steps);
	}

	@Override
	public String getSerializedName() {
		return name().toLowerCase(Locale.ROOT);
	}
}
