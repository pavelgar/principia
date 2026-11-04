package io.github.pavelgar.principia.content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import io.github.pavelgar.principia.Principia;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.references.BlockItemId;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.stats.StatFormatter;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Registers every block, block entity type, entity type, data component type, item and stat of the mod, in the {@code principia} namespace, and remembers the
 * order they were registered in. That order is the order of the Principia creative tab, so it follows the order in
 * which {@code Principia.onInitialize()} initializes the feature classes and, within a class, the order of its fields.
 *
 * <p>Ids come first, as in vanilla ({@code BlockItemIds}, {@code ItemIds}): a feature declares them as constants with
 * {@link #blockItemId} and {@link #itemId} and registers from them, because datagen's tag providers take ids, not
 * blocks or items. Each block and item is registered with its English name, which goes to {@link English}.
 */
public final class Content {
	private static final List<Item> ITEMS = new ArrayList<>();

	private Content() {
	}

	/** The id of an item that isn't a block item. */
	public static ResourceKey<Item> itemId(String name) {
		return ResourceKey.create(Registries.ITEM, Principia.id(name));
	}

	/** The id of a block that has no item. */
	public static ResourceKey<Block> blockId(String name) {
		return ResourceKey.create(Registries.BLOCK, Principia.id(name));
	}

	/** The ids of a block and its block item, both {@code name}. */
	public static BlockItemId blockItemId(String name) {
		Identifier id = Principia.id(name);
		return BlockItemId.create(id, id);
	}

	/** Registers an item with its English name. */
	public static Item item(ResourceKey<Item> id, String englishName, Function<Item.Properties, Item> factory,
			Item.Properties properties) {
		Item item = Registry.register(BuiltInRegistries.ITEM, id, factory.apply(properties.setId(id)));
		English.add(item.getDescriptionId(), englishName);
		return add(item);
	}

	/** Registers a block and its block item, which shares the block's English name. */
	public static Block block(BlockItemId ids, String englishName, Function<BlockBehaviour.Properties, Block> factory,
			BlockBehaviour.Properties properties) {
		Block block = blockWithoutItem(ids.block(), englishName, factory, properties);
		add(Registry.register(BuiltInRegistries.ITEM, ids.item(),
				new BlockItem(block, new Item.Properties().useBlockDescriptionPrefix().setId(ids.item()))));
		return block;
	}

	/**
	 * Registers a block with its English name and no item, for a block a player never holds: one that only comes from
	 * using another item. It isn't in the creative tab.
	 */
	public static Block blockWithoutItem(ResourceKey<Block> id, String englishName,
			Function<BlockBehaviour.Properties, Block> factory, BlockBehaviour.Properties properties) {
		Block block = Registry.register(BuiltInRegistries.BLOCK, id, factory.apply(properties.setId(id)));
		English.add(block.getDescriptionId(), englishName);
		return block;
	}

	/** Registers a block entity type for the given blocks. */
	public static <T extends BlockEntity> BlockEntityType<T> blockEntity(String name,
			BlockEntityType.BlockEntitySupplier<T> factory, Block... blocks) {
		return Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
				ResourceKey.create(Registries.BLOCK_ENTITY_TYPE, Principia.id(name)),
				new BlockEntityType<>(factory, Set.of(blocks)));
	}

	/** Registers an entity type with its English name. */
	public static <T extends Entity> EntityType<T> entityType(String name, String englishName,
			EntityType.Builder<T> builder) {
		ResourceKey<EntityType<?>> id = ResourceKey.create(Registries.ENTITY_TYPE, Principia.id(name));
		EntityType<T> type = Registry.register(BuiltInRegistries.ENTITY_TYPE, id, builder.build(id));
		English.add(type.getDescriptionId(), englishName);
		return type;
	}

	/** Registers a data component type, the kind of data an item stack can carry. */
	public static <T> DataComponentType<T> dataComponent(String name, DataComponentType.Builder<T> builder) {
		return Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, Principia.id(name), builder.build());
	}

	/**
	 * Registers a stat of the General tab of the Statistics screen, as vanilla's {@code Stats.makeCustomStat} does,
	 * with its English name, by which the screen sorts it. Award it with {@code Player.awardStat(id, count)}.
	 */
	public static Identifier customStat(String name, String englishName, StatFormatter formatter) {
		Identifier id = Principia.id(name);
		Registry.register(BuiltInRegistries.CUSTOM_STAT, id, id);
		Stats.CUSTOM.get(id, formatter);
		English.add(id.toLanguageKey("stat"), englishName);
		return id;
	}

	/** Every registered item, block items included, in registration order. */
	public static List<Item> items() {
		return Collections.unmodifiableList(ITEMS);
	}

	private static Item add(Item item) {
		ITEMS.add(item);
		return item;
	}
}
