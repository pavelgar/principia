package io.github.pavelgar.principia.skyline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mojang.serialization.Codec;
import io.github.pavelgar.principia.Principia;
import io.github.pavelgar.principia.content.TurnsWithStructures;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * What a Sky-Line anchor holds: the pitch of its tangent (the heading is the block's) and at most one segment on each
 * side. Both anchors of a segment hold it, each from its own end; clients get it with the anchors.
 *
 * <p>Each segment leaves the anchor along its tangent, so of a segment the anchor stores only the end at its partner
 * ({@link Segment.Partner}): its own end is its tangent and the side it holds the segment on. Its pitch therefore
 * changes only while it holds no segment.
 */
public final class AnchorBlockEntity extends BlockEntity implements TurnsWithStructures {
	/**
	 * The version of what {@link #saveAdditional} writes, saved with it. Data without one is version 1. A change to
	 * the format raises it, and {@link #loadAdditional} reads every older version.
	 */
	static final int SAVE_VERSION = 1;
	private static final Codec<Float> PITCH_CODEC = Codec.floatRange(-AnchorTangent.MAX_PITCH, AnchorTangent.MAX_PITCH);

	private float pitch;
	private Segment.@Nullable Partner front;
	private Segment.@Nullable Partner back;
	/** Whether the anchor is in its level's track: from when its chunk loads it until it unloads or is removed. */
	private boolean loaded;
	/** Whether the anchor's rails stay where they are as it is removed: mined in creative, or replaced by a command. */
	private boolean dropsNothing;

	public AnchorBlockEntity(BlockPos worldPosition, BlockState blockState) {
		super(SkyLine.ANCHOR_ENTITY, worldPosition, blockState);
	}

	/** The pitch of the anchor's tangent, in degrees, positive upwards. */
	public float pitch() {
		return pitch;
	}

	/**
	 * Sets the pitch of the anchor's tangent, which only a new anchor does: its segments leave along it.
	 *
	 * @throws IllegalStateException if the anchor holds a segment
	 */
	public void setPitch(float pitch) {
		if (front != null || back != null) {
			throw new IllegalStateException("the anchor at " + worldPosition + " holds segments along its pitch");
		}
		this.pitch = new AnchorTangent(heading(), pitch).pitch(); // which refuses a pitch past ±45°
		changed();
	}

	/** The anchor's tangent: the heading of its block and its pitch. Its segments leave along it or its reverse. */
	public AnchorTangent tangent() {
		return new AnchorTangent(heading(), pitch);
	}

	private Heading heading() {
		return getBlockState().getValue(AnchorBlock.HEADING);
	}

	/** The segment on one side of the anchor, if any. */
	public @Nullable Segment segment(AnchorSide side) {
		Segment.Partner partner = side == AnchorSide.FRONT ? front : back;
		return partner == null ? null : Segment.of(new Segment.End(tangent(), side), partner);
	}

	/** Every segment the anchor holds: none, one or two. */
	public List<Segment> segments() {
		List<Segment> segments = new ArrayList<>(2);
		for (AnchorSide side : AnchorSide.values()) {
			Segment segment = segment(side);
			if (segment != null) {
				segments.add(segment);
			}
		}
		return segments;
	}

	/**
	 * Holds a segment, on the side it leaves this anchor on, in place of any there before.
	 *
	 * @throws IllegalArgumentException if the segment doesn't leave along the anchor's tangent
	 */
	public void setSegment(Segment segment) {
		if (!segment.from().tangent().equals(tangent())) {
			throw new IllegalArgumentException("a segment leaving along " + segment.from().tangent()
					+ " for the anchor at " + worldPosition + ", whose tangent is " + tangent());
		}
		set(segment.from().side(), segment.partner());
	}

	/** Drops the segment on one side, if there is one. */
	public void removeSegment(AnchorSide side) {
		set(side, null);
	}

	/**
	 * Lays a segment from this anchor: both anchors hold it, each from its own end, the partner already in place.
	 * Server only.
	 *
	 * @throws IllegalArgumentException if either anchor's tangent isn't the segment's there
	 * @throws IllegalStateException if the partner isn't loaded, or either anchor already holds a segment on that side
	 */
	public void connect(Segment segment) {
		AnchorBlockEntity partner = level == null
				? null
				: loadedAnchorAt(level, worldPosition.offset(segment.offset()));
		if (partner == null) {
			throw new IllegalStateException("no anchor loaded at the far end of " + segment + " from " + worldPosition);
		}
		if (segment(segment.from().side()) != null || partner.segment(segment.to().side()) != null) {
			throw new IllegalStateException("a side " + segment + " would leave from " + worldPosition + " is taken");
		}
		setSegment(segment);
		partner.setSegment(segment.reversed());
	}

	/**
	 * Takes the segment on one side away from both its anchors. A partner that isn't loaded drops its copy when it
	 * loads. Server only.
	 */
	public void disconnect(AnchorSide side) {
		Segment segment = segment(side);
		if (segment == null || level == null) {
			return;
		}
		removeSegment(side);
		AnchorBlockEntity partner = loadedAnchorAt(level, worldPosition.offset(segment.offset()));
		if (partner != null && partner.holds(segment.reversed())) {
			partner.removeSegment(segment.to().side());
		}
	}

	/**
	 * Every change to the segments the anchor holds comes through here. An anchor left with none stays, alone, as
	 * Create's track ends stay when their curve breaks: a player placed it, and can lay from it again or mine it.
	 */
	private void set(AnchorSide side, Segment.@Nullable Partner partner) {
		if (side == AnchorSide.FRONT) {
			front = partner;
		} else {
			back = partner;
		}
		changed();
	}

	/** Keeps the anchor's rails, its own and its segments', from falling as it is removed. */
	void dropNothing() {
		dropsNothing = true;
	}

	/**
	 * As the anchor breaks (mined in survival, blown up, or destroyed by a command) its own rail falls from its point,
	 * and its segments' rails along them. Vanilla skips this when a command replaces the anchor, and the anchor skips it
	 * when a creative player mines it. Its partners then drop their copies of the segments, as it unloads, so each
	 * segment's rails fall once. The anchor's rail isn't in its loot table, whose explosion condition would keep some
	 * from falling when it is blown up: its segments' rails all fall then.
	 */
	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		super.preRemoveSideEffects(pos, state);
		if (level instanceof ServerLevel server && !dropsNothing) {
			RailDrops.drop(server, pos);
			segments().forEach(segment -> RailDrops.drop(server, pos, segment));
		}
	}

	/** Saves the change, puts it in the track and sends it to the clients that see the anchor. */
	private void changed() {
		setChanged();
		updateTrack();
		if (level != null) {
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	private void updateTrack() {
		if (loaded && level != null) {
			Track.of(level).index().put(worldPosition, segments());
		}
	}

	/**
	 * Called by the loader's hooks as the anchor's chunk loads it, on the server and on clients. On the server its
	 * segments are checked against their partners at the end of the tick, once every chunk loaded in it is in place.
	 */
	public void loaded() {
		if (loaded || level == null) {
			return;
		}
		loaded = true;
		updateTrack();
		if (!level.isClientSide()) {
			Track.of(level).checkAtTickEnd(this);
		}
	}

	/**
	 * Called by the loader's hooks as the anchor unloads with its chunk or is removed, on the server and on clients.
	 * When it is removed, the loaded anchors drop the segments that end at it.
	 */
	public void unloaded() {
		if (!loaded || level == null) {
			return;
		}
		loaded = false;
		LevelChunk chunk = chunkAt(level, worldPosition);
		if (chunk != null && chunk.getBlockEntities().get(worldPosition) instanceof AnchorBlockEntity other
				&& other != this) {
			return; // a new anchor's entity replaced this one and has already taken its place in the track
		}
		Track.of(level).index().remove(worldPosition);
		// Unloading leaves the anchor's block in place. Removing it, by any means (even /setblock, which skips
		// preRemoveSideEffects), has replaced the block by the time its entity goes.
		if (!level.isClientSide() && chunk != null && !chunk.getBlockState(worldPosition).is(SkyLine.ANCHOR)) {
			dropFromPartners(level);
		}
	}

	/**
	 * Follows the block's state as it changes in place, keeping this entity: a debug stick, or {@code /setblock} with
	 * another heading. A new heading turns the anchor's tangent away from its segments, which its partners hold
	 * leaving along the old one, so on the server they go at once, from both ends, as when the anchor is removed.
	 */
	@Override
	@SuppressWarnings("deprecation") // Deprecated for callers; vanilla's HopperBlockEntity overrides it the same way.
	public void setBlockState(BlockState blockState) {
		Heading before = heading();
		super.setBlockState(blockState);
		if (heading() != before && (front != null || back != null) && level != null && !level.isClientSide()) {
			dropFromPartners(level);
			removeSegment(AnchorSide.FRONT);
			removeSegment(AnchorSide.BACK);
		}
	}

	/** Has every loaded anchor drop the segments it holds that end here. Server only. */
	private void dropFromPartners(Level level) {
		// Those in the track, and this one's partners whose entities their chunks haven't made yet.
		Set<BlockPos> partners = new HashSet<>();
		Track.of(level).index().endingAt(worldPosition).forEach(held -> partners.add(held.anchor()));
		segments().forEach(segment -> partners.add(worldPosition.offset(segment.offset())));
		for (BlockPos pos : partners) {
			AnchorBlockEntity partner = loadedAnchorAt(level, pos);
			if (partner != null) {
				partner.segments().stream().filter(segment -> pos.offset(segment.offset()).equals(worldPosition))
						.forEach(segment -> partner.removeSegment(segment.from().side()));
			}
		}
	}

	/**
	 * Keeps only the segments both of whose anchors hold them, between this newly loaded anchor and the loaded ones:
	 * drops each of its segments whose partner end is loaded but doesn't hold it, and has each loaded anchor drop the
	 * segments it holds that end here but this one doesn't hold. A partner that isn't loaded checks back when it
	 * loads. Server only.
	 */
	void checkPartners() {
		if (!loaded || level == null) {
			return;
		}
		for (Segment segment : segments()) {
			BlockPos partnerPos = worldPosition.offset(segment.offset());
			if (chunkAt(level, partnerPos) != null) {
				AnchorBlockEntity partner = loadedAnchorAt(level, partnerPos);
				if (partner == null || !partner.holds(segment.reversed())) {
					removeSegment(segment.from().side());
				}
			}
		}
		for (SegmentIndex.Held held : Track.of(level).index().endingAt(worldPosition)) {
			AnchorBlockEntity holder = loadedAnchorAt(level, held.anchor());
			if (holder != null && !holds(held.segment().reversed())) {
				holder.removeSegment(held.segment().from().side());
			}
		}
	}

	private boolean holds(Segment segment) {
		return segment.equals(segment(segment.from().side()));
	}

	/** The chunk a position is in, if it is loaded; never loads one. */
	private static @Nullable LevelChunk chunkAt(Level level, BlockPos pos) {
		return level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(pos.getX()),
				SectionPos.blockToSectionCoord(pos.getZ()));
	}

	/** The anchor at a position, if its chunk is loaded. A chunk not yet ticking makes its entity on asking. */
	private static @Nullable AnchorBlockEntity loadedAnchorAt(Level level, BlockPos pos) {
		LevelChunk chunk = chunkAt(level, pos);
		return chunk != null && chunk.getBlockEntity(pos) instanceof AnchorBlockEntity anchor ? anchor : null;
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putInt("version", SAVE_VERSION);
		output.store("pitch", PITCH_CODEC, pitch);
		output.storeNullable("front", Segment.Partner.CODEC, front);
		output.storeNullable("back", Segment.Partner.CODEC, back);
	}

	/**
	 * Reads what {@link #saveAdditional} wrote, in any version up to {@link #SAVE_VERSION}. A segment that doesn't
	 * decode is reported by vanilla, with the anchor's position, and dropped; its partner then drops its copy when the
	 * two are checked, as for any segment only one anchor holds.
	 */
	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		int version = input.getIntOr("version", 1);
		if (version > SAVE_VERSION) {
			Principia.LOGGER.warn("The Sky-Line anchor at {} was saved by a newer Principia (save version {}, this one "
					+ "reads up to {}): its segments may not load", worldPosition.toShortString(), version,
					SAVE_VERSION);
		}
		pitch = input.read("pitch", PITCH_CODEC).orElse(0f);
		front = input.read("front", Segment.Partner.CODEC).orElse(null);
		back = input.read("back", Segment.Partner.CODEC).orElse(null);
		// A client gets the anchor's segments after its chunk loaded it, and again as they change.
		updateTrack();
	}

	/** The partners turn with the structure, as {@link AnchorBlock#rotate} and {@link AnchorBlock#mirror} turn the anchor. */
	@Override
	public void turnSavedData(CompoundTag saved, Mirror mirror, Rotation rotation) {
		for (String side : List.of("front", "back")) {
			Tag partner = saved.get(side);
			if (partner != null) {
				Segment.Partner.CODEC.parse(NbtOps.INSTANCE, partner).result()
						.flatMap(decoded -> Segment.Partner.CODEC
								.encodeStart(NbtOps.INSTANCE, decoded.transformed(mirror, rotation)).result())
						.ifPresent(encoded -> saved.put(side, encoded));
			}
		}
	}

	@Override
	public ClientboundBlockEntityDataPacket getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveCustomOnly(registries);
	}
}
