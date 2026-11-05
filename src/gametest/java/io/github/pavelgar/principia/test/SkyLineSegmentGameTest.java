package io.github.pavelgar.principia.test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import io.github.pavelgar.principia.skyline.AnchorBlockEntity;
import io.github.pavelgar.principia.skyline.AnchorSide;
import io.github.pavelgar.principia.skyline.AnchorTangent;
import io.github.pavelgar.principia.skyline.Heading;
import io.github.pavelgar.principia.skyline.Segment;
import io.github.pavelgar.principia.skyline.SegmentIndex;
import io.github.pavelgar.principia.skyline.Track;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * Sky-Line segments: kept only while both of their anchors hold them, and in the level's track while either anchor is
 * loaded. The rails of a segment run 1 block to either side of the line between its anchors' centres, their tops
 * level with the anchors' tops.
 */
public class SkyLineSegmentGameTest {
	/** Level, 5 east from (2, 3, 4): rails along x at z = 3.5 and 5.5, their tops at y = 4, from x = 2.5 to 7.5. */
	private static final BlockPos STRAIGHT_FROM = new BlockPos(2, 3, 4);
	private static final Segment STRAIGHT = segment(new Vec3i(5, 0, 0), Heading.EAST, 0, Heading.EAST, 0);
	/**
	 * Level and east, as long as saved data allows ({@link Segment.Partner#MAX_SAVED_OFFSET}), far past the limits,
	 * which only placing checks. Its anchors are 32 chunks apart: a forced chunk keeps the 13 around it in memory (up
	 * to {@code ChunkLevel.MAX_LEVEL}), so with one end forced, the other's chunk unloads.
	 */
	private static final Segment APART = segment(new Vec3i(Segment.Partner.MAX_SAVED_OFFSET, 0, 0), Heading.EAST, 0,
			Heading.EAST, 0);
	/** A 16 × 10 × 16 area, for tests that need more room than the default one. */
	private static final String WIDE = "principia-test:sky_line";
	/** A segment that bends, from an anchor at (2, 3, 2) heading east, pitched up, to one heading south-east. */
	private static final BlockPos BEND_FROM = new BlockPos(2, 3, 2);
	private static final Segment BEND = segment(new Vec3i(4, 0, 2), Heading.EAST, 10, Heading.SOUTH_EAST, 0);
	/** The north-west corner of the 7 × 1 × 5 cells around {@link #BEND}, its anchors' beam ends among them. */
	private static final BlockPos BEND_CORNER = new BlockPos(1, 3, 1);
	/** A cell beside {@link #BEND} that no beam end takes. */
	private static final BlockPos CHEST = new BlockPos(4, 3, 1);
	/** Where copies of the cells around {@link #BEND} go: above it, and in the area however they turn. */
	private static final BlockPos COPY_AT = new BlockPos(8, 6, 8);

	/**
	 * A structure holding a segment that bends and a chest holding 3 diamonds, saved from the level with both anchors'
	 * beam ends, and placed in every mirror and rotation in turn, above the original. Vanilla turns the blocks and
	 * loads their saved data unchanged: the segment turns with its anchors, so at the end of the tick, when the copies
	 * are checked against each other, both still hold it, between where vanilla moved the two anchors and leaving and
	 * arriving as vanilla turns the original's headings. The chest loads its data as saved. Each placement turns the
	 * structure's data afresh: the structure keeps it as saved.
	 */
	@GameTest(structure = WIDE, maxTicks = 60)
	public void aSegmentTurnsWithAStructurePlacedEveryWay(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		lay(helper, BEND_FROM, BEND);
		helper.setBlock(CHEST, Blocks.CHEST);
		helper.getBlockEntity(CHEST, ChestBlockEntity.class).setItem(0, new ItemStack(Items.DIAMOND, 3));
		StructureTemplate template = bendStructure(helper);

		GameTestSequence sequence = helper.startSequence();
		for (Mirror mirror : Mirror.values()) {
			for (Rotation rotation : Rotation.values()) {
				sequence.thenExecute(() -> placeCopy(helper, template, mirror, rotation))
						.thenIdle(1) // past the end of the tick the copies loaded in, when they are checked
						.thenExecute(() -> {
							assertBendCopied(helper, mirror, rotation);
							helper.assertTrue(level.getBlockEntity(copied(helper, CHEST, mirror,
									rotation)) instanceof ChestBlockEntity chest
									&& ItemStack.matches(chest.getItem(0), new ItemStack(Items.DIAMOND, 3)),
									mirror + ", " + rotation + ": the copied chest is gone or lost its diamonds");
							BlockPos min = helper.absolutePos(COPY_AT).offset(-7, 0, -7);
							BlockPos max = helper.absolutePos(COPY_AT).offset(7, 0, 7);
							command(helper, "fill %d %d %d %d %d %d air".formatted(min.getX(), min.getY(),
									min.getZ(), max.getX(), max.getY(), max.getZ()));
						})
						.thenIdle(1);
			}
		}
		sequence.thenExecute(() -> helper.assertValueEqual(anchorAt(level, helper.absolutePos(BEND_FROM)).segments(),
				List.of(BEND), "the original's segments")).thenSucceed();
	}

	/**
	 * The block entity a structure places, not the id saved with its data, decides whether its data turns: the
	 * structure of {@link #aSegmentTurnsWithAStructurePlacedEveryWay} with its block entities' ids taken out, placed
	 * mirrored front to back and turned half round, still copies the segment.
	 */
	@GameTest(structure = WIDE, maxTicks = 20)
	public void anAnchorsDataTurnsWithAStructureWithoutItsId(GameTestHelper helper) {
		lay(helper, BEND_FROM, BEND);
		CompoundTag saved = bendStructure(helper).save(new CompoundTag());
		saved.getListOrEmpty(StructureTemplate.BLOCKS_TAG).compoundStream().forEach(
				block -> block.getCompound(StructureTemplate.BLOCK_TAG_NBT).ifPresent(data -> data.remove("id")));
		StructureTemplate withoutIds = new StructureTemplate();
		withoutIds.load(helper.getLevel().holderLookup(Registries.BLOCK), saved);
		placeCopy(helper, withoutIds, Mirror.FRONT_BACK, Rotation.CLOCKWISE_180);

		helper.startSequence()
				.thenIdle(1) // past the end of the tick the copies loaded in, when they are checked
				.thenExecute(() -> assertBendCopied(helper, Mirror.FRONT_BACK, Rotation.CLOCKWISE_180))
				.thenSucceed();
	}

	/** {@link #BEND} and the cells around it, from {@link #BEND_CORNER}, as a structure. */
	private static StructureTemplate bendStructure(GameTestHelper helper) {
		StructureTemplate template = new StructureTemplate();
		template.fillFromWorld(helper.getLevel(), helper.absolutePos(BEND_CORNER), new Vec3i(7, 1, 5), false,
				List.of());
		return template;
	}

	/** Places a structure of the cells from {@link #BEND_CORNER} at {@link #COPY_AT}, mirrored, then rotated. */
	private static void placeCopy(GameTestHelper helper, StructureTemplate template, Mirror mirror,
			Rotation rotation) {
		ServerLevel level = helper.getLevel();
		BlockPos at = helper.absolutePos(COPY_AT);
		template.placeInWorld(level, at, at, new StructurePlaceSettings().setMirror(mirror).setRotation(rotation),
				level.getRandom(), Block.UPDATE_CLIENTS);
	}

	/** Where vanilla moves a cell of the structure from {@link #BEND_CORNER} placed by {@link #placeCopy}. */
	private static BlockPos copied(GameTestHelper helper, BlockPos pos, Mirror mirror, Rotation rotation) {
		return helper.absolutePos(COPY_AT)
				.offset(StructureTemplate.transform(pos.subtract(BEND_CORNER), mirror, rotation, BlockPos.ZERO));
	}

	/**
	 * Asserts that the copies of {@link #BEND}'s anchors placed by {@link #placeCopy} hold one segment between them,
	 * leaving and arriving along the original's headings as vanilla turns a direction, and that the track holds it.
	 */
	private static void assertBendCopied(GameTestHelper helper, Mirror mirror, Rotation rotation) {
		ServerLevel level = helper.getLevel();
		String turn = mirror + ", " + rotation + ": ";
		BlockPos copiedFrom = copied(helper, BEND_FROM, mirror, rotation);
		BlockPos copiedTo = copied(helper, BEND_FROM.offset(BEND.offset()), mirror, rotation);
		List<Segment> held = anchorAt(level, copiedFrom).segments();
		helper.assertValueEqual(held.size(), 1, turn + "the segments the copy of the west anchor holds");
		Segment turned = held.getFirst();
		helper.assertValueEqual(copiedFrom.offset(turned.offset()), copiedTo, turn + "where its partner is");
		helper.assertValueEqual(anchorAt(level, copiedTo).segments(), List.of(turned.reversed()),
				turn + "the copy of the east anchor's segments");
		helper.assertValueEqual(turned.from().tangent(), new AnchorTangent(turnedBy(Heading.EAST, mirror, rotation),
				BEND.from().tangent().pitch()), turn + "the copy's start");
		helper.assertValueEqual(turned.to().tangent(),
				new AnchorTangent(turnedBy(Heading.SOUTH_EAST, mirror, rotation), BEND.to().tangent().pitch()),
				turn + "the copy's end");
		// The track gives a segment as its anchor first in BlockPos order stores it.
		SegmentIndex.Held indexed = copiedFrom.compareTo(copiedTo) < 0
				? new SegmentIndex.Held(copiedFrom, turned)
				: new SegmentIndex.Held(copiedTo, turned.reversed());
		helper.assertValueEqual(trackBetween(level, copiedFrom, copiedTo), List.of(indexed),
				turn + "the track between the copies");
	}

	/** A heading as vanilla turns a direction, mirrored, then rotated: each of its steps along x and z turned. */
	private static Heading turnedBy(Heading heading, Mirror mirror, Rotation rotation) {
		Vec3i east = rotation.rotate(mirror.mirror(Direction.EAST)).getUnitVec3i();
		Vec3i south = rotation.rotate(mirror.mirror(Direction.SOUTH)).getUnitVec3i();
		Vec3i step = east.multiply(heading.step().getX()).offset(south.multiply(heading.step().getZ()));
		for (Heading turned : Heading.values()) {
			if (turned.step().equals(step)) {
				return turned;
			}
		}
		throw new IllegalStateException("no heading along " + step);
	}

	/**
	 * An anchor saves the version of its data. Data saved before anchors saved one is version 1, the format this one
	 * still writes: without the version, the west anchor of a straight loads its segment as it was.
	 */
	@GameTest
	public void anAnchorLoadsSavedDataWithoutAVersion(GameTestHelper helper) {
		lay(helper, STRAIGHT_FROM, STRAIGHT);
		AnchorBlockEntity west = helper.getBlockEntity(STRAIGHT_FROM, AnchorBlockEntity.class);
		CompoundTag saved = west.saveCustomOnly(helper.getLevel().registryAccess());
		helper.assertValueEqual(saved.getInt("version"), Optional.of(1), "the saved version");
		saved.remove("version");
		west.removeSegment(AnchorSide.FRONT);

		west.loadCustomOnly(
				TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
		helper.assertValueEqual(west.segments(), List.of(STRAIGHT), "the segments loaded without a version");
		helper.succeed();
	}

	/**
	 * Saved data can hold any offset, from {@code /data} or a structure file, and the track indexes every chunk section
	 * in a segment's bounding box: 30 million blocks off along two axes, trillions of them. The anchor refuses a partner
	 * that far as it loads, and holds no segment there: 30 million east (1.9 million sections, so that a regression
	 * fails the test rather than hanging the run), or 512 east and 65 south, past what saved data allows along two axes.
	 */
	@GameTest
	public void anAnchorRefusesASavedPartnerTooFarAway(GameTestHelper helper) {
		lay(helper, STRAIGHT_FROM, STRAIGHT);
		AnchorBlockEntity west = helper.getBlockEntity(STRAIGHT_FROM, AnchorBlockEntity.class);
		for (int[] tooFar : new int[][]{{30_000_000, 0, 0}, {512, 0, 65}}) {
			CompoundTag saved = west.saveCustomOnly(helper.getLevel().registryAccess());
			saved.getCompound("front").orElseThrow().put("offset", new IntArrayTag(tooFar));

			west.loadCustomOnly(
					TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
			helper.assertValueEqual(west.segments(), List.of(),
					"the segments loaded with a partner " + Arrays.toString(tooFar) + " away");
			west.setSegment(STRAIGHT);
		}
		helper.succeed();
	}

	/** Rails don't hold entities up: an armor stand and an item dropped onto them fall to the floor. */
	@GameTest(maxTicks = 60)
	public void armorStandAndItemFallThroughARail(GameTestHelper helper) {
		floor(helper);
		lay(helper, STRAIGHT_FROM, STRAIGHT);
		helper.assertValueEqual(straightTrack(helper), laidStraight(helper), "the track");
		ArmorStand stand = helper.spawn(EntityTypes.ARMOR_STAND, new Vec3(4.5, 6, 5.5));
		ItemEntity item = helper.spawnItem(Items.IRON_INGOT, new Vec3(5.5, 6, 3.5));

		helper.succeedWhen(() -> {
			SkyLineAnchorGameTest.assertRestsOn(helper, stand, 1);
			SkyLineAnchorGameTest.assertRestsOn(helper, item, 1);
		});
	}

	/**
	 * The east anchor lets go of its copy after both loaded, which leaves it nothing pointing back west. Removing it
	 * still takes the west anchor's copy away: no segment can end where no anchor is.
	 */
	@GameTest(maxTicks = 60)
	public void removingAnAnchorDropsTheSegmentsEndingAtItThatItDidntHold(GameTestHelper helper) {
		floor(helper);
		lay(helper, STRAIGHT_FROM, STRAIGHT);
		BlockPos eastPos = STRAIGHT_FROM.offset(STRAIGHT.offset());
		BlockPos east = helper.absolutePos(eastPos);
		AnchorBlockEntity west = helper.getBlockEntity(STRAIGHT_FROM, AnchorBlockEntity.class);

		helper.startSequence()
				.thenIdle(1) // past the end of the tick the anchors loaded in, when they are checked
				.thenExecute(() -> {
					helper.getBlockEntity(eastPos, AnchorBlockEntity.class).removeSegment(AnchorSide.BACK);
					command(helper, "setblock %d %d %d air".formatted(east.getX(), east.getY(), east.getZ()));
					helper.assertValueEqual(west.segments(), List.of(), "the west anchor's segments");
				})
				.thenSucceed();
	}

	/**
	 * {@code /setblock} skips a block entity's removal side effects ({@code Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS}),
	 * so it is the hardest way to remove an anchor to notice.
	 */
	@GameTest(maxTicks = 60)
	public void setblockAirOnAnAnchorDropsItsSegment(GameTestHelper helper) {
		floor(helper);
		lay(helper, STRAIGHT_FROM, STRAIGHT);
		BlockPos east = helper.absolutePos(STRAIGHT_FROM.offset(STRAIGHT.offset()));

		command(helper, "setblock %d %d %d air".formatted(east.getX(), east.getY(), east.getZ()));

		helper.assertValueEqual(helper.getBlockEntity(STRAIGHT_FROM, AnchorBlockEntity.class).segments(), List.of(),
				"the west anchor's segments");
		helper.assertValueEqual(straightTrack(helper), List.of(), "the track");
		helper.succeed();
	}

	/**
	 * Turning an anchor in place, as {@code /setblock} with another heading or a debug stick does, keeps its block
	 * entity but turns its tangent away from its segment, which its partner holds leaving along the old heading. The
	 * segment goes at once, from both anchors and the track, as when the anchor is removed.
	 */
	@GameTest(maxTicks = 60)
	public void turningAnAnchorInPlaceDropsItsSegment(GameTestHelper helper) {
		floor(helper);
		lay(helper, STRAIGHT_FROM, STRAIGHT);
		BlockPos west = helper.absolutePos(STRAIGHT_FROM);
		AnchorBlockEntity turned = helper.getBlockEntity(STRAIGHT_FROM, AnchorBlockEntity.class);
		AnchorBlockEntity east = helper.getBlockEntity(STRAIGHT_FROM.offset(STRAIGHT.offset()),
				AnchorBlockEntity.class);

		command(helper, "setblock %d %d %d principia:sky_line_anchor[heading=north]".formatted(west.getX(),
				west.getY(), west.getZ()));

		helper.assertTrue(helper.getBlockEntity(STRAIGHT_FROM, AnchorBlockEntity.class) == turned,
				"the turned anchor kept its block entity");
		helper.assertValueEqual(turned.segments(), List.of(), "the turned anchor's segments");
		helper.assertValueEqual(east.segments(), List.of(), "the east anchor's segments");
		helper.assertValueEqual(straightTrack(helper), List.of(), "the track");
		helper.succeed();
	}

	/**
	 * Two anchors that load in the same tick holding different segments between them: each finds the other doesn't
	 * hold its segment, and drops it.
	 */
	@GameTest(maxTicks = 60)
	public void anchorsThatDisagreeOnTheirSegmentDropItWhenTheyLoad(GameTestHelper helper) {
		floor(helper);
		BlockPos eastPos = STRAIGHT_FROM.offset(STRAIGHT.offset());
		place(helper, STRAIGHT_FROM, STRAIGHT);
		// The east anchor's copy leaves it 10° down, not level.
		place(helper, eastPos, new Segment(STRAIGHT.reversed().offset(),
				new Segment.End(new AnchorTangent(Heading.EAST, 10), AnchorSide.BACK), STRAIGHT.from()));
		AnchorBlockEntity west = helper.getBlockEntity(STRAIGHT_FROM, AnchorBlockEntity.class);
		AnchorBlockEntity east = helper.getBlockEntity(eastPos, AnchorBlockEntity.class);

		helper.succeedWhen(() -> {
			helper.assertValueEqual(west.segments(), List.of(), "the west anchor's segments");
			helper.assertValueEqual(east.segments(), List.of(), "the east anchor's segments");
			helper.assertValueEqual(straightTrack(helper), List.of(), "the track");
		});
	}

	/**
	 * A segment leaves its anchor along the anchor's tangent, the heading of its block and its pitch, or along its
	 * reverse. One that would leave an anchor facing east, level, 10° up or heading north-east instead is refused, and
	 * the anchor holds nothing.
	 */
	@GameTest
	public void anAnchorRefusesASegmentThatDoesntLeaveAlongItsTangent(GameTestHelper helper) {
		helper.setBlock(STRAIGHT_FROM, SkyLineAnchorGameTest.anchor(Heading.EAST));
		AnchorBlockEntity anchor = helper.getBlockEntity(STRAIGHT_FROM, AnchorBlockEntity.class);

		for (Segment wrong : List.of(segment(STRAIGHT.offset(), Heading.EAST, 10, Heading.EAST, 0),
				segment(STRAIGHT.offset(), Heading.NORTH_EAST, 0, Heading.EAST, 0))) {
			assertThrows(helper, IllegalArgumentException.class, () -> anchor.setSegment(wrong),
					"the anchor took " + wrong);
		}

		helper.assertValueEqual(anchor.segments(), List.of(), "the anchor's segments");
		helper.succeed();
	}

	/** An anchor's segments leave along its tangent, so its pitch changes only while it holds none. */
	@GameTest
	public void anAnchorsPitchChangesOnlyWhileItHoldsNoSegment(GameTestHelper helper) {
		lay(helper, STRAIGHT_FROM, STRAIGHT);
		AnchorBlockEntity west = helper.getBlockEntity(STRAIGHT_FROM, AnchorBlockEntity.class);

		assertThrows(helper, IllegalStateException.class, () -> west.setPitch(30),
				"the anchor's pitch changed under its segment");
		helper.assertValueEqual(west.pitch(), 0f, "the anchor's pitch");
		helper.assertValueEqual(west.segments(), List.of(STRAIGHT), "the anchor's segments");

		west.removeSegment(AnchorSide.FRONT);
		west.setPitch(30);
		helper.assertValueEqual(west.pitch(), 30f, "the anchor's pitch once it holds no segment");
		helper.succeed();
	}

	/**
	 * A segment 5 east whose west anchor is pitched 30° up, in a chunk of its own, unloaded and loaded again. While it
	 * is unloaded the segment is out of the track. Once it has loaded again, and the anchors have been checked against
	 * each other at the end of that tick, both anchors and the track hold it, and the west anchor still has its pitch.
	 */
	@GameTest(maxTicks = 200)
	public void anchorsAndTheTrackStillHoldASegmentAfterItsChunkReloads(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Segment pitched = segment(new Vec3i(5, 0, 0), Heading.EAST, 30, Heading.EAST, 0);
		BlockPos from = farChunk(helper, 40, 0).offset(2, 0, 4);
		BlockPos to = from.offset(pitched.offset());
		setForced(level, from, true);
		layAt(level, from, pitched);
		AnchorBlockEntity before = anchorAt(level, from);
		setForced(level, from, false);
		List<SegmentIndex.Held> laid = List.of(new SegmentIndex.Held(from, pitched));

		helper.startSequence()
				.thenWaitUntil(() -> assertUnloaded(helper, before))
				.thenExecute(() -> {
					helper.assertValueEqual(trackBetween(level, from, to), List.of(), "the track while unloaded");
					setForced(level, from, true);
				})
				.thenWaitUntil(() -> helper.assertValueEqual(trackBetween(level, from, to), laid, "the track"))
				.thenIdle(1)
				.thenExecute(() -> {
					helper.assertValueEqual(anchorAt(level, from).segments(), List.of(pitched),
							"the west anchor's segments");
					helper.assertValueEqual(anchorAt(level, to).segments(), List.of(pitched.reversed()),
							"the east anchor's segments");
					helper.assertValueEqual(trackBetween(level, from, to), laid, "the track");
					helper.assertValueEqual(anchorAt(level, from).pitch(), 30f, "the anchor's pitch");
					setForced(level, from, false);
				})
				.thenSucceed();
	}

	/**
	 * {@link #APART}, whose east anchor's chunk unloads while the west one's stays forced. The west anchor is removed
	 * while the east one is unloaded, which holds the segment until it loads and finds no anchor there. Left with no
	 * segment, it stays, alone.
	 */
	@GameTest(maxTicks = 300)
	public void anAnchorWhosePartnerWentWhileItWasUnloadedDropsTheSegmentWhenItLoads(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos west = farChunk(helper, 0, 80).offset(2, 0, 4);
		BlockPos east = west.offset(APART.offset());
		setForced(level, west, true);
		setForced(level, east, true);
		layAt(level, west, APART);
		AnchorBlockEntity eastBefore = anchorAt(level, east);
		setForced(level, east, false);

		helper.startSequence()
				.thenWaitUntil(() -> assertUnloaded(helper, eastBefore))
				.thenExecute(() -> {
					command(helper, "setblock %d %d %d air".formatted(west.getX(), west.getY(), west.getZ()));
					setForced(level, east, true);
				})
				.thenWaitUntil(() -> assertAlone(helper, level, east, "the east anchor"))
				.thenExecute(() -> {
					setForced(level, west, false);
					setForced(level, east, false);
				})
				.thenSucceed();
	}

	/**
	 * As {@link #anAnchorWhosePartnerWentWhileItWasUnloadedDropsTheSegmentWhenItLoads}, but the west anchor stays and
	 * changes its segment while the east one is unloaded. When the east one loads, it doesn't hold the west one's new
	 * segment, nor the west one its old: both drop theirs, though only the east one loaded, and both stay, alone.
	 */
	@GameTest(maxTicks = 300)
	public void aSegmentChangedWhileItsPartnerWasUnloadedIsDroppedWhenThePartnerLoads(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos west = farChunk(helper, 0, -80).offset(2, 0, 4);
		BlockPos east = west.offset(APART.offset());
		setForced(level, west, true);
		setForced(level, east, true);
		layAt(level, west, APART);
		AnchorBlockEntity eastBefore = anchorAt(level, east);
		setForced(level, east, false);

		helper.startSequence()
				.thenWaitUntil(() -> assertUnloaded(helper, eastBefore))
				.thenExecute(() -> {
					// Now leaving the west anchor 10° up. Its pitch changes only while it holds no segment.
					AnchorBlockEntity changed = anchorAt(level, west);
					changed.removeSegment(AnchorSide.FRONT);
					changed.setPitch(10);
					changed.setSegment(new Segment(APART.offset(),
							new Segment.End(new AnchorTangent(Heading.EAST, 10), AnchorSide.FRONT), APART.to()));
					setForced(level, east, true);
				})
				.thenWaitUntil(() -> {
					assertAlone(helper, level, east, "the east anchor");
					assertAlone(helper, level, west, "the west anchor");
				})
				.thenExecute(() -> {
					setForced(level, west, false);
					setForced(level, east, false);
				})
				.thenSucceed();
	}

	static Segment segment(Vec3i offset, Heading heading, float pitch, Heading arriving, float arrivingPitch) {
		return new Segment(offset, new Segment.End(new AnchorTangent(heading, pitch), AnchorSide.FRONT),
				new Segment.End(new AnchorTangent(arriving, arrivingPitch), AnchorSide.BACK));
	}

	/** Places the anchors at both ends of a segment, each holding it, as laying track will. */
	private static void lay(GameTestHelper helper, BlockPos from, Segment segment) {
		layAt(helper.getLevel(), helper.absolutePos(from), segment);
	}

	/** {@link #lay}, at a position in the level rather than in the test. */
	static void layAt(ServerLevel level, BlockPos from, Segment segment) {
		placeAt(level, from, segment);
		placeAt(level, from.offset(segment.offset()), segment.reversed());
	}

	/** An anchor holding one segment, facing the way the segment's tangent there does. */
	private static void place(GameTestHelper helper, BlockPos pos, Segment segment) {
		placeAt(helper.getLevel(), helper.absolutePos(pos), segment);
	}

	private static void placeAt(ServerLevel level, BlockPos pos, Segment segment) {
		AnchorTangent tangent = segment.from().tangent();
		level.setBlockAndUpdate(pos, SkyLineAnchorGameTest.anchor(tangent.heading()));
		AnchorBlockEntity anchor = anchorAt(level, pos);
		anchor.setPitch(tangent.pitch());
		anchor.setSegment(segment);
	}

	static AnchorBlockEntity anchorAt(ServerLevel level, BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof AnchorBlockEntity anchor) {
			return anchor;
		}
		throw new IllegalStateException("no anchor at " + pos);
	}

	/** Asserts that an anchor is there, holding no segment. */
	private static void assertAlone(GameTestHelper helper, ServerLevel level, BlockPos pos, String name) {
		helper.assertTrue(level.getBlockEntity(pos) instanceof AnchorBlockEntity anchor && anchor.segments().isEmpty(),
				name + " is gone or still holds a segment");
	}

	/** The segments the level's track finds in the cells from one corner to another. */
	private static List<SegmentIndex.Held> trackBetween(ServerLevel level, BlockPos from, BlockPos to) {
		return Track.of(level).index().near(AABB.encapsulatingFullBlocks(from, to));
	}

	/** The segments the track finds between the anchors of {@link #STRAIGHT} in the test. */
	private static List<SegmentIndex.Held> straightTrack(GameTestHelper helper) {
		return trackBetween(helper.getLevel(), helper.absolutePos(STRAIGHT_FROM),
				helper.absolutePos(STRAIGHT_FROM.offset(STRAIGHT.offset())));
	}

	/** {@link #STRAIGHT} in the test as the track gives it: as its west anchor, first in block order, stores it. */
	private static List<SegmentIndex.Held> laidStraight(GameTestHelper helper) {
		return List.of(new SegmentIndex.Held(helper.absolutePos(STRAIGHT_FROM), STRAIGHT));
	}

	/**
	 * The north-west corner, at the test's height plus 3, of a chunk far from the test's, loaded only while forced.
	 * The tests sit a few chunks apart, so each test that uses one goes its own way, and a forced chunk keeps no other
	 * test's chunk loaded.
	 */
	private static BlockPos farChunk(GameTestHelper helper, int chunksEast, int chunksSouth) {
		BlockPos origin = helper.absolutePos(BlockPos.ZERO);
		return new BlockPos(SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(origin.getX()) + chunksEast),
				origin.getY() + 3,
				SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(origin.getZ()) + chunksSouth));
	}

	/**
	 * Asserts that an anchor's chunk has unloaded, which removes its block entity. A server unloads the chunks no
	 * ticket holds only in a tick's spare time, and the game test server runs ticks back to back, so this unloads
	 * them as a server with time to spare would.
	 */
	static void assertUnloaded(GameTestHelper helper, AnchorBlockEntity anchor) {
		helper.getLevel().getChunkSource().tick(() -> true, false);
		if (!anchor.isRemoved()) {
			// The unload waits for the chunk's last loads and generation, on worker threads and the disk. A server's
			// ticks are 50 ms apart, but the game test server's run back to back: without a pause, that work can
			// outlast all of a test's ticks on a busy machine.
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
		}
		helper.assertTrue(anchor.isRemoved(),
				"the chunk of the anchor at " + anchor.getBlockPos() + " hasn't unloaded");
	}

	static void setForced(ServerLevel level, BlockPos pos, boolean forced) {
		level.setChunkForced(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()),
				forced);
	}

	/** Fails the test unless an action throws an exception of a type. */
	private static void assertThrows(GameTestHelper helper, Class<? extends RuntimeException> type, Runnable action,
			String failure) {
		try {
			action.run();
		} catch (RuntimeException thrown) {
			if (type.isInstance(thrown)) {
				return;
			}
			throw thrown;
		}
		helper.fail(failure);
	}

	private static void command(GameTestHelper helper, String command) {
		MinecraftServer server = helper.getLevel().getServer();
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
	}

	/** Stone across the bottom of the test area. */
	static void floor(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
			}
		}
	}
}
