package io.github.pavelgar.principia.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.pavelgar.principia.client.skyline.SkyHookPose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;

/**
 * In first person, the arm whose Sky-Hook grips the rail reaches up to the top of the view, with the hook above it.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
abstract class FirstPersonHandsAndItemsRendererMixin {
	@Shadow
	protected abstract void renderPlayerHand(PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
			int lightCoords, HumanoidArm arm, PlayerRenderState playerState);

	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
	private void principia$reachUpToTheRail(PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState state,
			float partialTicks, float xRot, InteractionHand hand, float attack, ItemStack itemStack,
			float inverseArmHeight, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int lightCoords,
			CallbackInfo ci) {
		LocalPlayer player = Minecraft.getInstance().player;
		AvatarRenderState avatar = playerState.avatarRenderState;
		if (player == null || avatar == null || state.isScoping || !SkyHookPose.grips(player, hand)) {
			return;
		}
		HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? avatar.mainArm : avatar.mainArm.getOpposite();
		float invert = arm == HumanoidArm.RIGHT ? 1 : -1;
		poseStack.pushPose();
		SkyHookPose.firstPersonGrip(poseStack, invert);
		if (!avatar.isInvisible) {
			poseStack.pushPose();
			SkyHookPose.firstPersonArm(poseStack, invert);
			renderPlayerHand(poseStack, submitNodeCollector, lightCoords, arm, playerState);
			poseStack.popPose();
		}
		SkyHookPose.firstPersonItem(poseStack);
		(hand == InteractionHand.MAIN_HAND ? state.mainHandRenderState : state.offHandRenderState)
				.submit(poseStack, submitNodeCollector, lightCoords, OverlayTexture.NO_OVERLAY, 0);
		poseStack.popPose();
		ci.cancel();
	}
}
