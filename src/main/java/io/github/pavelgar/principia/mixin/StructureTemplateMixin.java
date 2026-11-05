package io.github.pavelgar.principia.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import io.github.pavelgar.principia.content.TurnsWithStructures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * A structure placed rotated or mirrored loads its block entities' saved data unchanged, into the block entities its
 * turned blocks already made. Those that {@link TurnsWithStructures} turn it first. Neither Fabric API nor vanilla has
 * a hook for this.
 */
@Mixin(StructureTemplate.class)
abstract class StructureTemplateMixin {
	@ModifyArg(method = "placeInWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/TagValueInput;create(Lnet/minecraft/util/ProblemReporter;Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/level/storage/ValueInput;"), index = 2)
	private CompoundTag principia$turnBlockEntityData(CompoundTag saved, @Local BlockEntity blockEntity,
			@Local(argsOnly = true) StructurePlaceSettings settings) {
		return TurnsWithStructures.dataToLoad(blockEntity, saved, settings.getMirror(), settings.getRotation());
	}
}
