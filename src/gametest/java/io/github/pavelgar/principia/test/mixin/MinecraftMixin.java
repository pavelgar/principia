package io.github.pavelgar.principia.test.mixin;

import io.github.pavelgar.principia.test.Showcase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;

/** Hands every rendered frame to {@link Showcase}, before it reaches the window. */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V", shift = At.Shift.AFTER))
	private void principia$recordFrame(boolean advanceGameTime, CallbackInfo ci) {
		Showcase.onFrame((Minecraft) (Object) this);
	}
}
