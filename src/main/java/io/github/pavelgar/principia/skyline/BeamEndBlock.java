package io.github.pavelgar.principia.skyline;

import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * An end of an anchor's cross-beam, in one of the two cells the beam reaches into past its anchor's: part of the
 * anchor, as a door's upper half is part of the door, so no other block can be placed into the beam. It points at its
 * anchor. Whatever happens to an end happens to its anchor: mining or replacing one removes the anchor, and removing
 * the anchor removes its ends. The anchor's model draws the whole beam, so an end's draws nothing; its shape is its
 * piece of the beam, which a player aims at.
 */
public final class BeamEndBlock extends Block implements SimpleWaterloggedBlock {
	/** The heading from this end to its anchor. */
	public static final EnumProperty<Heading> ANCHOR = EnumProperty.create("anchor", Heading.class);
	public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

	private final Function<BlockState, VoxelShape> outlines;

	public BeamEndBlock(BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(ANCHOR, Heading.SOUTH).setValue(WATERLOGGED, false));
		outlines = getShapeForEachState(state -> CrossBeam.endOutline(state.getValue(ANCHOR)), WATERLOGGED);
	}

	/** The end in the cell at an offset from its anchor, pointing back at it, holding water if the cell does. */
	static BlockState at(Vec3i offsetFromAnchor, boolean waterlogged) {
		Vec3i toAnchor = new Vec3i(-offsetFromAnchor.getX(), 0, -offsetFromAnchor.getZ());
		for (Heading heading : Heading.values()) {
			if (heading.step().equals(toAnchor)) {
				return SkyLine.BEAM_END.defaultBlockState().setValue(ANCHOR, heading).setValue(WATERLOGGED,
						waterlogged);
			}
		}
		throw new IllegalArgumentException("no end of a beam at " + offsetFromAnchor + " from its anchor");
	}

	/** Whether a state at a position is an end pointing at an anchor's position. */
	static boolean pointsAt(BlockState state, BlockPos pos, BlockPos anchor) {
		return state.is(SkyLine.BEAM_END) && pos.offset(state.getValue(ANCHOR).step()).equals(anchor);
	}

	/** The position of the anchor an end is part of, if an anchor there reaches into the end's cell. */
	static @Nullable BlockPos anchorOf(BlockGetter level, BlockPos pos, BlockState end) {
		BlockPos anchor = pos.offset(end.getValue(ANCHOR).step());
		BlockState there = level.getBlockState(anchor);
		return there.is(SkyLine.ANCHOR) && AnchorBlock.reachesInto(there, anchor, pos) ? anchor : null;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(ANCHOR, WATERLOGGED);
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

	/**
	 * Mining an end mines its anchor, which takes both ends with it: its rails fall, unless the player is in creative.
	 */
	@Override
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		BlockState destroyed = super.playerWillDestroy(level, pos, state, player);
		BlockPos anchor = level.isClientSide() ? null : anchorOf(level, pos, state);
		if (anchor != null) {
			if (player.preventsBlockDrops() && level.getBlockEntity(anchor) instanceof AnchorBlockEntity entity) {
				entity.dropNothing();
			}
			level.destroyBlock(anchor, false, player);
		}
		return destroyed;
	}

	/**
	 * An end breaking with drops breaks its anchor with them, before the end itself goes: blown up, or destroyed by a
	 * command. (Mined, the anchor has already gone with {@link #playerWillDestroy}.)
	 */
	@Override
	protected void spawnAfterBreak(BlockState state, ServerLevel level, BlockPos pos, ItemStack tool,
			boolean dropExperience) {
		super.spawnAfterBreak(state, level, pos, tool, dropExperience);
		BlockPos anchor = anchorOf(level, pos, state);
		if (anchor != null) {
			level.destroyBlock(anchor, false);
		}
	}

	/**
	 * Called with the end's state once a new state is in its place, and with the new state as it is placed. An end
	 * replaced by anything else, removed by any means, takes its anchor with it: without drops, since an end breaking
	 * with drops has already broken its anchor with them.
	 */
	@Override
	protected void updateIndirectNeighbourShapes(BlockState state, LevelAccessor level, BlockPos pos,
			@Block.UpdateFlags int updateFlags, int updateLimit) {
		if (level.isClientSide() || level.getBlockState(pos).equals(state)) {
			return;
		}
		BlockState now = level.getBlockState(pos);
		if (now.is(this) && now.getValue(ANCHOR) == state.getValue(ANCHOR)) {
			return; // only its water changed
		}
		BlockPos anchor = anchorOf(level, pos, state);
		if (anchor != null) {
			AnchorBlock.remove(level, anchor);
		}
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
