package io.github.pavelgar.principia.test.mixin;

import io.github.pavelgar.principia.test.RegistrationRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;

/** Reports every network payload type registration to {@link RegistrationRecorder}. */
@Mixin(PayloadTypeRegistryImpl.class)
abstract class PayloadTypeRegistryImplMixin<B extends FriendlyByteBuf> {
	@Inject(method = "register", at = @At("HEAD"))
	private <T extends CustomPacketPayload> void principia$record(CustomPacketPayload.Type<T> type,
			StreamCodec<? super B, T> codec,
			CallbackInfoReturnable<CustomPacketPayload.TypeAndCodec<? super B, T>> cir) {
		RegistrationRecorder.record("payload type", type.id());
	}
}
