package io.github.pavelgar.principia.client.mixin;

import io.github.pavelgar.principia.client.skyline.BoostView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.minecraft.client.player.AbstractClientPlayer;

/**
 * Widens a boosting Sky-Hook rider's view, where vanilla widens a sprinting player's: before the modifier is blended
 * with the FOV Effects setting, so the setting scales it, and the camera eases it in and out.
 */
@Mixin(AbstractClientPlayer.class)
abstract class AbstractClientPlayerMixin {
	@ModifyArg(method = "getFieldOfViewModifier", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;lerp(FFF)F"), index = 2)
	private float principia$widenWhileBoosting(float modifier) {
		return BoostView.widen((AbstractClientPlayer) (Object) this, modifier);
	}
}
