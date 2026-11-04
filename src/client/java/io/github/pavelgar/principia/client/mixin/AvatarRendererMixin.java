package io.github.pavelgar.principia.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import io.github.pavelgar.principia.client.skyline.SkyHookPose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.item.ItemStack;

/** A Sky-Hook rider's arm gripping the rail takes the raised pose. */
@Mixin(AvatarRenderer.class)
abstract class AvatarRendererMixin {
	@ModifyReturnValue(method = "getArmPose(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/world/item/ItemStack;"
			+ "Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/client/model/HumanoidModel$ArmPose;", at = @At("RETURN"))
	private static HumanoidModel.ArmPose principia$raiseTheGrippingArm(HumanoidModel.ArmPose pose, Avatar avatar,
			ItemStack itemInHand, InteractionHand hand) {
		return SkyHookPose.grips(avatar, hand) ? HumanoidModel.ArmPose.PRINCIPIA_SKY_HOOK : pose;
	}
}
