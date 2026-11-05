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
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
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
	 * The server times mining a rail as vanilla's times mining a block, counting the tick mining starts on: a break the
	 * client sends once 0.7 of the anchor's mining is done by that count is taken at once, and fires Fabric's break
	 * event once, for the anchor of the rail point.
	 */
	@GameTest(structure = AREA, maxTicks = 300)
	public void aBreakFromSevenTenthsOfTheMiningTimeOnIsTakenAtOnce(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		SkyHookGameTest.lay(helper, WEST, SkyHookGameTest.straight(10));
		RailPoint onTheRail = new RailPoint(helper.absolutePos(WEST), AnchorSide.FRONT, RailSide.RIGHT, 5);
		TestPlayer miner = TestPlayer.place(helper, GameType.SURVIVAL, new Vec3(7.5, 2, 6.5), 180, -20);
		TestClaims.watch();
		float perTick = perTick(helper, miner);
		int start = serverTick(helper);
		RailBreaking.startMining(miner, onTheRail);

		at(helper, start, ticksSinceStart(perTick, 0.7F), () -> {
			assertMiningSpeed(helper, miner, perTick);
			RailBreaking.breakRail(miner, onTheRail);
			assertSegments(helper, List.of(), "after a break at 0.7 of the mining");
			helper.assertValueEqual(TestClaims.asked(miner), List.of(helper.absolutePos(WEST)),
					"the cells Fabric's break event asked about");
			TestClaims.lift(miner);
			TestPlayer.remove(helper, miner);
			helper.succeed();
		});
	}

	/**
	 * A break that comes a tick before 0.7 of the mining is done, as a lagging server counts it, isn't taken then, but
	 * kept, and taken on the tick the server's count reaches the whole mining time, as vanilla delays a block's
	 * destroy. The client starting on the same rail again meanwhile, as it does after sending a break, keeps it.
	 */
	@GameTest(structure = AREA, maxTicks = 300)
	public void anEarlyBreakIsTakenOnceTheServerHasCountedTheWholeMiningTime(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		Segment straight = SkyHookGameTest.straight(10);
		SkyHookGameTest.lay(helper, WEST, straight);
		RailPoint onTheRail = new RailPoint(helper.absolutePos(WEST), AnchorSide.FRONT, RailSide.RIGHT, 5);
		TestPlayer miner = TestPlayer.place(helper, GameType.SURVIVAL, new Vec3(7.5, 2, 6.5), 180, -20);
		TestClaims.watch();
		float perTick = perTick(helper, miner);
		int start = serverTick(helper);
		RailBreaking.startMining(miner, onTheRail);
		int early = ticksSinceStart(perTick, 0.7F) - 1;
		int whole = ticksSinceStart(perTick, 1);

		at(helper, start, early, () -> {
			RailBreaking.breakRail(miner, onTheRail);
			assertSegments(helper, List.of(straight), "after a break a tick before 0.7 of the mining");
		});
		at(helper, start, early + 5, () -> RailBreaking.startMining(miner, onTheRail));
		at(helper, start, whole - 1, () -> {
			assertMiningSpeed(helper, miner, perTick);
			assertSegments(helper, List.of(straight), "a tick before the whole mining time");
			helper.assertValueEqual(TestClaims.asked(miner), List.of(), "the cells Fabric's break event asked about");
		});
		at(helper, start, whole, () -> {
			assertSegments(helper, List.of(), "at the whole mining time");
			helper.assertValueEqual(rails(helper), 10, "the rails on the ground");
			helper.assertValueEqual(TestClaims.asked(miner), List.of(helper.absolutePos(WEST)),
					"the cells Fabric's break event asked about");
			TestClaims.lift(miner);
			TestPlayer.remove(helper, miner);
			helper.succeed();
		});
	}

	/**
	 * A break of a rail the server never saw the player start on, as when it refused the start, counts as starting on
	 * it then: it is taken once the server has counted the whole mining time from the break.
	 */
	@GameTest(structure = AREA, maxTicks = 300)
	public void aBreakWithNoStartIsTakenTheWholeMiningTimeAfterIt(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		Segment straight = SkyHookGameTest.straight(10);
		SkyHookGameTest.lay(helper, WEST, straight);
		RailPoint onTheRail = new RailPoint(helper.absolutePos(WEST), AnchorSide.FRONT, RailSide.RIGHT, 5);
		TestPlayer unstarted = TestPlayer.place(helper, GameType.SURVIVAL, new Vec3(7.5, 2, 6.5), 180, -20);
		float perTick = perTick(helper, unstarted);
		int start = serverTick(helper);
		RailBreaking.breakRail(unstarted, onTheRail);
		int whole = ticksSinceStart(perTick, 1);

		assertSegments(helper, List.of(straight), "after a break with no start");
		at(helper, start, whole - 1, () -> {
			assertMiningSpeed(helper, unstarted, perTick);
			assertSegments(helper, List.of(straight), "a tick before the whole mining time from the break");
		});
		at(helper, start, whole, () -> {
			assertSegments(helper, List.of(), "the whole mining time after the break");
			TestPlayer.remove(helper, unstarted);
			helper.succeed();
		});
	}

	/**
	 * A delayed break is dropped when the player starts on another rail: the segment stays past the whole mining time,
	 * as does the one they turned to, unmined.
	 */
	@GameTest(structure = AREA, maxTicks = 300)
	public void startingOnAnotherRailDropsADelayedBreak(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		Segment straight = SkyHookGameTest.straight(10);
		Segment next = SkyHookGameTest.straight(3);
		SkyHookGameTest.lay(helper, WEST, straight, next);
		RailPoint onTheRail = new RailPoint(helper.absolutePos(WEST), AnchorSide.FRONT, RailSide.RIGHT, 5);
		RailPoint onTheNext = new RailPoint(helper.absolutePos(EAST), AnchorSide.FRONT, RailSide.RIGHT, 1);
		TestPlayer miner = TestPlayer.place(helper, GameType.SURVIVAL, new Vec3(10.5, 2, 6.5), 180, -20);
		float perTick = perTick(helper, miner);
		int start = serverTick(helper);
		RailBreaking.startMining(miner, onTheRail);
		int whole = ticksSinceStart(perTick, 1);

		at(helper, start, 1, () -> {
			RailBreaking.breakRail(miner, onTheRail);
			RailBreaking.startMining(miner, onTheNext);
		});
		at(helper, start, whole + 1, () -> {
			assertMiningSpeed(helper, miner, perTick);
			assertSegments(helper, List.of(straight), "after starting on the next rail");
			helper.assertValueEqual(helper.getBlockEntity(EAST, AnchorBlockEntity.class).segments(),
					List.of(next, straight.reversed()), "the segments of the anchor between them");
			TestPlayer.remove(helper, miner);
			helper.succeed();
		});
	}

	/**
	 * A delayed break is checked again as it is taken: one player, kept out of the west anchor's cell by a claim made
	 * after their break, has it refused, Fabric's break event asked once, then canceled; another, gone out of reach,
	 * has it refused. Both segments stay.
	 */
	@GameTest(structure = AREA, maxTicks = 300)
	public void aDelayedBreakIsRefusedIfAClaimOrReachNoLongerAllowsIt(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		Segment straight = SkyHookGameTest.straight(10);
		Segment next = SkyHookGameTest.straight(3);
		SkyHookGameTest.lay(helper, WEST, straight, next);
		RailPoint onTheRail = new RailPoint(helper.absolutePos(WEST), AnchorSide.FRONT, RailSide.RIGHT, 5);
		RailPoint onTheNext = new RailPoint(helper.absolutePos(EAST), AnchorSide.FRONT, RailSide.RIGHT, 1);
		TestPlayer claimed = TestPlayer.place(helper, GameType.SURVIVAL, new Vec3(7.5, 2, 6.5), 180, -20);
		TestPlayer leaving = TestPlayer.place(helper, GameType.SURVIVAL, new Vec3(10.5, 2, 6.5), 180, -20);
		TestClaims.watch();
		float perTick = perTick(helper, claimed);
		int start = serverTick(helper);
		RailBreaking.startMining(claimed, onTheRail);
		RailBreaking.startMining(leaving, onTheNext);
		int whole = ticksSinceStart(perTick, 1);

		at(helper, start, 1, () -> {
			RailBreaking.breakRail(claimed, onTheRail);
			RailBreaking.breakRail(leaving, onTheNext);
			TestClaims.byEvent(claimed, helper.absolutePos(WEST));
			Vec3 away = helper.absoluteVec(new Vec3(7.5, 1, 14.5));
			leaving.snapTo(away.x, away.y, away.z, 180, 0);
		});
		at(helper, start, whole + 1, () -> {
			assertMiningSpeed(helper, claimed, perTick);
			assertSegments(helper, List.of(straight), "after the claimed player's break");
			helper.assertValueEqual(helper.getBlockEntity(EAST, AnchorBlockEntity.class).segments(),
					List.of(next, straight.reversed()), "the segments of the anchor between them");
			helper.assertValueEqual(TestClaims.asked(claimed), List.of(helper.absolutePos(WEST)),
					"the cells Fabric's break event asked about");
			helper.assertValueEqual(TestClaims.canceled(claimed), List.of(helper.absolutePos(WEST)),
					"the breaks canceled for the claimed player");
			TestClaims.lift(claimed, leaving);
			TestPlayer.remove(helper, claimed, leaving);
			helper.succeed();
		});
	}

	/**
	 * Changing dimension forgets the rail a player mines: going to the nether and back, their break at 0.7 of the
	 * mining time from the start is delayed, as one with no start is.
	 */
	@GameTest(structure = AREA, maxTicks = 300)
	public void changingDimensionForgetsTheRailMined(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		Segment straight = SkyHookGameTest.straight(10);
		SkyHookGameTest.lay(helper, WEST, straight);
		RailPoint onTheRail = new RailPoint(helper.absolutePos(WEST), AnchorSide.FRONT, RailSide.RIGHT, 5);
		TestPlayer miner = TestPlayer.place(helper, GameType.SURVIVAL, new Vec3(7.5, 2, 6.5), 180, -20);
		ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
		if (nether == null) {
			throw helper.assertionException(Component.literal("no nether to go to"));
		}
		float perTick = perTick(helper, miner);
		int start = serverTick(helper);
		RailBreaking.startMining(miner, onTheRail);
		Vec3 back = miner.position();
		miner.teleport(new TeleportTransition(nether, new Vec3(0.5, 64, 0.5), Vec3.ZERO, 0, 0,
				TeleportTransition.DO_NOTHING));
		miner.teleport(new TeleportTransition(helper.getLevel(), back, Vec3.ZERO, 180, -20,
				TeleportTransition.DO_NOTHING));

		at(helper, start, ticksSinceStart(perTick, 0.7F), () -> {
			assertMiningSpeed(helper, miner, perTick);
			RailBreaking.breakRail(miner, onTheRail);
			assertSegments(helper, List.of(straight), "after a break at 0.7 of the mining, started elsewhere");
			TestPlayer.remove(helper, miner);
			helper.succeed();
		});
	}

	/**
	 * Claim mods keep players from breaking rails in their claims, through either convention they share: one player
	 * is kept from the west anchor's cell, the one their rail point names, through Fabric API's break event, which then
	 * reports the break canceled; another from the east anchor's through the common protection API, refused before the
	 * event is asked. Neither breaks the segment between them, mined as long as it takes; a player no claim keeps out
	 * does, the event asked once, of the west anchor's cell.
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
					helper.assertValueEqual(TestClaims.asked(byEvent), List.of(helper.absolutePos(WEST)),
							"the cells the break event asked about for the player kept out by it");
					helper.assertValueEqual(TestClaims.canceled(byEvent), List.of(helper.absolutePos(WEST)),
							"the breaks canceled for the player kept out by the event");
					helper.assertValueEqual(TestClaims.asked(byProtection), List.of(),
							"the cells the break event asked about for the player kept out by common protection");
					RailBreaking.breakRail(owner, onTheRail);
					helper.assertValueEqual(helper.getBlockEntity(WEST, AnchorBlockEntity.class).segments(), List.of(),
							"the west anchor's segments after the owner's break");
					helper.assertValueEqual(TestClaims.asked(owner), List.of(helper.absolutePos(WEST)),
							"the cells the break event asked about for the owner");
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
	 * How many server ticks after the one mining starts on the server's count of it reaches a share of the mining, at
	 * a speed of so much a tick: the start's tick counts as one.
	 */
	private static int ticksSinceStart(float perTick, float share) {
		int counted = 1;
		while (perTick * counted < share) {
			counted++;
		}
		return counted - 1;
	}

	/** Runs an action on the server tick so many after a start: after the levels ticked, as tests' callbacks run. */
	private static void at(GameTestHelper helper, int start, int ticks, Runnable action) {
		helper.runAfterDelay(start + ticks - serverTick(helper), () -> {
			helper.assertValueEqual(serverTick(helper) - start, ticks, "the server ticks since the start");
			action.run();
		});
	}

	private static int serverTick(GameTestHelper helper) {
		return helper.getLevel().getServer().getTickCount();
	}

	private static float perTick(GameTestHelper helper, TestPlayer player) {
		return SkyLine.ANCHOR.defaultBlockState().getDestroyProgress(player, helper.getLevel(),
				helper.absolutePos(WEST));
	}

	/** Asserts the player mines at the speed they started at, which the tick counts were worked out from. */
	private static void assertMiningSpeed(GameTestHelper helper, TestPlayer player, float perTick) {
		helper.assertValueEqual(perTick(helper, player), perTick, "how much of the anchor the player mines a tick");
	}

	private static void assertSegments(GameTestHelper helper, List<Segment> segments, String when) {
		helper.assertValueEqual(helper.getBlockEntity(WEST, AnchorBlockEntity.class).segments(), segments,
				"the west anchor's segments " + when);
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
