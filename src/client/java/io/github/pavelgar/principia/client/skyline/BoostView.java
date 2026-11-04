package io.github.pavelgar.principia.client.skyline;

import io.github.pavelgar.principia.skyline.SkyHookEntity;

import net.minecraft.client.player.AbstractClientPlayer;

/**
 * A boosting rider's view widens as a sprinting player's does: sprinting adds 30 % to the movement speed, which widens
 * the view by (1.3 + 1) / 2. The server decides the boost, so the view can't widen once hunger has stopped it.
 */
public final class BoostView {
	private static final float WIDENING = 1.15F;

	private BoostView() {
	}

	/** A player's field of view modifier, before the FOV Effects setting scales it, widened if they boost. */
	public static float widen(AbstractClientPlayer player, float modifier) {
		return player.getVehicle() instanceof SkyHookEntity hook && hook.boosting() ? modifier * WIDENING : modifier;
	}
}
