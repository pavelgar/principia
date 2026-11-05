package io.github.pavelgar.principia.skyline;

import io.github.pavelgar.principia.Principia;
import io.github.pavelgar.principia.content.ClientActions;
import org.jspecify.annotations.Nullable;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityDataRegistry;

/**
 * Where the Sky-Line meets Fabric API, with the client's {@code SkyLineClientHooks}: each level's {@link Track} and each
 * player's selected anchor ({@link TrackStart}) as data attachments, the events that tell anchors they loaded or
 * unloaded, that a server tick ended and that a player changed dimension, the payloads a client sends of the rail it
 * uses an item on, starts mining or breaks and of the block it lays track to, and the hook's synced rail point. A port to another
 * loader replaces these two classes.
 */
public final class SkyLineHooks {
	private static final AttachmentType<Track> TRACK = AttachmentRegistry.createDefaulted(Principia.id("track"),
			Track::new);
	/** Saved with the player and sent to their client only; gone on death, as nothing copies it to the new player. */
	private static final AttachmentType<TrackStart> SELECTION = AttachmentRegistry.create(Principia.id("selection"),
			builder -> builder.persistent(TrackStart.CODEC).syncWith(TrackStart.STREAM_CODEC,
					AttachmentSyncPredicate.targetOnly()));

	/** The rail a player is mining, on the server only: neither saved nor sent, as vanilla keeps a block's mining. */
	private static final AttachmentType<RailBreaking.Mining> MINING = AttachmentRegistry
			.create(Principia.id("mining_rail"));

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
		ClientActions.register(UseOnRailPayload.TYPE, UseOnRailPayload.STREAM_CODEC,
				(player, payload) -> SkyHook.useOnRail(player, payload.hand(), payload.target()));
		ClientActions.register(LayTrackPayload.TYPE, LayTrackPayload.STREAM_CODEC,
				(player, payload) -> Placement.lay(player, payload.hand(), payload.hit(), payload.chosen()));
		ClientActions.register(StartMiningRailPayload.TYPE, StartMiningRailPayload.STREAM_CODEC,
				(player, payload) -> RailBreaking.startMining(player, payload.target()));
		ClientActions.register(BreakRailPayload.TYPE, BreakRailPayload.STREAM_CODEC,
				(player, payload) -> RailBreaking.breakRail(player, payload.target()));
	}

	static Track track(Level level) {
		return level.getAttachedOrCreate(TRACK);
	}

	static RailBreaking.@Nullable Mining mining(Player player) {
		return player.getAttached(MINING);
	}

	static void mine(Player player, RailBreaking.@Nullable Mining mining) {
		player.setAttached(MINING, mining);
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
