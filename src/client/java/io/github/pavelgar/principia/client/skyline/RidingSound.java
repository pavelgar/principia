package io.github.pavelgar.principia.client.skyline;

import java.util.Objects;

import io.github.pavelgar.principia.skyline.SkyHookEntity;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.RidingEntitySoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

/**
 * The sound of riding a Sky-Line, which only the rider hears: vanilla's minecart riding loop, louder the faster the hook
 * goes, as {@code LocalPlayer.startRiding} plays it for a minecart. It is silent while the hook rides no rail, as in a
 * jump's flight, and stops by itself when the rider lets go.
 */
final class RidingSound {
	/** The vehicle the player rode last tick, so the sound starts once per ride. */
	private static @Nullable Entity riding;

	private RidingSound() {
	}

	static void tick(Minecraft client) {
		LocalPlayer player = client.player;
		Entity vehicle = player == null ? null : player.getVehicle();
		if (player != null && vehicle instanceof SkyHookEntity hook && !Objects.equals(hook, riding)) {
			client.getSoundManager().play(new Loop(player, hook, true, SoundEvents.MINECART_INSIDE_UNDERWATER));
			client.getSoundManager().play(new Loop(player, hook, false, SoundEvents.MINECART_INSIDE));
		}
		riding = vehicle;
	}

	/** Vanilla's riding loop, at a minecart's volumes, silent while the hook rides no rail. */
	private static final class Loop extends RidingEntitySoundInstance {
		private final SkyHookEntity hook;

		Loop(LocalPlayer player, SkyHookEntity hook, boolean underwater, SoundEvent sound) {
			super(player, hook, underwater, sound, SoundSource.NEUTRAL, 0, 0.75F, 1);
			this.hook = hook;
		}

		@Override
		protected boolean shoudlPlaySound() {
			return hook.rail() != null;
		}
	}
}
