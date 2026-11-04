package io.github.pavelgar.principia.client.mixin;

import io.github.pavelgar.principia.client.skyline.RailTargeting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;

/** Rails aren't blocks: once vanilla has picked its target, the rail the player aims at may take it. */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Inject(method = "pick", at = @At("TAIL"))
	private void principia$aimAtRails(float partialTicks, CallbackInfo ci) {
		RailTargeting.afterPick((Minecraft) (Object) this, partialTicks);
	}
}
