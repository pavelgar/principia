package io.github.pavelgar.principia.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.pavelgar.principia.skyline.Heading;
import io.github.pavelgar.principia.skyline.SkyHookEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;

/**
 * Riding over the network: the client sends its keys, the server moves the hook by them and the client only follows
 * it, so a rider on a dedicated server ends up where the server put them, their view as they left it though the rail
 * turned, and the server never finds them moved wrongly. A rider who right-clicks the other rail jumps over to it, the
 * client following the hook the server flies. A rider who lets go at speed flies on, by the motion the server sends
 * their client once it has dismounted.
 *
 * <p>From the player's feet, the track runs 6 east from (-2, 3, 2), turns left with radius 4 to (8, 3, -2), heading
 * north, and runs 4 on to (8, 3, -6). The player attaches to its north rail, the left one, which comes out of the turn
 * on the west side: it ends at x = 8.5 - 1, z = -6 + 0.5, where the rider's feet hang 2.5 below its top, y = 4 - 2.5.
 */
@SuppressWarnings("UnstableApiUsage")
public class SkyHookDedicatedServerClientGameTest implements FabricClientGameTest {
	private static final double EYE = 1.62;
	private static final Vec3 END = new Vec3(7.5, 1.5, -5.5);

	@Override
	public void runTest(ClientGameTestContext context) {
		// A free port instead of 25565, so client tests in several worktrees can run side by side.
		Properties properties = new Properties();
		properties.setProperty("server-port", Integer.toString(freePort()));
		MovedWrongly movedWrongly = new MovedWrongly();
		LoggerContext logs = (LoggerContext) LogManager.getContext(false);
		movedWrongly.start();
		logs.getRootLogger().addAppender(movedWrongly);
		try (TestDedicatedServerContext server = context.worldBuilder().createServer(properties);
				TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksRender();
			BlockPos feet = server.computeOnServer(s -> connection.getServerPlayer().blockPosition());
			SkyLineClientGameTest.lay(context, server, feet.offset(-2, 3, 2),
					SkyLineSegmentGameTest.segment(new Vec3i(6, 0, 0), Heading.EAST, 0, Heading.EAST, 0),
					SkyLineSegmentGameTest.segment(new Vec3i(4, 0, -4), Heading.EAST, 0, Heading.NORTH, 0),
					SkyLineSegmentGameTest.segment(new Vec3i(0, 0, -4), Heading.NORTH, 0, Heading.NORTH, 0));
			server.runCommand("gamemode survival @a");
			server.runCommand("item replace entity @a weapon.mainhand with principia:sky_hook");
			// Up at the north rail of the straight, 1 south and 2.3175 up from the eyes, a little east of south so the
			// ride heads east.
			float yaw = -10;
			server.runCommand("tp @a %s %s %s %s %s".formatted(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, yaw,
					-Math.toDegrees(Math.atan2(3.9375 - EYE, 1))));
			context.waitTicks(10);
			connection.waitForChunksRender();

			context.getInput().pressKey(options -> options.keyUse);
			context.waitFor(client -> client.player != null && client.player.getVehicle() instanceof SkyHookEntity,
					20);
			// W, by the client's own key, which the server reads from the keys the client sends: it picks east from the
			// look and keeps driving the rider the way it picked, round the turn though the view stays.
			context.getInput().holdKey(options -> options.keyUp);
			Vec3 end = Vec3.atLowerCornerOf(feet).add(END);
			for (int tick = 0; tick < 200 && serverFeet(server, connection).distanceTo(end) > 1e-6; tick++) {
				context.waitTick();
			}
			context.getInput().releaseKey(options -> options.keyUp);
			context.waitTicks(10); // the client's interpolation catches up
			connection.waitForClientboundPackets();

			Vec3 onServer = serverFeet(server, connection);
			Vec3 onClient = context
					.computeOnClient(client -> client.player == null ? Vec3.ZERO : client.player.position());
			if (onServer.distanceTo(end) > 1e-6) {
				throw new AssertionError("the server has the rider at " + onServer + ", not at the end " + end);
			}
			if (onClient.distanceTo(onServer) > 0.01) {
				throw new AssertionError("the client has the rider at " + onClient + ", the server at " + onServer);
			}
			// The rail turned from east to north, 90° to the left; the view, which only the player turns, didn't.
			float turned = context.computeOnClient(client -> client.player == null ? 0 : client.player.getYRot()) - yaw;
			if (Math.abs(Mth.wrapDegrees(turned)) > 1e-3) {
				throw new AssertionError("the rider's view turned by " + Mth.wrapDegrees(turned) + "°");
			}
			context.takeScreenshot("sky-hook-dedicated-server-rider-at-the-end");

			// Looking at the east rail 2 east, 1 south and 0.8175 up from the eyes, short of the cross-beam at its end, a
			// right-click by the client's own key jumps the rider over to it: the server moves them, the client follows.
			context.getInput().lookAt((float) -Math.toDegrees(Math.atan2(2, 1)),
					(float) -Math.toDegrees(Math.atan2(0.8175, Math.sqrt(5))));
			context.waitTicks(2);
			context.getInput().pressKey(options -> options.keyUse);
			// Where the look passes closest to the rail, to well within 0.01 of the point it was turned towards.
			Vec3 across = end.add(2, 0, 1);
			for (int tick = 0; tick < 40 && serverFeet(server, connection).distanceTo(across) > 0.01; tick++) {
				context.waitTick();
			}
			context.waitTicks(10); // the client's interpolation catches up
			connection.waitForClientboundPackets();
			Vec3 jumpedOnServer = serverFeet(server, connection);
			Vec3 jumpedOnClient = context
					.computeOnClient(client -> client.player == null ? Vec3.ZERO : client.player.position());
			if (jumpedOnServer.distanceTo(across) > 0.01) {
				throw new AssertionError("the server has the rider at " + jumpedOnServer + ", not across at " + across);
			}
			if (jumpedOnClient.distanceTo(jumpedOnServer) > 0.01) {
				throw new AssertionError(
						"the client has the jumped rider at " + jumpedOnClient + ", the server at " + jumpedOnServer);
			}
			context.getInput().lookAt(yaw, 0);

			// W again, still looking a little east of south: back south along the north straight. 8 ticks take the
			// rider to about 0.39 a tick; Shift lets go there, 1.5 above the ground. Flying on south they fall to it
			// in 6 ticks, 0.39 · (1 - 0.91⁶) / 0.09 = 1.9 blocks on, where dropping straight down would keep them put.
			context.getInput().holdKey(options -> options.keyUp);
			context.waitTicks(8);
			context.getInput().holdKey(options -> options.keyShift);
			context.waitFor(client -> client.player != null && client.player.getVehicle() == null, 20);
			context.getInput().releaseKey(options -> options.keyShift);
			context.getInput().releaseKey(options -> options.keyUp);
			Vec3 letGo = context
					.computeOnClient(client -> client.player == null ? Vec3.ZERO : client.player.position());
			context.waitTicks(20);
			Vec3 landed = context
					.computeOnClient(client -> client.player == null ? Vec3.ZERO : client.player.position());
			if (!(landed.z - letGo.z > 1.5)) {
				throw new AssertionError("the client's player went from " + letGo + " to " + landed
						+ " after letting go, not on south");
			}
			context.takeScreenshot("sky-hook-dedicated-server-rider-flew-on-after-letting-go");
		} finally {
			logs.getRootLogger().removeAppender(movedWrongly);
			movedWrongly.stop();
		}
		if (!movedWrongly.lines.isEmpty()) {
			throw new AssertionError("the server logged " + movedWrongly.lines);
		}
	}

	private static Vec3 serverFeet(TestDedicatedServerContext server, TestDedicatedServerConnection connection) {
		return server.computeOnServer(s -> {
			Player player = connection.getServerPlayer();
			return player.position();
		});
	}

	private static int freePort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		} catch (IOException e) {
			throw new UncheckedIOException("no free port for the dedicated server", e);
		}
	}

	/** Collects the server's warnings that a player or their vehicle moved wrongly. */
	private static final class MovedWrongly extends AbstractAppender {
		final List<String> lines = new CopyOnWriteArrayList<>();

		MovedWrongly() {
			super("principia-moved-wrongly", null, null, true, Property.EMPTY_ARRAY);
		}

		@Override
		public void append(LogEvent event) {
			String message = event.getMessage().getFormattedMessage();
			if (message.contains("moved wrongly")) {
				lines.add(message);
			}
		}
	}
}
