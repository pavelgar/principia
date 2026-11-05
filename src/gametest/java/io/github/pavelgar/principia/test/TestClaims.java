package io.github.pavelgar.principia.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import eu.pb4.common.protection.api.CommonProtection;
import eu.pb4.common.protection.api.ProtectionProvider;
import io.github.pavelgar.principia.Principia;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;

/**
 * Claims as a claim mod makes them, for tests: cells a player is kept out of, by one of the two conventions claim mods
 * share. {@link #byEvent} refuses breaks through Fabric API's {@link PlayerBlockBreakEvents#BEFORE}; {@link
 * #byCommonProtection} refuses placing and breaking through the common protection API. Each test keeps out only its
 * own players, so the claims never reach another test; {@link #lift} ends them.
 */
final class TestClaims {
	private static final Map<UUID, Set<BlockPos>> BY_EVENT = new ConcurrentHashMap<>();
	private static final Map<UUID, Set<BlockPos>> BY_COMMON_PROTECTION = new ConcurrentHashMap<>();
	private static final Map<UUID, List<BlockPos>> CANCELED = new ConcurrentHashMap<>();
	private static boolean registered;

	private TestClaims() {
	}

	/** Keeps a player from breaking blocks in these cells, as a claim mod listening to Fabric's break event would. */
	static void byEvent(Player player, BlockPos... cells) {
		register();
		BY_EVENT.computeIfAbsent(player.getUUID(), ignored -> ConcurrentHashMap.newKeySet()).addAll(List.of(cells));
	}

	/** Keeps a player from placing or breaking blocks in these cells, as a claim mod providing common protection would. */
	static void byCommonProtection(Player player, BlockPos... cells) {
		register();
		BY_COMMON_PROTECTION.computeIfAbsent(player.getUUID(), ignored -> ConcurrentHashMap.newKeySet())
				.addAll(List.of(cells));
	}

	/** The cells whose break Fabric's {@link PlayerBlockBreakEvents#CANCELED} reported refused to a player, in order. */
	static List<BlockPos> canceled(Player player) {
		return List.copyOf(CANCELED.getOrDefault(player.getUUID(), List.of()));
	}

	/** Lets the players go anywhere again, and forgets their canceled breaks. */
	static void lift(Player... players) {
		for (Player player : players) {
			BY_EVENT.remove(player.getUUID());
			BY_COMMON_PROTECTION.remove(player.getUUID());
			CANCELED.remove(player.getUUID());
		}
	}

	private static synchronized void register() {
		if (registered) {
			return;
		}
		registered = true;
		PlayerBlockBreakEvents.BEFORE
				.register((level, player, pos, state, blockEntity) -> !keptOut(BY_EVENT, player.getUUID(), pos));
		PlayerBlockBreakEvents.CANCELED.register((level, player, pos, state, blockEntity) -> CANCELED
				.computeIfAbsent(player.getUUID(), ignored -> new ArrayList<>()).add(pos.immutable()));
		CommonProtection.register(Principia.id("test_claims"), new ProtectionProvider() {
			@Override
			public boolean isProtected(Level level, BlockPos pos) {
				return BY_COMMON_PROTECTION.values().stream().anyMatch(cells -> cells.contains(pos));
			}

			@Override
			public boolean isAreaProtected(Level level, AABB area) {
				return BY_COMMON_PROTECTION.values().stream()
						.anyMatch(cells -> cells.stream().anyMatch(cell -> area.contains(Vec3.atCenterOf(cell))));
			}

			@Override
			public boolean canBreakBlock(Level level, BlockPos pos, NameAndId profile, @Nullable Player player) {
				return !keptOut(BY_COMMON_PROTECTION, profile.id(), pos);
			}

			@Override
			public boolean canPlaceBlock(Level level, BlockPos pos, NameAndId profile, @Nullable Player player) {
				return !keptOut(BY_COMMON_PROTECTION, profile.id(), pos);
			}
		});
	}

	private static boolean keptOut(Map<UUID, Set<BlockPos>> claims, UUID player, BlockPos pos) {
		Set<BlockPos> cells = claims.get(player);
		return cells != null && cells.contains(pos);
	}
}
