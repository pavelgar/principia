package io.github.pavelgar.principia.skyline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.code_intelligence.jazzer.mutation.annotation.FloatInRange;
import com.code_intelligence.jazzer.mutation.annotation.InRange;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/** Sky-Line track built from what a fuzz test is given, and an index holding it as its anchors would. */
final class FuzzedTrack {
	private FuzzedTrack() {
	}

	/** An end of a segment: its anchor's tangent and the side of the anchor the segment is on. */
	record End(@NotNull Heading heading, @FloatInRange(min = -45, max = 45, allowNaN = false) float pitch,
			@NotNull AnchorSide side) {
		Segment.End end() {
			return new Segment.End(new AnchorTangent(heading, pitch), side);
		}
	}

	/** An offset between two different cells within the reach of a segment, and some way past it. */
	record Offset(@InRange(min = -24, max = 24) int x, @InRange(min = -16, max = 16) int y,
			@InRange(min = -24, max = 24) int z) {
		@Nullable
		Vec3i vector() {
			return x == 0 && y == 0 && z == 0 ? null : new Vec3i(x, y, z);
		}
	}

	/**
	 * A segment's shape seen from the end it leaves: so far the way that end heads, so far to its side, so far up, and
	 * arriving within 90° of that heading, so that it is often placeable.
	 */
	record Shape(@InRange(min = 1, max = 22) int forward, @InRange(min = -22, max = 22) int sideways,
			@InRange(min = -22, max = 22) int rise, @InRange(min = -2, max = 2) int turn,
			@FloatInRange(min = -45, max = 45, allowNaN = false) float pitch, @NotNull AnchorSide side) {
		Segment from(Segment.End from) {
			Heading leaving = from.leavingHeading();
			Vec3i offset = leaving.step().multiply(forward).offset(leaving.turned(2).step().multiply(sideways))
					.offset(0, rise, 0);
			// The segment arrives heading `arriving`, so it leaves the partner the opposite way.
			Heading arriving = leaving.turned(turn);
			Heading tangent = side == AnchorSide.FRONT ? arriving.opposite() : arriving;
			return new Segment(offset, from, new Segment.End(new AnchorTangent(tangent, pitch), side));
		}
	}

	/** A segment between any two different cells, whatever limits it breaks: old saves can hold those. Null if none. */
	static @Nullable Segment any(Offset offset, End from, End to) {
		Vec3i vector = offset.vector();
		return vector == null ? null : new Segment(vector, from.end(), to.end());
	}

	/** A segment of a shape, if it breaks no limit. */
	static @Nullable Segment placeable(End from, Shape shape) {
		Segment segment = shape.from(from.end());
		return segment.brokenLimits().isEmpty() ? segment : null;
	}

	/**
	 * Segments of shapes, each leaving the far anchor of the one before on its other side, as riding goes on across an
	 * anchor. Null unless each breaks no limit and every anchor is in a cell of its own.
	 */
	static @Nullable List<Segment> placeableChain(End from, List<Shape> shapes) {
		List<Segment> chain = new ArrayList<>();
		Set<BlockPos> anchors = new HashSet<>(List.of(BlockPos.ZERO));
		BlockPos anchor = BlockPos.ZERO;
		Segment.End leaving = from.end();
		for (Shape shape : shapes) {
			Segment segment = shape.from(leaving);
			anchor = anchor.offset(segment.offset());
			if (!segment.brokenLimits().isEmpty() || !anchors.add(anchor)) {
				return null;
			}
			chain.add(segment);
			leaving = new Segment.End(segment.to().tangent(), segment.to().side().opposite());
		}
		return chain;
	}

	/** Runs a check of an input, naming the input in a failure: Jazzer saves only the bytes it was built from. */
	static void checking(Object input, Runnable check) {
		try {
			check.run();
		} catch (AssertionError failure) {
			throw new AssertionError(input + ": " + failure.getMessage(), failure);
		}
	}

	/**
	 * An index holding a chain of segments laid from an anchor, each leaving the far anchor of the one before, as their
	 * anchors would hold them: each anchor every segment that ends at it.
	 */
	static SegmentIndex lay(BlockPos start, List<Segment> chain) {
		Map<BlockPos, List<Segment>> held = new HashMap<>();
		BlockPos anchor = start;
		for (Segment segment : chain) {
			BlockPos partner = anchor.offset(segment.offset());
			held.computeIfAbsent(anchor, ignored -> new ArrayList<>()).add(segment);
			held.computeIfAbsent(partner, ignored -> new ArrayList<>()).add(segment.reversed());
			anchor = partner;
		}
		SegmentIndex index = new SegmentIndex();
		held.forEach(index::put);
		return index;
	}
}
