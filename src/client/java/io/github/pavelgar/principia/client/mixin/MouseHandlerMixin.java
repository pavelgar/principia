package io.github.pavelgar.principia.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.pavelgar.principia.client.skyline.TrackLaying;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.MouseHandler;

/**
 * The mouse wheel in game moves the hotbar's selection, unless laying track takes the turn first: sneaking while aiming
 * at a new anchor's spot, it steps the anchor's heading. Fabric API has no event for the wheel outside screens.
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@WrapOperation(method = "onScroll", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/ScrollWheelHandler;getNextScrollWheelSelection(DII)I"))
	private int principia$turnANewAnchor(double wheel, int currentSelected, int limit, Operation<Integer> original) {
		return TrackLaying.scroll(wheel) ? currentSelected : original.call(wheel, currentSelected, limit);
	}
}
