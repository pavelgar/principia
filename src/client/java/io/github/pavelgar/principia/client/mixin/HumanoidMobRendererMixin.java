package io.github.pavelgar.principia.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.pavelgar.principia.client.skyline.SkyHookPose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.world.entity.LivingEntity;

/** Every passenger is drawn seated; a Sky-Hook rider hangs upright instead. */
@Mixin(HumanoidMobRenderer.class)
abstract class HumanoidMobRendererMixin {
	@ModifyExpressionValue(method = "extractHumanoidRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;isPassenger()Z"))
	private static boolean principia$hangUpright(boolean passenger, LivingEntity entity, HumanoidRenderState state,
			float partialTicks, ItemModelResolver itemModelResolver) {
		return passenger && !SkyHookPose.hangs(entity);
	}
}
