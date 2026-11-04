package io.github.pavelgar.principia.client;

import io.github.pavelgar.principia.client.datagen.ModBlockLootTableProvider;
import io.github.pavelgar.principia.client.datagen.ModBlockTagProvider;
import io.github.pavelgar.principia.client.datagen.ModItemTagProvider;
import io.github.pavelgar.principia.client.datagen.ModLanguageProvider;
import io.github.pavelgar.principia.client.datagen.ModModelProvider;
import io.github.pavelgar.principia.client.datagen.ModRecipeProvider;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;

public class PrincipiaDataGenerator implements DataGeneratorEntrypoint {
	@Override
	public void onInitializeDataGenerator(FabricDataGenerator fabricDataGenerator) {
		FabricDataGenerator.Pack pack = fabricDataGenerator.createPack();
		pack.addProvider(ModModelProvider::new);
		pack.addProvider(ModBlockLootTableProvider::new);
		pack.addProvider(ModRecipeProvider::new);
		pack.addProvider(ModBlockTagProvider::new);
		pack.addProvider(ModItemTagProvider::new);
		pack.addProvider(ModLanguageProvider::new);
	}
}
