package io.github.pavelgar.principia.test;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import io.github.pavelgar.principia.skyline.AnchorBlock;
import io.github.pavelgar.principia.skyline.AnchorBlockEntity;
import io.github.pavelgar.principia.skyline.AnchorSide;
import io.github.pavelgar.principia.skyline.RailBreaking;
import io.github.pavelgar.principia.skyline.RailPoint;
import io.github.pavelgar.principia.skyline.RailSide;
import io.github.pavelgar.principia.skyline.Segment;
import io.github.pavelgar.principia.skyline.SkyLine;
import io.github.pavelgar.principia.skyline.TrackStart;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * Breaking Sky-Line track: its rails fall along the segments that break, an anchor that breaks drops its own rail, and
 * an anchor left with no segment stays.
 *
 * <p>The tests run in a 16 × 10 × 16 area on a stone floor. A straight 10 east from (2, 3, 4), anchors heading east,
 * has its centre line at y = 3.9375 and z = 4.5, from x = 2.5 to 12.5: its 10 rails fall from x = 3, 4, ..., 12, as
 * far below the line as half an item entity's height (0.125), and the west anchor's own rail from its point, x = 2.5.
 * The west anchor's beam ends at (2, 3, 3) and (2, 3, 5).
 */
public class SkyLineBreakingGameTest {
	private static final String AREA = "principia-test:sky_line";
	private static final BlockPos WEST = new BlockPos(2, 3, 4);
	private static final BlockPos EAST = WEST.east(10);
	private static final double EXACT = 1e-6;
	/** How far a rail may fall from beside its point, either way. */
	private static final double SPREAD = 0.25;

	/**
	 * Mining an end of an anchor's beam in survival breaks the whole anchor: its own rail falls from its point, and its
	 * segment's rails along the segment. Its partner, left with no segment, stays, dropping nothing more.
	 */
	@GameTest(structure = AREA)
	public void miningAnAnchorInSurvivalDropsItsRailAndItsSegmentsRailsAlongThem(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		SkyHookGameTest.lay(helper, WEST, SkyHookGameTest.straight(10));
		TestPlayer player = TestPlayer.place(helper, GameType.SURVIVAL, new Vec3(2.5, 1, 1.5), 0, 0);

		player.gameMode.destroyBlock(helper.absolutePos(WEST.north()));

		assertRailsFellAlongTheStraight(helper, true);
		helper.assertBlockPresent(Blocks.AIR, WEST);
		helper.assertBlockPresent(Blocks.AIR, WEST.south());
		helper.runAfterDelay(1, () -> {
			assertAlone(helper, EAST);
			helper.assertValueEqual(rails(helper), 11, "the rails on the ground");
			TestPlayer.remove(helper, player);
			helper.succeed();
		});
	}

	/**
	 * In creative, mining an anchor drops nothing, at its middle or at an end of its beam. The anchor between them,
	 * left with no segment, stays.
	 */
	@GameTest(structure = AREA)
	public void miningAnAnchorInCreativeDropsNothing(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		SkyHookGameTest.lay(helper, WEST, SkyHookGameTest.straight(10), SkyHookGameTest.straight(3));
		TestPlayer player = TestPlayer.place(helper, GameType.CREATIVE, new Vec3(2.5, 1, 1.5), 0, 0);

		player.gameMode.destroyBlock(helper.absolutePos(WEST.north()));
		player.gameMode.destroyBlock(helper.absolutePos(EAST.east(3)));

		helper.runAfterDelay(1, () -> {
			assertGone(helper, WEST);
			assertAlone(helper, EAST);
			assertGone(helper, EAST.east(3));
			helper.assertValueEqual(rails(helper), 0, "the rails on the ground");
			TestPlayer.remove(helper, player);
			helper.succeed();
		});
	}

	/**
	 * An explosion at an end of an anchor's beam breaks the anchor: its own rail falls, and all its segment's rails
	 * along it.
	 */
	@GameTest(structure = AREA)
	public void anExplosionAtAnEndDropsTheAnchorsRailAndItsSegmentsRails(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		SkyHookGameTest.lay(helper, WEST, SkyHookGameTest.straight(10));
		Vec3 end = helper.absoluteVec(Vec3.atCenterOf(WEST.north()));

		helper.getLevel().explode(null, end.x, end.y, end.z, 2, Level.ExplosionInteraction.BLOCK);

		assertRailsFellAlongTheStraight(helper, true);
		assertGone(helper, WEST);
		helper.succeed();
	}

	/**
	 * Destroying an end of an anchor's beam with a command ({@code /setblock ... destroy}) breaks the anchor with its
	 * drops, as destroying the anchor itself does: its own rail falls, and the segment's rails along it.
	 */
	@GameTest(structure = AREA)
	public void destroyingAnEndWithACommandDropsTheAnchorsRailAndItsSegmentsRails(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		SkyHookGameTest.lay(helper, WEST, SkyHookGameTest.straight(10));
		BlockPos end = helper.absolutePos(WEST.north());

		SkyLineAnchorGameTest.command(helper,
				"setblock %d %d %d air destroy".formatted(end.getX(), end.getY(), end.getZ()));

		assertRailsFellAlongTheStraight(helper, true);
		assertGone(helper, WEST);
		helper.succeed();
	}

	/**
	 * Replacing an end of an anchor's beam with a command removes the anchor without drops, as replacing the anchor
	 * itself does. Its partner stays.
	 */
	@GameTest(structure = AREA)
	public void replacingAnEndWithACommandDropsNothing(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		SkyHookGameTest.lay(helper, WEST, SkyHookGameTest.straight(10));
		BlockPos end = helper.absolutePos(WEST.north());

		SkyLineAnchorGameTest.command(helper, "setblock %d %d %d stone".formatted(end.getX(), end.getY(), end.getZ()));

		helper.runAfterDelay(1, () -> {
			helper.assertBlockPresent(Blocks.STONE, WEST.north());
			assertGone(helper, WEST);
			assertAlone(helper, EAST);
			helper.assertValueEqual(rails(helper), 0, "the rails on the ground");
			helper.succeed();
		});
	}

	/**
	 * An anchor that loses its last segment stays, with its ends and its water: the chain runs east from (2, 3, 4)
	 * through a waterlogged anchor at (8, 3, 4) to (14, 3, 4), and both ends of it are removed with commands, which
	 * drop nothing.
	 */
	@GameTest(structure = AREA)
	public void anAnchorThatLosesItsLastSegmentStays(GameTestHelper helper) {
		BlockPos middle = WEST.east(6);
		SkyHookGameTest.lay(helper, WEST, SkyHookGameTest.straight(6), SkyHookGameTest.straight(6));
		helper.setBlock(middle, helper.getBlockState(middle).setValue(AnchorBlock.WATERLOGGED, true));

		setAir(helper, middle.east(6));
		setAir(helper, WEST);

		helper.runAfterDelay(1, () -> {
			assertAlone(helper, middle);
			helper.assertBlockProperty(middle, AnchorBlock.WATERLOGGED, true);
			helper.assertValueEqual(rails(helper), 0, "the rails on the ground");
			helper.succeed();
		});
	}

	/**
	 * Breaking a rail, as a client sends it once mining is done, removes its whole segment from both anchors and drops
	 * its rails along it, but not the anchors' own: the anchor between it and the next segment keeps that one, and the
	 * west anchor, left alone, stays, and a click with a Sky-Line selects it again. The player stands 1 block south of
	 * the right rail, 5 along it, and mines it as long as its anchor takes them.
	 */
	@GameTest(structure = AREA, maxTicks = 300)
	public void breakingARailRemovesItsSegmentAndDropsItsRailsAlongIt(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		SkyHookGameTest.lay(helper, WEST, SkyHookGameTest.straight(10), SkyHookGameTest.straight(3));
		TestPlayer player = TestPlayer.place(helper, GameType.SURVIVAL, new Vec3(7.5, 2, 6.5), 180, -20);
		RailPoint onTheRail = new RailPoint(helper.absolutePos(WEST), AnchorSide.FRONT, RailSide.RIGHT, 5);
		RailBreaking.startMining(player, onTheRail);

		helper.startSequence()
				.thenIdle(miningTicks(helper, player))
				.thenExecute(() -> {
					RailBreaking.breakRail(player, onTheRail);
					assertRailsFellAlongTheStraight(helper, false);
					helper.assertValueEqual(helper.getBlockEntity(EAST, AnchorBlockEntity.class).segments(),
							List.of(SkyHookGameTest.straight(3)), "the segments of the anchor between them");
				})
				.thenIdle(1)
				.thenExecute(() -> {
					assertAlone(helper, WEST);
					player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(SkyLine.RAIL));
					BlockPos west = helper.absolutePos(WEST);
					player.gameMode.useItemOn(player, helper.getLevel(), player.getMainHandItem(),
							InteractionHand.MAIN_HAND,
							new BlockHitResult(Vec3.atCenterOf(west), Direction.UP, west, false));
					helper.assertValueEqual(Optional.ofNullable(TrackStart.of(player)),
							Optional.of(new TrackStart(west, Optional.empty())), "the selection");
					TestPlayer.remove(helper, player);
				})
				.thenSucceed();
	}

	/**
	 * The server times mining a rail as vanilla's times mining a block: from the start the client tells it of, a break
	 * that comes before 0.7 of the anchor's mining time is refused. So is one with no start, or after starting on
	 * another segment. Each mines from the same spot; the one who mined as long as the anchor takes them breaks it, and
	 * only then.
	 */
	@GameTest(structure = AREA, maxTicks = 300)
	public void theServerBreaksARailOnlyOnceMinedForTheAnchorsMiningTime(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		Segment straight = SkyHookGameTest.straight(10);
		SkyHookGameTest.lay(helper, WEST, straight, SkyHookGameTest.straight(3));
		RailPoint onTheRail = new RailPoint(helper.absolutePos(WEST), AnchorSide.FRONT, RailSide.RIGHT, 5);
		RailPoint onTheNext = new RailPoint(helper.absolutePos(EAST), AnchorSide.FRONT, RailSide.RIGHT, 1);
		TestPlayer early = TestPlayer.place(helper, GameType.SURVIVAL, new Vec3(7.5, 2, 6.5), 180, -20);
		TestPlayer unstarted = TestPlayer.place(helper, GameType.SURVIVAL, new Vec3(7.5, 2, 6.5), 180, -20);
		TestPlayer elsewhere = TestPlayer.place(helper, GameType.SURVIVAL, new Vec3(10.5, 2, 6.5), 180, -20);
		TestPlayer miner = TestPlayer.place(helper, GameType.SURVIVAL, new Vec3(7.5, 2, 6.5), 180, -20);
		RailBreaking.startMining(early, onTheRail);
		RailBreaking.startMining(elsewhere, onTheNext);
		RailBreaking.startMining(miner, onTheRail);
		int full = miningTicks(helper, miner);
		// 0.7 of the mining time, less a tick: one more tick of progress than this still falls short.
		int tooEarly = (int) Math.ceil(0.7 * full) - 2;

		helper.startSequence()
				.thenIdle(tooEarly)
				.thenExecute(() -> {
					RailBreaking.breakRail(early, onTheRail);
					helper.assertValueEqual(helper.getBlockEntity(WEST, AnchorBlockEntity.class).segments(),
							List.of(straight), "the west anchor's segments after a break " + tooEarly + " ticks in");
				})
				.thenIdle(full - tooEarly)
				.thenExecute(() -> {
					RailBreaking.breakRail(unstarted, onTheRail);
					RailBreaking.breakRail(elsewhere, onTheRail);
					helper.assertValueEqual(helper.getBlockEntity(WEST, AnchorBlockEntity.class).segments(),
							List.of(straight), "the west anchor's segments after breaks with no start or another's");
					RailBreaking.breakRail(miner, onTheRail);
					helper.assertValueEqual(helper.getBlockEntity(WEST, AnchorBlockEntity.class).segments(), List.of(),
							"the west anchor's segments after a break " + full + " ticks in");
					TestPlayer.remove(helper, early, unstarted, elsewhere, miner);
				})
				.thenSucceed();
	}

	/**
	 * Claim mods keep players from breaking rails in their claims, through either convention they share: one player
	 * is kept from the west anchor's cell through Fabric API's break event, which then reports the break canceled;
	 * another from the east anchor's through the common protection API. Neither breaks the segment between them, mined
	 * as long as it takes; a player no claim keeps out does.
	 */
	@GameTest(structure = AREA, maxTicks = 300)
	public void aClaimOverEitherAnchorKeepsAPlayerFromBreakingTheirRail(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		Segment straight = SkyHookGameTest.straight(10);
		SkyHookGameTest.lay(helper, WEST, straight);
		RailPoint onTheRail = new RailPoint(helper.absolutePos(WEST), AnchorSide.FRONT, RailSide.RIGHT, 5);
		TestPlayer byEvent = TestPlayer.place(helper, GameType.SURVIVAL, new Vec3(7.5, 2, 6.5), 180, -20);
		TestPlayer byProtection = TestPlayer.place(helper, GameType.SURVIVAL, new Vec3(7.5, 2, 6.5), 180, -20);
		TestPlayer owner = TestPlayer.place(helper, GameType.SURVIVAL, new Vec3(7.5, 2, 6.5), 180, -20);
		TestClaims.byEvent(byEvent, helper.absolutePos(WEST));
		TestClaims.byCommonProtection(byProtection, helper.absolutePos(EAST));
		for (TestPlayer player : List.of(byEvent, byProtection, owner)) {
			RailBreaking.startMining(player, onTheRail);
		}

		helper.startSequence()
				.thenIdle(miningTicks(helper, owner))
				.thenExecute(() -> {
					RailBreaking.breakRail(byEvent, onTheRail);
					RailBreaking.breakRail(byProtection, onTheRail);
					helper.assertValueEqual(helper.getBlockEntity(WEST, AnchorBlockEntity.class).segments(),
							List.of(straight), "the west anchor's segments after the claimed breaks");
					helper.assertValueEqual(TestClaims.canceled(byEvent), List.of(helper.absolutePos(WEST)),
							"the breaks canceled for the player kept out by the event");
					RailBreaking.breakRail(owner, onTheRail);
					helper.assertValueEqual(helper.getBlockEntity(WEST, AnchorBlockEntity.class).segments(), List.of(),
							"the west anchor's segments after the owner's break");
					TestClaims.lift(byEvent, byProtection, owner);
					TestPlayer.remove(helper, byEvent, byProtection, owner);
				})
				.thenSucceed();
	}

	/** In creative, breaking a rail drops nothing. */
	@GameTest(structure = AREA)
	public void breakingARailInCreativeDropsNothing(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		SkyHookGameTest.lay(helper, WEST, SkyHookGameTest.straight(10));
		TestPlayer player = TestPlayer.place(helper, GameType.CREATIVE, new Vec3(7.5, 2, 6.5), 180, -20);

		RailBreaking.breakRail(player, new RailPoint(helper.absolutePos(WEST), AnchorSide.FRONT, RailSide.RIGHT, 5));

		helper.assertValueEqual(helper.getBlockEntity(WEST, AnchorBlockEntity.class).segments(), List.of(),
				"the west anchor's segments");
		helper.assertValueEqual(rails(helper), 0, "the rails on the ground");
		TestPlayer.remove(helper, player);
		helper.succeed();
	}

	/**
	 * The server checks what the client sends. It refuses a rail point out of reach (4.5 and 1 for lag: this one is
	 * about 9.1 from the eyes), a segment that isn't there, a player in adventure mode, a spectator, and a creative
	 * player holding a sword, which breaks no blocks. Each leaves the segment and drops nothing.
	 */
	@GameTest(structure = AREA, maxTicks = 300)
	public void theServerRefusesABreakOutOfReachGoneOrByAPlayerWhoMayNotBreakIt(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		Segment straight = SkyHookGameTest.straight(10);
		SkyHookGameTest.lay(helper, WEST, straight);
		RailPoint onTheRail = new RailPoint(helper.absolutePos(WEST), AnchorSide.FRONT, RailSide.RIGHT, 5);

		RailPoint gonePoint = new RailPoint(helper.absolutePos(WEST), AnchorSide.BACK, RailSide.RIGHT, 5);
		TestPlayer far = TestPlayer.place(helper, GameType.SURVIVAL, new Vec3(7.5, 1, 14.5), 180, 0);
		TestPlayer gone = TestPlayer.place(helper, GameType.SURVIVAL, new Vec3(7.5, 2, 6.5), 180, -20);
		TestPlayer adventure = TestPlayer.place(helper, GameType.ADVENTURE, new Vec3(7.5, 2, 6.5), 180, -20);
		TestPlayer spectator = TestPlayer.place(helper, GameType.SPECTATOR, new Vec3(7.5, 2, 6.5), 180, -20);
		TestPlayer sword = TestPlayer.place(helper, GameType.CREATIVE, new Vec3(7.5, 2, 6.5), 180, -20);
		sword.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
		// Each mines as long as it takes, so each is refused for its own reason, not for mining too short.
		for (TestPlayer player : List.of(far, adventure, spectator, sword)) {
			RailBreaking.startMining(player, onTheRail);
		}
		RailBreaking.startMining(gone, gonePoint);

		helper.startSequence()
				.thenIdle(miningTicks(helper, adventure))
				.thenExecute(() -> {
					RailBreaking.breakRail(far, onTheRail);
					RailBreaking.breakRail(gone, gonePoint);
					RailBreaking.breakRail(adventure, onTheRail);
					RailBreaking.breakRail(spectator, onTheRail);
					RailBreaking.breakRail(sword, onTheRail);
					helper.assertValueEqual(helper.getBlockEntity(WEST, AnchorBlockEntity.class).segments(),
							List.of(straight), "the west anchor's segments");
					helper.assertValueEqual(helper.getBlockEntity(EAST, AnchorBlockEntity.class).segments(),
							List.of(straight.reversed()), "the east anchor's segments");
					helper.assertValueEqual(rails(helper), 0, "the rails on the ground");
					TestPlayer.remove(helper, far, gone, adventure, spectator, sword);
				})
				.thenSucceed();
	}

	/**
	 * How many ticks a player's client mines a rail for, by hand: until the anchor block's mining reaches 1, as the
	 * client counts it, which is the earliest it sends the break. The tests' players stand in the air, within reach of
	 * the rail, so it takes them five times as long as on the ground, as vanilla's mining does: about 105 ticks.
	 */
	private static int miningTicks(GameTestHelper helper, TestPlayer player) {
		BlockPos west = helper.absolutePos(WEST);
		float perTick = SkyLine.ANCHOR.defaultBlockState().getDestroyProgress(player, helper.getLevel(), west);
		return (int) Math.ceil(1 / perTick);
	}

	/**
	 * Asserts the 10 rails of the straight from {@link #WEST} lie along it, each a little beside its point, and with
	 * them, if it broke, the west anchor's own rail beside its point.
	 */
	private static void assertRailsFellAlongTheStraight(GameTestHelper helper, boolean westAnchorBroke) {
		List<ItemEntity> items = helper.getEntities(EntityTypes.ITEM).stream()
				.sorted(Comparator.comparingDouble(ItemEntity::getX)).toList();
		int first = westAnchorBroke ? 1 : 0;
		helper.assertValueEqual(rails(helper), 10 + first, "the rails dropped");
		helper.assertValueEqual(items.size(), 10 + first, "the items dropped");
		for (int i = 0; i < items.size(); i++) {
			Vec3 at = helper.relativeVec(items.get(i).position());
			// The anchor's point is at x = 2.5, the segment's from 3 on: within the spread, sorted by x, it comes first.
			Vec3 point = new Vec3(i < first ? 2.5 : 3 + i - first, 3.9375 - 0.125, 4.5);
			helper.assertTrue(Math.abs(at.x - point.x) <= SPREAD + EXACT && Math.abs(at.y - point.y) < EXACT
					&& Math.abs(at.z - point.z) <= SPREAD + EXACT, "rail " + i + " fell from " + at + ", not " + point);
		}
	}

	private static void setAir(GameTestHelper helper, BlockPos pos) {
		BlockPos at = helper.absolutePos(pos);
		SkyLineAnchorGameTest.command(helper, "setblock %d %d %d air".formatted(at.getX(), at.getY(), at.getZ()));
	}

	/** How many Sky-Lines lie in the test's area. */
	private static int rails(GameTestHelper helper) {
		return helper.getEntities(EntityTypes.ITEM).stream().filter(item -> item.getItem().is(SkyLine.RAIL))
				.mapToInt(item -> item.getItem().getCount()).sum();
	}

	/** Asserts an anchor is there, its ends with it, holding no segment. */
	private static void assertAlone(GameTestHelper helper, BlockPos anchor) {
		helper.assertValueEqual(helper.getBlockEntity(anchor, AnchorBlockEntity.class).segments(), List.of(),
				"the segments of the anchor at " + anchor);
		helper.assertBlockPresent(SkyLine.BEAM_END, anchor.north());
		helper.assertBlockPresent(SkyLine.BEAM_END, anchor.south());
	}

	/** Asserts an anchor is gone, its ends with it. */
	private static void assertGone(GameTestHelper helper, BlockPos anchor) {
		helper.assertBlockNotPresent(SkyLine.ANCHOR, anchor);
		for (BlockPos end : new BlockPos[]{anchor.north(), anchor.south()}) {
			helper.assertBlockNotPresent(SkyLine.BEAM_END, end);
		}
	}
}
