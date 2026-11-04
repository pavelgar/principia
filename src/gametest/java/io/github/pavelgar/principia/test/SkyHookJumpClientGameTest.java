package io.github.pavelgar.principia.test;

import io.github.pavelgar.principia.client.skyline.RailTargeting;
import io.github.pavelgar.principia.skyline.Heading;
import io.github.pavelgar.principia.skyline.RailAim;
import io.github.pavelgar.principia.skyline.RailPoint;
import io.github.pavelgar.principia.skyline.RailSide;
import io.github.pavelgar.principia.skyline.SkyHookEntity;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Jumping from one rail to another while riding, as a player does: aiming at the track's other rail marks it, as far as
 * a jump reaches, and outlines it only within block reach, where it could be mined. The rider's own rail is never a
 * jump's target, but they can mine it. Right-clicking switches them over, with nothing to
 * aim at during the flight. A Sky-Line in the main hand takes the right-click from an off-hand hook; a golden apple
 * doesn't. Riding on, the client shows the hook close behind the server, and a crash hurts as the rider sees the stop.
 * The screenshots are for the human to judge.
 *
 * <p>The track runs east, 3 blocks south of the player and 3 up: its north rail's line at z = 2.5 and its south one's
 * at z = 4.5, y = 3.9375 from the player's feet, (0.5, 0, 0.5) from their cell's corner. The player hangs from the
 * north rail at x = -4.5, their eyes 0.8175 below its line.
 */
@SuppressWarnings("UnstableApiUsage")
public class SkyHookJumpClientGameTest implements FabricClientGameTest {
	/** The eye height of a standing player. */
	private static final double EYE = 1.62;
	/** How far below a rail's line a rider's eyes are: they hang 2.5 below its top, 1 px above it. */
	private static final double EYES_BELOW = 2.5 - 1 / 16.0 - EYE;
	/** How far the rails are apart. */
	private static final double ACROSS = 2;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			TestServerContext server = singleplayer.getServer();
			BlockPos feet = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().blockPosition());
			SkyLineClientGameTest.lay(context, server, feet.offset(-6, 3, 3),
					SkyLineSegmentGameTest.segment(new Vec3i(12, 0, 0), Heading.EAST, 0, Heading.EAST, 0));
			server.runCommand("gamemode survival @a");
			server.runCommand("item replace entity @a weapon.mainhand with principia:sky_hook");
			// Up at the north rail, 2 south and 2.3175 up from the eyes, and attach there.
			server.runCommand("tp @a %s %s %s 0 %s".formatted(feet.getX() - 4.5, feet.getY(), feet.getZ() + 0.5,
					pitchTo(ACROSS, 3.9375 - EYE)));
			context.waitTicks(10);
			singleplayer.getConnection().waitForChunksRender();
			context.getInput().pressKey(options -> options.keyUse);
			context.waitFor(client -> rail(client) != null, 20);
			context.waitTicks(60); // until vanilla's hint to dismount and the held item's name fade

			// Straight across at the south rail: marked, and within block reach, so outlined.
			look(context, 0, pitchTo(ACROSS, EYES_BELOW));
			assertHookAims(context, RailSide.RIGHT);
			assertMines(context, RailSide.RIGHT);
			context.takeScreenshot("sky-hook-riding-marks-the-other-rail");

			// Up and ahead along their own rail, which the ray passes through overhead: nothing to jump to, but their
			// own rail to mine.
			look(context, -90, -30);
			assertHookAimsNowhere(context, "along their own rail");
			assertMines(context, RailSide.LEFT);

			// At the south rail 7 east, 7.33 from the eyes: within a rider's reach, so marked, and out of block reach,
			// so not outlined. With a block on the way 5.8 out, the eyes can't see it.
			float yaw = (float) -Math.toDegrees(Math.atan2(7, ACROSS));
			look(context, yaw, pitchTo(Math.hypot(7, ACROSS), EYES_BELOW));
			singleplayer.getConnection().waitForChunksRender();
			assertHookAims(context, RailSide.RIGHT);
			assertMines(context, null);
			context.takeScreenshot("sky-hook-riding-marks-a-rail-beyond-block-reach");
			BlockPos inTheWay = feet.offset(1, 3, 4);
			server.runCommand("setblock %d %d %d minecraft:stone".formatted(inTheWay.getX(), inTheWay.getY(),
					inTheWay.getZ()));
			context.waitTicks(2);
			assertHookAimsNowhere(context, "behind a block 6 blocks off");
			server.runCommand("setblock %d %d %d minecraft:air".formatted(inTheWay.getX(), inTheWay.getY(),
					inTheWay.getZ()));

			// With a Sky-Line in the main hand and the hook in the off hand, the Sky-Line decides: the rail is outlined
			// but not marked, and a right-click jumps nowhere.
			server.runCommand("item replace entity @a weapon.offhand with principia:sky_hook");
			server.runCommand("item replace entity @a weapon.mainhand with principia:sky_line");
			look(context, 0, pitchTo(ACROSS, EYES_BELOW));
			assertHookAimsNowhere(context, "holding a Sky-Line");
			assertMines(context, RailSide.RIGHT);
			context.getInput().pressKey(options -> options.keyUse);
			context.waitTicks(10);
			assertRidesAt(context, feet, 2.5);

			// With a golden apple in the main hand, which a player can always eat, the hook in the off hand still takes
			// the right-click, as a minecart would. Straight across, it carries the rider over to the south rail, aiming
			// at nothing on the way, though the look still passes over it, and they eat nothing.
			server.runCommand("item replace entity @a weapon.mainhand with minecraft:golden_apple");
			look(context, 0, pitchTo(ACROSS, EYES_BELOW));
			assertHookAims(context, RailSide.RIGHT);
			context.getInput().pressKey(options -> options.keyUse);
			context.waitFor(client -> client.player != null && client.player.getVehicle() instanceof SkyHookEntity
					&& rail(client) == null, 20);
			// The test runs between the client handling packets and picking its target: a tick picks it again.
			context.waitTick();
			assertHookAimsNowhere(context, "during the flight");
			if (context.computeOnClient(client -> client.player != null && client.player.isUsingItem())) {
				throw new AssertionError("the rider eats the golden apple");
			}
			context.waitFor(client -> {
				RailPoint riding = rail(client);
				return riding != null && riding.rail() == RailSide.RIGHT;
			}, 20);
			context.waitTicks(5); // the client's interpolation catches up
			assertRidesAt(context, feet, 4.5);
			server.runCommand("item replace entity @a weapon.mainhand with principia:sky_hook");
			server.runCommand("item replace entity @a weapon.offhand with minecraft:air");
			// Looking north, level, so the camera behind sees the rider below the south rail, the north one beyond.
			context.getInput().lookAt(180, 0);
			setCamera(context, CameraType.THIRD_PERSON_BACK);
			context.takeScreenshot("sky-hook-landed-on-the-other-rail");
			setCamera(context, CameraType.FIRST_PERSON);

			// Stone 3 east, at the south rail's body height, stands in the rider's way. W east for 14 ticks brings them to
			// 0.5 a tick: the client shows the hook two ticks of that behind the server, 1.0, where smoothing over 3
			// ticks kept it 1.94 behind.
			BlockPos wall = feet.offset(3, 2, 4);
			server.runCommand("setblock %d %d %d minecraft:stone".formatted(wall.getX(), wall.getY(), wall.getZ()));
			look(context, -90, 0);
			context.getInput().holdKey(options -> options.keyUp);
			context.waitTicks(14);
			double behind = 0;
			for (int tick = 0; tick < 3; tick++) {
				Vec3 onServer = server.computeOnServer(s -> {
					Entity hook = s.getPlayerList().getPlayers().getFirst().getVehicle();
					return hook == null ? Vec3.ZERO : hook.position();
				});
				behind = Math.max(behind, clientHook(context).distanceTo(onServer));
				context.waitTick();
			}
			if (behind > 1.1) {
				throw new AssertionError("the client shows the hook " + behind + " behind the server");
			}
			// Hitting the stone at 0.5 hurts them. The hurt comes the tick the client shows the hook where it stopped, or
			// the next, as their view gets there; without the server holding it back it came 2 ticks before.
			int shownStopped = -1;
			int hurt = -1;
			Vec3 last = clientHook(context);
			for (int tick = 0; tick < 15 && (shownStopped < 0 || hurt < 0); tick++) {
				context.waitTick();
				Vec3 now = clientHook(context);
				if (shownStopped < 0 && now.distanceTo(last) < 1e-6) {
					shownStopped = tick - 1;
				}
				if (hurt < 0
						&& context.computeOnClient(client -> client.player != null && client.player.hurtTime > 0)) {
					hurt = tick;
				}
				last = now;
			}
			context.getInput().releaseKey(options -> options.keyUp);
			if (shownStopped < 0 || hurt < shownStopped || hurt > shownStopped + 1) {
				throw new AssertionError("the client shows the hook stopped on tick " + shownStopped
						+ " and the rider hurt on tick " + hurt);
			}
		}
	}

	/** Where the client shows the player's vehicle. */
	private static Vec3 clientHook(ClientGameTestContext context) {
		return context.computeOnClient(client -> client.player == null || client.player.getVehicle() == null
				? Vec3.ZERO
				: client.player.getVehicle().position());
	}

	/** Where the client's hook rides, as the server sent it: null while it doesn't ride, or the player has no hook. */
	private static @Nullable RailPoint rail(Minecraft client) {
		return client.player != null && client.player.getVehicle() instanceof SkyHookEntity hook ? hook.rail() : null;
	}

	/** The pitch, in degrees, of looking at a point so far ahead and so far up. */
	private static float pitchTo(double ahead, double up) {
		return (float) -Math.toDegrees(Math.atan2(up, ahead));
	}

	/** Turns the rider's view, and waits for the client to pick its target. */
	private static void look(ClientGameTestContext context, float yaw, float pitch) {
		context.getInput().lookAt(yaw, pitch);
		context.waitTicks(2);
	}

	/** Fails unless the rider aims the hook at one of the track's rails, which is marked. */
	private static void assertHookAims(ClientGameTestContext context, RailSide rail) {
		RailAim.Hit hooked = context.computeOnClient(client -> RailTargeting.hookTarget());
		if (hooked == null || hooked.rail() != rail) {
			throw new AssertionError("the rider aims the hook at " + hooked + ", not the " + rail + " rail");
		}
	}

	private static void assertHookAimsNowhere(ClientGameTestContext context, String looking) {
		RailAim.Hit hooked = context.computeOnClient(client -> RailTargeting.hookTarget());
		if (hooked != null) {
			throw new AssertionError("looking " + looking + ", the rider aims the hook at " + hooked);
		}
	}

	/** Fails unless the rider would mine one of the track's rails, outlined, or none if null. */
	private static void assertMines(ClientGameTestContext context, @Nullable RailSide rail) {
		RailAim.Hit mined = context.computeOnClient(client -> RailTargeting.mineTarget());
		if (mined == null ? rail != null : mined.rail() != rail) {
			throw new AssertionError("the rider would mine " + mined + ", not " + (rail == null ? "nothing" : rail));
		}
	}

	/** Fails unless the rider hangs from a rail with their feet at x = -4.5 and at a z, from the player's start. */
	private static void assertRidesAt(ClientGameTestContext context, BlockPos feet, double z) {
		Vec3 at = context.computeOnClient(client -> client.player == null ? Vec3.ZERO : client.player.position())
				.subtract(Vec3.atLowerCornerOf(feet));
		if (at.distanceTo(new Vec3(-4.5, 1.5, z)) > 0.01) {
			throw new AssertionError("the rider's feet are at " + at + ", not under the rail at z = " + z);
		}
	}

	private static void setCamera(ClientGameTestContext context, CameraType camera) {
		context.runOnClient((Minecraft client) -> client.options.setCameraType(camera));
		context.waitTicks(2);
	}
}
