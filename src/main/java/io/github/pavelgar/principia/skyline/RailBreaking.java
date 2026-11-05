package io.github.pavelgar.principia.skyline;

import io.github.pavelgar.principia.content.Protection;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Breaking a rail, as vanilla breaks a block: a player's client tells the server when they start mining a rail
 * ({@link StartMiningRailPayload}) and, once they have mined it as long as its anchor block takes, sends the rail point
 * again ({@link BreakRailPayload}), and its whole segment breaks. The server times it ({@link MiningTime}), so a
 * modified client can't break rails at once, and a lagging one still breaks them, a little later.
 */
public final class RailBreaking {
	private RailBreaking() {
	}

	/**
	 * A rail a player is mining, as the server counts it: the segment, seen from the anchor of the rail point last
	 * sent, in a dimension, since a server tick. Delayed once the client asked to break it before the server counted
	 * enough. Kept with the player on the server ({@link SkyLineHooks#mining}).
	 */
	record Mining(ResourceKey<Level> dimension, RailPoint target, Segment segment, MiningTime time, boolean delayed) {
		/** Whether this is the segment a rail point names in a level: the same, seen from either end. */
		boolean of(Level level, RailPoint other, Segment otherSegment) {
			BlockPos anchor = target.anchor();
			return dimension.equals(level.dimension()) && (anchor.equals(other.anchor())
					? segment.equals(otherSegment)
					: anchor.offset(segment.offset()).equals(other.anchor())
							&& segment.reversed().equals(otherSegment));
		}
	}

	/**
	 * A player started mining the rail at a point, as their client sends it: past the checks every rail use has
	 * ({@link RailTarget}), within their block reach, the server counts from this tick. A start on the segment of a
	 * delayed break keeps it, as the client starts again on a rail the server hasn't broken yet; anything else replaces
	 * what they were mining, or clears it.
	 */
	public static void startMining(ServerPlayer player, RailPoint target) {
		RailTarget found = RailTarget.check(player, target, player.blockInteractionRange());
		if (found == null) {
			SkyLineHooks.mine(player, null);
			return;
		}
		Mining mining = SkyLineHooks.mining(player);
		if (mining != null && mining.delayed() && mining.of(player.level(), target, found.segment())) {
			return;
		}
		SkyLineHooks.mine(player, started(player, target, found));
	}

	/**
	 * A player broke the rail at a point, as their client sends it. Past the checks every rail use has ({@link
	 * RailTarget}), within their block reach, and if they may destroy the anchor block ({@link #mayDestroy}), it breaks
	 * once mined long enough: at once from {@link MiningTime#ON_REQUEST} of the mining on, else delayed until
	 * {@link #tick} finds it done. A break of a rail they didn't start on counts as starting on it now. In creative it
	 * breaks at once.
	 */
	public static void breakRail(ServerPlayer player, RailPoint target) {
		RailTarget found = RailTarget.check(player, target, player.blockInteractionRange());
		ServerLevel level = player.level();
		if (found == null || !mayDestroy(player, level, target.anchor())) {
			return;
		}
		Mining mining = SkyLineHooks.mining(player);
		if (mining == null || !mining.of(level, target, found.segment())) {
			mining = started(player, target, found);
		}
		if (player.hasInfiniteMaterials() || mining.time().breaksOnRequest(perTick(player, target), now(player))) {
			SkyLineHooks.mine(player, null);
			disconnect(player, target, found);
		} else {
			SkyLineHooks.mine(player,
					new Mining(mining.dimension(), target, found.segment(), mining.time(), true));
		}
	}

	/**
	 * Called for each player as each server tick of their level ends: takes their delayed break once its mining is
	 * done, if it still passes every check a break has, as at that moment; else drops it.
	 */
	static void tick(ServerPlayer player) {
		Mining mining = SkyLineHooks.mining(player);
		if (mining == null || !mining.delayed()) {
			return;
		}
		RailPoint target = mining.target();
		if (!mining.time().delayedBreaks(perTick(player, target), now(player))) {
			return;
		}
		SkyLineHooks.mine(player, null);
		RailTarget found = RailTarget.check(player, target, player.blockInteractionRange());
		if (found != null && mining.of(player.level(), target, found.segment())
				&& mayDestroy(player, player.level(), target.anchor())) {
			disconnect(player, target, found);
		}
	}

	/**
	 * Whether a player may destroy an anchor block where a rail point's anchor is, as vanilla asks before a block
	 * breaks: not in adventure mode, nor holding an item that breaks no blocks (a sword in creative).
	 */
	private static boolean mayDestroy(ServerPlayer player, ServerLevel level, BlockPos anchor) {
		return player.mayBuild()
				&& player.getMainHandItem().canDestroyBlock(SkyLine.ANCHOR.defaultBlockState(), level, anchor, player);
	}

	/**
	 * Breaks the segment, unless spawn protection or a claim keeps the player from either anchor ({@link
	 * Protection#mayBreak}, asked last, as vanilla asks claim mods just before a block breaks): it leaves both anchors
	 * and its rails fall along it, unless the player is in creative.
	 */
	private static void disconnect(ServerPlayer player, RailPoint target, RailTarget found) {
		ServerLevel level = player.level();
		BlockPos partner = target.anchor().offset(found.segment().offset());
		if (!(level.getBlockEntity(target.anchor()) instanceof AnchorBlockEntity holder)
				|| !Protection.mayBreak(level, player, target.anchor(), partner)) {
			return;
		}
		holder.disconnect(target.side());
		if (!player.preventsBlockDrops()) {
			RailDrops.drop(level, target.anchor(), found.segment());
		}
		SoundType sound = SkyLine.ANCHOR.defaultBlockState().getSoundType();
		level.playSound(null, found.point().x, found.point().y, found.point().z, sound.getBreakSound(),
				SoundSource.BLOCKS, (sound.getVolume() + 1) / 2, sound.getPitch() * 0.8F);
	}

	private static Mining started(ServerPlayer player, RailPoint target, RailTarget found) {
		return new Mining(player.level().dimension(), target, found.segment(), new MiningTime(now(player)), false);
	}

	/**
	 * How much of the anchor block a player mines a tick where a rail point's anchor is, now: their tool, effects,
	 * standing in water or in the air.
	 */
	private static float perTick(ServerPlayer player, RailPoint target) {
		BlockState anchor = SkyLine.ANCHOR.defaultBlockState();
		return anchor.getDestroyProgress(player, player.level(), target.anchor());
	}

	private static int now(ServerPlayer player) {
		return player.level().getServer().getTickCount();
	}
}
