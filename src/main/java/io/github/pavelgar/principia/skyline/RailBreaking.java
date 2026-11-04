package io.github.pavelgar.principia.skyline;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Breaking a rail: once a player has held attack on a rail as long as mining its anchor takes, their client sends the
 * rail point, and its whole segment breaks.
 */
public final class RailBreaking {
	private RailBreaking() {
	}

	/**
	 * A player broke the rail at a point, as their client sends it. Past the checks every rail use has ({@link
	 * RailTarget}), within their block reach, they must be able to break the anchor block where both anchors are: not
	 * in adventure mode, nor with spawn protection over either, nor holding an item that breaks no blocks (a sword in
	 * creative). The segment then leaves both anchors and its rails fall along it, unless the player is in creative.
	 * How long they mined isn't checked: vanilla does for blocks, but no mod with lines to break does for them.
	 */
	public static void breakRail(ServerPlayer player, RailPoint target) {
		if (!player.mayBuild()) {
			return;
		}
		RailTarget found = RailTarget.check(player, target, player.blockInteractionRange());
		if (found == null) {
			return;
		}
		ServerLevel level = player.level();
		BlockPos partner = target.anchor().offset(found.segment().offset());
		BlockState anchor = SkyLine.ANCHOR.defaultBlockState();
		if (!level.mayInteract(player, target.anchor()) || !level.mayInteract(player, partner)
				|| !player.getMainHandItem().canDestroyBlock(anchor, level, target.anchor(), player)
				|| !(level.getBlockEntity(target.anchor()) instanceof AnchorBlockEntity holder)) {
			return;
		}
		holder.disconnect(target.side());
		if (!player.preventsBlockDrops()) {
			RailDrops.drop(level, target.anchor(), found.segment());
		}
		SoundType sound = anchor.getSoundType();
		level.playSound(null, found.point().x, found.point().y, found.point().z, sound.getBreakSound(),
				SoundSource.BLOCKS, (sound.getVolume() + 1) / 2, sound.getPitch() * 0.8F);
	}
}
