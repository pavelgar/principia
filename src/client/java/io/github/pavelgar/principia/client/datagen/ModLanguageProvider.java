package io.github.pavelgar.principia.client.datagen;

import java.util.concurrent.CompletableFuture;

import io.github.pavelgar.principia.content.English;

import net.minecraft.core.HolderLookup;

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricLanguageProvider;

/** {@code en_us.json}, from the English text declared in {@link English}. Declare new text there, not here. */
public class ModLanguageProvider extends FabricLanguageProvider {
	public ModLanguageProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registriesFuture) {
		super(output, registriesFuture);
	}

	@Override
	public void generateTranslations(HolderLookup.Provider registries, TranslationBuilder translationBuilder) {
		English.all().forEach(translationBuilder::add);
	}
}
