package io.github.pavelgar.principia.skyline;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * The Sky-Line: rails, laid as track ({@link Placement}). With nothing selected, a click on a block face places a lone
 * anchor and selects it, and a click on an anchor selects it, for the player ({@link TrackStart}); with an anchor
 * selected, a click on a block lays a segment to there. A sneak-use, wherever the player aims, clears the selection. A
 * plain use on nothing does nothing, nor does any use by a player hanging from a rail.
 */
public final class SkyLineItem extends Item {
	public SkyLineItem(Item.Properties properties) {
		super(properties);
	}

	/**
	 * A click on a block, through vanilla's use-on: placing, selecting and clearing, with nothing to show first. Vanilla
	 * skips the block's own use while the player sneaks, and comes here: a sneak-use with an anchor selected clears it.
	 * With none selected it acts as a plain use, so a player can place or select beside a block that has a use of its
	 * own. With one selected, a plain click lays track through the client's {@link LayTrackPayload} instead, so here it
	 * does nothing.
	 */
	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (!(context.getPlayer() instanceof ServerPlayer player)) {
			return InteractionResult.SUCCESS;
		}
		if (Placement.hanging(player)) {
			return InteractionResult.PASS;
		}
		if (TrackStart.of(player) != null) {
			// Laying comes only through the client's payload, which carries what it showed.
			if (!player.isSecondaryUseActive()) {
				return InteractionResult.PASS;
			}
			clear(player);
			return InteractionResult.SUCCESS;
		}
		BlockPos anchor = AnchorBlock.anchorOfPart(player.level(), context.getClickedPos());
		if (anchor != null && player.level().getBlockEntity(anchor) instanceof AnchorBlockEntity existing) {
			Placement.select(player, existing);
		} else {
			Placement.placeAlone(player, context);
		}
		return InteractionResult.SUCCESS;
	}

	/** A use on nothing, or on a rail: a sneak-use clears the selection. */
	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!player.isSecondaryUseActive() || TrackStart.of(player) == null) {
			return InteractionResult.PASS;
		}
		if (player instanceof ServerPlayer server) {
			clear(server);
		}
		return InteractionResult.SUCCESS;
	}

	private static void clear(ServerPlayer player) {
		TrackStart.set(player, null);
	}
}
