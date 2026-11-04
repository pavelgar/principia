package io.github.pavelgar.principia.skyline;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;

/** Riding a Sky-Line with the Sky-Hook: attaching to the rail a player aims at, as the server checks it. */
public final class SkyHook {
	/** How far from the eyes a player reaches a rail with the Sky-Hook. */
	public static final double REACH = 6;
	/**
	 * How far from the eyes a rider reaches the rail they jump to: longer jumps between tracks are the feature, where
	 * {@link #REACH} is about how far the hand reaches from the ground. A starting value, to be tuned in game.
	 */
	public static final double JUMP_REACH = 8;

	private SkyHook() {
	}

	/** Whether either of a player's hands holds a Sky-Hook. */
	public static boolean holds(LivingEntity player) {
		return grippingHand(player) != null;
	}

	/** The hand whose Sky-Hook grips the rail: the main hand if both hold one, as vanilla tries it first. */
	public static @Nullable InteractionHand grippingHand(LivingEntity player) {
		for (InteractionHand hand : InteractionHand.values()) {
			if (player.getItemInHand(hand).is(SkyLine.HOOK)) {
				return hand;
			}
		}
		return null;
	}

	/**
	 * A player used the Sky-Hook in a hand on the rail they aim at, as their client sends it. A rider jumps to it
	 * ({@link SkyHookEntity#jump}). Anyone else, past the checks every rail use has ({@link RailTarget}), within {@link
	 * #REACH}, hangs below the rail at that point, even with their body in blocks, as a minecart takes a player in whose
	 * head would be in one.
	 */
	public static void useOnRail(ServerPlayer player, InteractionHand hand, RailPoint target) {
		if (!player.getItemInHand(hand).is(SkyLine.HOOK)) {
			return;
		}
		if (player.getVehicle() instanceof SkyHookEntity hook) {
			hook.jump(player, target);
			return;
		}
		if (RailTarget.check(player, target, REACH) == null) {
			return;
		}
		ServerLevel level = player.level();
		Ride ride = Ride.attach(Track.of(level).index(), target);
		if (ride == null) {
			return;
		}
		SkyHookEntity hook = new SkyHookEntity(level, ride);
		level.addFreshEntity(hook);
		if (!player.startRiding(hook)) {
			hook.discard();
			return;
		}
		hook.grip(player);
	}
}
