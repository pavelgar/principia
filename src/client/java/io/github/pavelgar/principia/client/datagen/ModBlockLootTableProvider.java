package io.github.pavelgar.principia.client.datagen;

import java.util.concurrent.CompletableFuture;

import io.github.pavelgar.principia.skyline.SkyLine;

import net.minecraft.core.HolderLookup;

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricBlockLootSubProvider;

/** Block loot tables. Add every new block here, or strict datagen validation fails. */
public class ModBlockLootTableProvider extends FabricBlockLootSubProvider {
	public ModBlockLootTableProvider(FabricPackOutput output,
			CompletableFuture<HolderLookup.Provider> registriesFuture) {
		super(output, registriesFuture);
	}

	@Override
	public void generate() {
		// An anchor's own rail falls with its segments' rails, from its block entity: a loot table would keep some in an
		// explosion. Its ends are part of it.
		add(SkyLine.ANCHOR, noDrop());
		add(SkyLine.BEAM_END, noDrop());
	}
}
