package io.github.pavelgar.principia.client.datagen;

import java.util.concurrent.CompletableFuture;

import net.minecraft.core.HolderLookup;

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagsProvider;

/**
 * Item tags. Put a material's items in their conventional {@code c:} tags (for example {@code c:ingots/<material>}) here,
 * so other mods' recipes accept them. Fabric ships only the parents ({@code ConventionalItemTags.INGOTS}); add the
 * material's sub-tag to its parent.
 */
public class ModItemTagProvider extends FabricTagsProvider.ItemTagsProvider {
	public ModItemTagProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registriesFuture) {
		super(output, registriesFuture);
	}

	@Override
	protected void addTags(HolderLookup.Provider registries) {
	}
}
