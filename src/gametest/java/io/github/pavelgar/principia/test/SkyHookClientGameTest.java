package io.github.pavelgar.principia.test;

import java.util.Objects;

import io.github.pavelgar.principia.client.skyline.RailTargeting;
import io.github.pavelgar.principia.skyline.Heading;
import io.github.pavelgar.principia.skyline.RailAim;
import io.github.pavelgar.principia.skyline.RailSide;
import io.github.pavelgar.principia.skyline.SkyHookEntity;
import io.github.pavelgar.principia.skyline.SkyLine;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.achievement.StatsScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.stats.StatFormatter;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.phys.HitResult;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Riding a Sky-Line with the Sky-Hook as a player does: aiming at a rail outlines its segment, marks where the hook
 * would grip it and takes the crosshair from the block behind it, and right-clicking hangs the player below it; the
 * ride's distance shows in the Statistics screen. The screenshots are for the human to judge: the outline and the mark,
 * the raised arm and the hook on the rail, and the distance's row.
 *
 * <p>The track runs east, 3 blocks south of the player and 3 up: its north rail's line at z = 2.5 and y = 3.9375 from
 * the player's feet, (0.5, 0, 0.5) from their cell's corner.
 */
@SuppressWarnings("UnstableApiUsage")
public class SkyHookClientGameTest implements FabricClientGameTest {
	/** The eye height of a standing player. */
	private static final double EYE = 1.62;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			TestServerContext server = singleplayer.getServer();
			BlockPos feet = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().blockPosition());
			BlockPos west = feet.offset(-6, 3, 3);
			SkyLineClientGameTest.lay(context, server, west,
					SkyLineSegmentGameTest.segment(new Vec3i(12, 0, 0), Heading.EAST, 0, Heading.EAST, 0));
			server.runCommand("gamemode survival @a");
			server.runCommand("item replace entity @a weapon.mainhand with principia:sky_hook");

			// Up at the north rail straight ahead, 2 south and 2.3175 up from the eyes.
			look(context, singleplayer, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0, pitchTo(2, 3.9375 - EYE));
			assertTargets(context, RailSide.LEFT);
			context.takeScreenshot("sky-hook-marks-the-rail-aimed-at");

			// A stone wall just south of the north rail, west of the player: the rail in front of it takes the crosshair.
			server.runCommand("fill %d %d %d %d %d %d minecraft:stone".formatted(feet.getX() - 5, feet.getY(),
					feet.getZ() + 3, feet.getX() - 2, feet.getY() + 5, feet.getZ() + 3));
			look(context, singleplayer, feet.getX() - 3.5, feet.getY(), feet.getZ() + 0.5, 0,
					pitchTo(2, 3.9375 - EYE));
			assertTargets(context, RailSide.LEFT);
			HitResult.Type picked = context.computeOnClient(client -> client.hitResult == null
					? HitResult.Type.MISS
					: client.hitResult.getType());
			if (picked != HitResult.Type.MISS) {
				throw new AssertionError("vanilla still targets " + picked + " behind the rail");
			}
			context.takeScreenshot("sky-hook-rail-in-front-of-a-wall-without-the-walls-outline");
			// A block between the eyes and the rail hides it. Back at the first spot, the look passes 2.2 up from the feet
			// as it enters the cell 1 south (0.5 south of the eyes, 0.58 higher), so stone 2 up there is in the way.
			BlockPos inTheWay = feet.offset(0, 2, 1);
			server.runCommand("setblock %d %d %d minecraft:stone".formatted(inTheWay.getX(), inTheWay.getY(),
					inTheWay.getZ()));
			look(context, singleplayer, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0, pitchTo(2, 3.9375 - EYE));
			RailAim.Hit hidden = context.computeOnClient(client -> RailTargeting.hookTarget());
			RailAim.Hit hiddenMined = context.computeOnClient(client -> RailTargeting.mineTarget());
			if (hidden != null || hiddenMined != null) {
				throw new AssertionError("behind a block, the player aims the hook at " + hidden + " and mines "
						+ hiddenMined);
			}
			context.takeScreenshot("sky-hook-rail-hidden-behind-a-block");
			server.runCommand("setblock %d %d %d minecraft:air".formatted(inTheWay.getX(), inTheWay.getY(),
					inTheWay.getZ()));
			// Looking north, level, so the camera in front is clear of the rails, the wall behind the player.
			context.getInput().lookAt(180, 0);
			setCamera(context, CameraType.THIRD_PERSON_FRONT);
			context.takeScreenshot("sky-hook-held-in-third-person");
			setCamera(context, CameraType.FIRST_PERSON);

			// Looking a little east of south, so the ride heads east, to the track's end 2.5 east of the player.
			look(context, singleplayer, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, -10,
					pitchTo(2, 3.9375 - EYE));
			context.getInput().pressKey(options -> options.keyUse);
			context.waitFor(client -> client.player != null && client.player.getVehicle() instanceof SkyHookEntity,
					20);
			// W picks east from the look, a little east of south, and keeps driving the rider east as the look turns.
			context.getInput().holdKey(options -> options.keyUp);
			context.waitTicks(5);
			// Under way, still 5 or more blocks from either end: looking north, level, the view narrowed as a spyglass
			// would, the camera behind sees the rail from the side, running across the view, and the hook on it.
			context.getInput().lookAt(180, 0);
			context.runOnClient(client -> client.options.fov().set(40));
			setCamera(context, CameraType.THIRD_PERSON_BACK);
			context.takeScreenshot("sky-hook-riding-third-person-from-the-side");
			context.runOnClient(client -> client.options.fov().set(70));
			setCamera(context, CameraType.FIRST_PERSON);
			context.waitTicks(60); // to the track's end, where the rider hangs still
			context.getInput().releaseKey(options -> options.keyUp);
			double x = context.computeOnClient(client -> client.player == null ? 0 : client.player.getX());
			if (Math.abs(x - (feet.getX() + 6.5)) > 0.01) {
				throw new AssertionError("the rider is at x = " + x + ", not at the track's east end");
			}
			singleplayer.getConnection().waitForChunksRender();
			// Looking back west along the track, a little down, so the camera behind sees the rider below the rail.
			context.getInput().lookAt(90, 15);
			setCamera(context, CameraType.THIRD_PERSON_BACK);
			context.takeScreenshot("sky-hook-riding-third-person-from-behind");
			setCamera(context, CameraType.THIRD_PERSON_FRONT);
			context.takeScreenshot("sky-hook-riding-third-person-from-the-front");
			// Looking ahead, level: the raised arm and the hook at the top of the view.
			context.getInput().lookAt(90, 0);
			setCamera(context, CameraType.FIRST_PERSON);
			context.takeScreenshot("sky-hook-riding-first-person");
			showDistanceInStatistics(context, server);

			// Back west with W, then sprinting too, which boosts the motor and widens the view as sprinting does: by
			// 1.15, scaled by the FOV Effects setting, here at full. Looking south and up, across the travel, so both
			// views are of the same scene: the track's south rail running across them, and the horizon.
			context.runOnClient(client -> client.options.fovEffectScale().set(1.0));
			context.getInput().holdKey(options -> options.keyUp);
			context.waitTicks(6);
			context.getInput().lookAt(0, -30);
			context.waitTick();
			assertFieldOfView(context, false, 1);
			context.takeScreenshot("sky-hook-riding-first-person-not-boosting");
			context.getInput().holdKey(options -> options.keySprint);
			context.waitTicks(6); // the camera eases the view wider, halving what's left each tick
			assertFieldOfView(context, true, 1.15F);
			context.takeScreenshot("sky-hook-riding-first-person-boosting");
			context.getInput().releaseKey(options -> options.keySprint);
			context.getInput().releaseKey(options -> options.keyUp);
		}
	}

	/**
	 * Opens the Statistics screen, as the pause menu's button does, and scrolls its General tab to the Sky-Line's
	 * distance, among vanilla's distances by minecart, boat and horse. Fails unless the row shows what the server
	 * counted, more than nothing.
	 */
	private static void showDistanceInStatistics(ClientGameTestContext context, TestServerContext server) {
		int counted = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().getStats()
				.getValue(Stats.CUSTOM.get(SkyLine.DISTANCE)));
		if (counted <= 0) {
			throw new AssertionError("the ride counted " + counted + " cm");
		}
		StatsCounter stats = context.computeOnClient(client -> Objects.requireNonNull(client.player).getStats());
		context.setScreen(() -> new StatsScreen(new PauseScreen(true), stats));
		// The General tab's list replaces the screen's loading tabs once the server's stats have come.
		context.waitFor(client -> generalList(client) != null, 100);
		String row = Component.translatable(SkyLine.DISTANCE.toLanguageKey("stat")).getString() + " "
				+ StatFormatter.DISTANCE.format(counted);
		boolean shown = context.computeOnClient(client -> {
			ObjectSelectionList<?> list = Objects.requireNonNull(generalList(client));
			for (int i = 0; i < list.children().size(); i++) {
				if (list.children().get(i).getNarration().getString().contains(row)) {
					list.setScrollAmount(
							list.scrollAmount() + list.getRowTop(i) - (list.getY() + list.getHeight() / 2));
					return true;
				}
			}
			return false;
		});
		if (!shown) {
			throw new AssertionError("the Statistics screen has no row \"" + row + "\"");
		}
		context.waitTick();
		context.takeScreenshot("statistics-sky-line-distance-among-vanillas");
		context.setScreen(() -> null);
	}

	/** The Statistics screen's General tab's list, once the screen has the server's stats. */
	private static @Nullable ObjectSelectionList<?> generalList(Minecraft client) {
		if (client.gui.screen() instanceof StatsScreen screen) {
			for (var child : screen.children()) {
				if (child instanceof ObjectSelectionList<?> list) {
					return list;
				}
			}
		}
		return null;
	}

	/** Fails unless the player's hook is boosting or not, and their view in first person is widened by a factor. */
	private static void assertFieldOfView(ClientGameTestContext context, boolean boosting, float widened) {
		boolean hookBoosting = context.computeOnClient(
				client -> client.player != null && client.player.getVehicle() instanceof SkyHookEntity hook
						&& hook.boosting());
		if (hookBoosting != boosting) {
			throw new AssertionError("the hook's boosting is " + hookBoosting + ", not " + boosting);
		}
		float modifier = context.computeOnClient(
				client -> client.player == null ? 0 : client.player.getFieldOfViewModifier(true, 1.0F));
		if (Math.abs(modifier - widened) > 1e-6) {
			throw new AssertionError("the view is widened by " + modifier + ", not " + widened);
		}
	}

	/** The pitch, in degrees, of looking at a point so far ahead and so far up. */
	private static float pitchTo(double ahead, double up) {
		return (float) -Math.toDegrees(Math.atan2(up, ahead));
	}

	/** Puts the player at a point, facing a yaw and a pitch. */
	private static void look(ClientGameTestContext context, TestSingleplayerContext singleplayer, double x, double y,
			double z, float yaw, float pitch) {
		singleplayer.getServer().runCommand("tp @a %s %s %s %s %s".formatted(x, y, z, yaw, pitch));
		context.waitTicks(10); // the teleport reaches the client, and the chunks re-mesh after block changes
		singleplayer.getConnection().waitForChunksRender();
	}

	/** Fails unless the player aims the hook at a rail, marked, and would mine it, outlined. */
	private static void assertTargets(ClientGameTestContext context, RailSide rail) {
		RailAim.Hit hooked = context.computeOnClient(client -> RailTargeting.hookTarget());
		RailAim.Hit mined = context.computeOnClient(client -> RailTargeting.mineTarget());
		if (hooked == null || hooked.rail() != rail || mined == null || mined.rail() != rail) {
			throw new AssertionError("the player aims the hook at " + hooked + " and mines " + mined + ", not the "
					+ rail + " rail");
		}
	}

	private static void setCamera(ClientGameTestContext context, CameraType camera) {
		context.runOnClient((Minecraft client) -> client.options.setCameraType(camera));
		context.waitTicks(2);
	}
}
