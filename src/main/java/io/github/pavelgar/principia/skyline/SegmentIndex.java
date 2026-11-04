package io.github.pavelgar.principia.skyline;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceArraySet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

/**
 * The segments of one level's loaded anchors, by the chunk sections they cross, for what asks about rails at a place:
 * aiming at them and riding them. A segment is in it, once, while either of its anchors is loaded; each anchor
 * holds the segment's full data. Each segment's curve is built once, as it enters the index, and handed out with it.
 *
 * <p>Sections are keyed by position alone, never checked against the level's height: a curve can reach past its
 * anchors, above the build height or below the floor.
 */
public final class SegmentIndex {
	/** The segments each loaded anchor holds. */
	private final Map<BlockPos, List<Key>> held = new HashMap<>();
	private final Map<Key, Entry> entries = new HashMap<>();
	private final Long2ObjectMap<List<Entry>> bySection = new Long2ObjectOpenHashMap<>();

	/** Sets the segments a loaded anchor holds, in place of those it held before. */
	public void put(BlockPos anchor, Collection<Segment> segments) {
		BlockPos at = anchor.immutable();
		remove(at);
		if (segments.isEmpty()) {
			return;
		}
		List<Key> keys = new ArrayList<>(segments.size());
		for (Segment segment : segments) {
			Key key = Key.of(at, segment);
			keys.add(key);
			entries.computeIfAbsent(key, this::add).holders.add(at);
		}
		held.put(at, keys);
	}

	/** Forgets the segments an anchor held, as it unloads or is removed. */
	public void remove(BlockPos anchor) {
		List<Key> keys = held.remove(anchor);
		if (keys == null) {
			return;
		}
		for (Key key : keys) {
			Entry entry = entries.get(key);
			if (entry != null && entry.holders.remove(anchor) && entry.holders.isEmpty()) {
				entries.remove(key);
				forEachSection(entry.bounds, section -> {
					List<Entry> inSection = bySection.get(section);
					inSection.remove(entry);
					if (inSection.isEmpty()) {
						bySection.remove(section);
					}
				});
			}
		}
	}

	/**
	 * The segments whose rails may reach into an area: those whose bounds, a box around both rails, intersect it. Each
	 * comes once, as its anchor first in {@code BlockPos} order stores it, with the curve the index built for it.
	 */
	public List<Held> near(AABB area) {
		ReferenceSet<Entry> inSections = new ReferenceArraySet<>();
		forEachSection(area, section -> {
			List<Entry> inSection = bySection.get(section);
			if (inSection != null) {
				inSections.addAll(inSection);
			}
		});
		List<Held> near = new ArrayList<>();
		for (Entry entry : inSections) {
			if (entry.bounds.intersects(area)) {
				near.add(entry.held());
			}
		}
		return near;
	}

	/**
	 * The segment a loaded anchor holds on one side, if any, as {@link #near} gives it: as its anchor first in {@code
	 * BlockPos} order stores it, which may be the other anchor, with the curve the index built for it.
	 */
	public @Nullable Held at(BlockPos anchor, AnchorSide side) {
		List<Key> keys = held.get(anchor);
		if (keys == null) {
			return null;
		}
		for (Key key : keys) {
			Segment own = key.anchor().equals(anchor) ? key.segment() : key.segment().reversed();
			Entry entry = entries.get(key);
			if (own.from().side() == side && entry != null) {
				return entry.held();
			}
		}
		return null;
	}

	/**
	 * The segments a loaded anchor draws: those the index holds from it and from no anchor before it in
	 * {@code BlockPos} order. So each segment is drawn once, while either of its anchors is loaded. Each comes as
	 * {@link #near} gives it, with the curve the index built for it.
	 */
	public List<Held> drawnFrom(BlockPos anchor) {
		List<Key> keys = held.get(anchor);
		if (keys == null) {
			return List.of();
		}
		List<Held> drawn = new ArrayList<>(keys.size());
		for (Key key : keys) {
			Entry entry = entries.get(key);
			if (entry != null && entry.holders.stream().noneMatch(holder -> holder.compareTo(anchor) < 0)) {
				drawn.add(entry.held());
			}
		}
		return drawn;
	}

	/**
	 * The segments that loaded anchors other than {@code anchor} hold and that end at it, each as the anchor holding it
	 * stores it.
	 */
	public List<Held> endingAt(BlockPos anchor) {
		// A segment's bounds hold its ends' anchor points, so it is in the section of each end.
		List<Entry> inSection = bySection.get(SectionPos.asLong(anchor));
		if (inSection == null) {
			return List.of();
		}
		List<Held> held = new ArrayList<>();
		for (Entry entry : inSection) {
			BlockPos low = entry.key.anchor();
			BlockPos high = low.offset(entry.key.segment().offset());
			if (anchor.equals(low) && entry.holders.contains(high)) {
				held.add(new Held(high, entry.key.segment().reversed()));
			} else if (anchor.equals(high) && entry.holders.contains(low)) {
				held.add(new Held(low, entry.key.segment()));
			}
		}
		return held;
	}

	/**
	 * A segment as the anchor at a position stores it. Two are equal when their anchor and segment are, whatever their
	 * curves.
	 */
	public static final class Held {
		private final BlockPos anchor;
		private final Segment segment;
		private final @Nullable SegmentCurve curve;

		public Held(BlockPos anchor, Segment segment) {
			this(anchor, segment, null);
		}

		private Held(BlockPos anchor, Segment segment, @Nullable SegmentCurve curve) {
			this.anchor = anchor;
			this.segment = segment;
			this.curve = curve;
		}

		public BlockPos anchor() {
			return anchor;
		}

		public Segment segment() {
			return segment;
		}

		/**
		 * The segment's curve, from {@link #anchor()}: for those {@link #near} finds, the one the index built when the
		 * segment entered it; for others, built on each call.
		 */
		public SegmentCurve curve() {
			return curve != null ? curve : segment.curve(anchor);
		}

		@Override
		public boolean equals(@Nullable Object other) {
			return other instanceof Held held && anchor.equals(held.anchor) && segment.equals(held.segment);
		}

		@Override
		public int hashCode() {
			return Objects.hash(anchor, segment);
		}

		@Override
		public String toString() {
			return "Held[anchor=" + anchor + ", segment=" + segment + "]";
		}
	}

	private Entry add(Key key) {
		Entry entry = new Entry(key, key.segment().curve(key.anchor()));
		forEachSection(entry.bounds,
				section -> bySection.computeIfAbsent(section, ignored -> new ArrayList<>()).add(entry));
		return entry;
	}

	private static void forEachSection(AABB box, SectionConsumer action) {
		int minX = SectionPos.blockToSectionCoord(Mth.floor(box.minX));
		int minY = SectionPos.blockToSectionCoord(Mth.floor(box.minY));
		int minZ = SectionPos.blockToSectionCoord(Mth.floor(box.minZ));
		int maxX = SectionPos.blockToSectionCoord(Mth.floor(box.maxX));
		int maxY = SectionPos.blockToSectionCoord(Mth.floor(box.maxY));
		int maxZ = SectionPos.blockToSectionCoord(Mth.floor(box.maxZ));
		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					action.accept(SectionPos.asLong(x, y, z));
				}
			}
		}
	}

	@FunctionalInterface
	private interface SectionConsumer {
		void accept(long section);
	}

	/**
	 * A segment as the anchor at the lower position stores it, so both anchors' copies of a segment have the same key.
	 * The whole segment is in the key: two anchors that disagree on a segment hold two, until the consistency check
	 * drops the wrong one.
	 */
	private record Key(BlockPos anchor, Segment segment) {
		static Key of(BlockPos anchor, Segment segment) {
			BlockPos partner = anchor.offset(segment.offset());
			return partner.compareTo(anchor) < 0 ? new Key(partner, segment.reversed()) : new Key(anchor, segment);
		}
	}

	private static final class Entry {
		final Key key;
		final SegmentCurve curve;
		/** A box around both rails, which places the segment in the sections it crosses. */
		final AABB bounds;
		final Set<BlockPos> holders = new HashSet<>(2);

		Entry(Key key, SegmentCurve curve) {
			this.key = key;
			this.curve = curve;
			this.bounds = curve.railBounds();
		}

		Held held() {
			return new Held(key.anchor(), key.segment(), curve);
		}
	}
}
