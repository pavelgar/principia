package io.github.pavelgar.principia.client.datagen;

import java.util.concurrent.CompletableFuture;

import io.github.pavelgar.principia.skyline.SkyLine;

import net.minecraft.core.HolderLookup;
import net.minecraft.tags.BlockTags;

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagsProvider;

/**
 * Block tags. Add every new block to its mining tool tag (and tool tier tag, if any) here, and a storage block to its
 * {@code c:storage_blocks/<material>} tag (the item side is in {@link ModItemTagProvider}). For a tag that is both a
 * block and an item tag, create the pair with {@code BlockItemTagId}.
 */
public class ModBlockTagProvider extends FabricTagsProvider.BlockTagsProvider {
	public ModBlockTagProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registriesFuture) {
		super(output, registriesFuture);
	}

	@Override
	protected void addTags(HolderLookup.Provider registries) {
		// As vanilla rails are.
		builder(BlockTags.MINEABLE_WITH_PICKAXE).add(SkyLine.ANCHOR_ID).add(SkyLine.BEAM_END_ID);
	}
}
