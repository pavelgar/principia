package io.github.pavelgar.principia.skyline;

import io.github.pavelgar.principia.content.Content;
import io.github.pavelgar.principia.content.English;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.stats.StatFormatter;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;

/**
 * The Sky-Line: a track of two parallel rails, 3 blocks wide, running in smooth curves through the air between
 * anchors, and the Sky-Hook a player rides a rail with, hanging below it.
 */
public final class SkyLine {
	public static final ResourceKey<Block> ANCHOR_ID = Content.blockId("sky_line_anchor");
	/**
	 * Built as a vanilla rail is: no collision, as the track's rails have none, and mined as fast. It has no item:
	 * anchors only come from laying track.
	 */
	public static final Block ANCHOR = Content.blockWithoutItem(ANCHOR_ID, "Sky-Line Anchor", AnchorBlock::new,
			BlockBehaviour.Properties.of().noCollision().strength(0.7F).sound(SoundType.METAL));
	public static final ResourceKey<Block> BEAM_END_ID = Content.blockId("sky_line_beam_end");
	/**
	 * An end of an anchor's cross-beam, in a cell next to the anchor's: part of the anchor, so it is named and mined as
	 * the anchor is. Pistons can't move it, as they can't move the anchor, whose block entity holds it in place.
	 */
	public static final Block BEAM_END = Content.blockWithoutItem(BEAM_END_ID, "Sky-Line Anchor", BeamEndBlock::new,
			BlockBehaviour.Properties.of().noCollision().strength(0.7F).sound(SoundType.METAL)
					.pushReaction(PushReaction.IMMOVEABLE));
	public static final BlockEntityType<AnchorBlockEntity> ANCHOR_ENTITY = Content.blockEntity("sky_line_anchor",
			AnchorBlockEntity::new, ANCHOR);
	public static final ResourceKey<Item> RAIL_ID = Content.itemId("sky_line");
	/**
	 * The rails track is laid with: an anchor costs one, and a segment one per block of its length. A click places a
	 * lone anchor, another selects it, and from there each click lays a segment.
	 */
	public static final Item RAIL = Content.item(RAIL_ID, "Sky-Line", SkyLineItem::new, new Item.Properties());
	// The action bar says only why a click can't lay or select: the preview shows the rest.
	/** On the action bar when an anchor clicked holds a segment on both sides. */
	public static final String NO_FREE_SIDE = English.add("item.principia.sky_line.no_free_side",
			"This anchor has no free side");
	/** On the action bar when a new anchor's cell, or a cell its beam reaches into, holds a block. */
	public static final String NO_ROOM_FOR_ANCHOR = English.add("item.principia.sky_line.no_room", "No room here");
	/** On the action bar when the player may not build at one of the anchors: adventure mode, spawn protection. */
	public static final String MAY_NOT_BUILD = English.add("item.principia.sky_line.may_not_build",
			"You may not build here");
	// On the action bar when a segment breaks a limit on its shape: its length, its slope, or how sharply it turns,
	// which the ghost shows.
	public static final String TOO_LONG = English.add("item.principia.sky_line.too_long",
			"Too long: at most %s blocks");
	public static final String TOO_STEEP = English.add("item.principia.sky_line.too_steep", "Too steep: at most %s°");
	public static final String TURN_TOO_SHARP = English.add("item.principia.sky_line.turn_too_sharp",
			"Turn too sharp");
	/** On the action bar when a survival player has too few rails: the cost above the hotbar shows how many it takes. */
	public static final String TOO_FEW_RAILS = English.add("item.principia.sky_line.too_few_rails",
			"Not enough Sky-Lines");
	public static final ResourceKey<Item> HOOK_ID = Content.itemId("sky_hook");
	public static final Item HOOK = Content.item(HOOK_ID, "Sky-Hook", Item::new, new Item.Properties().stacksTo(1));
	/**
	 * The invisible hook a rider hangs from, which carries them along a rail. The server moves it every tick and
	 * clients interpolate, so it sends its position every tick. It is only ever made by attaching.
	 */
	public static final EntityType<SkyHookEntity> HOOK_ENTITY = Content.entityType("sky_hook", "Sky-Hook",
			EntityType.Builder.<SkyHookEntity>of(SkyHookEntity::new, MobCategory.MISC).noLootTable().noSummon()
					.fireImmune()
					.sized(0.25F, 0.25F).clientTrackingRange(10).updateInterval(1));
	/**
	 * How far a player has ridden along Sky-Line rails, in centimetres, as vanilla counts a minecart's distance: its
	 * English name sorts it among vanilla's distances by minecart, boat and horse.
	 */
	public static final Identifier DISTANCE = Content.customStat("sky_line_one_cm", "Distance by Sky-Line",
			StatFormatter.DISTANCE);

	private SkyLine() {
	}

	public static void initialize() {
		SkyLineHooks.register();
	}
}
