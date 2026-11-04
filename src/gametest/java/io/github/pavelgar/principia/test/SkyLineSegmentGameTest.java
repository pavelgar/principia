package io.github.pavelgar.principia.test;

import java.util.List;
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
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
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
	 * A segment 512 blocks long, far past the limits (which only placing checks), so that its east anchor's chunk
	 * unloads while the west one's stays forced. A forced chunk keeps those within 2 of it loaded, and one 6 chunks
	 * away never unloaded in this test. The west anchor is removed while the east one is unloaded, which holds the
	 * segment until it loads and finds no anchor there. Left with no segment, it stays, alone.
	 */
	@GameTest(maxTicks = 300)
	public void anAnchorWhosePartnerWentWhileItWasUnloadedDropsTheSegmentWhenItLoads(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos west = farChunk(helper, 0, 80).offset(2, 0, 4);
		BlockPos east = west.east(512);
		setForced(level, west, true);
		setForced(level, east, true);
		layAt(level, west, segment(new Vec3i(512, 0, 0), Heading.EAST, 0, Heading.EAST, 0));
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
		BlockPos east = west.east(512);
		setForced(level, west, true);
		setForced(level, east, true);
		Segment level512 = segment(new Vec3i(512, 0, 0), Heading.EAST, 0, Heading.EAST, 0);
		layAt(level, west, level512);
		AnchorBlockEntity eastBefore = anchorAt(level, east);
		setForced(level, east, false);

		helper.startSequence()
				.thenWaitUntil(() -> assertUnloaded(helper, eastBefore))
				.thenExecute(() -> {
					// Now leaving the west anchor 10° up. Its pitch changes only while it holds no segment.
					AnchorBlockEntity changed = anchorAt(level, west);
					changed.removeSegment(AnchorSide.FRONT);
					changed.setPitch(10);
					changed.setSegment(new Segment(level512.offset(),
							new Segment.End(new AnchorTangent(Heading.EAST, 10), AnchorSide.FRONT), level512.to()));
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
