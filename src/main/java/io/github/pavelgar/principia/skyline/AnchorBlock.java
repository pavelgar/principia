package io.github.pavelgar.principia.skyline;

import java.util.List;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Where a Sky-Line's segments meet: an anchor faces one of eight headings, and its cross-beam joins the two rails
 * there. Its block entity holds the segments. It has no collision and is waterloggable, as vanilla rails are.
 *
 * <p>The beam reaches past the anchor's cell into the cells on either side, which hold its ends ({@link
 * BeamEndBlock}): the anchor and its ends are one multiblock. However it is placed, even by a command, the anchor adds
 * its ends where their cells are free, and removes them as it goes.
 */
public final class AnchorBlock extends BaseEntityBlock implements SimpleWaterloggedBlock {
	public static final EnumProperty<Heading> HEADING = EnumProperty.create("heading", Heading.class);
	public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

	private final Function<BlockState, VoxelShape> outlines;

	public AnchorBlock(BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(HEADING, Heading.SOUTH).setValue(WATERLOGGED, false));
		outlines = getShapeForEachState(state -> CrossBeam.outline(state.getValue(HEADING)), WATERLOGGED);
	}

	/**
	 * A structure placed rotated or mirrored turns the anchor's heading as it moves its cells, and its segments with it
	 * ({@link AnchorBlockEntity#turnSavedData}).
	 */
	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(HEADING, state.getValue(HEADING).transformed(Mirror.NONE, rotation));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.setValue(HEADING, state.getValue(HEADING).transformed(mirror, Rotation.NONE));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(HEADING, WATERLOGGED);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos worldPosition, BlockState blockState) {
		return new AnchorBlockEntity(worldPosition, blockState);
	}

	@Override
	public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
		boolean inWater = context.getLevel().getFluidState(context.getClickedPos()).is(Fluids.WATER);
		return defaultBlockState().setValue(WATERLOGGED, inWater);
	}

	/**
	 * The corners of an anchor's whole beam, ends included, from the anchor's cell: what aiming at any part of it
	 * outlines. Corners whose indices differ in one bit share an edge.
	 */
	public static List<Vec3> beamCorners(BlockState anchor) {
		return CrossBeam.corners(anchor.getValue(HEADING));
	}

	/** The position of the anchor a block is part of: its own for an anchor, its anchor's for an end of its beam. */
	public static @Nullable BlockPos anchorOfPart(BlockGetter level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.is(SkyLine.ANCHOR)) {
			return pos;
		}
		return state.is(SkyLine.BEAM_END) ? BeamEndBlock.anchorOf(level, pos, state) : null;
	}

	/** Whether an anchor's beam reaches into a cell, where one of its ends belongs. */
	static boolean reachesInto(BlockState anchor, BlockPos pos, BlockPos cell) {
		Vec3i offset = cell.subtract(pos);
		return CrossBeam.ends(anchor.getValue(HEADING)).contains(offset);
	}

	/**
	 * Removes an anchor without drops, as when one of its ends goes without them: its ends go with it, a waterlogged
	 * one leaving its water.
	 */
	static void remove(LevelAccessor level, BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof AnchorBlockEntity anchor) {
			anchor.dropNothing();
		}
		level.setBlock(pos, level.getFluidState(pos).createLegacyBlock(),
				Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
	}

	/** A player in creative mines the anchor without its rails falling, as vanilla blocks drop nothing then. */
	@Override
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		if (player.preventsBlockDrops() && level.getBlockEntity(pos) instanceof AnchorBlockEntity anchor) {
			anchor.dropNothing();
		}
		return super.playerWillDestroy(level, pos, state, player);
	}

	/**
	 * Adds the ends of the beam where their cells are free: as the anchor is placed, and as it turns in place. Turned
	 * round, its beam lies where it did, and keeps its ends.
	 */
	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		super.onPlace(state, level, pos, oldState, movedByPiston);
		if (oldState.is(this) && oldState.getValue(HEADING) == state.getValue(HEADING)) {
			return;
		}
		// Turned, its old ends go here: a debug stick turns it with flags that skip updateIndirectNeighbourShapes.
		if (oldState.is(this)) {
			removeEndsLeftBy(level, pos, oldState);
		}
		for (Vec3i offset : CrossBeam.ends(state.getValue(HEADING))) {
			BlockPos cell = pos.offset(offset);
			if (!level.isLoaded(cell)) {
				continue;
			}
			BlockState there = level.getBlockState(cell);
			if (!BeamEndBlock.pointsAt(there, cell, pos) && there.canBeReplaced()) {
				level.setBlock(cell, BeamEndBlock.at(offset, level.getFluidState(cell).is(Fluids.WATER)),
						Block.UPDATE_ALL);
			}
		}
	}

	/**
	 * Called with the anchor's state once a new state is in its place, and with the new state as it is placed. An
	 * anchor removed, or turned so its beam lies elsewhere, removes the ends it no longer has.
	 */
	@Override
	protected void updateIndirectNeighbourShapes(BlockState state, LevelAccessor level, BlockPos pos,
			@Block.UpdateFlags int updateFlags, int updateLimit) {
		if (!level.isClientSide() && !level.getBlockState(pos).equals(state)) {
			removeEndsLeftBy(level, pos, state);
		}
	}

	/**
	 * Removes the ends an anchor's beam had in a former state that it no longer has: all of them once the anchor is
	 * gone, those no longer in its way once it turns. A waterlogged end leaves its water.
	 */
	private static void removeEndsLeftBy(LevelAccessor level, BlockPos pos, BlockState former) {
		BlockState now = level.getBlockState(pos);
		for (Vec3i offset : CrossBeam.ends(former.getValue(HEADING))) {
			BlockPos cell = pos.offset(offset);
			BlockState there = level.getBlockState(cell);
			if (BeamEndBlock.pointsAt(there, cell, pos) && !(now.is(SkyLine.ANCHOR) && reachesInto(now, pos, cell))) {
				level.setBlock(cell, there.getFluidState().createLegacyBlock(),
						Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
			}
		}
	}

	/** Picking it gives the item that lays it, as picking tripwire gives string. */
	@Override
	protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
		return new ItemStack(SkyLine.RAIL);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return outlines.apply(state);
	}

	@Override
	protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
			Direction directionToNeighbour, BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
		if (state.getValue(WATERLOGGED)) {
			ticks.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
		}
		return super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random);
	}

	@Override
	protected FluidState getFluidState(BlockState state) {
		return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
	}
}
