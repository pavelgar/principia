package io.github.pavelgar.principia.test;

import java.util.List;
import java.util.Optional;

import io.github.pavelgar.principia.skyline.AnchorBlock;
import io.github.pavelgar.principia.skyline.AnchorBlockEntity;
import io.github.pavelgar.principia.skyline.AnchorSide;
import io.github.pavelgar.principia.skyline.Heading;
import io.github.pavelgar.principia.skyline.Placement;
import io.github.pavelgar.principia.skyline.Segment;
import io.github.pavelgar.principia.skyline.SkyLine;
import io.github.pavelgar.principia.skyline.TrackStart;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/**
 * The Sky-Line item: how it is made, and laying track with it as a player does, clicking blocks and the air. Placing,
 * selecting and clearing come through vanilla's use of an item on a block, and laying through the block the client
 * sends ({@link Placement#lay}).
 *
 * <p>The laying tests run in a 16 × 10 × 16 area on a stone floor at y = 0: a click on the floor's top at (x, 0, z)
 * puts a new anchor in the cell above it, (x, 1, z). A player faces east at a yaw of -90°, south at 0°.
 */
public class SkyLineItemGameTest {
	private static final String AREA = "principia-test:sky_line";
	private static final BlockPos ANCHOR = new BlockPos(3, 3, 3);
	private static final float EAST = -90;
	private static final float SOUTH = 0;
	/** Where the laying tests start their track: a new anchor heading east, from a click on the floor below it. */
	private static final BlockPos START = new BlockPos(2, 1, 4);

	/**
	 * Vanilla's rail recipe without the stick, as a crafting table's grid has it: iron ingots down the left and right
	 * columns, the middle one empty. It makes 16.
	 */
	@GameTest
	public void theCraftingRecipeMakesSixteenSkyLines(GameTestHelper helper) {
		ItemStack iron = new ItemStack(Items.IRON_INGOT);
		CraftingInput grid = CraftingInput.of(3, 3, List.of(iron, ItemStack.EMPTY, iron, iron, ItemStack.EMPTY, iron,
				iron, ItemStack.EMPTY, iron));

		ItemStack made = helper.getLevel().recipeAccess().getRecipeFor(RecipeType.CRAFTING, grid, helper.getLevel())
				.map(recipe -> recipe.value().assemble(grid)).orElse(ItemStack.EMPTY);

		helper.assertTrue(made.is(SkyLine.RAIL) && made.getCount() == 16, "the recipe makes " + made);
		helper.succeed();
	}

	/** Picking an anchor in creative, at its middle or at an end of its beam, gives the item that lays it. */
	@GameTest
	public void pickingAnAnchorGivesASkyLine(GameTestHelper helper) {
		helper.setBlock(ANCHOR, SkyLineAnchorGameTest.anchor(Heading.SOUTH));

		for (BlockPos part : new BlockPos[]{ANCHOR, ANCHOR.east()}) {
			BlockPos absolute = helper.absolutePos(part);
			ItemStack picked = helper.getLevel().getBlockState(absolute).getCloneItemStack(helper.getLevel(),
					absolute, false);
			helper.assertTrue(picked.is(SkyLine.RAIL), "picking the anchor at " + part + " gives " + picked);
		}
		helper.succeed();
	}

	/**
	 * With nothing selected, a click on the floor's top places a lone anchor in the cell above, level, heading the way
	 * the player faces snapped to 45°: a yaw of -130° is nearest north-east's -135°. Its beam's ends go in the corner
	 * cells square to it, north-west and south-east. It costs one Sky-Line, and is selected, to lay from at once. A
	 * sneak-use with nothing selected does the same, so a player can place beside a block with a use of its own: here
	 * after a sneak-use that clears the first anchor's selection.
	 */
	@GameTest(structure = AREA)
	public void aClickOnABlockFacePlacesALoneLevelAnchorHeadingTheFacingForOneSkyLine(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		TestPlayer player = layer(helper, GameType.SURVIVAL, 16, -130);

		clickTop(helper, player, START.below());
		assertSelected(helper, player, START, Optional.empty());
		player.setShiftKeyDown(true);
		clickTop(helper, player, START.east(6).below());
		helper.assertTrue(TrackStart.of(player) == null, "the sneak-use didn't clear the selection");
		helper.assertBlockNotPresent(SkyLine.ANCHOR, START.east(6));
		clickTop(helper, player, START.east(6).below());

		for (BlockPos anchor : List.of(START, START.east(6))) {
			assertAnchor(helper, anchor, Heading.NORTH_EAST);
			helper.assertValueEqual(helper.getBlockEntity(anchor, AnchorBlockEntity.class).pitch(), 0f,
					"the pitch of the anchor at " + anchor);
			SkyLineAnchorGameTest.assertEnd(helper, anchor.offset(-1, 0, -1), Heading.SOUTH_EAST);
			SkyLineAnchorGameTest.assertEnd(helper, anchor.offset(1, 0, 1), Heading.NORTH_WEST);
		}
		helper.assertValueEqual(player.getMainHandItem().getCount(), 14, "the Sky-Lines left");
		assertSelected(helper, player, START.east(6), Optional.empty());
		TestPlayer.remove(helper, player);
		helper.succeed();
	}

	/**
	 * A lone anchor needs room: a torch in its cell, or in a cell its beam reaches into, keeps it out, and the player
	 * is told why. A player in adventure mode, who may not build, places none either. None of them pays.
	 */
	@GameTest(structure = AREA)
	public void aLoneAnchorNeedsRoomAndAPlayerWhoMayBuild(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		TestPlayer player = layer(helper, GameType.SURVIVAL, 16, EAST);
		TestPlayer adventure = layer(helper, GameType.ADVENTURE, 16, EAST);

		helper.setBlock(START, Blocks.TORCH);
		clickTop(helper, player, START.below());
		helper.setBlock(START, Blocks.AIR);
		helper.setBlock(START.south(), Blocks.TORCH);
		clickTop(helper, player, START.below());
		helper.setBlock(START.south(), Blocks.AIR);
		clickTop(helper, adventure, START.below());

		helper.assertBlockNotPresent(SkyLine.ANCHOR, START);
		helper.assertValueEqual(player.overlay, List.of(red(SkyLine.NO_ROOM_FOR_ANCHOR),
				red(SkyLine.NO_ROOM_FOR_ANCHOR)), "the action bar");
		helper.assertValueEqual(player.getMainHandItem().getCount(), 16, "the Sky-Lines left");
		helper.assertValueEqual(adventure.getMainHandItem().getCount(), 16, "the adventurer's Sky-Lines left");
		TestPlayer.remove(helper, player, adventure);
		helper.succeed();
	}

	/**
	 * A click on any part of an anchor with nothing selected selects it, for the player: a lone anchor at its middle,
	 * keeping no side while both are free, and at an end of its beam. One holding a segment on its front is selected
	 * with its back, the side a segment from it will leave by. A sneak-use clears each before the next.
	 */
	@GameTest(structure = AREA)
	public void aClickOnAnyPartOfAnAnchorSelectsIt(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		helper.setBlock(START, SkyLineAnchorGameTest.anchor(Heading.EAST));
		BlockPos held = START.south(6);
		SkyHookGameTest.lay(helper, held, SkyHookGameTest.straight(6));
		TestPlayer player = layer(helper, GameType.SURVIVAL, 16, EAST);

		clickTop(helper, player, START);
		assertSelected(helper, player, START, Optional.empty());
		clear(helper, player);
		clickTop(helper, player, START.north());
		assertSelected(helper, player, START, Optional.empty());
		clear(helper, player);
		clickTop(helper, player, held);
		assertSelected(helper, player, held, Optional.of(AnchorSide.BACK));

		helper.assertValueEqual(player.getMainHandItem().getCount(), 16, "the Sky-Lines left");
		TestPlayer.remove(helper, player);
		helper.succeed();
	}

	/** An anchor holding a segment on both sides can't be selected: the click is refused and nothing is kept. */
	@GameTest(structure = AREA)
	public void anAnchorWithNoFreeSideCantBeSelected(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		SkyHookGameTest.lay(helper, START, SkyHookGameTest.straight(6), SkyHookGameTest.straight(6));
		TestPlayer player = layer(helper, GameType.SURVIVAL, 16, EAST);

		clickTop(helper, player, START.east(6));

		helper.assertTrue(TrackStart.of(player) == null, "the click on a full anchor is kept");
		helper.assertValueEqual(player.overlay, List.of(red(SkyLine.NO_FREE_SIDE)),
				"the action bar");
		TestPlayer.remove(helper, player);
		helper.succeed();
	}

	/**
	 * Facing east, a lone anchor placed and selected, a click on the floor 10 east lays a straight 10 east to a new
	 * anchor heading east: it leaves the west one on its front and the east one on its back, level. The anchor costs 1
	 * and the straight 10 and its new anchor 11, taken from the held stack (6, then 5) and then the rest of the
	 * inventory (8, leaving 2).
	 */
	@GameTest(structure = AREA)
	public void placingSelectingAndLayingPayFromTheHeldStackFirst(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		TestPlayer player = layer(helper, GameType.SURVIVAL, 6, EAST);
		player.getInventory().setItem(9, new ItemStack(SkyLine.RAIL, 8));

		placeAndSelect(helper, player, START);
		layTo(helper, player, START.east(10).below());

		assertAnchor(helper, START, Heading.EAST, SkyHookGameTest.straight(10));
		assertAnchor(helper, START.east(10), Heading.EAST, SkyHookGameTest.straight(10).reversed());
		SkyLineAnchorGameTest.assertEnd(helper, START.east(10).north(), Heading.SOUTH);
		SkyLineAnchorGameTest.assertEnd(helper, START.east(10).south(), Heading.NORTH);
		helper.assertTrue(player.getMainHandItem().isEmpty(), "the held stack is " + player.getMainHandItem());
		helper.assertValueEqual(player.getInventory().getItem(9).getCount(), 2, "the Sky-Lines left in the inventory");
		TestPlayer.remove(helper, player);
		helper.succeed();
	}

	/**
	 * From a lone anchor, a click on top of a pillar 2 high, 10 east, lays a segment to a new anchor 10 east and 2 up.
	 * Neither anchor held a segment, so both take the pitch of the chord, atan(2 / 10) ≈ 11.31°, and the segment is
	 * straight, √104 ≈ 10.2 long: it costs 11, and 1 more for the new anchor. With the anchor placed first, 13 of 16.
	 * The selection moves on to the new anchor's free side, its front.
	 */
	@GameTest(structure = AREA)
	public void layingToANewAnchorTiltsBothAlongTheChordAndMovesTheSelectionOn(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		BlockPos end = START.offset(10, 2, 0);
		helper.setBlock(end.below(), Blocks.STONE);
		helper.setBlock(end.below(2), Blocks.STONE);
		TestPlayer player = layer(helper, GameType.SURVIVAL, 16, EAST);

		placeAndSelect(helper, player, START);
		layTo(helper, player, end.below());

		for (BlockPos anchor : List.of(START, end)) {
			helper.assertBlockProperty(anchor, AnchorBlock.HEADING, Heading.EAST);
			helper.assertTrue(Math.abs(helper.getBlockEntity(anchor, AnchorBlockEntity.class).pitch() - 11.3099) < 1e-3,
					"the anchor at " + anchor + " is pitched " + helper.getBlockEntity(anchor, AnchorBlockEntity.class)
							.pitch() + "°, not 11.31°");
		}
		Segment laid = helper.getBlockEntity(START, AnchorBlockEntity.class).segment(AnchorSide.FRONT);
		helper.assertTrue(laid != null && laid.offset().equals(new Vec3i(10, 2, 0))
				&& laid.to().side() == AnchorSide.BACK, "the west anchor holds " + laid + " on its front");
		helper.assertValueEqual(player.getMainHandItem().getCount(), 3, "the Sky-Lines left");
		assertSelected(helper, player, end, Optional.of(AnchorSide.FRONT));
		TestPlayer.remove(helper, player);
		helper.succeed();
	}

	/**
	 * A segment laid to an existing anchor costs only its length, and the selection moves on to that anchor's other
	 * side, or clears once it has none. From a lone anchor at (2, 1, 4) heading east: a click on the middle of a lone
	 * anchor 5 east lays a straight 5 to its back (rule A, the side toward the start), 5 rails, and selects its front;
	 * a click on an end of the beam of an anchor 5 further, whose front holds a straight 3 east, lays a straight 5 to its
	 * back, 5 rails, and clears the selection. With the first anchor, 11 of 16.
	 */
	@GameTest(structure = AREA)
	public void layingToAnExistingAnchorCostsTheLengthAndClearsTheSelectionOnceItIsFull(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		BlockPos middle = START.east(5);
		BlockPos full = START.east(10);
		helper.setBlock(middle, SkyLineAnchorGameTest.anchor(Heading.EAST));
		SkyHookGameTest.lay(helper, full, SkyHookGameTest.straight(3));
		TestPlayer player = layer(helper, GameType.SURVIVAL, 16, EAST);

		placeAndSelect(helper, player, START);
		layTo(helper, player, middle);
		assertSelected(helper, player, middle, Optional.of(AnchorSide.FRONT));
		layTo(helper, player, full.north());

		assertAnchor(helper, START, Heading.EAST, SkyHookGameTest.straight(5));
		assertAnchor(helper, middle, Heading.EAST, SkyHookGameTest.straight(5),
				SkyHookGameTest.straight(5).reversed());
		assertAnchor(helper, full, Heading.EAST, SkyHookGameTest.straight(3), SkyHookGameTest.straight(5).reversed());
		helper.assertValueEqual(player.getMainHandItem().getCount(), 5, "the Sky-Lines left");
		helper.assertTrue(TrackStart.of(player) == null, "the selection is kept");
		TestPlayer.remove(helper, player);
		helper.succeed();
	}

	/**
	 * Selecting an anchor at an end of its beam continues the line from its free side. With a straight 6 east from
	 * (2, 1, 4), the east anchor's front is free: facing south, a click 6 east and 6 south of it lays a quarter turn of
	 * radius 6 leaving it along its tangent, so the line runs on through it smoothly. The turn's new anchor heads south,
	 * holding it on its back. A quarter turn of radius 6 is about 9.42 long, so it costs 10, and its anchor 1.
	 */
	@GameTest(structure = AREA)
	public void anAnchorSelectedAtAnEndContinuesTheLineFromItsFreeSide(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		SkyHookGameTest.lay(helper, START, SkyHookGameTest.straight(6));
		BlockPos middle = START.east(6);
		TestPlayer player = layer(helper, GameType.SURVIVAL, 16, SOUTH);

		clickTop(helper, player, middle.north());
		layTo(helper, player, middle.offset(6, -1, 6));

		Segment turn = SkyLineSegmentGameTest.segment(new Vec3i(6, 0, 6), Heading.EAST, 0, Heading.SOUTH, 0);
		assertAnchor(helper, middle, Heading.EAST, turn, SkyHookGameTest.straight(6).reversed());
		assertAnchor(helper, middle.offset(6, 0, 6), Heading.SOUTH, turn.reversed());
		helper.assertValueEqual(player.getMainHandItem().getCount(), 5, "the Sky-Lines left");
		assertSelected(helper, player, middle.offset(6, 0, 6), Optional.of(AnchorSide.FRONT));
		TestPlayer.remove(helper, player);
		helper.succeed();
	}

	/**
	 * A new anchor heads the way the track arrives where the player aims, wherever they face. From an anchor heading
	 * east, a player facing north lays a straight 10 east to a new anchor heading east, on its back; facing north still,
	 * 6 on east and 6 south, a quarter turn to a new anchor heading south.
	 */
	@GameTest(structure = AREA)
	public void aNewAnchorHeadsTheWayTheTrackArrivesWhereverThePlayerFaces(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		helper.setBlock(START.south(), SkyLineAnchorGameTest.anchor(Heading.EAST));
		TestPlayer player = layer(helper, GameType.SURVIVAL, 32, 180);
		clickTop(helper, player, START.south());

		layTo(helper, player, START.offset(6, -1, 1));
		layTo(helper, player, START.offset(12, -1, 7));

		Segment quarter = SkyLineSegmentGameTest.segment(new Vec3i(6, 0, 6), Heading.EAST, 0, Heading.SOUTH, 0);
		assertAnchor(helper, START.offset(6, 0, 1), Heading.EAST, quarter, SkyHookGameTest.straight(6).reversed());
		assertAnchor(helper, START.offset(12, 0, 7), Heading.SOUTH, quarter.reversed());
		TestPlayer.remove(helper, player);
		helper.succeed();
	}

	/**
	 * A new anchor whose heading has no room turns 45° to one that has. From a lone anchor heading east at (2, 1, 4), a
	 * new anchor 8 east and 2 south heads south-east, along the circle that turns twice the chord's 14°; with a torch at
	 * (11, 1, 5), a cell the beam of one heading south-east would reach into, it heads east, its segment an easy S.
	 */
	@GameTest(structure = AREA)
	public void aNewAnchorWithNoRoomForItsBeamTurnsToAHeadingThatHas(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		BlockPos end = START.offset(8, 0, 2);
		helper.setBlock(new BlockPos(11, 1, 5), Blocks.TORCH);
		TestPlayer player = layer(helper, GameType.SURVIVAL, 16, EAST);
		placeAndSelect(helper, player, START);

		layTo(helper, player, end.below());

		helper.assertBlockProperty(end, AnchorBlock.HEADING, Heading.EAST);
		Segment laid = helper.getBlockEntity(end, AnchorBlockEntity.class).segment(AnchorSide.BACK);
		helper.assertTrue(laid != null && laid.offset().equals(new Vec3i(-8, 0, -2)),
				"the new anchor holds " + laid + " on its back");
		TestPlayer.remove(helper, player);
		helper.succeed();
	}

	/**
	 * The player may choose another heading for a new anchor among those that work. From a lone anchor heading east at
	 * (2, 1, 4), a new anchor 8 east and 2 south heads south-east by itself; the headings that work there start with it
	 * and, clockwise, end with east, which works too (an easy S); west, a U-turn, doesn't. Chosen, east is laid. A
	 * chosen heading that doesn't work is refused: west turns too sharply.
	 */
	@GameTest(structure = AREA)
	public void aNewAnchorHeadsTheWayThePlayerChoosesAmongThoseThatWork(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		BlockPos end = START.offset(8, 0, 2);
		TestPlayer player = layer(helper, GameType.SURVIVAL, 32, EAST);
		placeAndSelect(helper, player, START);
		TrackStart start = TrackStart.of(player);
		BlockPos at = helper.absolutePos(end.below());
		List<Heading> work = start == null
				? List.of()
				: Placement.headingsThatWork(player, InteractionHand.MAIN_HAND, start,
						new BlockHitResult(Vec3.atCenterOf(at).add(0, 0.5, 0), Direction.UP, at, false));
		helper.assertTrue(!work.isEmpty() && work.getFirst() == Heading.SOUTH_EAST
				&& work.getLast() == Heading.EAST && !work.contains(Heading.WEST), "the headings that work: " + work);

		walkTo(helper, player, end.below());
		send(helper, player, InteractionHand.MAIN_HAND, end.below(), Optional.of(Heading.WEST));
		helper.assertBlockNotPresent(SkyLine.ANCHOR, end);
		helper.assertValueEqual(player.overlay.getLast(), red(SkyLine.TURN_TOO_SHARP), "the action bar");
		send(helper, player, InteractionHand.MAIN_HAND, end.below(), Optional.of(Heading.EAST));

		helper.assertBlockProperty(end, AnchorBlock.HEADING, Heading.EAST);
		TestPlayer.remove(helper, player);
		helper.succeed();
	}

	/**
	 * A rail may pass through a block, as a block may be put on a rail later: with stone at (7, 1, 5), where the right
	 * rail (z = 5.5, its top at y = 2) of a straight 10 east from (2, 1, 4) runs, the straight is laid.
	 */
	@GameTest(structure = AREA)
	public void aSegmentIsLaidThroughABlock(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		helper.setBlock(START.offset(5, 0, 1), Blocks.STONE);
		TestPlayer player = layer(helper, GameType.SURVIVAL, 16, EAST);

		placeAndSelect(helper, player, START);
		layTo(helper, player, START.east(10).below());

		assertAnchor(helper, START, Heading.EAST, SkyHookGameTest.straight(10));
		assertAnchor(helper, START.east(10), Heading.EAST, SkyHookGameTest.straight(10).reversed());
		TestPlayer.remove(helper, player);
		helper.succeed();
	}

	/**
	 * A click that can't lay its segment lays nothing and takes nothing, keeps the selection, and says why on the
	 * action bar. From a lone anchor heading east at (2, 1, 4):
	 * <ul>
	 * <li>4 up and 3 east is too steep (both anchors are held at 45°, the chord is 53°);</li>
	 * <li>6 south, heading east, is a turn of 180°;</li>
	 * <li>10 east, with a torch in the end's cell, has no room for the anchor;</li>
	 * <li>10 east costs 11, more than a survival player's 3;</li>
	 * <li>an anchor holding a segment on both sides has no free side;</li>
	 * </ul>
	 * A click on the floor below the selected anchor, which would put a new anchor in its own cell, or on the selected
	 * anchor itself, does nothing, and says nothing.
	 */
	@GameTest(structure = AREA)
	public void aClickThatCantLayItsSegmentLaysNothingAndKeepsTheSelection(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		BlockPos end = START.east(10);
		// A pillar 3 east of the start, its top at y = 5: a click on it puts the end 4 up.
		for (int y = 0; y < 5; y++) {
			helper.setBlock(START.offset(3, y - 1, 0), Blocks.STONE);
		}
		TestPlayer player = layer(helper, GameType.SURVIVAL, 16, EAST);
		placeAndSelect(helper, player, START);

		assertRefused(helper, player, START.offset(3, 3, 0), red(SkyLine.TOO_STEEP, 45));
		assertRefused(helper, player, START.offset(0, -1, 6), red(SkyLine.TURN_TOO_SHARP));
		helper.setBlock(end, Blocks.TORCH);
		assertRefused(helper, player, end.below(), red(SkyLine.NO_ROOM_FOR_ANCHOR));
		helper.setBlock(end, Blocks.AIR);
		player.getMainHandItem().setCount(3);
		assertRefused(helper, player, end.below(), red(SkyLine.TOO_FEW_RAILS));
		// The middle anchor of a straight chain of two, at (5, 1, 12), has no free side.
		SkyHookGameTest.lay(helper, START.south(8), SkyHookGameTest.straight(3), SkyHookGameTest.straight(3));
		assertRefused(helper, player, START.offset(3, 0, 8), red(SkyLine.NO_FREE_SIDE));
		int told = player.overlay.size();
		layTo(helper, player, START.below());
		layTo(helper, player, START);
		helper.assertValueEqual(player.overlay.size(), told, "the messages after clicks on and below the selection");
		assertSelected(helper, player, START, Optional.empty());
		helper.assertValueEqual(player.getMainHandItem().getCount(), 3, "the Sky-Lines left");

		assertAnchor(helper, START, Heading.EAST);
		TestPlayer.remove(helper, player);
		helper.succeed();
	}

	/**
	 * Some clicks have no segment at all to show: a target anchor with no free side, whose reason the client shows
	 * without a ghost; and, with nothing to say, a new anchor in the selected one's own cell, and a selection whose
	 * anchor has gone or whose side has been taken this tick, before it follows its anchor. A segment that breaks a
	 * limit is still one, to show in red. From
	 * a lone anchor heading east at (2, 1, 4), with a straight chain of two from (2, 1, 12).
	 */
	@GameTest(structure = AREA)
	public void aClickThatCantMakeASegmentAtAllHasNoneToShow(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		helper.setBlock(START, SkyLineAnchorGameTest.anchor(Heading.EAST));
		BlockPos chain = START.south(8);
		SkyHookGameTest.lay(helper, chain, SkyHookGameTest.straight(3), SkyHookGameTest.straight(3));
		TestPlayer player = layer(helper, GameType.SURVIVAL, 16, EAST);
		TrackStart start = new TrackStart(helper.absolutePos(START), Optional.empty());

		assertPlan(helper, player, new TrackStart(helper.absolutePos(START.north(3)), Optional.empty()),
				START.east(6).below(), new Placement.Plan.Nothing());
		assertPlan(helper, player, start, chain.east(3),
				new Placement.Plan.Refused(red(SkyLine.NO_FREE_SIDE)));
		assertPlan(helper, player, start, START.below(), new Placement.Plan.Nothing());
		// The chain's east anchor, both sides free when selected: a click south-west of it would leave by its back,
		// which holds the chain's second straight.
		TrackStart taken = new TrackStart(helper.absolutePos(chain.east(6)),
				Optional.empty());
		assertPlan(helper, player, taken, chain.offset(3, -1, 2), new Placement.Plan.Nothing());
		BlockPos at = helper.absolutePos(START.offset(1, -1, 3));
		Placement.Plan tooTight = Placement.plan(player, InteractionHand.MAIN_HAND, start,
				new BlockHitResult(Vec3.atCenterOf(at).add(0, 0.5, 0), Direction.UP, at, false));
		helper.assertTrue(tooTight instanceof Placement.Plan.Laying laying && laying.why() != null,
				"a segment 1 east and 3 south plans " + tooTight + ", not a red segment");
		TestPlayer.remove(helper, player);
		helper.succeed();
	}

	/**
	 * A click on a block face plans a new anchor in the cell a block would be placed in: out of the face clicked, above
	 * a top, north of a north face, below a bottom; or, clicking a block that can be replaced (a layer of snow), in its
	 * own cell. From a lone anchor heading east at (2, 1, 4), with stone at (8, 0, 4), (8, 2, 6) and (8, 4, 4).
	 */
	@GameTest(structure = AREA)
	public void aClickPlansANewAnchorInTheCellABlockWouldGoIn(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		helper.setBlock(START, SkyLineAnchorGameTest.anchor(Heading.EAST));
		helper.setBlock(new BlockPos(8, 2, 6), Blocks.STONE);
		helper.setBlock(new BlockPos(8, 4, 4), Blocks.STONE);
		helper.setBlock(new BlockPos(11, 1, 4), Blocks.SNOW);
		TestPlayer player = layer(helper, GameType.SURVIVAL, 16, EAST);
		TrackStart start = new TrackStart(helper.absolutePos(START), Optional.empty());

		assertPlansAnAnchorIn(helper, player, start, new BlockPos(8, 0, 4), Direction.UP, new BlockPos(8, 1, 4));
		assertPlansAnAnchorIn(helper, player, start, new BlockPos(8, 2, 6), Direction.NORTH, new BlockPos(8, 2, 5));
		assertPlansAnAnchorIn(helper, player, start, new BlockPos(8, 4, 4), Direction.DOWN, new BlockPos(8, 3, 4));
		assertPlansAnAnchorIn(helper, player, start, new BlockPos(11, 1, 4), Direction.UP, new BlockPos(11, 1, 4));
		TestPlayer.remove(helper, player);
		helper.succeed();
	}

	/**
	 * A selection whose anchor has gone, or whose side has been taken, within the tick, before the selection follows
	 * its anchor: a click lays nothing, takes nothing, and says nothing.
	 */
	@GameTest(structure = AREA)
	public void aClickFromASelectionGoneThisTickLaysNothing(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		SkyHookGameTest.lay(helper, START, SkyHookGameTest.straight(6));
		BlockPos east = START.east(6);
		BlockPos end = east.offset(6, 0, 0);
		TestPlayer player = layer(helper, GameType.SURVIVAL, 16, EAST);
		// The east anchor's front side, free when it was selected, is taken by a straight 3 east before the click.
		clickTop(helper, player, east);
		helper.setBlock(east.east(3), SkyLineAnchorGameTest.anchor(Heading.EAST));
		helper.getBlockEntity(east, AnchorBlockEntity.class).connect(SkyHookGameTest.straight(3));
		assertNothingLaid(helper, player, end.below());
		helper.getBlockEntity(east, AnchorBlockEntity.class).disconnect(AnchorSide.FRONT);
		BlockPos at = helper.absolutePos(east);
		SkyLineAnchorGameTest.command(helper, "setblock %d %d %d air".formatted(at.getX(), at.getY(), at.getZ()));

		assertNothingLaid(helper, player, end.below());

		// An anchor alone, with both sides free when it was selected, which keeps neither. A straight 3 east then takes
		// its front, the side a click east of it would leave by: refused, and the straight keeps its place.
		BlockPos alone = START.south(6);
		helper.setBlock(alone, SkyLineAnchorGameTest.anchor(Heading.EAST));
		clear(helper, player);
		clickTop(helper, player, alone);
		helper.setBlock(alone.east(3), SkyLineAnchorGameTest.anchor(Heading.EAST));
		helper.getBlockEntity(alone, AnchorBlockEntity.class).connect(SkyHookGameTest.straight(3));
		assertNothingLaid(helper, player, alone.offset(10, -1, 0));
		assertAnchor(helper, alone, Heading.EAST, SkyHookGameTest.straight(3));
		assertAnchor(helper, alone.east(3), Heading.EAST, SkyHookGameTest.straight(3).reversed());

		TestPlayer.remove(helper, player);
		helper.succeed();
	}

	/**
	 * A sneak-use clears the selection, whether on the air or on a block, saying nothing: the preview goes. A plain use
	 * on the air keeps it.
	 */
	@GameTest(structure = AREA)
	public void aSneakUseClearsTheSelection(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		TestPlayer player = layer(helper, GameType.SURVIVAL, 16, EAST);
		placeAndSelect(helper, player, START);

		clickAir(helper, player);
		helper.assertTrue((TrackStart.of(player) != null), "a plain use on the air cleared it");
		player.setShiftKeyDown(true);
		clickAir(helper, player);
		helper.assertFalse((TrackStart.of(player) != null), "sneaking on the air didn't clear it");
		player.setShiftKeyDown(false);
		clickTop(helper, player, START);
		player.setShiftKeyDown(true);
		clickTop(helper, player, START.east(10).below());
		helper.assertFalse((TrackStart.of(player) != null), "sneaking on a block didn't clear it");

		helper.assertBlockPresent(Blocks.AIR, START.east(10));
		helper.assertValueEqual(player.overlay, List.of(), "the action bar");
		TestPlayer.remove(helper, player);
		helper.succeed();
	}

	/**
	 * The selection is the player's, not a stack's: a rail is a rail. Selected with the 16 Sky-Lines in the main hand,
	 * a straight 10 to a new anchor is laid with the 16 in the off hand once the main hand is empty, and paid from them:
	 * 11, leaving 5.
	 */
	@GameTest(structure = AREA)
	public void theSelectionIsThePlayersSoAnySkyLineLaysFromIt(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		TestPlayer player = layer(helper, GameType.SURVIVAL, 16, EAST);
		placeAndSelect(helper, player, START);
		player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(SkyLine.RAIL, 16));

		walkTo(helper, player, START.east(10).below());
		send(helper, player, InteractionHand.OFF_HAND, START.east(10).below());

		assertAnchor(helper, START, Heading.EAST, SkyHookGameTest.straight(10));
		helper.assertValueEqual(player.getOffhandItem().getCount(), 5, "the Sky-Lines left in the off hand");
		TestPlayer.remove(helper, player);
		helper.succeed();
	}

	/**
	 * The selection follows its anchor as each tick ends. A lone anchor, both sides free, is selected with neither; once
	 * a straight east takes its front, the selection keeps to its back; once another takes its back too, it is cleared.
	 * A selected anchor that is removed clears it too.
	 */
	@GameTest(structure = AREA)
	public void theSelectionFollowsItsAnchorAndGoesWithIt(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		BlockPos middle = START.east(5);
		helper.setBlock(middle, SkyLineAnchorGameTest.anchor(Heading.EAST));
		helper.setBlock(middle.east(5), SkyLineAnchorGameTest.anchor(Heading.EAST));
		helper.setBlock(START, SkyLineAnchorGameTest.anchor(Heading.EAST));
		TestPlayer player = layer(helper, GameType.SURVIVAL, 16, EAST);
		clickTop(helper, player, middle);

		helper.startSequence()
				.thenExecute(() -> helper.getBlockEntity(middle, AnchorBlockEntity.class)
						.connect(SkyHookGameTest.straight(5)))
				.thenIdle(1)
				.thenExecute(() -> {
					assertSelected(helper, player, middle, Optional.of(AnchorSide.BACK));
					helper.getBlockEntity(START, AnchorBlockEntity.class).connect(SkyHookGameTest.straight(5));
				})
				.thenIdle(1)
				.thenExecute(() -> {
					helper.assertTrue(TrackStart.of(player) == null, "the full anchor is still selected");
					clickTop(helper, player, middle.east(5));
					assertSelected(helper, player, middle.east(5), Optional.of(AnchorSide.FRONT));
					BlockPos at = helper.absolutePos(middle.east(5));
					SkyLineAnchorGameTest.command(helper,
							"setblock %d %d %d air".formatted(at.getX(), at.getY(), at.getZ()));
				})
				.thenIdle(1)
				.thenExecute(() -> {
					helper.assertTrue(TrackStart.of(player) == null, "the removed anchor is still selected");
					TestPlayer.remove(helper, player);
				})
				.thenSucceed();
	}

	/**
	 * A selection left behind clears: kept while the player is 60 blocks from the anchor's middle, cleared once they are
	 * 65 away, more than 64.
	 */
	@GameTest(structure = AREA)
	public void aSelectionLeftMoreThan64BlocksBehindClears(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		TestPlayer player = layer(helper, GameType.SURVIVAL, 16, EAST);
		placeAndSelect(helper, player, START);
		Vec3 middle = helper.absoluteVec(Vec3.atCenterOf(START));

		player.snapTo(middle.x + 60, middle.y, middle.z, EAST, 0);
		helper.startSequence()
				.thenIdle(1)
				.thenExecute(() -> {
					assertSelected(helper, player, START, Optional.empty());
					player.snapTo(middle.x + 65, middle.y, middle.z, EAST, 0);
				})
				.thenIdle(1)
				.thenExecute(() -> {
					helper.assertTrue(TrackStart.of(player) == null, "the selection left 65 blocks behind is kept");
					TestPlayer.remove(helper, player);
				})
				.thenSucceed();
	}

	/** Changing dimension clears the selection: an anchor in another dimension is out of reach. */
	@GameTest(structure = AREA)
	public void changingDimensionClearsTheSelection(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		TestPlayer player = layer(helper, GameType.SURVIVAL, 16, EAST);
		placeAndSelect(helper, player, START);
		ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
		if (nether == null) {
			throw helper.assertionException(Component.literal("no nether to go to"));
		}

		player.teleport(new TeleportTransition(nether, new Vec3(0.5, 64, 0.5), Vec3.ZERO, 0, 0,
				TeleportTransition.DO_NOTHING));

		helper.assertTrue(TrackStart.of(player) == null, "the selection went to the nether");
		TestPlayer.remove(helper, player);
		helper.succeed();
	}

	/** Dying clears the selection: the player who respawns has none, as nothing carries it over to them. */
	@GameTest(structure = AREA)
	public void dyingClearsTheSelection(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		TestPlayer player = layer(helper, GameType.SURVIVAL, 16, EAST);
		placeAndSelect(helper, player, START);
		helper.assertTrue(TrackStart.of(player) != null, "nothing was selected");

		player.kill(helper.getLevel());
		ServerPlayer respawned = helper.getLevel().getServer().getPlayerList().respawn(player, false,
				Entity.RemovalReason.KILLED);

		helper.assertTrue(TrackStart.of(respawned) == null, "the selection outlived the player's death");
		helper.getLevel().getServer().getPlayerList().remove(respawned);
		helper.succeed();
	}

	/**
	 * In creative, laying track is free: the Sky-Lines held and elsewhere in the inventory are all still there after
	 * placing an anchor and laying a straight from it. The selection moves on to the straight's far end.
	 */
	@GameTest(structure = AREA)
	public void layingTrackInCreativeIsFree(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		TestPlayer player = layer(helper, GameType.CREATIVE, 1, EAST);
		player.getInventory().setItem(9, new ItemStack(SkyLine.RAIL, 16));

		placeAndSelect(helper, player, START);
		layTo(helper, player, START.east(10).below());

		assertAnchor(helper, START, Heading.EAST, SkyHookGameTest.straight(10));
		helper.assertValueEqual(player.getMainHandItem().getCount(), 1, "the Sky-Lines held");
		helper.assertValueEqual(player.getInventory().getItem(9).getCount(), 16, "the Sky-Lines in the inventory");
		assertSelected(helper, player, START.east(10), Optional.of(AnchorSide.FRONT));
		TestPlayer.remove(helper, player);
		helper.succeed();
	}

	/**
	 * The server checks the click the client sends: one on a block beyond the player's reach, 4.5 and 1 for lag (the
	 * floor 10 east of the start is about 10.7 from the eyes), lays nothing and says nothing, as vanilla does for a use
	 * on a block; so does one from a spectator, or with no anchor selected. A player in adventure mode may not build.
	 * Each keeps the selection and the Sky-Lines.
	 */
	@GameTest(structure = AREA)
	public void theServerRefusesAClickBeyondReachWithNothingSelectedOrByAPlayerWhoMayNotBuild(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		helper.setBlock(START, SkyLineAnchorGameTest.anchor(Heading.EAST));
		BlockPos end = START.east(10).below();
		TestPlayer far = layer(helper, GameType.SURVIVAL, 16, EAST);
		clickTop(helper, far, START);
		TestPlayer unselected = layer(helper, GameType.SURVIVAL, 16, EAST);
		TestPlayer spectator = layer(helper, GameType.SURVIVAL, 16, EAST);
		clickTop(helper, spectator, START);
		spectator.setGameMode(GameType.SPECTATOR);
		TestPlayer adventure = layer(helper, GameType.SURVIVAL, 16, EAST);
		clickTop(helper, adventure, START);
		adventure.setGameMode(GameType.ADVENTURE);

		send(helper, far, end);
		layTo(helper, unselected, end);
		layTo(helper, spectator, end);
		layTo(helper, adventure, end);

		helper.assertBlockNotPresent(SkyLine.ANCHOR, end.above());
		for (TestPlayer player : List.of(far, spectator, adventure)) {
			assertSelected(helper, player, START, Optional.empty());
		}
		for (TestPlayer player : List.of(far, unselected, spectator, adventure)) {
			helper.assertValueEqual(player.getMainHandItem().getCount(), 16, "the Sky-Lines left");
		}
		helper.assertValueEqual(far.overlay, List.of(), "the action bar of the player out of reach");
		helper.assertValueEqual(spectator.overlay, List.of(), "the spectator's action bar");
		helper.assertValueEqual(adventure.overlay, List.of(red(SkyLine.MAY_NOT_BUILD)),
				"the adventurer's action bar");
		TestPlayer.remove(helper, far, unselected, spectator, adventure);
		helper.succeed();
	}

	/**
	 * With an anchor selected, a plain click on a block through vanilla's use on a block lays nothing: laying only
	 * comes through the block the client sends, with the heading it showed.
	 */
	@GameTest(structure = AREA)
	public void aPlainUseOnABlockWithAnAnchorSelectedLaysNothing(GameTestHelper helper) {
		SkyHookGameTest.floor(helper);
		TestPlayer player = layer(helper, GameType.SURVIVAL, 16, EAST);
		placeAndSelect(helper, player, START);

		clickTop(helper, player, START.east(4).below());

		helper.assertBlockPresent(Blocks.AIR, START.east(4));
		assertAnchor(helper, START, Heading.EAST);
		assertSelected(helper, player, START, Optional.empty());
		helper.assertValueEqual(player.getMainHandItem().getCount(), 15, "the Sky-Lines left");
		TestPlayer.remove(helper, player);
		helper.succeed();
	}

	/** Asserts a click on the middle of a face of a block, from a selection, plans a segment to a new anchor in a cell. */
	private static void assertPlansAnAnchorIn(GameTestHelper helper, TestPlayer player, TrackStart start,
			BlockPos block,
			Direction face, BlockPos cell) {
		BlockPos at = helper.absolutePos(block);
		Placement.Plan plan = Placement.plan(player, InteractionHand.MAIN_HAND, start,
				new BlockHitResult(Vec3.atCenterOf(at).relative(face, 0.5), face, at, false));
		helper.assertTrue(plan instanceof Placement.Plan.Laying laying && laying.toNewAnchor()
				&& laying.end().equals(helper.absolutePos(cell)),
				"a click on the " + face + " face of " + block + " plans " + plan + ", not a new anchor at " + cell);
	}

	/** Asserts what a click on the top of a block plans, from a selection. */
	private static void assertPlan(GameTestHelper helper, TestPlayer player, TrackStart start, BlockPos block,
			Placement.Plan expected) {
		BlockPos at = helper.absolutePos(block);
		Placement.Plan plan = Placement.plan(player, InteractionHand.MAIN_HAND, start,
				new BlockHitResult(Vec3.atCenterOf(at).add(0, 0.5, 0), Direction.UP, at, false));
		helper.assertValueEqual(plan, expected, "what a click on " + block + " plans");
	}

	/** Asserts a click laying track to the top of a block lays nothing, takes nothing and says nothing. */
	private static void assertNothingLaid(GameTestHelper helper, TestPlayer player, BlockPos block) {
		int held = player.getMainHandItem().getCount();
		int told = player.overlay.size();
		layTo(helper, player, block);
		helper.assertBlockNotPresent(SkyLine.ANCHOR, block.above());
		helper.assertValueEqual(player.getMainHandItem().getCount(), held, "the Sky-Lines held");
		helper.assertValueEqual(player.overlay.size(), told, "the messages");
	}

	/** A reason on the action bar, in Create's red for what can't be placed. */
	private static Component red(String key, Object... args) {
		return Component.translatable(key, args).withColor(0xEA5C2B);
	}

	/** A player at the area's corner holding Sky-Lines, facing along a yaw, level. */
	private static TestPlayer layer(GameTestHelper helper, GameType gameType, int count, float yaw) {
		TestPlayer player = TestPlayer.place(helper, gameType, new Vec3(1.5, 1, 1.5), yaw, 0);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(SkyLine.RAIL, count));
		return player;
	}

	/** Places a lone anchor in a cell on the floor, clicking the floor's top below it, which selects it. */
	private static void placeAndSelect(GameTestHelper helper, TestPlayer player, BlockPos anchor) {
		clickTop(helper, player, anchor.below());
		helper.assertTrue((TrackStart.of(player) != null), "the anchor at " + anchor
				+ " isn't selected");
	}

	/** Uses the held Sky-Line on the top of a block, as the server does when a client clicks it. */
	private static void clickTop(GameTestHelper helper, TestPlayer player, BlockPos block) {
		BlockPos at = helper.absolutePos(block);
		player.gameMode.useItemOn(player, helper.getLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND,
				new BlockHitResult(Vec3.atCenterOf(at).add(0, 0.5, 0), Direction.UP, at, false));
	}

	/**
	 * Walks the player within reach of a block, onto the floor 2 blocks north of it, still facing the way they did, and
	 * clicks its top with the selection held, as their client sends it: the click that lays track.
	 */
	private static void layTo(GameTestHelper helper, TestPlayer player, BlockPos block) {
		walkTo(helper, player, block);
		send(helper, player, block);
	}

	/** Walks the player onto the floor 2 blocks north of a block, still facing the way they did. */
	private static void walkTo(GameTestHelper helper, TestPlayer player, BlockPos block) {
		Vec3 feet = helper.absoluteVec(new Vec3(block.getX() + 0.5, 1, block.getZ() - 1.5));
		player.snapTo(feet.x, feet.y, feet.z, player.getYRot(), player.getXRot());
	}

	/** Sends a click on the top of a block with the Sky-Line in the main hand, as the client does to lay track. */
	private static void send(GameTestHelper helper, TestPlayer player, BlockPos block) {
		send(helper, player, InteractionHand.MAIN_HAND, block);
	}

	/** Sends a click on the top of a block with the Sky-Line in a hand, as the client does to lay track. */
	private static void send(GameTestHelper helper, TestPlayer player, InteractionHand hand, BlockPos block) {
		send(helper, player, hand, block, Optional.empty());
	}

	/**
	 * Sends a click on the top of a block with the Sky-Line in a hand, as the client does to lay track, with the heading
	 * the player chose for a new anchor there, if any.
	 */
	private static void send(GameTestHelper helper, TestPlayer player, InteractionHand hand, BlockPos block,
			Optional<Heading> chosen) {
		BlockPos at = helper.absolutePos(block);
		Placement.lay(player, hand,
				new BlockHitResult(Vec3.atCenterOf(at).add(0, 0.5, 0), Direction.UP, at, false), chosen);
	}

	/** Clears the player's selection with a sneak-use on the air. */
	private static void clear(GameTestHelper helper, TestPlayer player) {
		player.setShiftKeyDown(true);
		clickAir(helper, player);
		player.setShiftKeyDown(false);
	}

	/** Uses the held Sky-Line on nothing, as the server does when a client clicks the air. */
	private static void clickAir(GameTestHelper helper, TestPlayer player) {
		player.gameMode.useItem(player, helper.getLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
	}

	/**
	 * Asserts a click laying track to the top of a block lays nothing there, keeps the selection and the Sky-Lines
	 * held, and tells the player why.
	 */
	private static void assertRefused(GameTestHelper helper, TestPlayer player, BlockPos block, Component why) {
		int held = player.getMainHandItem().getCount();
		Optional<TrackStart> selected = Optional.ofNullable(TrackStart.of(player));
		layTo(helper, player, block);
		helper.assertBlockNotPresent(SkyLine.ANCHOR, block.above());
		helper.assertValueEqual(Optional.ofNullable(TrackStart.of(player)), selected,
				"the selection after " + why);
		helper.assertValueEqual(player.getMainHandItem().getCount(), held, "the Sky-Lines held after " + why);
		helper.assertValueEqual(player.overlay.getLast(), why, "the action bar");
	}

	/** Asserts the held stack selects an anchor, with a side or none. */
	private static void assertSelected(GameTestHelper helper, TestPlayer player, BlockPos anchor,
			Optional<AnchorSide> side) {
		helper.assertValueEqual(Optional.ofNullable(TrackStart.of(player)),
				Optional.of(new TrackStart(helper.absolutePos(anchor), side)), "the selection");
	}

	/** Asserts an anchor faces a heading and holds segments, as its block entity lists them: front, then back. */
	private static void assertAnchor(GameTestHelper helper, BlockPos pos, Heading heading, Segment... segments) {
		helper.assertBlockProperty(pos, AnchorBlock.HEADING, heading);
		helper.assertValueEqual(helper.getBlockEntity(pos, AnchorBlockEntity.class).segments(), List.of(segments),
				"the segments of the anchor at " + pos);
	}
}
