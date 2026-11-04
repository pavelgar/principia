package io.github.pavelgar.principia.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.pavelgar.principia.skyline.Segment;
import io.github.pavelgar.principia.skyline.SkyLine;
import io.github.pavelgar.principia.skyline.Track;
import org.apache.commons.io.FileUtils;

import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.WorldData;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Playtesting as {@code scripts/play.sh} starts it: from the title screen straight into a new playtest world, creative,
 * superflat and with cheats on; and each scene, built by {@code /playtest <scene>}, with every anchor holding the
 * segments its lines lay. The screenshots show each scene from where it puts the player, for the human to judge.
 */
@SuppressWarnings("UnstableApiUsage")
public class PlaytestClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		opensANewPlaytestWorld(context);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			TestServerContext server = singleplayer.getServer();
			BlockPos feet = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().blockPosition());
			for (Scene scene : Scene.values()) {
				server.runCommand("tp @a %d %d %d".formatted(feet.getX(), feet.getY(), feet.getZ()));
				context.waitTicks(2);
				server.runCommand("execute as @a at @s run playtest " + scene.id());
				context.waitTicks(10);
				Map<BlockPos, List<Segment>> anchors = Playtest.anchors(scene.lines(scene.origin(feet)));
				String missing = server.computeOnServer(s -> missing(s.overworld(), anchors));
				if (!missing.isEmpty()) {
					throw new AssertionError("the " + scene.id() + " scene is missing " + missing);
				}
				singleplayer.getConnection().waitForChunksRender();
				context.takeScreenshot("playtest-" + scene.id() + "-scene");
			}
		}
	}

	/** Deletes any playtest world, then goes from the title screen into a new one, as play.sh's first run does. */
	private static void opensANewPlaytestWorld(ClientGameTestContext context) {
		context.runOnClient(client -> {
			try {
				FileUtils.deleteDirectory(client.getLevelSource().getBaseDir().resolve(PlaytestClient.WORLD).toFile());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			PlaytestClient.open(client, new TitleScreen());
		});
		context.waitFor(client -> client.level != null && client.player != null && client.gui.screen() == null,
				SharedConstants.TICKS_PER_MINUTE);
		WorldData world = context.computeOnClient(client -> {
			MinecraftServer server = client.getSingleplayerServer();
			if (server == null) {
				throw new AssertionError("the playtest world isn't a singleplayer one");
			}
			return server.getWorldData();
		});
		if (!world.getLevelName().equals(PlaytestClient.WORLD) || world.getGameType() != GameType.CREATIVE
				|| !world.isAllowCommands() || !world.isFlatWorld()) {
			throw new AssertionError("the playtest world is " + world.getLevelName() + ", " + world.getGameType()
					+ ", commands " + world.isAllowCommands() + ", flat " + world.isFlatWorld());
		}
		context.takeScreenshot("playtest-world-on-arrival");
		// As Fabric's tests close a world they made: save and leave, then back to the title screen.
		context.runOnClient(client -> {
			if (client.level != null) {
				client.level.disconnect(Component.translatable("menu.savingLevel"));
			}
			client.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")), false);
		});
		context.waitFor(client -> client.level == null && client.getSingleplayerServer() == null,
				SharedConstants.TICKS_PER_MINUTE);
		context.waitTicks(2);
		context.setScreen(TitleScreen::new);
	}

	/** The anchors that aren't there, or don't hold a segment they should, each with the side it's missing. */
	private static String missing(ServerLevel level, Map<BlockPos, List<Segment>> anchors) {
		return anchors.entrySet().stream().flatMap(entry -> entry.getValue().stream()
				.filter(segment -> !level.getBlockState(entry.getKey()).is(SkyLine.ANCHOR)
						|| Track.of(level).index().at(entry.getKey(), segment.from().side()) == null)
				.map(segment -> entry.getKey() + " " + segment.from().side())).collect(Collectors.joining(", "));
	}
}
