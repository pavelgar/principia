package io.github.pavelgar.principia.test;

import java.util.Optional;

import io.github.pavelgar.principia.skyline.AnchorBlock;
import io.github.pavelgar.principia.skyline.BeamEndBlock;
import io.github.pavelgar.principia.skyline.Heading;
import io.github.pavelgar.principia.skyline.SkyLine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/** The Sky-Line anchor as a block: its cross-beam, how it mines, and water. */
public class SkyLineAnchorGameTest {
	private static final BlockPos ANCHOR = new BlockPos(3, 3, 3);

	/** The cross-beam has no collision, as the rails have none: a stand dropped onto its middle falls to the floor. */
	@GameTest(maxTicks = 60)
	public void armorStandFallsThroughACrossBeam(GameTestHelper helper) {
		SkyLineSegmentGameTest.floor(helper);
		helper.setBlock(ANCHOR, anchor(Heading.SOUTH));
		ArmorStand stand = helper.spawn(EntityTypes.ARMOR_STAND, new Vec3(ANCHOR.getX() + 0.5, 6, ANCHOR.getZ() + 0.5));

		helper.succeedWhen(() -> assertRestsOn(helper, stand, 1));
	}

	/**
	 * The beam runs across the heading from rail to rail, so with the anchor heading south it runs along x. A player
	 * aims at its middle inside the cell, 4 px wide around z = 0.5 and flush with the cell's top: looking straight down
	 * onto it they aim at the anchor's top, and 0.3 to its side they miss it.
	 */
	@GameTest
	public void aPlayerAimsAtAStraightCrossBeam(GameTestHelper helper) {
		helper.setBlock(ANCHOR, anchor(Heading.SOUTH));

		assertAimsAt(helper, 0.2, 0.5, true);
		assertAimsAt(helper, 0.5, 0.2, false);
		helper.succeed();
	}

	/**
	 * With the anchor heading south-east the beam runs north-east to south-west through the cell's centre: 0.3 north
	 * and 0.3 east of it is on the beam, and as far south-east, along the heading, is beside it.
	 */
	@GameTest
	public void aPlayerAimsAtADiagonalCrossBeam(GameTestHelper helper) {
		helper.setBlock(ANCHOR, anchor(Heading.SOUTH_EAST));

		assertAimsAt(helper, 0.8, 0.2, true);
		assertAimsAt(helper, 0.8, 0.8, false);
		helper.succeed();
	}

	/**
	 * The beam is iron as a rail is: it takes as long to mine as a rail, by hand and with a pickaxe, at its middle and
	 * at its ends.
	 */
	@GameTest
	public void anchorMinesAsFastAsARail(GameTestHelper helper) {
		BlockPos rail = ANCHOR.east(2);
		helper.setBlock(ANCHOR, anchor(Heading.SOUTH));
		helper.setBlock(rail, Blocks.RAIL);
		Player player = helper.makeMockPlayer(GameType.SURVIVAL);

		for (ItemStack tool : new ItemStack[]{ItemStack.EMPTY, new ItemStack(Items.IRON_PICKAXE)}) {
			player.setItemInHand(InteractionHand.MAIN_HAND, tool);
			float railProgress = progress(helper, player, rail);
			for (BlockPos part : new BlockPos[]{ANCHOR, ANCHOR.east()}) {
				float partProgress = progress(helper, player, part);
				helper.assertTrue(partProgress == railProgress, "with " + tool + " the anchor at " + part
						+ " mines at " + partProgress + " per tick and a rail at " + railProgress);
			}
		}
		helper.succeed();
	}

	/**
	 * The beam reaches into the cells either side of its anchor, square to the heading, each holding an end of it that
	 * points back at the anchor. Heading south the beam runs along x; heading south-east, from north-east to
	 * south-west, into the cells at the anchor's corners.
	 */
	@GameTest
	public void anAnchorAddsItsEndsInTheCellsItsBeamReachesInto(GameTestHelper helper) {
		BlockPos diagonal = ANCHOR.south(3);
		helper.setBlock(ANCHOR, anchor(Heading.SOUTH));
		helper.setBlock(diagonal, anchor(Heading.SOUTH_EAST));

		assertEnd(helper, ANCHOR.west(), Heading.EAST);
		assertEnd(helper, ANCHOR.east(), Heading.WEST);
		assertEnd(helper, diagonal.north().east(), Heading.SOUTH_WEST);
		assertEnd(helper, diagonal.south().west(), Heading.NORTH_EAST);
		helper.succeed();
	}

	/** An end cell holding a block keeps it: that anchor goes without that end. */
	@GameTest
	public void anAnchorLeavesABlockInAnEndCell(GameTestHelper helper) {
		helper.setBlock(ANCHOR.west(), Blocks.STONE);
		helper.setBlock(ANCHOR, anchor(Heading.SOUTH));

		helper.assertBlockPresent(Blocks.STONE, ANCHOR.west());
		assertEnd(helper, ANCHOR.east(), Heading.WEST);
		helper.assertBlockPresent(SkyLine.ANCHOR, ANCHOR);
		helper.succeed();
	}

	/**
	 * Removing an anchor removes its ends. An end in water holds it, as the anchor does, and leaves it behind: the
	 * anchor is placed into three cells of still water, west to east, on stone.
	 */
	@GameTest
	public void removingAnAnchorRemovesItsEndsLeavingTheirWater(GameTestHelper helper) {
		BlockPos dry = ANCHOR.south(3);
		for (int x = 1; x <= 5; x++) {
			helper.setBlock(x, ANCHOR.getY() - 1, ANCHOR.getZ(), Blocks.STONE);
		}
		for (BlockPos cell : new BlockPos[]{ANCHOR.west(), ANCHOR, ANCHOR.east()}) {
			helper.setBlock(cell, Blocks.WATER);
		}
		helper.setBlock(ANCHOR, anchor(Heading.SOUTH).setValue(AnchorBlock.WATERLOGGED, true));
		helper.setBlock(dry, anchor(Heading.SOUTH));
		helper.assertTrue(helper.getBlockState(ANCHOR.east()).getValue(BeamEndBlock.WATERLOGGED),
				"the end in water doesn't hold it");

		helper.setBlock(ANCHOR, Blocks.AIR);
		helper.setBlock(dry, Blocks.AIR);

		for (BlockPos end : new BlockPos[]{ANCHOR.west(), ANCHOR.east()}) {
			FluidState left = helper.getLevel().getFluidState(helper.absolutePos(end));
			helper.assertTrue(helper.getBlockState(end).is(Blocks.WATER) && left.isSource(),
					"the end at " + end + " left " + helper.getBlockState(end) + ", not its water");
		}
		helper.assertBlockPresent(Blocks.AIR, dry.west());
		helper.assertBlockPresent(Blocks.AIR, dry.east());
		helper.succeed();
	}

	/**
	 * An end is part of its anchor: replacing it, with a command or otherwise, removes the whole anchor, the other end
	 * with it. Straight and diagonal.
	 */
	@GameTest
	public void replacingAnEndRemovesTheWholeAnchor(GameTestHelper helper) {
		BlockPos diagonal = ANCHOR.south(3);
		helper.setBlock(ANCHOR, anchor(Heading.SOUTH));
		helper.setBlock(diagonal, anchor(Heading.SOUTH_EAST));

		BlockPos east = helper.absolutePos(ANCHOR.east());
		command(helper, "setblock %d %d %d stone".formatted(east.getX(), east.getY(), east.getZ()));
		helper.setBlock(diagonal.north().east(), Blocks.AIR);

		helper.assertBlockPresent(Blocks.AIR, ANCHOR);
		helper.assertBlockPresent(Blocks.AIR, ANCHOR.west());
		helper.assertBlockPresent(Blocks.STONE, ANCHOR.east());
		helper.assertBlockPresent(Blocks.AIR, diagonal);
		helper.assertBlockPresent(Blocks.AIR, diagonal.south().west());
		helper.succeed();
	}

	/** Mining an end, as a player does, mines the whole anchor: it and both its ends are gone. */
	@GameTest
	public void miningAnEndMinesTheWholeAnchor(GameTestHelper helper) {
		helper.setBlock(ANCHOR, anchor(Heading.SOUTH));
		TestPlayer player = TestPlayer.place(helper, GameType.SURVIVAL, new Vec3(5.5, 1, 3.5), 90, 0);

		player.gameMode.destroyBlock(helper.absolutePos(ANCHOR.east()));

		helper.assertBlockPresent(Blocks.AIR, ANCHOR.west());
		helper.assertBlockPresent(Blocks.AIR, ANCHOR);
		helper.assertBlockPresent(Blocks.AIR, ANCHOR.east());
		TestPlayer.remove(helper, player);
		helper.succeed();
	}

	/** A piston can't push an end out of its beam: pushing the east end south, it stays and the piston stays in. */
	@GameTest
	public void aPistonDoesntMoveAnEnd(GameTestHelper helper) {
		BlockPos piston = ANCHOR.east().north();
		helper.setBlock(ANCHOR, anchor(Heading.SOUTH));
		helper.setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.SOUTH));
		helper.setBlock(piston.north(), Blocks.REDSTONE_BLOCK);

		helper.runAfterDelay(4, () -> {
			assertEnd(helper, ANCHOR.east(), Heading.WEST);
			helper.assertBlockProperty(piston, PistonBaseBlock.EXTENDED, false);
			helper.succeed();
		});
	}

	/**
	 * Turning an anchor in place, with a command or a debug stick, moves its ends to the cells its beam now reaches
	 * into. Turned round, the beam lies where it did, and so do its ends.
	 */
	@GameTest
	public void turningAnAnchorMovesItsEnds(GameTestHelper helper) {
		helper.setBlock(ANCHOR, anchor(Heading.SOUTH));
		BlockPos at = helper.absolutePos(ANCHOR);

		command(helper, "setblock %d %d %d principia:sky_line_anchor[heading=east]".formatted(at.getX(), at.getY(),
				at.getZ()));
		helper.assertBlockPresent(Blocks.AIR, ANCHOR.west());
		helper.assertBlockPresent(Blocks.AIR, ANCHOR.east());
		assertEnd(helper, ANCHOR.north(), Heading.SOUTH);
		assertEnd(helper, ANCHOR.south(), Heading.NORTH);

		command(helper, "setblock %d %d %d principia:sky_line_anchor[heading=west]".formatted(at.getX(), at.getY(),
				at.getZ()));
		assertEnd(helper, ANCHOR.north(), Heading.SOUTH);
		assertEnd(helper, ANCHOR.south(), Heading.NORTH);

		// A debug stick turns it with flags that skip the neighbours' shapes (UPDATE_KNOWN_SHAPE), to south-east.
		helper.getLevel().setBlock(at, anchor(Heading.SOUTH_EAST), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
		helper.assertBlockPresent(Blocks.AIR, ANCHOR.north());
		helper.assertBlockPresent(Blocks.AIR, ANCHOR.south());
		assertEnd(helper, ANCHOR.north().east(), Heading.SOUTH_WEST);
		assertEnd(helper, ANCHOR.south().west(), Heading.NORTH_EAST);
		helper.succeed();
	}

	/**
	 * A player aims at the beam where it reaches past the anchor's cell: looking straight down onto it, 0.2 into the
	 * east cell, they aim at the end there; 0.3 to the beam's side, they miss it.
	 */
	@GameTest
	public void aPlayerAimsAtABeamsEnd(GameTestHelper helper) {
		helper.setBlock(ANCHOR, anchor(Heading.SOUTH));

		helper.assertValueEqual(aimedAt(helper, ANCHOR.east(), 0.2, 0.5),
				Optional.of(helper.absolutePos(ANCHOR.east())), "the block aimed at on the beam's end");
		helper.assertValueEqual(aimedAt(helper, ANCHOR.east(), 0.2, 0.2), Optional.empty(),
				"the block aimed at beside it");
		helper.succeed();
	}

	/**
	 * An anchor placed where there is water keeps it: the water stays a source and flows out of the anchor, to the north
	 * where no end of its beam is.
	 */
	@GameTest(maxTicks = 40)
	public void anchorPlacedInWaterKeepsItsWater(GameTestHelper helper) {
		for (int x = 1; x <= 5; x++) {
			for (int z = 1; z <= 5; z++) {
				helper.setBlock(x, ANCHOR.getY() - 1, z, Blocks.STONE);
			}
		}
		helper.setBlock(ANCHOR, Blocks.WATER);
		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		BlockPos absolute = helper.absolutePos(ANCHOR);
		BlockState placed = SkyLine.ANCHOR.getStateForPlacement(new BlockPlaceContext(helper.getLevel(), player,
				InteractionHand.MAIN_HAND, ItemStack.EMPTY,
				new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, false)));
		if (placed == null) {
			helper.fail("the anchor can't be placed in water");
			return;
		}
		helper.setBlock(ANCHOR, placed);

		helper.succeedWhen(() -> {
			FluidState inAnchor = helper.getLevel().getFluidState(absolute);
			helper.assertTrue(inAnchor.is(Fluids.WATER) && inAnchor.isSource(),
					"the anchor holds " + inAnchor + ", not a water source");
			helper.assertTrue(helper.getLevel().getFluidState(absolute.north()).is(Fluids.FLOWING_WATER),
					"no water flows out of the anchor");
		});
	}

	static BlockState anchor(Heading heading) {
		return SkyLine.ANCHOR.defaultBlockState().setValue(AnchorBlock.HEADING, heading);
	}

	/** Asserts that an entity stands on something whose top is at a height relative to the test. */
	static void assertRestsOn(GameTestHelper helper, Entity entity, double top) {
		double y = helper.relativeVec(entity.position()).y;
		helper.assertTrue(entity.onGround() && Math.abs(y - top) < 1e-6,
				entity.getType().getDescription().getString() + " is at y " + y + (entity.onGround() ? "" : ", falling")
						+ ", not resting on " + top);
	}

	/**
	 * Asserts whether a player looking straight down onto a point of the anchor's cell, (x, z) within it, aims at the
	 * anchor's top.
	 */
	private static void assertAimsAt(GameTestHelper helper, double x, double z, boolean onTheAnchor) {
		Vec3 eye = helper.absoluteVec(new Vec3(ANCHOR.getX() + x, ANCHOR.getY() + 3, ANCHOR.getZ() + z));
		BlockHitResult hit = helper.getLevel().clip(new ClipContext(eye, eye.subtract(0, 4, 0),
				ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
		boolean onTop = hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(helper.absolutePos(ANCHOR))
				&& Math.abs(hit.getLocation().y - (eye.y - 2)) < 1e-6;
		helper.assertTrue(onTop == onTheAnchor, "looking down at (" + x + ", " + z + ") in the anchor's cell aims at "
				+ hit.getType() + " " + hit.getLocation() + (onTheAnchor ? ", not the anchor's top" : ", not past it"));
	}

	/** The block a player looking straight down onto a point of a cell, (x, z) within it, aims at, if any. */
	private static Optional<BlockPos> aimedAt(GameTestHelper helper, BlockPos cell, double x, double z) {
		Vec3 eye = helper.absoluteVec(new Vec3(cell.getX() + x, cell.getY() + 3, cell.getZ() + z));
		BlockHitResult hit = helper.getLevel().clip(new ClipContext(eye, eye.subtract(0, 4, 0),
				ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
		return hit.getType() == HitResult.Type.BLOCK ? Optional.of(hit.getBlockPos()) : Optional.empty();
	}

	/** Asserts that a cell holds an end of a cross-beam, pointing at its anchor. */
	static void assertEnd(GameTestHelper helper, BlockPos pos, Heading toAnchor) {
		BlockState state = helper.getBlockState(pos);
		helper.assertTrue(state.is(SkyLine.BEAM_END) && state.getValue(BeamEndBlock.ANCHOR) == toAnchor,
				"the cell at " + pos + " holds " + state + ", not an end of a beam to the " + toAnchor);
	}

	static void command(GameTestHelper helper, String command) {
		MinecraftServer server = helper.getLevel().getServer();
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
	}

	private static float progress(GameTestHelper helper, Player player, BlockPos pos) {
		BlockPos absolute = helper.absolutePos(pos);
		return helper.getLevel().getBlockState(absolute).getDestroyProgress(player, helper.getLevel(), absolute);
	}
}
