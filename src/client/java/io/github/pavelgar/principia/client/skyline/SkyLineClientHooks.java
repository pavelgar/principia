package io.github.pavelgar.principia.client.skyline;

import io.github.pavelgar.principia.Principia;
import io.github.pavelgar.principia.skyline.AnchorBlockEntity;
import io.github.pavelgar.principia.skyline.SkyLine;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.world.InteractionResult;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;

/**
 * The client's side of {@code SkyLineHooks}: tells anchors they loaded or unloaded in the client's level, draws their
 * rails, sends the rail a Sky-Hook is used on or a player mines and the block a Sky-Line lays track to, shows what
 * laying track would do, draws the outlines of the rail and of the anchor's beam aimed at, plays the riding sound, and
 * draws nothing for the hook a rider hangs from: what shows is the rider's raised arm and the Sky-Hook in it. {@link
 * BlockEntityRenderers#register} is vanilla's, opened by Fabric API's transitive access wideners.
 */
public final class SkyLineClientHooks {
	private SkyLineClientHooks() {
	}

	public static void register() {
		BlockEntityRenderers.register(SkyLine.ANCHOR_ENTITY, context -> new RailRenderer());
		ClientBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, level) -> {
			if (blockEntity instanceof AnchorBlockEntity anchor) {
				anchor.loaded();
			}
		});
		ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, level) -> {
			if (blockEntity instanceof AnchorBlockEntity anchor) {
				anchor.unloaded();
			}
		});
		// Fired on both sides; in singleplayer the server's uses come through the same event.
		UseItemCallback.EVENT.register((player, level, hand) -> level.isClientSide()
				? RailTargeting.use(hand)
				: InteractionResult.PASS);
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> level.isClientSide()
				? TrackLaying.use(player, hand, hit)
				: InteractionResult.PASS);
		LevelRenderEvents.COLLECT_SUBMITS.register(RailHighlight::submit);
		LevelRenderEvents.COLLECT_SUBMITS.register(TrackLaying::submit);
		LevelRenderEvents.COLLECT_SUBMITS.register(RailMining::submitCracks);
		ClientTickEvents.END_CLIENT_TICK.register(TrackLaying::tick);
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, Principia.id("sky_line_cost"),
				TrackLaying::extractCost);
		LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register(BeamOutline::beforeBlockOutline);
		ClientPreAttackCallback.EVENT.register(RailMining::attack);
		ClientTickEvents.END_CLIENT_TICK.register(RailMining::tickEnded);
		ClientTickEvents.END_CLIENT_TICK.register(RidingSound::tick);
		EntityRenderers.register(SkyLine.HOOK_ENTITY, NoopRenderer::new);
	}
}
