package io.github.pavelgar.principia.content;

import eu.pb4.common.protection.api.CommonProtection;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Whether a player may place or break a block in a cell, for actions that do so without vanilla's block placing and
 * mining, which claim and protection mods watch: laying track places anchors, breaking a rail takes a segment from
 * both its anchors. Vanilla's own rules (adventure mode, spawn protection, the world border) apply everywhere; on the
 * server, so do the claim mods'.
 *
 * <p>Claim mods are reached through the two conventions they share: Fabric API's {@link PlayerBlockBreakEvents} for
 * breaking, and the common protection API ({@code eu.pb4:common-protection-api}, which claim mods bundle) for both.
 * Principia compiles against the latter and calls it only once a mod has loaded it.
 */
public final class Protection {
	private static final boolean COMMON_PROTECTION = FabricLoader.getInstance().isModLoaded("common-protection-api");

	private Protection() {
	}

	/** Whether a player may place a block in a cell. On a client, only by vanilla's rules: claims are the server's. */
	public static boolean mayPlace(Level level, Player player, BlockPos pos) {
		if (!player.mayBuild() || !level.mayInteract(player, pos)) {
			return false;
		}
		return !(player instanceof ServerPlayer server && COMMON_PROTECTION)
				|| CommonProtection.canPlaceBlock(level, pos, server.nameAndId(), server);
	}

	/**
	 * Whether a player may break the block in a cell, asked of the claim mods as vanilla mining asks them: if one says
	 * no, the break's {@link PlayerBlockBreakEvents#CANCELED} follows, as it does for a block. Nothing fires
	 * {@link PlayerBlockBreakEvents#AFTER}: the block stays, only what it holds changes.
	 */
	public static boolean mayBreak(ServerLevel level, ServerPlayer player, BlockPos pos) {
		if (!player.mayBuild() || !level.mayInteract(player, pos)) {
			return false;
		}
		BlockState state = level.getBlockState(pos);
		BlockEntity blockEntity = level.getBlockEntity(pos);
		boolean allowed = (!COMMON_PROTECTION || CommonProtection.canBreakBlock(level, pos, player.nameAndId(), player))
				&& PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, player, pos, state, blockEntity);
		if (!allowed) {
			PlayerBlockBreakEvents.CANCELED.invoker().onBlockBreakCanceled(level, player, pos, state, blockEntity);
		}
		return allowed;
	}
}
