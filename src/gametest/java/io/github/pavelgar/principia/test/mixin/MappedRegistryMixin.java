package io.github.pavelgar.principia.test.mixin;

import io.github.pavelgar.principia.test.RegistrationRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.resources.ResourceKey;

/** Reports every registry registration to {@link RegistrationRecorder}. */
@Mixin(MappedRegistry.class)
abstract class MappedRegistryMixin<T> {
	@Inject(method = "register", at = @At("HEAD"))
	private void principia$record(ResourceKey<T> key, T value, RegistrationInfo registrationInfo,
			CallbackInfoReturnable<Holder.Reference<T>> cir) {
		RegistrationRecorder.record(key.registry().toString(), key.identifier());
	}
}
