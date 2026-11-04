package io.github.pavelgar.principia.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import net.minecraft.client.model.HumanoidModel;

/** Constructs the arm pose the class tweaker adds to vanilla's: see {@code principia.classtweaker}. */
@Mixin(HumanoidModel.ArmPose.class)
enum ArmPoseMixin {
	/** The arm whose Sky-Hook grips the rail, raised overhead: one-handed, leaving the other arm its own pose. */
	PRINCIPIA_SKY_HOOK(false, false);

	// A shadow of vanilla's constructor, which the constant above is built with: this body never runs.
	@SuppressWarnings("UnusedVariable")
	@Shadow
	ArmPoseMixin(boolean twoHanded, boolean affectsOffhandPose) {
	}
}
