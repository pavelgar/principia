package io.github.pavelgar.principia.test;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

/**
 * With {@value Playtest#PROPERTY} set, as {@code scripts/play.sh} sets it, the game goes from the title screen straight
 * into the world {@value #WORLD}, creating it the first time: creative, superflat, cheats on, time and weather standing
 * still, no mobs. Worlds live in each checkout's run/saves, so each worktree has its own.
 */
public final class PlaytestClient implements ClientModInitializer {
	static final String WORLD = "playtest";

	private static boolean opened;

	@Override
	public void onInitializeClient() {
		if (System.getProperty(Playtest.PROPERTY) == null) {
			return;
		}
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof TitleScreen && !opened) {
				opened = true;
				client.execute(() -> open(client, screen));
			}
		});
	}

	/** Into the playtest world from the title screen, creating it the first time. */
	static void open(Minecraft client, Screen title) {
		if (client.getLevelSource().levelExists(WORLD)) {
			client.createWorldOpenFlows().openWorld(WORLD, () -> client.gui.setScreen(title));
			return;
		}
		// As Fabric's client game tests make their worlds, through the screen a player would use.
		CreateWorldScreen.openFresh(client, () -> client.gui.setScreen(title));
		if (!(client.gui.screen() instanceof CreateWorldScreen create)) {
			throw new IllegalStateException("the create world screen didn't open");
		}
		WorldCreationUiState world = create.getUiState();
		world.setName(WORLD);
		world.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
		world.setAllowCommands(true);
		Holder<WorldPreset> flat = world.getSettings().worldgenLoadContext().lookupOrThrow(Registries.WORLD_PRESET)
				.getOrThrow(WorldPresets.FLAT);
		world.setWorldType(new WorldCreationUiState.WorldTypeEntry(flat));
		world.setGenerateStructures(false);
		world.getGameRules().set(GameRules.ADVANCE_TIME, false, null);
		world.getGameRules().set(GameRules.ADVANCE_WEATHER, false, null);
		world.getGameRules().set(GameRules.SPAWN_MOBS, false, null);
		String createLabel = Component.translatable("selectWorld.create").getString();
		create.children().stream()
				.filter(child -> child instanceof Button button && button.getMessage().getString().equals(createLabel))
				.map(Button.class::cast).findFirst()
				.orElseThrow(() -> new IllegalStateException("no Create button on the create world screen"))
				.onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
	}
}
