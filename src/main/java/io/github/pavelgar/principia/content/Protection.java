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
 * Whether a player may place or break blocks, for actions that do so without vanilla's block placing and mining, which
 * claim mods watch: laying track places anchors, breaking a rail takes a segment from both its anchors. Vanilla's own
 * rules (adventure mode, spawn protection, the world border) apply everywhere; on the server, so do the claim mods',
 * through the two conventions they share: the common protection API ({@code eu.pb4:common-protection-api}, which claim
 * mods bundle, called only once a mod has loaded it) and Fabric API's {@link PlayerBlockBreakEvents}.
 */
public final class Protection {
	private Protection() {
	}

	/** Whether a player may place a block in a cell. On a client, only by vanilla's rules: claims are the server's. */
	public static boolean mayPlace(Level level, Player player, BlockPos pos) {
		if (!player.mayBuild() || !level.mayInteract(player, pos)) {
			return false;
		}
		return !(player instanceof ServerPlayer server && CommonProtectionLoaded.LOADED)
				|| CommonProtection.canPlaceBlock(level, pos, server.nameAndId(), server);
	}

	/**
	 * Whether a player may break the block in a cell, one action that also changes blocks in other cells: by vanilla's
	 * rules and the common protection API in every cell, then by {@link PlayerBlockBreakEvents#BEFORE}, fired once, for
	 * the cell broken, as vanilla mining fires it just before a block breaks. If that says no, {@link
	 * PlayerBlockBreakEvents#CANCELED} follows. Nothing fires {@link PlayerBlockBreakEvents#AFTER}: the blocks stay,
	 * only what they hold changes.
	 */
	public static boolean mayBreak(ServerLevel level, ServerPlayer player, BlockPos pos, BlockPos... others) {
		if (!mayBreakByRules(level, player, pos)) {
			return false;
		}
		for (BlockPos other : others) {
			if (!mayBreakByRules(level, player, other)) {
				return false;
			}
		}
		BlockState state = level.getBlockState(pos);
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, player, pos, state, blockEntity)) {
			return true;
		}
		PlayerBlockBreakEvents.CANCELED.invoker().onBlockBreakCanceled(level, player, pos, state, blockEntity);
		return false;
	}

	private static boolean mayBreakByRules(ServerLevel level, ServerPlayer player, BlockPos pos) {
		return player.mayBuild() && level.mayInteract(player, pos) && (!CommonProtectionLoaded.LOADED
				|| CommonProtection.canBreakBlock(level, pos, player.nameAndId(), player));
	}

	/** Whether a mod loaded the common protection API, looked up the first time a claim is asked about. */
	private static final class CommonProtectionLoaded {
		static final boolean LOADED = FabricLoader.getInstance().isModLoaded("common-protection-api");
	}
}
