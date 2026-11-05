package io.github.pavelgar.principia.skyline;

import java.util.Arrays;
import java.util.Set;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;

/**
 * A Sky-Line segment as seen from one of its two anchors: where the other anchor is, as an offset from this one, and
 * how the segment leaves each of them. Seen from the partner anchor, the offset is negated and the ends swapped.
 * Offsets rather than absolute positions keep a segment whose two anchors are copied together (by {@code /clone} or a
 * structure block) attached to the copies.
 *
 * <p>An anchor stores only the {@link Partner} end of each of its segments: its own end is its tangent and the side
 * it holds the segment on, so it isn't stored twice.
 *
 * @param offset the partner anchor's position minus this anchor's
 * @param from how the segment leaves this anchor
 * @param to how the segment leaves the partner anchor
 */
public record Segment(Vec3i offset, End from, End to) {
	/** A segment over the network, as tests check it encodes; anchors reach clients as their saved data. */
	public static final StreamCodec<ByteBuf, Segment> STREAM_CODEC = StreamCodec.composite(Vec3i.STREAM_CODEC,
			Segment::offset, End.STREAM_CODEC, Segment::from, End.STREAM_CODEC, Segment::to, Segment::new);

	/** One end of a segment: the tangent of its anchor and the side of the anchor the segment is on. */
	public record End(AnchorTangent tangent, AnchorSide side) {
		public static final Codec<End> CODEC = RecordCodecBuilder.create(instance -> instance
				.group(AnchorTangent.CODEC.fieldOf("tangent").forGetter(End::tangent),
						AnchorSide.CODEC.fieldOf("side").forGetter(End::side))
				.apply(instance, End::new));
		public static final StreamCodec<ByteBuf, End> STREAM_CODEC = StreamCodec.composite(
				AnchorTangent.STREAM_CODEC, End::tangent, AnchorSide.STREAM_CODEC, End::side, End::new);

		/** The unit vector the segment leaves this end's anchor along. */
		public Vec3 leaving() {
			Vec3 direction = tangent.direction();
			return side == AnchorSide.FRONT ? direction : direction.reverse();
		}

		/** The heading the segment leaves this end's anchor in. */
		public Heading leavingHeading() {
			return side == AnchorSide.FRONT ? tangent.heading() : tangent.heading().opposite();
		}
	}

	/**
	 * What an anchor stores of one of its segments, the end at its partner anchor, for its block entity to save.
	 *
	 * <p>Decoding refuses an offset of zero, of more than {@link #MAX_SAVED_OFFSET} along an axis, or of more than
	 * {@link #MAX_SAVED_SPREAD} along two: saved data can hold anything ({@code /data}, a structure file), and loading a
	 * segment indexes every chunk section in its bounding box. That box is then some 34 × 6 × 6 sections at most.
	 *
	 * @param offset the partner anchor's position minus this anchor's
	 * @param tangent the partner anchor's tangent
	 * @param side the side of the partner anchor the segment is on
	 */
	public record Partner(Vec3i offset, AnchorTangent tangent, AnchorSide side) {
		/**
		 * The furthest a saved partner can be along one axis: far past what laying allows, for tests that need anchors
		 * whose chunks unload apart (14 chunks or more from a forced one).
		 */
		public static final int MAX_SAVED_OFFSET = 512;
		/** The furthest a saved partner can be along the other two axes: twice {@link SegmentLimit#MAX_LENGTH}. */
		public static final int MAX_SAVED_SPREAD = 64;
		public static final Codec<Partner> CODEC = RecordCodecBuilder
				.<Partner>create(instance -> instance
						.group(Vec3i.CODEC.fieldOf("offset").forGetter(Partner::offset),
								AnchorTangent.CODEC.fieldOf("tangent").forGetter(Partner::tangent),
								AnchorSide.CODEC.fieldOf("side").forGetter(Partner::side))
						.apply(instance, Partner::new))
				.validate(Partner::checkOffset);

		private static DataResult<Partner> checkOffset(Partner partner) {
			Vec3i offset = partner.offset();
			if (offset.equals(Vec3i.ZERO)) {
				return DataResult.error(() -> "a segment's partner at its own anchor");
			}
			int[] along = {Math.abs(offset.getX()), Math.abs(offset.getY()), Math.abs(offset.getZ())};
			Arrays.sort(along);
			return along[2] <= MAX_SAVED_OFFSET && along[1] <= MAX_SAVED_SPREAD
					? DataResult.success(partner)
					: DataResult.error(() -> "a segment's partner " + offset.toShortString() + " away, more than "
							+ MAX_SAVED_OFFSET + " along an axis or " + MAX_SAVED_SPREAD + " along two");
		}

		/**
		 * This partner as a structure placed mirrored, then rotated, moves it: its offset as the structure moves the
		 * cells, its heading with them. The pitch and the side stay.
		 */
		public Partner transformed(Mirror mirror, Rotation rotation) {
			return new Partner(
					StructureTemplate.transform(BlockPos.ZERO.offset(offset), mirror, rotation, BlockPos.ZERO),
					new AnchorTangent(tangent.heading().transformed(mirror, rotation), tangent.pitch()), side);
		}
	}

	/** A segment from what an anchor stores of it and the anchor's own end of it. */
	public static Segment of(End own, Partner partner) {
		return new Segment(partner.offset(), own, new End(partner.tangent(), partner.side()));
	}

	/** What the anchor this segment is seen from stores of it. */
	public Partner partner() {
		return new Partner(offset, to.tangent(), to.side());
	}

	/** The same segment as seen from the partner anchor. */
	public Segment reversed() {
		return new Segment(offset.multiply(-1), to, from);
	}

	/** The segment's curve, with this anchor at {@code anchor}. */
	public SegmentCurve curve(BlockPos anchor) {
		return new SegmentCurve(SegmentCurve.anchorPoint(anchor), from.leaving(),
				SegmentCurve.anchorPoint(anchor.offset(offset)), to.leaving());
	}

	/** Every limit this segment's shape breaks, none if it can be placed. */
	public Set<SegmentLimit> brokenLimits() {
		return SegmentLimit.brokenBy(this);
	}
}
