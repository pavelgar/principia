package io.github.pavelgar.principia.test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.pavelgar.principia.skyline.AnchorBlockEntity;
import io.github.pavelgar.principia.skyline.AnchorSide;
import io.github.pavelgar.principia.skyline.Heading;
import io.github.pavelgar.principia.skyline.Placement;
import io.github.pavelgar.principia.skyline.RailPoint;
import io.github.pavelgar.principia.skyline.RailSide;
import io.github.pavelgar.principia.skyline.Segment;
import io.github.pavelgar.principia.skyline.SkyHook;
import io.github.pavelgar.principia.skyline.SkyHookEntity;
import io.github.pavelgar.principia.skyline.SkyLine;
import io.github.pavelgar.principia.skyline.TrackStart;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * The Sky-Hook: how it is made, and riding a Sky-Line with it as a player does, by the target their client sends.
 *
 * <p>The riding tests run in a 16 × 10 × 16 area, with track laid 3 above a stone floor: anchors at y = 3, the rails'
 * lines 1 px below their tops at y = 3.9375 and their tops at y = 4, so a rider's feet hang 2.5 lower, at y = 1.5,
 * half a block above the floor. A straight east from (1, 3, 4) has its rails at z = 3.5 (left) and z = 5.5 (right).
 */
public class SkyHookGameTest {
	private static final String AREA = "principia-test:sky_line";
	private static final double EXACT = 1e-6;
	private static final BlockPos WEST = new BlockPos(1, 3, 4);
	/** The top of {@link #descent()}, 6 above {@link #WEST}. */
	private static final BlockPos HIGH = new BlockPos(1, 9, 4);
	/** The right rail of a straight east from {@link #WEST}. */
	private static final double RIGHT_Z = 5.5;
	/** The left rail of a straight east from {@link #WEST}. */
	private static final double LEFT_Z = 3.5;
	private static final double FEET_Y = 1.5;
	private static final float EAST = -90;
	private static final float WEST_YAW = 90;
	private static final Input NO_KEYS = Input.EMPTY;
	private static final Input FORWARD = new Input(true, false, false, false, false, false, false);
	private static final Input BACK = new Input(false, true, false, false, false, false, false);

	/**
	 * The recipe, as a crafting table's grid has it: iron ingots along the top and down the left, any planks in the
	 * middle, leather to its right and below it, a gold ingot in the corner.
	 */
	@GameTest
	public void theCraftingRecipeMakesASkyHook(GameTestHelper helper) {
		ItemStack iron = new ItemStack(Items.IRON_INGOT);
		ItemStack leather = new ItemStack(Items.LEATHER);
		CraftingInput grid = CraftingInput.of(3, 3, List.of(iron, iron, ItemStack.EMPTY, iron,
				new ItemStack(Items.BIRCH_PLANKS), leather, ItemStack.EMPTY, leather, new ItemStack(Items.GOLD_INGOT)));

		ItemStack made = helper.getLevel().recipeAccess().getRecipeFor(RecipeType.CRAFTING, grid, helper.getLevel())
				.map(recipe -> recipe.value().assemble(grid)).orElse(ItemStack.EMPTY);

		helper.assertTrue(made.is(SkyLine.HOOK) && made.getCount() == 1, "the recipe makes " + made);
		helper.succeed();
	}

	/**
	 * Three players attach to the rails of a straight east: one holding W looking east and up at it, 4 blocks along,
	 * one holding W looking west, 8 along, and one holding nothing, 10 along. Each hangs with their feet 2.5 below the
	 * rail's top, at rest. W drives a rider the way they look, from rest: 0.05 the first tick, then each 0.997 v -
	 * 0.0005 + 0.05, 2.70308 blocks in 10 ticks. With no keys, the rider stays put.
	 */
	@GameTest(structure = AREA, maxTicks = 40)
	public void aPlayerAttachingHangsBelowTheRailAtRestAndWRidesThemTheWayTheyLook(GameTestHelper helper) {
		floor(helper);
		lay(helper, WEST, straight(12));
		TestPlayer east = rider(helper, GameType.SURVIVAL, new Vec3(5.5, 1, RIGHT_Z), EAST, -30);
		TestPlayer west = rider(helper, GameType.SURVIVAL, new Vec3(9.5, 1, 3.5), WEST_YAW, -30);
		TestPlayer still = rider(helper, GameType.SURVIVAL, new Vec3(11.5, 1, RIGHT_Z), WEST_YAW, -30);

		attach(helper, east, RailSide.RIGHT, 4);
		attach(helper, west, RailSide.LEFT, 8);
		attach(helper, still, RailSide.RIGHT, 10);
		east.setLastClientInput(FORWARD);
		west.setLastClientInput(FORWARD);

		helper.startSequence()
				.thenWaitUntil(
						() -> helper.assertTrue(hook(helper, east).tickCount >= 10, "the hook hasn't ticked 10 times"))
				.thenExecute(() -> {
					helper.assertValueEqual(hook(helper, east).tickCount, 10, "the hook's ticks");
					assertFeet(helper, east, new Vec3(5.5 + 2.7030769, FEET_Y, RIGHT_Z));
					assertFeet(helper, west, new Vec3(9.5 - 2.7030769, FEET_Y, 3.5));
					assertFeet(helper, still, new Vec3(11.5, FEET_Y, RIGHT_Z));
				})
				.thenExecute(() -> done(helper, east, west, still))
				.thenSucceed();
	}

	/**
	 * The server checks the target the client sends. A rail point 8 blocks from the eyes is out of reach (6, and 1 for
	 * lag); a segment that isn't there is gone; a spectator doesn't attach.
	 */
	@GameTest(structure = AREA)
	public void theServerRefusesATargetOutOfReachGoneOrBySpectator(GameTestHelper helper) {
		floor(helper);
		lay(helper, WEST, straight(12));

		TestPlayer far = rider(helper, GameType.SURVIVAL, new Vec3(3.5, 1, 13.5), EAST, 0);
		attach(helper, far, RailSide.RIGHT, 2);
		TestPlayer gone = rider(helper, GameType.SURVIVAL, new Vec3(5.5, 1, RIGHT_Z), EAST, 0);
		SkyHook.useOnRail(gone, InteractionHand.MAIN_HAND,
				new RailPoint(helper.absolutePos(WEST), AnchorSide.BACK, RailSide.RIGHT, 4));
		TestPlayer spectator = rider(helper, GameType.SPECTATOR, new Vec3(5.5, 1, RIGHT_Z), EAST, 0);
		attach(helper, spectator, RailSide.RIGHT, 4);

		for (TestPlayer refused : List.of(far, gone, spectator)) {
			helper.assertFalse(refused.isPassenger(), refused.getPlainTextName() + " attached");
		}
		helper.assertValueEqual(far.overlay, List.of(), "the action bar of the player out of reach");
		done(helper, far, gone, spectator);
		helper.succeed();
	}

	/**
	 * A client can send any double as the distance along the rail. One that isn't a number is refused, as vanilla
	 * refuses a move to a position that isn't one, rather than read as the rail's start, which would hang the rider at
	 * a position that isn't one either.
	 */
	@GameTest(structure = AREA)
	public void theServerRefusesATargetThatIsntANumberAlongTheRail(GameTestHelper helper) {
		floor(helper);
		lay(helper, WEST, straight(12));

		TestPlayer player = rider(helper, GameType.SURVIVAL, new Vec3(3.5, 1, RIGHT_Z), EAST, 0);
		attach(helper, player, RailSide.RIGHT, Double.NaN);

		helper.assertFalse(player.isPassenger(), "the player attached at NaN along the rail");
		done(helper, player);
		helper.succeed();
	}

	/**
	 * As a minecart lets a player in whose head would be in a block, the hook lets a player hang where their body is in
	 * blocks: the stone at (9, 2, 5) is in the body of anyone hanging from the right rail at x = 9.5. Holding W, the
	 * rider rides on east out of it, a stop at a block in their way coming only once they are clear.
	 */
	@GameTest(structure = AREA, maxTicks = 40)
	public void aPlayerHangsWithTheirBodyInABlockAndRidesOutOfIt(GameTestHelper helper) {
		floor(helper);
		lay(helper, WEST, straight(12));
		helper.setBlock(new BlockPos(9, 2, 5), Blocks.STONE);
		TestPlayer rider = rider(helper, GameType.SURVIVAL, new Vec3(9.5, 1, 6.5), EAST, 0);

		attach(helper, rider, RailSide.RIGHT, 8);
		helper.assertTrue(rider.getVehicle() instanceof SkyHookEntity, "the player didn't attach");
		assertFeet(helper, rider, new Vec3(9.5, FEET_Y, RIGHT_Z));
		rider.setLastClientInput(FORWARD);

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(relativeFeet(helper, rider).x > 11, "not out of the stone yet"))
				.thenExecute(() -> done(helper, rider))
				.thenSucceed();
	}

	/**
	 * East 4, a quarter turn to the left, radius 4, to the north, then 6 north. The turn's far anchor is first in block
	 * order, so the track holds the turn running the other way from the ride. The rider on the right rail, the south
	 * one, goes round the outside of the turn and comes out on the east rail, x = 9.5 + 1, still to their right.
	 */
	@GameTest(structure = AREA, maxTicks = 100)
	public void aRiderCrossesAnchorsOntoTheRailOnTheSameSide(GameTestHelper helper) {
		floor(helper);
		BlockPos start = new BlockPos(1, 3, 12);
		lay(helper, start, straight(4), turn(Heading.NORTH, 4, -4), segment(new Vec3i(0, 0, -6), Heading.NORTH));
		TestPlayer rider = rider(helper, GameType.SURVIVAL, new Vec3(2.5, 1, 13.5), EAST, -30);
		SkyHook.useOnRail(rider, InteractionHand.MAIN_HAND,
				new RailPoint(helper.absolutePos(start), AnchorSide.FRONT, RailSide.RIGHT, 1));
		rider.setLastClientInput(FORWARD);

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(relativeFeet(helper, rider).z < 6, "not past the turn yet"))
				.thenExecute(() -> {
					Vec3 feet = relativeFeet(helper, rider);
					helper.assertTrue(Math.abs(feet.x - 10.5) < EXACT && Math.abs(feet.y - FEET_Y) < EXACT,
							"the rider hangs at " + feet + ", not from the east rail");
				})
				.thenExecute(() -> done(helper, rider))
				.thenSucceed();
	}

	/**
	 * East 4 from (1, 3, 12), a quarter turn left of radius 4 to the north, another to the west, and 4 west to (1, 3, 4):
	 * a U-turn. A rider holding W, looking east all along, comes out of it riding west on its outer rail, z = 4.5 - 1:
	 * the way W picked stays, where looking east and holding W would brake a rider riding west.
	 */
	@GameTest(structure = AREA, maxTicks = 100)
	public void wHeldRoundAUTurnBringsTheRiderOutRidingTheOtherWay(GameTestHelper helper) {
		floor(helper);
		BlockPos start = new BlockPos(1, 3, 12);
		lay(helper, start, straight(4), turn(Heading.NORTH, 4, -4),
				quarter(new Vec3i(-4, 0, -4), Heading.NORTH, Heading.WEST), segment(new Vec3i(-4, 0, 0), Heading.WEST));
		TestPlayer rider = rider(helper, GameType.SURVIVAL, new Vec3(2.5, 1, 13.5), EAST, -30);
		SkyHook.useOnRail(rider, InteractionHand.MAIN_HAND,
				new RailPoint(helper.absolutePos(start), AnchorSide.FRONT, RailSide.RIGHT, 1));
		rider.setLastClientInput(FORWARD);

		helper.startSequence()
				.thenWaitUntil(() -> {
					Vec3 feet = relativeFeet(helper, rider);
					helper.assertTrue(feet.x < 4 && Math.abs(feet.z - 3.5) < EXACT,
							"not out on the westward straight yet: at " + feet);
				})
				.thenExecute(() -> helper.assertTrue(hook(helper, rider).getDeltaMovement().x < -0.3,
						"the rider came out at " + hook(helper, rider).getDeltaMovement()))
				.thenExecute(() -> done(helper, rider))
				.thenSucceed();
	}

	/**
	 * Riding east at top speed with W, a rider who turns to look west and presses W again brakes through the stop and
	 * rides back west.
	 */
	@GameTest(structure = AREA, maxTicks = 80)
	public void wPressedLookingBackReversesTheRider(GameTestHelper helper) {
		floor(helper);
		lay(helper, WEST, straight(13));
		TestPlayer rider = rider(helper, GameType.SURVIVAL, new Vec3(3.5, 1, RIGHT_Z), EAST, -30);
		attach(helper, rider, RailSide.RIGHT, 2);
		rider.setLastClientInput(FORWARD);

		helper.startSequence()
				.thenWaitUntil(
						() -> assertNear(helper, hook(helper, rider).getDeltaMovement().x, 0.5, "the speed east"))
				.thenExecute(() -> {
					rider.setYRot(WEST_YAW);
					rider.setLastClientInput(NO_KEYS);
				})
				.thenIdle(1)
				.thenExecute(() -> rider.setLastClientInput(FORWARD))
				.thenWaitUntil(
						() -> assertNear(helper, hook(helper, rider).getDeltaMovement().x, -0.5, "the speed west"))
				.thenExecute(() -> done(helper, rider))
				.thenSucceed();
	}

	/**
	 * A rider attaching with no keys 2 blocks down a 45° descent rolls downhill: gravity pulls them along the rail by
	 * 0.0245 · √½ = 0.017324 a tick, less 0.0005 of rolling resistance, so the first tick moves them 0.016824 along it,
	 * 0.011896 east and as far down.
	 */
	@GameTest(structure = AREA, maxTicks = 40)
	public void aRiderAttachingOnASlopeWithNoKeysRollsDownhill(GameTestHelper helper) {
		floor(helper);
		lay(helper, HIGH, descent());
		TestPlayer rider = rider(helper, GameType.SURVIVAL, new Vec3(2.5, 5, RIGHT_Z), EAST, -60);
		SkyHook.useOnRail(rider, InteractionHand.MAIN_HAND,
				new RailPoint(helper.absolutePos(HIGH), AnchorSide.FRONT, RailSide.RIGHT, 2));
		// 2 along the rail from (1.5, 9.9375, 5.5), its top 1 px higher and the feet 2.5 below that.
		Vec3 attached = new Vec3(1.5 + Math.sqrt(2), 9.9375 - Math.sqrt(2) + 0.0625 - 2.5, RIGHT_Z);

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(hook(helper, rider).tickCount >= 1, "the hook hasn't ticked"))
				.thenExecute(() -> {
					helper.assertValueEqual(hook(helper, rider).tickCount, 1, "the hook's ticks");
					assertFeet(helper, rider, attached.add(0.0118963, -0.0118963, 0));
				})
				.thenIdle(10)
				.thenExecute(() -> helper.assertTrue(hook(helper, rider).getDeltaMovement().x > 0.1,
						"the rider isn't rolling down: " + hook(helper, rider).getDeltaMovement()))
				.thenExecute(() -> done(helper, rider))
				.thenSucceed();
	}

	/**
	 * Rolling down a 45° descent, a rider who holds S looking down it (up the rail, against the travel) stops, further
	 * down than where they pressed it, and stays there while holding it, the brake's 0.05 a tick beating gravity's
	 * 0.0173. Released, they roll on down.
	 */
	@GameTest(structure = AREA, maxTicks = 100)
	public void sAgainstTheTravelStopsARiderOnASlopeAndHoldsThem(GameTestHelper helper) {
		floor(helper);
		lay(helper, HIGH, descent());
		TestPlayer rider = rider(helper, GameType.SURVIVAL, new Vec3(2.5, 5, RIGHT_Z), EAST, -60);
		SkyHook.useOnRail(rider, InteractionHand.MAIN_HAND,
				new RailPoint(helper.absolutePos(HIGH), AnchorSide.FRONT, RailSide.RIGHT, 1));
		Vec3[] pressed = new Vec3[1];
		Vec3[] held = new Vec3[1];

		helper.startSequence()
				.thenIdle(12)
				.thenExecute(() -> {
					helper.assertTrue(hook(helper, rider).getDeltaMovement().x > 0.1, "the rider isn't rolling down");
					rider.setLastClientInput(BACK);
					pressed[0] = relativeFeet(helper, rider);
				})
				.thenWaitUntil(
						() -> assertNear(helper, hook(helper, rider).getDeltaMovement().length(), 0, "the speed"))
				.thenExecute(() -> {
					held[0] = relativeFeet(helper, rider);
					helper.assertTrue(held[0].x > pressed[0].x,
							"the rider stopped at " + held[0] + ", not down from " + pressed[0]);
				})
				.thenIdle(20)
				.thenExecute(() -> {
					assertFeet(helper, rider, held[0]);
					rider.setLastClientInput(NO_KEYS);
				})
				.thenIdle(3)
				.thenExecute(() -> helper.assertTrue(relativeFeet(helper, rider).x > held[0].x,
						"released, the rider didn't roll on down"))
				.thenExecute(() -> done(helper, rider))
				.thenSucceed();
	}

	/**
	 * East 6, a quarter turn to the right, radius 4, then 6 south. On the right rail, the inner one, of radius 3, the
	 * rider slows to about √(0.05 · 3) ≈ 0.39 through the turn and speeds up to 0.5 after it.
	 */
	@GameTest(structure = AREA, maxTicks = 100)
	public void aRiderSlowsIntoATightTurnAndSpeedsUpAfterIt(GameTestHelper helper) {
		floor(helper);
		lay(helper, WEST, straight(6), turn(Heading.SOUTH, 4, 4), segment(new Vec3i(0, 0, 6), Heading.SOUTH));
		TestPlayer rider = rider(helper, GameType.SURVIVAL, new Vec3(1.5, 1, RIGHT_Z), EAST, -30);
		attach(helper, rider, RailSide.RIGHT, 0);
		rider.setLastClientInput(FORWARD);
		List<Double> inTurn = new ArrayList<>();
		List<Double> after = new ArrayList<>();
		helper.onEachTick(() -> {
			if (!rider.isPassenger()) {
				return;
			}
			Vec3 feet = relativeFeet(helper, rider);
			double speed = hook(helper, rider).getDeltaMovement().length();
			// The inner rail runs round from (7.5, _, 5.5) to (10.5, _, 8.5).
			if (feet.x > 7.6 && feet.z < 8.4) {
				inTurn.add(speed);
			} else if (feet.z > 8.6) {
				after.add(speed);
			}
		});

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(after.size() > 6, "not far past the turn yet"))
				.thenExecute(() -> {
					helper.assertTrue(inTurn.size() > 3 && inTurn.stream().allMatch(speed -> speed < 0.4),
							"in the turn the rider went at " + inTurn);
					helper.assertTrue(after.stream().anyMatch(speed -> speed > 0.5 - EXACT),
							"after the turn the rider went at " + after);
				})
				.thenExecute(() -> done(helper, rider))
				.thenSucceed();
	}

	/**
	 * Riding never turns the rider's view: round the same quarter turn, from east to south, they still look east and
	 * 30° up, where they looked when they attached. The rail carries their body; they aim their own view.
	 */
	@GameTest(structure = AREA, maxTicks = 100)
	public void ridingRoundATurnLeavesTheRidersViewAlone(GameTestHelper helper) {
		floor(helper);
		lay(helper, WEST, straight(6), turn(Heading.SOUTH, 4, 4), segment(new Vec3i(0, 0, 6), Heading.SOUTH));
		TestPlayer rider = rider(helper, GameType.SURVIVAL, new Vec3(1.5, 1, RIGHT_Z), EAST, -30);
		attach(helper, rider, RailSide.RIGHT, 0);
		rider.setLastClientInput(FORWARD);

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(relativeFeet(helper, rider).z > 8.6, "not past the turn yet"))
				.thenExecute(() -> {
					helper.assertValueEqual(rider.getYRot(), EAST, "the rider's yaw");
					helper.assertValueEqual(rider.getXRot(), -30f, "the rider's pitch");
				})
				.thenExecute(() -> done(helper, rider))
				.thenSucceed();
	}

	/**
	 * Holding W, at the end of a straight the rider stops exactly at the east anchor's point, x = 7.5, and hangs there.
	 * Pressing S (looking east: west) from rest takes them back along the rail.
	 */
	@GameTest(structure = AREA, maxTicks = 80)
	public void aRiderStopsAndHangsAtAnEndAndGoesBackWhenAsked(GameTestHelper helper) {
		floor(helper);
		lay(helper, WEST, straight(6));
		TestPlayer rider = rider(helper, GameType.SURVIVAL, new Vec3(2.5, 1, RIGHT_Z), EAST, -30);
		attach(helper, rider, RailSide.RIGHT, 1);
		rider.setLastClientInput(FORWARD);

		helper.startSequence()
				.thenWaitUntil(() -> assertFeet(helper, rider, new Vec3(7.5, FEET_Y, RIGHT_Z)))
				.thenIdle(5)
				.thenExecute(() -> {
					assertFeet(helper, rider, new Vec3(7.5, FEET_Y, RIGHT_Z));
					rider.setLastClientInput(BACK);
				})
				.thenWaitUntil(() -> helper.assertTrue(relativeFeet(helper, rider).x < 6.5, "not back yet"))
				.thenExecute(() -> done(helper, rider))
				.thenSucceed();
	}

	/**
	 * A rider doesn't lay track: from below a rail, block reach finds no spot a segment could go to. Two riders stop at
	 * the end of a straight 6 from (1, 3, 4), each with Sky-Lines in the other hand. One selected the anchor above
	 * before attaching: a click on the top of the stone at (11, 2, 4) lays nothing. The other, with nothing selected,
	 * places no anchor there with a click, and selects nothing with a click on the anchor above.
	 */
	@GameTest(structure = AREA, maxTicks = 100)
	public void aRiderDoesntLayTrack(GameTestHelper helper) {
		floor(helper);
		lay(helper, WEST, straight(6));
		BlockPos above = helper.absolutePos(WEST.east(6));
		BlockPos stone = new BlockPos(11, 2, 4);
		helper.setBlock(stone, Blocks.STONE);
		BlockPos top = helper.absolutePos(stone);
		TestPlayer selected = rider(helper, GameType.SURVIVAL, new Vec3(2.5, 1, RIGHT_Z), EAST, -30);
		TestPlayer unselected = rider(helper, GameType.SURVIVAL, new Vec3(2.5, 1, 3.5), EAST, -30);
		for (TestPlayer rider : List.of(selected, unselected)) {
			rider.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(SkyLine.RAIL, 16));
		}
		selected.gameMode.useItemOn(selected, helper.getLevel(), selected.getOffhandItem(), InteractionHand.OFF_HAND,
				new BlockHitResult(Vec3.atCenterOf(above), Direction.DOWN, above, false));
		helper.assertTrue(TrackStart.of(selected) != null, "the anchor wasn't selected before attaching");
		attach(helper, selected, RailSide.RIGHT, 1);
		attach(helper, unselected, RailSide.LEFT, 1);
		selected.setLastClientInput(FORWARD);
		unselected.setLastClientInput(FORWARD);

		helper.startSequence()
				.thenWaitUntil(() -> {
					assertFeet(helper, selected, new Vec3(7.5, FEET_Y, RIGHT_Z));
					assertFeet(helper, unselected, new Vec3(7.5, FEET_Y, 3.5));
				})
				.thenExecute(() -> {
					Placement.lay(selected, InteractionHand.OFF_HAND,
							new BlockHitResult(Vec3.atCenterOf(top).add(0, 0.5, 0), Direction.UP, top, false),
							Optional.empty());
					unselected.gameMode.useItemOn(unselected, helper.getLevel(), unselected.getOffhandItem(),
							InteractionHand.OFF_HAND,
							new BlockHitResult(Vec3.atCenterOf(top).add(0, 0.5, 0), Direction.UP, top, false));
					unselected.gameMode.useItemOn(unselected, helper.getLevel(), unselected.getOffhandItem(),
							InteractionHand.OFF_HAND, new BlockHitResult(Vec3.atCenterOf(above), Direction.DOWN, above,
									false));
					helper.assertBlockNotPresent(SkyLine.ANCHOR, stone.above());
					helper.assertTrue(TrackStart.of(unselected) == null, "a rider selected an anchor");
					for (TestPlayer rider : List.of(selected, unselected)) {
						helper.assertValueEqual(rider.getOffhandItem().getCount(), 16, "the Sky-Lines left");
					}
				})
				.thenExecute(() -> done(helper, selected, unselected))
				.thenSucceed();
	}

	/**
	 * A rider attaching 2 blocks along a straight 12 long and holding W to its east end, where they stop, has ridden 10
	 * blocks along the rail, 1000 cm: the server adds that to their Sky-Line distance stat.
	 */
	@GameTest(structure = AREA, maxTicks = 100)
	public void aRideAddsTheDistanceAlongTheRailToTheRidersSkyLineStat(GameTestHelper helper) {
		floor(helper);
		lay(helper, WEST, straight(12));
		TestPlayer rider = rider(helper, GameType.SURVIVAL, new Vec3(3.5, 1, RIGHT_Z), EAST, -30);
		attach(helper, rider, RailSide.RIGHT, 2);
		rider.setLastClientInput(FORWARD);

		helper.startSequence()
				.thenWaitUntil(() -> assertFeet(helper, rider, new Vec3(13.5, FEET_Y, RIGHT_Z)))
				.thenExecute(() -> helper.assertValueEqual(
						rider.getStats().getValue(Stats.CUSTOM.get(SkyLine.DISTANCE)), 1000, "the Sky-Line distance"))
				.thenExecute(() -> done(helper, rider))
				.thenSucceed();
	}

	/**
	 * A rider who hits stone at (9, 2, 5) at 0.5, as in the test below, stopping with their feet just west of x = 8.7,
	 * and holds Shift on that tick lets go on the next, before the crash's hurt would come: it comes as they let go, 2
	 * damage.
	 */
	@GameTest(structure = AREA, maxTicks = 60)
	public void lettingGoJustAfterACrashStillHurts(GameTestHelper helper) {
		floor(helper);
		lay(helper, WEST, straight(12));
		helper.setBlock(new BlockPos(9, 2, 5), Blocks.STONE);
		TestPlayer rider = rider(helper, GameType.SURVIVAL, new Vec3(3.5, 1, RIGHT_Z), EAST, -30);
		attach(helper, rider, RailSide.RIGHT, 2);
		rider.setLastClientInput(FORWARD);

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(relativeFeet(helper, rider).x > 8.69, "not at the stone yet"))
				.thenExecute(() -> {
					helper.assertValueEqual(rider.getHealth(), rider.getMaxHealth(), "the health on the stop");
					rider.setShiftKeyDown(true);
				})
				.thenWaitUntil(() -> helper.assertFalse(rider.isPassenger(), "shift didn't let go"))
				.thenExecute(() -> {
					helper.assertValueEqual(rider.getHealth(), rider.getMaxHealth() - 2, "the health");
					rider.setShiftKeyDown(false);
				})
				.thenExecute(() -> done(helper, rider))
				.thenSucceed();
	}

	/**
	 * Stone at (9, 2, 5) and (9, 2, 3) stands in the way of riders' bodies on the right and left rails, 0.3 either side
	 * of their feet: riding at it with W, they stop at once just short of it, their feet just west of x = 9 - 0.3. One
	 * starts 2 along the right rail, at x = 3.5, so W takes them to 0.5 by x = 6.70308 (3.20308 in 11 ticks), and they
	 * hit at 0.5: an elytra crash's 10 · 0.5 - 3 = 2 damage. The other starts 7 along the left rail, at x = 8.5, and
	 * hits on W's third tick, having reached 0.09935, under 0.3, unhurt.
	 */
	@GameTest(structure = AREA, maxTicks = 60)
	public void aBlockInTheBodysWayStopsTheRiderShortOfItAndHurtsAtSpeed(GameTestHelper helper) {
		floor(helper);
		lay(helper, WEST, straight(12));
		helper.setBlock(new BlockPos(9, 2, 5), Blocks.STONE);
		helper.setBlock(new BlockPos(9, 2, 3), Blocks.STONE);
		TestPlayer fast = rider(helper, GameType.SURVIVAL, new Vec3(3.5, 1, RIGHT_Z), EAST, -30);
		attach(helper, fast, RailSide.RIGHT, 2);
		fast.setLastClientInput(FORWARD);
		TestPlayer slow = rider(helper, GameType.SURVIVAL, new Vec3(8.5, 1, 3.5), EAST, -30);
		attach(helper, slow, RailSide.LEFT, 7);
		slow.setLastClientInput(FORWARD);

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(relativeFeet(helper, fast).x > 5, "not under way yet"))
				.thenWaitUntil(() -> assertNear(helper, hook(helper, fast).getDeltaMovement().x, 0, "the speed"))
				.thenExecute(() -> {
					for (TestPlayer rider : List.of(fast, slow)) {
						double x = relativeFeet(helper, rider).x;
						helper.assertTrue(x <= 8.7 && x > 8.69, "a rider stopped with their feet at x = " + x);
						assertNear(helper, hook(helper, rider).getDeltaMovement().x, 0, "a rider's speed");
					}
					helper.assertValueEqual(fast.getHealth(), fast.getMaxHealth() - 2, "the fast rider's health");
					helper.assertValueEqual(slow.getHealth(), slow.getMaxHealth(), "the slow rider's health");
				})
				.thenExecute(() -> done(helper, fast, slow))
				.thenSucceed();
	}

	/**
	 * Two riders sprint with W round a loop of four quarter turns of radius 6, east, south, west and north, on its
	 * outer rail, radius 7, which allows √(0.05 · 7) = 0.59: faster than the motor's 0.5, slower than the boost's 0.75.
	 * One, fed, boosts past 0.5, and every block they go costs them vanilla sprinting's 0.1 exhaustion; at 4, more
	 * than 40 blocks, it takes 1 of the 5 saturation a player starts with. The other, with food 6, too little to
	 * sprint, keeps to 0.5.
	 */
	@GameTest(structure = AREA, maxTicks = 200)
	public void sprintingWithWBoostsAFedRiderAndCostsThemFood(GameTestHelper helper) {
		floor(helper);
		BlockPos north = new BlockPos(8, 3, 2);
		lay(helper, north, turn(Heading.SOUTH, 6, 6), quarter(new Vec3i(-6, 0, 6), Heading.SOUTH, Heading.WEST),
				quarter(new Vec3i(-6, 0, -6), Heading.WEST, Heading.NORTH),
				quarter(new Vec3i(6, 0, -6), Heading.NORTH, Heading.EAST));
		Input sprinting = new Input(true, false, false, false, false, false, true);
		// On the outer, left rails: of the eastward turn at z = 2.5 - 1, and of the westward one at z = 14.5 + 1.
		TestPlayer fed = rider(helper, GameType.SURVIVAL, new Vec3(8.5, 1, 1.5), EAST, -60);
		SkyHook.useOnRail(fed, InteractionHand.MAIN_HAND,
				new RailPoint(helper.absolutePos(north), AnchorSide.FRONT, RailSide.LEFT, 0));
		fed.setLastClientInput(sprinting);
		TestPlayer hungry = rider(helper, GameType.SURVIVAL, new Vec3(8.5, 1, 15.5), WEST_YAW, -60);
		hungry.getFoodData().setFoodLevel(6);
		SkyHook.useOnRail(hungry, InteractionHand.MAIN_HAND,
				new RailPoint(helper.absolutePos(new BlockPos(8, 3, 14)), AnchorSide.FRONT, RailSide.LEFT, 0));
		hungry.setLastClientInput(sprinting);
		double[] fedSpeed = new double[1];
		double[] hungrySpeed = new double[1];
		double[] boosted = new double[1];
		helper.onEachTick(() -> {
			if (fed.getVehicle() instanceof SkyHookEntity hook && hook.tickCount > 0) {
				double speed = hook.getDeltaMovement().length();
				fedSpeed[0] = Math.max(fedSpeed[0], speed);
				boosted[0] += speed;
				helper.assertTrue(hook.boosting(), "the fed rider isn't boosting");
			}
			if (hungry.getVehicle() instanceof SkyHookEntity hook && hook.tickCount > 0) {
				hungrySpeed[0] = Math.max(hungrySpeed[0], hook.getDeltaMovement().length());
				helper.assertFalse(hook.boosting(), "the hungry rider is boosting");
			}
			// The player's own tick, which its client's connection runs and these players have none of, eats.
			fed.getFoodData().tick(fed);
		});

		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(boosted[0] > 38, "not 38 blocks boosted yet"))
				.thenExecute(() -> helper.assertValueEqual(fed.getFoodData().getSaturationLevel(), 5.0F,
						"the saturation after 38 blocks"))
				.thenWaitUntil(() -> helper.assertTrue(boosted[0] > 42, "not 42 blocks boosted yet"))
				.thenExecute(() -> {
					helper.assertValueEqual(fed.getFoodData().getSaturationLevel(), 4.0F,
							"the saturation after 42 blocks");
					helper.assertValueEqual(fed.getFoodData().getFoodLevel(), 20, "the food after 42 blocks");
					helper.assertTrue(fedSpeed[0] > 0.55, "the fed rider went no faster than " + fedSpeed[0]);
					// The hook's move is measured between points the rail's arc-length table finds, to within a
					// thousandth of a block.
					helper.assertTrue(hungrySpeed[0] <= 0.501, "the hungry rider went as fast as " + hungrySpeed[0]);
				})
				.thenExecute(() -> done(helper, fed, hungry))
				.thenSucceed();
	}

	/**
	 * Track 7 up, so a rider's feet hang 5.5 above the floor, 4.5 above its top. Jump, added to W, does nothing: the
	 * rider rides on, 16.5 - 16.45 · 0.997⁷ = 0.392356 a tick after 8 ticks. Shift lets go on the 9th: the rider is
	 * where they hung, not on top of the rail, and flies on east at 0.392356, as vanilla's air moves them, keeping 0.91
	 * of it a tick. They fall the 4.5 blocks to the floor in 11 ticks, by then at least 0.392356 · (1 - 0.91¹¹) / 0.09
	 * = 2.81 further east, and less than flying on for ever would take them, 0.392356 / 0.09 = 4.36, and take a fall's
	 * damage, ⌊4.5 - 3⌋ = 1. The hook, with no rider, is gone.
	 */
	@GameTest(structure = AREA, maxTicks = 100)
	public void shiftLetsGoAndTheRiderFliesOnAtTheHooksSpeed(GameTestHelper helper) {
		floor(helper);
		BlockPos high = new BlockPos(1, 7, 4);
		lay(helper, high, straight(12));
		TestPlayer rider = rider(helper, GameType.SURVIVAL, new Vec3(4.5, 1, RIGHT_Z), EAST, -60);
		SkyHook.useOnRail(rider, InteractionHand.MAIN_HAND,
				new RailPoint(helper.absolutePos(high), AnchorSide.FRONT, RailSide.RIGHT, 2));
		rider.setLastClientInput(FORWARD);
		Vec3[] letGo = new Vec3[1];
		SkyHookEntity[] hook = new SkyHookEntity[1];

		helper.startSequence()
				.thenIdle(3)
				.thenExecute(() -> rider.setLastClientInput(new Input(true, false, false, false, true, false, false)))
				.thenIdle(5)
				.thenExecute(() -> {
					helper.assertTrue(rider.isPassenger(), "jump let go");
					assertNear(helper, hook(helper, rider).getDeltaMovement().x, 0.392356, "the speed after 8 ticks");
					rider.setLastClientInput(new Input(true, false, false, false, false, true, false));
					rider.setShiftKeyDown(true);
					letGo[0] = relativeFeet(helper, rider);
					hook[0] = hook(helper, rider);
				})
				.thenWaitUntil(() -> helper.assertFalse(rider.isPassenger(), "shift didn't let go"))
				.thenExecute(() -> {
					Vec3 feet = relativeFeet(helper, rider);
					helper.assertTrue(Math.abs(feet.y - 5.5) < EXACT && Math.abs(feet.z - RIGHT_Z) < EXACT
							&& Math.abs(feet.x - letGo[0].x) <= 0.5, "the rider let go at " + feet);
					Vec3 motion = rider.getDeltaMovement();
					helper.assertTrue(motion.distanceTo(new Vec3(0.392356, 0, 0)) < 1e-6,
							"the rider let go moving at " + motion);
					letGo[0] = feet;
					rider.setShiftKeyDown(false);
					rider.setLastClientInput(NO_KEYS);
				})
				.thenWaitUntil(() -> helper.assertTrue(hook[0].isRemoved(), "the hook stays without a rider"))
				.thenWaitUntil(() -> {
					// The server moves this player, as a client would its own.
					rider.doTick();
					helper.assertTrue(rider.onGround(), "still falling at " + relativeFeet(helper, rider));
				})
				.thenExecute(() -> {
					Vec3 feet = relativeFeet(helper, rider);
					assertNear(helper, feet.y, 1, "the height of the feet on the floor");
					helper.assertTrue(feet.x - letGo[0].x > 2.81 && feet.x - letGo[0].x < 4.36,
							"the rider landed " + (feet.x - letGo[0].x) + " east of where they let go");
					helper.assertValueEqual(rider.getHealth(), rider.getMaxHealth() - 1, "the health after the fall");
				})
				.thenExecute(() -> done(helper, rider))
				.thenSucceed();
	}

	/**
	 * Moving the Sky-Hook to the off hand keeps the rider on; with no hand holding one, they let go, and the hook, with
	 * no rider, is gone.
	 */
	@GameTest(structure = AREA, maxTicks = 40)
	public void aRiderLetsGoWhenNoHandHoldsASkyHook(GameTestHelper helper) {
		floor(helper);
		lay(helper, WEST, straight(12));
		TestPlayer rider = rider(helper, GameType.SURVIVAL, new Vec3(3.5, 1, RIGHT_Z), EAST, -30);
		attach(helper, rider, RailSide.RIGHT, 2);
		SkyHookEntity[] hook = new SkyHookEntity[1];

		helper.startSequence()
				.thenIdle(2)
				.thenExecute(() -> {
					hook[0] = hook(helper, rider);
					rider.setItemInHand(InteractionHand.OFF_HAND, rider.getMainHandItem());
					rider.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
				})
				.thenIdle(2)
				.thenExecute(() -> {
					helper.assertTrue(rider.isPassenger(), "the rider let go with the Sky-Hook in the off hand");
					rider.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
				})
				.thenIdle(2)
				.thenExecute(() -> {
					helper.assertFalse(rider.isPassenger(), "the rider still hangs with no Sky-Hook");
					assertNear(helper, relativeFeet(helper, rider).y, FEET_Y, "the height the rider let go at");
					helper.assertTrue(hook[0].isRemoved(), "the hook stays without a rider");
				})
				.thenExecute(() -> done(helper, rider))
				.thenSucceed();
	}

	/**
	 * Two straights, from x = 1 to 7 and on to 13. With the one ahead removed (its far anchor taken away), the anchor
	 * between them becomes an end and the rider, holding W, stops at it, x = 7.5. With the one under them removed, they
	 * let go.
	 */
	@GameTest(structure = AREA, maxTicks = 80)
	public void removingTheSegmentAheadStopsTheRiderAndTheOneUnderThemDropsThem(GameTestHelper helper) {
		floor(helper);
		lay(helper, WEST, straight(6), straight(6));
		TestPlayer rider = rider(helper, GameType.SURVIVAL, new Vec3(2.5, 1, RIGHT_Z), EAST, -30);
		attach(helper, rider, RailSide.RIGHT, 1);
		rider.setLastClientInput(FORWARD);
		helper.setBlock(new BlockPos(13, 3, 4), Blocks.AIR);

		helper.startSequence()
				.thenWaitUntil(() -> assertFeet(helper, rider, new Vec3(7.5, FEET_Y, RIGHT_Z)))
				.thenIdle(3)
				.thenExecute(() -> {
					assertFeet(helper, rider, new Vec3(7.5, FEET_Y, RIGHT_Z));
					rider.setLastClientInput(BACK);
				})
				.thenIdle(10)
				.thenExecute(() -> helper.setBlock(WEST, Blocks.AIR))
				.thenIdle(1)
				.thenExecute(() -> helper.assertFalse(rider.isPassenger(), "the rider still hangs from no segment"))
				.thenExecute(() -> done(helper, rider))
				.thenSucceed();
	}

	/**
	 * A hook saved mid-ride and loaded again, as when its rider logs out and back in, resumes at the same point, at
	 * rest: with W still held, its first tick moves its rider 0.05 on.
	 */
	@GameTest(structure = AREA, maxTicks = 40)
	public void aHooksRideSurvivesSavingAndLoading(GameTestHelper helper) {
		floor(helper);
		lay(helper, WEST, straight(12));
		TestPlayer rider = rider(helper, GameType.SURVIVAL, new Vec3(3.5, 1, RIGHT_Z), EAST, -30);
		attach(helper, rider, RailSide.RIGHT, 2);
		rider.setLastClientInput(FORWARD);
		Vec3[] saved = new Vec3[1];

		helper.startSequence()
				.thenIdle(6)
				.thenExecute(() -> saved[0] = reload(helper, rider).position())
				.thenWaitUntil(() -> helper.assertTrue(hook(helper, rider).tickCount >= 1, "not ticked yet"))
				.thenExecute(() -> helper.assertTrue(
						hook(helper, rider).position().distanceTo(saved[0].add(0.05, 0, 0)) < EXACT,
						"the loaded hook moved from " + saved[0] + " to " + hook(helper, rider).position()))
				.thenExecute(() -> done(helper, rider))
				.thenSucceed();
	}

	/** A hook loaded after its segment went, while its rider was away, lets them go. */
	@GameTest(structure = AREA, maxTicks = 40)
	public void aHookLoadedAfterItsSegmentWentLetsGo(GameTestHelper helper) {
		floor(helper);
		lay(helper, WEST, straight(12));
		TestPlayer rider = rider(helper, GameType.SURVIVAL, new Vec3(3.5, 1, RIGHT_Z), EAST, -30);
		attach(helper, rider, RailSide.RIGHT, 2);

		helper.startSequence()
				.thenIdle(6)
				.thenExecute(() -> {
					helper.setBlock(WEST.east(12), Blocks.AIR);
					reload(helper, rider);
				})
				.thenIdle(2)
				.thenExecute(() -> helper.assertFalse(rider.isPassenger(), "the rider still hangs from a gone segment"))
				.thenExecute(() -> done(helper, rider))
				.thenSucceed();
	}

	/**
	 * A hook loaded before the chunk of the anchor that names its segment, as when its rider logs back in before the
	 * track's chunks have loaded, waits with its rider hanging on, then resumes once that chunk has loaded, at rest:
	 * with W held, its first tick on moves its rider 0.05 on. The straight runs 512 east to (13, 3, 4), far past the limits (which only
	 * placing checks), so that its west anchor, first in {@code BlockPos} order and so the one naming it, is in a chunk
	 * far from the test's, loaded only while forced.
	 */
	@GameTest(structure = AREA, maxTicks = 300)
	public void aHookLoadedBeforeItsTracksChunkWaitsForItWithItsRiderOn(GameTestHelper helper) {
		floor(helper);
		ServerLevel level = helper.getLevel();
		BlockPos west = helper.absolutePos(WEST.east(12)).west(512);
		SkyLineSegmentGameTest.setForced(level, west, true);
		SkyLineSegmentGameTest.layAt(level, west, straight(512));
		AnchorBlockEntity westAnchor = SkyLineSegmentGameTest.anchorAt(level, west);
		TestPlayer rider = rider(helper, GameType.SURVIVAL, new Vec3(7.5, 1, RIGHT_Z), EAST, -30);
		// 6 short of the east end, at x = 13.5: x = 7.5.
		SkyHook.useOnRail(rider, InteractionHand.MAIN_HAND, new RailPoint(west, AnchorSide.FRONT, RailSide.RIGHT, 506));
		rider.setLastClientInput(FORWARD);
		ValueInput[] saved = {logOut(helper, rider)};
		SkyLineSegmentGameTest.setForced(level, west, false);
		SkyHookEntity[] loaded = new SkyHookEntity[1];
		Vec3 hanging = helper.absoluteVec(new Vec3(7.5, 4, RIGHT_Z));

		helper.startSequence()
				.thenWaitUntil(() -> SkyLineSegmentGameTest.assertUnloaded(helper, westAnchor))
				.thenExecute(() -> loaded[0] = logIn(helper, rider, saved[0]))
				.thenIdle(5)
				.thenExecute(() -> {
					helper.assertTrue(loaded[0].hasPassenger(rider), "the rider let go before the track loaded");
					helper.assertTrue(loaded[0].position().distanceTo(hanging) < EXACT,
							"the hook moved before the track loaded, to " + loaded[0].position());
					SkyLineSegmentGameTest.setForced(level, west, true);
				})
				.thenWaitUntil(() -> helper.assertFalse(loaded[0].position().equals(hanging), "not resumed yet"))
				.thenExecute(() -> {
					helper.assertTrue(loaded[0].hasPassenger(rider), "the rider let go once the track loaded");
					helper.assertTrue(loaded[0].position().distanceTo(hanging.add(0.05, 0, 0)) < EXACT,
							"the resumed hook moved from " + hanging + " to " + loaded[0].position());
					SkyLineSegmentGameTest.setForced(level, west, false);
				})
				.thenExecute(() -> done(helper, rider))
				.thenSucceed();
	}

	/**
	 * A rider on the right rail riding east at 0.5, 10 blocks a second, which 11 ticks of W from x = 2.5 bring them to
	 * at x = 2.5 + 3.2030769, right-clicks the left rail straight across: a 2-block hop, ⌈2 / 0.8⌉ = 3 ticks. They land
	 * at the speed they took off at, all of it along the rail, and W, held all along, keeps them at 0.5: a tick later
	 * they are 0.5 further east.
	 */
	@GameTest(structure = AREA, maxTicks = 40)
	public void aRiderAtSpeedHopsToTheTracksOtherRailAndRidesOnAtTheirSpeed(GameTestHelper helper) {
		floor(helper);
		lay(helper, WEST, straight(12));
		TestPlayer rider = rider(helper, GameType.SURVIVAL, new Vec3(2.5, 1, RIGHT_Z), EAST, -30);
		attach(helper, rider, RailSide.RIGHT, 1);
		rider.setLastClientInput(FORWARD);
		double x = 2.5 + 3.2030769;

		helper.startSequence()
				.thenWaitUntil(() -> assertTicked(helper, rider, 11))
				.thenExecute(() -> {
					assertFeet(helper, rider, new Vec3(x, FEET_Y, RIGHT_Z));
					jump(helper, rider, WEST, RailSide.LEFT, x - 1.5);
				})
				.thenWaitUntil(() -> assertTicked(helper, rider, 14))
				.thenExecute(() -> assertFeet(helper, rider, new Vec3(x, FEET_Y, LEFT_Z)))
				.thenWaitUntil(() -> assertTicked(helper, rider, 15))
				.thenExecute(() -> {
					assertFeet(helper, rider, new Vec3(x + 0.5, FEET_Y, LEFT_Z));
					assertNear(helper, hook(helper, rider).getDeltaMovement().x, 0.5, "the speed east");
				})
				.thenExecute(() -> done(helper, rider))
				.thenSucceed();
	}

	/**
	 * Riding east at 0.5, a rider jumps to a straight running south across their track, at x = 10.5, 7.5 along it from
	 * z = 0.5: 5.41 blocks, 7 ticks. Their velocity is square to the rail they land on, so they land at rest, and with
	 * no keys stay there.
	 */
	@GameTest(structure = AREA, maxTicks = 60)
	public void aRiderJumpingToARailAtRightAnglesLandsAtRest(GameTestHelper helper) {
		floor(helper);
		lay(helper, WEST, straight(12));
		BlockPos north = new BlockPos(9, 3, 0);
		lay(helper, north, segment(new Vec3i(0, 0, 14), Heading.SOUTH));
		TestPlayer rider = rider(helper, GameType.SURVIVAL, new Vec3(2.5, 1, RIGHT_Z), EAST, -30);
		attach(helper, rider, RailSide.RIGHT, 1);
		rider.setLastClientInput(FORWARD);
		Vec3 landing = new Vec3(10.5, FEET_Y, 8);

		helper.startSequence()
				.thenWaitUntil(() -> assertTicked(helper, rider, 11))
				.thenExecute(() -> {
					rider.setLastClientInput(NO_KEYS);
					// The left rail of a straight south is its east one.
					jump(helper, rider, north, RailSide.LEFT, 7.5);
				})
				.thenWaitUntil(() -> assertTicked(helper, rider, 18))
				.thenExecute(() -> {
					assertFeet(helper, rider, landing);
					// The rider's client plays the riding loop by it: a landing at rest makes no sound of the flight.
					assertNear(helper, hook(helper, rider).getDeltaMovement().length(), 0, "the speed on landing");
				})
				.thenIdle(3)
				.thenExecute(() -> {
					assertFeet(helper, rider, landing);
					assertNear(helper, hook(helper, rider).getDeltaMovement().length(), 0, "the speed");
				})
				.thenExecute(() -> done(helper, rider))
				.thenSucceed();
	}

	/**
	 * The server lets a rider jump to a rail point within reach, 8 and 1 for lag: the left rail 9 along is 8.29 from
	 * the eyes of a rider 1 along the right one, an 8.25-block jump of 11 ticks. It refuses one out of reach, the left
	 * rail 10 along, 9.26 from them, and one to the rider's own line, here their own rail ahead. Those riders ride on
	 * where they were, told nothing.
	 */
	@GameTest(structure = AREA, maxTicks = 30)
	public void aRiderJumpsWithinReachButNotBeyondItNorAlongTheirOwnLine(GameTestHelper helper) {
		floor(helper);
		lay(helper, WEST, straight(12));
		TestPlayer near = rider(helper, GameType.SURVIVAL, new Vec3(2.5, 1, RIGHT_Z), EAST, -30);
		attach(helper, near, RailSide.RIGHT, 1);
		TestPlayer far = rider(helper, GameType.SURVIVAL, new Vec3(2.5, 1, RIGHT_Z), EAST, -30);
		attach(helper, far, RailSide.RIGHT, 1);
		TestPlayer own = rider(helper, GameType.SURVIVAL, new Vec3(4.5, 1, RIGHT_Z), EAST, -30);
		attach(helper, own, RailSide.RIGHT, 3);

		jump(helper, near, WEST, RailSide.LEFT, 9);
		jump(helper, far, WEST, RailSide.LEFT, 10);
		jump(helper, own, WEST, RailSide.RIGHT, 7);

		helper.startSequence()
				.thenIdle(12)
				.thenExecute(() -> {
					assertFeet(helper, near, new Vec3(10.5, FEET_Y, LEFT_Z));
					assertFeet(helper, far, new Vec3(2.5, FEET_Y, RIGHT_Z));
					assertFeet(helper, own, new Vec3(4.5, FEET_Y, RIGHT_Z));
					helper.assertValueEqual(far.overlay, List.of(), "the action bar of the rider out of reach");
					helper.assertValueEqual(own.overlay, List.of(), "the action bar of the rider on their own line");
				})
				.thenExecute(() -> done(helper, near, far, own))
				.thenSucceed();
	}

	/**
	 * Blocks don't refuse a jump, as nothing keeps a player from jumping between minecarts they can aim at. Stone at
	 * (4, 2, 3) is where the body of anyone hanging from the left rail at x = 4.5 would be: a rider jumping there lands
	 * with their body in it. Stone at (6, 1, 4), between the rails, is below the eyes of a rider at x = 6.5 (y = 1.5 +
	 * 1.62) but in their body's way on the hop across: they fly through it. Both land after the hop's 3 ticks, told
	 * nothing.
	 */
	@GameTest(structure = AREA, maxTicks = 20)
	public void blocksInTheBodysWayDontStopAJump(GameTestHelper helper) {
		floor(helper);
		lay(helper, WEST, straight(12));
		helper.setBlock(new BlockPos(4, 2, 3), Blocks.STONE);
		helper.setBlock(new BlockPos(6, 1, 4), Blocks.STONE);
		TestPlayer cramped = rider(helper, GameType.SURVIVAL, new Vec3(4.5, 1, RIGHT_Z), EAST, -30);
		attach(helper, cramped, RailSide.RIGHT, 3);
		TestPlayer walled = rider(helper, GameType.SURVIVAL, new Vec3(6.5, 1, RIGHT_Z), EAST, -30);
		attach(helper, walled, RailSide.RIGHT, 5);

		jump(helper, cramped, WEST, RailSide.LEFT, 3);
		jump(helper, walled, WEST, RailSide.LEFT, 5);

		helper.startSequence()
				.thenWaitUntil(() -> assertTicked(helper, walled, 3))
				.thenExecute(() -> {
					assertFeet(helper, cramped, new Vec3(4.5, FEET_Y, LEFT_Z));
					assertFeet(helper, walled, new Vec3(6.5, FEET_Y, LEFT_Z));
					helper.assertValueEqual(cramped.overlay, List.of(), "the action bar of the rider landing in stone");
					helper.assertValueEqual(walled.overlay, List.of(), "the action bar of the rider flying through it");
				})
				.thenExecute(() -> done(helper, cramped, walled))
				.thenSucceed();
	}

	/**
	 * A rider who right-clicks another rail during a flight, here the right one they took off from, 2 further east and
	 * no part of the left rail's line, lands where the flight goes, on the left rail across.
	 */
	@GameTest(structure = AREA, maxTicks = 20)
	public void aSecondJumpDuringAFlightIsRefused(GameTestHelper helper) {
		floor(helper);
		lay(helper, WEST, straight(12));
		TestPlayer rider = rider(helper, GameType.SURVIVAL, new Vec3(10.5, 1, RIGHT_Z), EAST, -30);
		attach(helper, rider, RailSide.RIGHT, 9);
		jump(helper, rider, WEST, RailSide.LEFT, 9);

		helper.startSequence()
				.thenWaitUntil(() -> assertTicked(helper, rider, 1))
				.thenExecute(() -> jump(helper, rider, WEST, RailSide.RIGHT, 11))
				.thenIdle(5)
				.thenExecute(() -> assertFeet(helper, rider, new Vec3(10.5, FEET_Y, LEFT_Z)))
				.thenExecute(() -> done(helper, rider))
				.thenSucceed();
	}

	/**
	 * A rider at rest at x = 6.5 hops to the left rail across, 3 ticks, and holds Shift after the first. They let go
	 * where they hang, a third of the way, carrying the hook's last move: p(1/3) - p(0) = (0, 2/9, -2/3), the lift 4 ·
	 * 0.25 · (1/3) · (2/3).
	 */
	@GameTest(structure = AREA, maxTicks = 20)
	public void shiftDuringAFlightLetsGoAtTheFlightsLastMove(GameTestHelper helper) {
		floor(helper);
		lay(helper, WEST, straight(12));
		TestPlayer rider = rider(helper, GameType.SURVIVAL, new Vec3(6.5, 1, RIGHT_Z), EAST, -30);
		attach(helper, rider, RailSide.RIGHT, 5);
		jump(helper, rider, WEST, RailSide.LEFT, 5);
		Vec3 move = new Vec3(0, 2 / 9.0, -2 / 3.0);

		helper.startSequence()
				.thenWaitUntil(() -> assertTicked(helper, rider, 1))
				.thenExecute(() -> rider.setShiftKeyDown(true))
				.thenWaitUntil(() -> helper.assertFalse(rider.isPassenger(), "shift didn't let go"))
				.thenExecute(() -> {
					assertFeet(helper, rider, new Vec3(6.5, FEET_Y, RIGHT_Z).add(move));
					helper.assertTrue(rider.getDeltaMovement().distanceTo(move) < EXACT,
							"the rider let go moving at " + rider.getDeltaMovement());
				})
				.thenExecute(() -> done(helper, rider))
				.thenSucceed();
	}

	/**
	 * A rider at rest at x = 6.5 jumps to a second straight 6 to the south, from (1, 3, 10): 4 blocks to its left rail,
	 * 5 ticks. With that straight removed after 2 of them, they let go, carrying the hook's last move: p(2/5) - p(1/5),
	 * 0.8 south and, with a lift of 4 / 8 = 0.5, 4 · 0.5 · (0.4 · 0.6 - 0.2 · 0.8) = 0.16 up.
	 */
	@GameTest(structure = AREA, maxTicks = 20)
	public void removingTheTargetsSegmentDuringAFlightLetsTheRiderGo(GameTestHelper helper) {
		floor(helper);
		lay(helper, WEST, straight(12));
		BlockPos south = new BlockPos(1, 3, 10);
		lay(helper, south, straight(12));
		TestPlayer rider = rider(helper, GameType.SURVIVAL, new Vec3(6.5, 1, RIGHT_Z), EAST, -30);
		attach(helper, rider, RailSide.RIGHT, 5);
		jump(helper, rider, south, RailSide.LEFT, 5);

		helper.startSequence()
				.thenWaitUntil(() -> assertTicked(helper, rider, 2))
				.thenExecute(() -> helper.setBlock(south, Blocks.AIR))
				.thenIdle(1)
				.thenExecute(() -> {
					helper.assertFalse(rider.isPassenger(), "the rider still hangs from a flight to no segment");
					helper.assertTrue(rider.getDeltaMovement().distanceTo(new Vec3(0, 0.16, 0.8)) < EXACT,
							"the rider let go moving at " + rider.getDeltaMovement());
				})
				.thenExecute(() -> done(helper, rider))
				.thenSucceed();
	}

	/**
	 * A hook saved and loaded during a flight, as when its rider logs out and back in, resumes where the flight was
	 * going, at rest: on the left rail across, with no keys staying there.
	 */
	@GameTest(structure = AREA, maxTicks = 20)
	public void aHookSavedDuringAFlightResumesOnTheTargetAtRest(GameTestHelper helper) {
		floor(helper);
		lay(helper, WEST, straight(12));
		TestPlayer rider = rider(helper, GameType.SURVIVAL, new Vec3(6.5, 1, RIGHT_Z), EAST, -30);
		attach(helper, rider, RailSide.RIGHT, 5);
		jump(helper, rider, WEST, RailSide.LEFT, 5);
		Vec3 landing = new Vec3(6.5, FEET_Y, LEFT_Z);

		helper.startSequence()
				.thenWaitUntil(() -> assertTicked(helper, rider, 1))
				.thenExecute(() -> reload(helper, rider))
				.thenWaitUntil(() -> assertTicked(helper, rider, 1))
				.thenExecute(() -> assertFeet(helper, rider, landing))
				.thenIdle(3)
				.thenExecute(() -> assertFeet(helper, rider, landing))
				.thenExecute(() -> done(helper, rider))
				.thenSucceed();
	}

	/**
	 * Riding east at 0.5 with W, a survival rider jumps to a straight 6 long from (1, 3, 10), landing on its left rail
	 * a quarter of a block from its east end, x = 7.5. They land at 2/15, the most braking lets stop in 0.25, and so
	 * reach the end with too little speed to hurt, where landing at 0.5 would have crashed them into it for 2 damage,
	 * which comes 2 ticks after the stop.
	 */
	@GameTest(structure = AREA, maxTicks = 60)
	public void aRiderLandingFastNearAnEndIsntHurt(GameTestHelper helper) {
		floor(helper);
		lay(helper, WEST, straight(12));
		BlockPos south = new BlockPos(1, 3, 10);
		lay(helper, south, straight(6));
		TestPlayer rider = rider(helper, GameType.SURVIVAL, new Vec3(2.5, 1, RIGHT_Z), EAST, -30);
		attach(helper, rider, RailSide.RIGHT, 1);
		rider.setLastClientInput(FORWARD);

		helper.startSequence()
				.thenWaitUntil(() -> assertTicked(helper, rider, 11))
				.thenExecute(() -> jump(helper, rider, south, RailSide.LEFT, 5.75))
				.thenWaitUntil(() -> assertFeet(helper, rider, new Vec3(7.5, FEET_Y, 9.5)))
				.thenIdle(4)
				.thenExecute(() -> helper.assertValueEqual(rider.getHealth(), rider.getMaxHealth(), "the health"))
				.thenExecute(() -> done(helper, rider))
				.thenSucceed();
	}

	/** Fails unless the rider's hook has ticked so many times, to wait for in a sequence. */
	private static void assertTicked(GameTestHelper helper, TestPlayer rider, int ticks) {
		helper.assertValueEqual(hook(helper, rider).tickCount, ticks, "the hook's ticks");
	}

	/** Uses the Sky-Hook in the main hand on a rail of the segment leaving an anchor's front, as a client sends it. */
	private static void jump(GameTestHelper helper, TestPlayer rider, BlockPos anchor, RailSide rail, double distance) {
		SkyHook.useOnRail(rider, InteractionHand.MAIN_HAND,
				new RailPoint(helper.absolutePos(anchor), AnchorSide.FRONT, rail, distance));
	}

	/**
	 * Saves the rider's hook, takes it away and loads it again, the rider riding it, as when the rider logs out and
	 * back in; returns the loaded hook.
	 */
	private static SkyHookEntity reload(GameTestHelper helper, TestPlayer rider) {
		return logIn(helper, rider, logOut(helper, rider));
	}

	/** Saves the rider's hook and takes it away, the rider off it, as when the rider logs out; returns what it saved. */
	private static ValueInput logOut(GameTestHelper helper, TestPlayer rider) {
		SkyHookEntity hook = hook(helper, rider);
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING,
				helper.getLevel().registryAccess());
		hook.saveWithoutId(output);
		rider.stopRiding();
		hook.discard();
		return TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(),
				output.buildResult());
	}

	/** Loads a saved hook, the rider riding it, as when the rider logs back in; returns the loaded hook. */
	private static SkyHookEntity logIn(GameTestHelper helper, TestPlayer rider, ValueInput saved) {
		SkyHookEntity loaded = new SkyHookEntity(SkyLine.HOOK_ENTITY, helper.getLevel());
		loaded.load(saved);
		loaded.setUUID(UUID.randomUUID());
		helper.getLevel().addFreshEntity(loaded);
		helper.assertTrue(rider.startRiding(loaded, true, false), "the rider can't ride the loaded hook");
		return loaded;
	}

	/** A straight 45° descent, 6 east and 6 down. */
	private static Segment descent() {
		return SkyLineSegmentGameTest.segment(new Vec3i(6, -6, 0), Heading.EAST, -45, Heading.EAST, -45);
	}

	/** A level straight east of a length. */
	static Segment straight(int length) {
		return segment(new Vec3i(length, 0, 0), Heading.EAST);
	}

	/** A level segment from an anchor heading the same way as it, front side to back. */
	private static Segment segment(Vec3i offset, Heading heading) {
		return SkyLineSegmentGameTest.segment(offset, heading, 0, heading, 0);
	}

	/** A level quarter turn from one heading to another, to an anchor so far off. */
	private static Segment quarter(Vec3i offset, Heading from, Heading to) {
		return SkyLineSegmentGameTest.segment(offset, from, 0, to, 0);
	}

	/** A level quarter turn from east to another heading, to an anchor offset as far east and north or south. */
	private static Segment turn(Heading to, int east, int south) {
		return SkyLineSegmentGameTest.segment(new Vec3i(east, 0, south), Heading.EAST, 0, to, 0);
	}

	/**
	 * Lays a chain of segments, each leaving the far anchor of the one before, as laying track will: each anchor faces
	 * its tangent and holds the segments on both its sides.
	 */
	static void lay(GameTestHelper helper, BlockPos from, Segment... chain) {
		Map<BlockPos, List<Segment>> held = new LinkedHashMap<>();
		BlockPos anchor = from;
		for (Segment segment : chain) {
			BlockPos partner = anchor.offset(segment.offset());
			held.computeIfAbsent(anchor, ignored -> new ArrayList<>()).add(segment);
			held.computeIfAbsent(partner, ignored -> new ArrayList<>()).add(segment.reversed());
			anchor = partner;
		}
		held.forEach((pos, segments) -> {
			helper.setBlock(pos, SkyLineAnchorGameTest.anchor(segments.getFirst().from().tangent().heading()));
			AnchorBlockEntity entity = helper.getBlockEntity(pos, AnchorBlockEntity.class);
			entity.setPitch(segments.getFirst().from().tangent().pitch());
			segments.forEach(entity::setSegment);
		});
	}

	/** Uses the Sky-Hook in the main hand on a rail of the straight from {@link #WEST}, as a client sends it. */
	private static void attach(GameTestHelper helper, TestPlayer rider, RailSide rail, double distance) {
		SkyHook.useOnRail(rider, InteractionHand.MAIN_HAND,
				new RailPoint(helper.absolutePos(WEST), AnchorSide.FRONT, rail, distance));
	}

	/** A player in the test's level holding a Sky-Hook, standing at a point and looking along a yaw and a pitch. */
	private static TestPlayer rider(GameTestHelper helper, GameType gameType, Vec3 feet, float yaw, float pitch) {
		TestPlayer rider = TestPlayer.place(helper, gameType, feet, yaw, pitch);
		rider.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(SkyLine.HOOK));
		return rider;
	}

	/** Takes the test's players out of the level, as when they log out. */
	private static void done(GameTestHelper helper, TestPlayer... riders) {
		TestPlayer.remove(helper, riders);
	}

	private static SkyHookEntity hook(GameTestHelper helper, TestPlayer rider) {
		if (rider.getVehicle() instanceof SkyHookEntity hook) {
			return hook;
		}
		throw helper.assertionException(Component.literal("the rider isn't hanging from a hook"));
	}

	private static Vec3 relativeFeet(GameTestHelper helper, TestPlayer rider) {
		return helper.relativeVec(rider.position());
	}

	private static void assertNear(GameTestHelper helper, double actual, double expected, String name) {
		helper.assertTrue(Math.abs(actual - expected) < EXACT, name + " is " + actual + ", not " + expected);
	}

	private static void assertFeet(GameTestHelper helper, TestPlayer rider, Vec3 expected) {
		Vec3 actual = relativeFeet(helper, rider);
		helper.assertTrue(actual.distanceTo(expected) < EXACT,
				"the rider's feet are at " + actual + ", not " + expected);
	}

	/** Stone across the bottom of the area. */
	static void floor(GameTestHelper helper) {
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
			}
		}
	}
}
