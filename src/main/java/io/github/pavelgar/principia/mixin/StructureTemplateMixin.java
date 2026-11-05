package io.github.pavelgar.principia.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import io.github.pavelgar.principia.skyline.AnchorBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * A structure placed rotated or mirrored (by a structure block, {@code /place}, or world generation) turns its blocks'
 * states, but loads their block entities' saved data unchanged. A Sky-Line anchor saves where its partners are, so
 * its data turns too, before it loads ({@link AnchorBlockEntity#transformedForStructure}). Neither Fabric API nor
 * vanilla has a hook for a block entity's data as a structure places it: a block's own {@code rotate} and
 * {@code mirror} see only its state, and from a state alone a mirror looks like a rotation.
 */
@Mixin(StructureTemplate.class)
abstract class StructureTemplateMixin {
	@ModifyArg(method = "placeInWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/TagValueInput;create(Lnet/minecraft/util/ProblemReporter;Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/level/storage/ValueInput;"), index = 2)
	private CompoundTag principia$turnAnchors(CompoundTag saved,
			@Local(argsOnly = true) StructurePlaceSettings settings) {
		return AnchorBlockEntity.transformedForStructure(saved, settings.getMirror(), settings.getRotation());
	}
}
