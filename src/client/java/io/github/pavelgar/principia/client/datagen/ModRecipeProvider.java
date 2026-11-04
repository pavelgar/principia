package io.github.pavelgar.principia.client.datagen;

import java.util.concurrent.CompletableFuture;

import io.github.pavelgar.principia.skyline.SkyLine;

import net.minecraft.advancements.Advancement;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;

import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricRecipeProvider;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalItemTags;

/**
 * Crafting and other recipes. Add a new item's or block's recipes in {@code buildRecipes}, using vanilla's {@link
 * RecipeProvider} helpers. Ingredients are the {@code c:} tags of {@link ConventionalItemTags} where one exists, so
 * other mods' iron or leather works too; unlocking stays on the vanilla item.
 */
public class ModRecipeProvider extends FabricRecipeProvider {
	public ModRecipeProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registriesFuture) {
		super(output, registriesFuture);
	}

	@Override
	protected RecipeProvider createRecipeProvider(HolderLookup.Provider registries, BootstrapContext<Recipe<?>> recipes,
			BootstrapContext<Advancement> advancements) {
		return new RecipeProvider(recipes, advancements) {
			@Override
			public void buildRecipes() {
				// Vanilla's rail recipe without the stick: the two columns read as the two rails.
				shaped(RecipeCategory.TRANSPORTATION, SkyLine.RAIL, 16)
						.define('I', ConventionalItemTags.IRON_INGOTS)
						.pattern("I I")
						.pattern("I I")
						.pattern("I I")
						.unlockedBy(getHasName(Items.IRON_INGOT), has(Items.IRON_INGOT))
						.save(output);
				// A gold ingot for an ornate touch.
				shaped(RecipeCategory.TRANSPORTATION, SkyLine.HOOK)
						.define('I', ConventionalItemTags.IRON_INGOTS)
						.define('P', ItemTags.PLANKS)
						.define('L', ConventionalItemTags.LEATHERS)
						.define('G', ConventionalItemTags.GOLD_INGOTS)
						.pattern("II ")
						.pattern("IPL")
						.pattern(" LG")
						.unlockedBy(getHasName(Items.IRON_INGOT), has(Items.IRON_INGOT))
						.save(output);
			}
		};
	}
}
