package io.github.pavelgar.principia.client.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import io.github.pavelgar.principia.client.skyline.SkyHookPose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.model.AnimationUtils;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.HumanoidArm;

/**
 * Poses an arm in the Sky-Hook's raised pose, which vanilla's switch over the poses doesn't know, and keeps it from
 * swaying idly, as vanilla keeps an arm holding a spyglass: the hook in its hand stays on the rail.
 */
@Mixin(HumanoidModel.class)
abstract class HumanoidModelMixin {
	@Inject(method = "poseRightArm", at = @At("TAIL"))
	private void principia$raiseRightArm(HumanoidRenderState state, CallbackInfo ci) {
		if (state.rightArmPose == HumanoidModel.ArmPose.PRINCIPIA_SKY_HOOK) {
			SkyHookPose.raise(((HumanoidModel<?>) (Object) this).rightArm, HumanoidArm.RIGHT);
		}
	}

	@Inject(method = "poseLeftArm", at = @At("TAIL"))
	private void principia$raiseLeftArm(HumanoidRenderState state, CallbackInfo ci) {
		if (state.leftArmPose == HumanoidModel.ArmPose.PRINCIPIA_SKY_HOOK) {
			SkyHookPose.raise(((HumanoidModel<?>) (Object) this).leftArm, HumanoidArm.LEFT);
		}
	}

	/** Skips {@link AnimationUtils#bobModelPart} for an arm in the Sky-Hook's raised pose. */
	@WrapWithCondition(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/AnimationUtils;bobModelPart(Lnet/minecraft/client/model/geom/ModelPart;FF)V"))
	private boolean principia$holdRaisedArmStill(ModelPart part, float ageInTicks, float scale,
			HumanoidRenderState state) {
		HumanoidModel<?> model = (HumanoidModel<?>) (Object) this;
		return !((part == model.rightArm && state.rightArmPose == HumanoidModel.ArmPose.PRINCIPIA_SKY_HOOK)
				|| (part == model.leftArm && state.leftArmPose == HumanoidModel.ArmPose.PRINCIPIA_SKY_HOOK));
	}
}
