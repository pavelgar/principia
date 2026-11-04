package io.github.pavelgar.principia.test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.pavelgar.principia.Principia;
import io.github.pavelgar.principia.content.English;
import io.github.pavelgar.principia.content.PrincipiaCreativeTab;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

/** Guards on how Principia registers its content, whatever that content is. */
public class RegistrationGameTest {
	/** Every id Principia registers, in any registry or as a payload type, is in the {@code principia} namespace. */
	@GameTest
	public void everythingIsInPrincipiaNamespace(GameTestHelper helper) {
		helper.assertTrue(RegistrationRecorder.principiaCount() > 0,
				"no principia registrations were recorded: the test mixins didn't apply");
		helper.assertValueEqual(RegistrationRecorder.foreign(), List.of(),
				"registrations by Principia outside the principia namespace");
		helper.succeed();
	}

	/**
	 * Every {@code c:} or {@code principia} tag holding Principia items or blocks has an English name, which recipe
	 * viewers show for slots that accept the tag. A sub-tag's name ends with its parent's, following Fabric's own
	 * names: {@code c:ingots/iron} is "Iron Ingots" under "Ingots", {@code c:storage_blocks/iron} is "Iron Storage
	 * Blocks" under "Storage Blocks".
	 */
	@GameTest
	public void everyTagHasAnEnglishName(GameTestHelper helper) {
		RegistryAccess registries = helper.getLevel().registryAccess();
		checkTagNames(helper, registries.lookupOrThrow(Registries.ITEM));
		checkTagNames(helper, registries.lookupOrThrow(Registries.BLOCK));
		helper.succeed();
	}

	private static <T> void checkTagNames(GameTestHelper helper, Registry<T> registry) {
		Language language = Language.getInstance();
		registry.getTags().forEach(tag -> {
			TagKey<T> key = tag.key();
			String namespace = key.location().getNamespace();
			boolean holdsPrincipia = tag.stream()
					.anyMatch(holder -> holder.is(entry -> entry.identifier().getNamespace().equals(Principia.MOD_ID)));
			if (!holdsPrincipia || !(namespace.equals("c") || namespace.equals(Principia.MOD_ID))) {
				return;
			}
			helper.assertTrue(language.has(key.getTranslationKey()),
					"missing en_us.json entry for tag #" + key.location() + ": " + key.getTranslationKey());
			String path = key.location().getPath();
			if (path.contains("/")) {
				TagKey<T> parent = TagKey.create(registry.key(),
						Identifier.fromNamespaceAndPath(namespace, path.substring(0, path.lastIndexOf('/'))));
				if (language.has(parent.getTranslationKey())) {
					String name = language.getOrDefault(key.getTranslationKey());
					String parentName = language.getOrDefault(parent.getTranslationKey());
					helper.assertTrue(name.endsWith(" " + parentName), "tag #" + key.location() + " is named \""
							+ name + "\", which should end with its parent's name \"" + parentName + "\"");
				}
			}
		});
	}

	/** Every Principia block and item, and the creative tab, has an English name. */
	@GameTest
	public void everythingHasAnEnglishName(GameTestHelper helper) {
		Language language = Language.getInstance();
		for (Block block : BuiltInRegistries.BLOCK) {
			if (BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals(Principia.MOD_ID)) {
				helper.assertTrue(language.has(block.getDescriptionId()),
						"missing en_us.json entry: " + block.getDescriptionId());
			}
		}
		for (Item item : BuiltInRegistries.ITEM) {
			if (BuiltInRegistries.ITEM.getKey(item).getNamespace().equals(Principia.MOD_ID)) {
				helper.assertTrue(language.has(item.getDescriptionId()),
						"missing en_us.json entry: " + item.getDescriptionId());
			}
		}
		helper.assertTrue(language.has("itemGroup.principia"), "missing en_us.json entry: itemGroup.principia");
		helper.succeed();
	}

	/**
	 * Every text declared in Java through {@link English} is in the loaded {@code en_us.json}, as declared: datagen's
	 * output is current. Text a player sees without a block or item to name it, such as an action-bar message or an
	 * entity's name, is caught here.
	 */
	@GameTest
	public void everyDeclaredTextIsInTheLanguageFile(GameTestHelper helper) {
		Language language = Language.getInstance();
		English.all().forEach((key, text) -> helper.assertValueEqual(language.getOrDefault(key), text,
				"the en_us.json text of " + key + " (rerun runDatagen)"));
		helper.succeed();
	}

	/**
	 * Every Principia item appears in the Principia creative tab. Only a server test can check this: no client test
	 * can open the tab, because {@code CreativeModeInventoryScreen.selectTab} is private.
	 */
	@GameTest
	public void creativeTabListsEveryItem(GameTestHelper helper) {
		CreativeModeTab tab = PrincipiaCreativeTab.TAB;
		tab.buildContents(new CreativeModeTab.ItemDisplayParameters(FeatureFlags.DEFAULT_FLAGS, false,
				helper.getLevel().registryAccess()));
		Set<Item> listed = tab.getDisplayItems().stream().map(ItemStack::getItem)
				.collect(Collectors.toSet());
		for (Item item : BuiltInRegistries.ITEM) {
			if (BuiltInRegistries.ITEM.getKey(item).getNamespace().equals(Principia.MOD_ID)) {
				helper.assertTrue(listed.contains(item),
						"not in the Principia creative tab: " + BuiltInRegistries.ITEM.getKey(item));
			}
		}
		helper.succeed();
	}
}
