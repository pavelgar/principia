package io.github.pavelgar.principia.content;

import io.github.pavelgar.principia.Principia;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;

/**
 * The "Principia" creative tab. It lists every item registered through {@link Content}, in registration order, and
 * shows the first of them as its icon. Vanilla hides a tab with no items, so it stays out of sight until the first one.
 */
public final class PrincipiaCreativeTab {
	public static final ResourceKey<CreativeModeTab> KEY = ResourceKey.create(Registries.CREATIVE_MODE_TAB,
			Principia.id("principia"));
	public static final CreativeModeTab TAB = FabricCreativeModeTab.builder()
			.icon(() -> Content.items().isEmpty() ? ItemStack.EMPTY : new ItemStack(Content.items().getFirst()))
			.title(Component.translatable(English.add("itemGroup.principia", "Principia")))
			.displayItems((parameters, output) -> Content.items().forEach(output::accept))
			.build();

	private PrincipiaCreativeTab() {
	}

	public static void initialize() {
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, KEY, TAB);
	}
}
