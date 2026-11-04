package io.github.pavelgar.principia.skyline;

import io.github.pavelgar.principia.Principia;
import org.jspecify.annotations.Nullable;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityDataRegistry;

/**
 * Where the Sky-Line meets Fabric API, with the client's {@code SkyLineClientHooks}: each level's {@link Track} and each
 * player's selected anchor ({@link TrackStart}) as data attachments, the events that tell anchors they loaded or
 * unloaded, that a server tick ended and that a player changed dimension, the payloads a client sends of the rail it
 * uses an item on or breaks and of the block it lays track to, and the hook's synced rail point. A port to another
 * loader replaces these two classes.
 */
public final class SkyLineHooks {
	private static final AttachmentType<Track> TRACK = AttachmentRegistry.createDefaulted(Principia.id("track"),
			Track::new);
	/** Saved with the player and sent to their client only; gone on death, as nothing copies it to the new player. */
	private static final AttachmentType<TrackStart> SELECTION = AttachmentRegistry.create(Principia.id("selection"),
			builder -> builder.persistent(TrackStart.CODEC).syncWith(TrackStart.STREAM_CODEC,
					AttachmentSyncPredicate.targetOnly()));

	private SkyLineHooks() {
	}

	static void register() {
		FabricEntityDataRegistry.register(Principia.id("optional_rail_point"), SkyHookEntity.OPTIONAL_RAIL_POINT);
		ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, level) -> {
			if (blockEntity instanceof AnchorBlockEntity anchor) {
				anchor.loaded();
			}
		});
		ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, level) -> {
			if (blockEntity instanceof AnchorBlockEntity anchor) {
				anchor.unloaded();
			}
		});
		ServerTickEvents.END_LEVEL_TICK.register(level -> {
			Track track = trackIfAny(level);
			if (track != null) {
				track.tickEnded();
			}
			level.players().forEach(Placement::keepCurrent);
		});
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL
				.register((player, origin, destination) -> select(player, null));
		PayloadTypeRegistry.serverboundPlay().register(UseOnRailPayload.TYPE, UseOnRailPayload.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(UseOnRailPayload.TYPE,
				(payload, context) -> SkyHook.useOnRail(context.player(), payload.hand(), payload.target()));
		PayloadTypeRegistry.serverboundPlay().register(LayTrackPayload.TYPE, LayTrackPayload.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(LayTrackPayload.TYPE, (payload, context) -> Placement
				.lay(context.player(), payload.hand(), payload.hit(), payload.chosen()));
		PayloadTypeRegistry.serverboundPlay().register(BreakRailPayload.TYPE, BreakRailPayload.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(BreakRailPayload.TYPE,
				(payload, context) -> RailBreaking.breakRail(context.player(), payload.target()));
	}

	static Track track(Level level) {
		return level.getAttachedOrCreate(TRACK);
	}

	static @Nullable TrackStart selection(Player player) {
		return player.getAttached(SELECTION);
	}

	static void select(Player player, @Nullable TrackStart start) {
		player.setAttached(SELECTION, start);
	}

	/** The level's track, if any anchor has been loaded in it. */
	static @Nullable Track trackIfAny(Level level) {
		return level.getAttached(TRACK);
	}
}
