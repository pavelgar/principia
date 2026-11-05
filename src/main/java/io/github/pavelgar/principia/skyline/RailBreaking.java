package io.github.pavelgar.principia.skyline;

import io.github.pavelgar.principia.content.Protection;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Breaking a rail, as vanilla breaks a block: a player's client tells the server when they start mining a rail
 * ({@link StartMiningRailPayload}) and, once they have held attack on it as long as mining its anchor takes, sends
 * the rail point again ({@link BreakRailPayload}), and its whole segment breaks. The server times it, so a modified
 * client can't break rails at once.
 */
public final class RailBreaking {
	/**
	 * How much of its mining a break may come short of, for lag: as vanilla's server takes a block broken once 0.7 of
	 * its mining is done by its own count of the ticks since the start.
	 */
	private static final float LAG_ALLOWANCE = 0.7F;

	private RailBreaking() {
	}

	/**
	 * A rail a player started mining, as their client told the server: the segment, seen from an anchor, at a server
	 * tick. Kept with the player on the server while they play ({@link SkyLineHooks#mining}).
	 */
	record Mining(BlockPos anchor, Segment segment, int startTick) {
		/** Whether this is the segment the rail point of another anchor names: the same, seen from either end. */
		boolean of(BlockPos otherAnchor, Segment other) {
			return anchor.equals(otherAnchor)
					? segment.equals(other)
					: anchor.offset(segment.offset()).equals(otherAnchor) && segment.reversed().equals(other);
		}
	}

	/**
	 * A player started mining the rail at a point, as their client sends it: past the checks every rail use has
	 * ({@link RailTarget}), within their block reach, the server notes the segment and the tick. Anything else clears
	 * what they were mining.
	 */
	public static void startMining(ServerPlayer player, RailPoint target) {
		RailTarget found = RailTarget.check(player, target, player.blockInteractionRange());
		SkyLineHooks.mine(player, found == null ? null : new Mining(target.anchor(), found.segment(), tick(player)));
	}

	/**
	 * A player broke the rail at a point, as their client sends it. Past the checks every rail use has ({@link
	 * RailTarget}), within their block reach, they must have mined it long enough and be able to break the anchor block
	 * where both anchors are: not in adventure mode, nor holding an item that breaks no blocks (a sword in creative),
	 * nor kept out by spawn protection or a claim over either ({@link Protection#mayBreak}, asked last, as vanilla asks
	 * claim mods just before a block breaks). The segment then leaves both anchors and its rails fall along it, unless
	 * the player is in creative.
	 */
	public static void breakRail(ServerPlayer player, RailPoint target) {
		RailTarget found = RailTarget.check(player, target, player.blockInteractionRange());
		if (found == null) {
			return;
		}
		ServerLevel level = player.level();
		BlockPos partner = target.anchor().offset(found.segment().offset());
		BlockState anchor = SkyLine.ANCHOR.defaultBlockState();
		if (!player.mayBuild() || !player.getMainHandItem().canDestroyBlock(anchor, level, target.anchor(), player)
				|| !(level.getBlockEntity(target.anchor()) instanceof AnchorBlockEntity holder)
				|| !minedLongEnough(player, level, target.anchor(), found.segment())
				|| !Protection.mayBreak(level, player, target.anchor())
				|| !Protection.mayBreak(level, player, partner)) {
			return;
		}
		SkyLineHooks.mine(player, null);
		holder.disconnect(target.side());
		if (!player.preventsBlockDrops()) {
			RailDrops.drop(level, target.anchor(), found.segment());
		}
		SoundType sound = anchor.getSoundType();
		level.playSound(null, found.point().x, found.point().y, found.point().z, sound.getBreakSound(),
				SoundSource.BLOCKS, (sound.getVolume() + 1) / 2, sound.getPitch() * 0.8F);
	}

	/**
	 * Whether a player has mined a segment long enough to break it: since they started on it, by the server's ticks,
	 * as long as mining its anchor block takes them now (their tool, effects, standing in water or in the air), less
	 * {@link #LAG_ALLOWANCE}. A player with infinite materials (creative) breaks it at once, as a block.
	 */
	private static boolean minedLongEnough(ServerPlayer player, ServerLevel level, BlockPos anchor, Segment segment) {
		if (player.hasInfiniteMaterials()) {
			return true;
		}
		@Nullable
		Mining mining = SkyLineHooks.mining(player);
		if (mining == null || !mining.of(anchor, segment)) {
			return false;
		}
		int ticks = tick(player) - mining.startTick() + 1;
		return SkyLine.ANCHOR.defaultBlockState().getDestroyProgress(player, level, anchor) * ticks >= LAG_ALLOWANCE;
	}

	private static int tick(ServerPlayer player) {
		return player.level().getServer().getTickCount();
	}
}
