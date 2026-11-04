package io.github.pavelgar.principia.test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.pavelgar.principia.skyline.AnchorBlockEntity;
import io.github.pavelgar.principia.skyline.AnchorSide;
import io.github.pavelgar.principia.skyline.AnchorTangent;
import io.github.pavelgar.principia.skyline.Heading;
import io.github.pavelgar.principia.skyline.Segment;
import io.github.pavelgar.principia.skyline.SegmentLimit;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

/**
 * A line of Sky-Line track for a playtest scene, anchor to anchor: each segment leaves the anchor the one before
 * arrived at, on its other side, as riding goes on across it. Every segment must be one a player could place, so a
 * scene shows track as it is laid in game: {@link #to} fails on one that breaks a limit.
 */
final class Line {
	private final Map<BlockPos, List<Segment>> held = new LinkedHashMap<>();
	private BlockPos at;
	private Segment.End leaving;

	private Line(BlockPos start, Heading heading, float pitch) {
		at = start;
		leaving = new Segment.End(new AnchorTangent(heading, pitch), AnchorSide.FRONT);
	}

	/** A line starting at an anchor, leaving it heading a way at a pitch, in degrees up. */
	static Line from(BlockPos start, Heading heading, float pitch) {
		return new Line(start, heading, pitch);
	}

	/** A level line starting at an anchor, leaving it heading a way. */
	static Line from(BlockPos start, Heading heading) {
		return from(start, heading, 0);
	}

	/** On to an anchor so far from the last, arriving there heading a way at a pitch, in degrees up. */
	Line to(int x, int y, int z, Heading arriving, float pitch) {
		AnchorTangent tangent = new AnchorTangent(arriving, pitch);
		Segment segment = new Segment(new Vec3i(x, y, z), leaving, new Segment.End(tangent, AnchorSide.BACK));
		Set<SegmentLimit> broken = segment.brokenLimits();
		if (!broken.isEmpty()) {
			throw new IllegalArgumentException("the segment from " + at + ", " + segment + ", breaks " + broken);
		}
		BlockPos partner = at.offset(segment.offset());
		held.computeIfAbsent(at, ignored -> new ArrayList<>()).add(segment);
		held.computeIfAbsent(partner, ignored -> new ArrayList<>()).add(segment.reversed());
		at = partner;
		leaving = new Segment.End(tangent, AnchorSide.FRONT);
		return this;
	}

	/** On to a level anchor so far from the last, arriving there heading a way. */
	Line to(int x, int y, int z, Heading arriving) {
		return to(x, y, z, arriving, 0);
	}

	/** Every anchor of the line and the segments it holds, each as seen from it. */
	Map<BlockPos, List<Segment>> anchors() {
		return held;
	}

	/** Places the line's anchors in a level, each holding its segments, as two anchors hold a segment laid in game. */
	void lay(ServerLevel level) {
		held.forEach((pos, segments) -> {
			level.setBlock(pos, SkyLineAnchorGameTest.anchor(segments.getFirst().from().tangent().heading()),
					Block.UPDATE_ALL);
			if (!(level.getBlockEntity(pos) instanceof AnchorBlockEntity anchor)) {
				throw new IllegalStateException("no anchor placed at " + pos);
			}
			anchor.setPitch(segments.getFirst().from().tangent().pitch());
			segments.forEach(anchor::setSegment);
		});
	}
}
