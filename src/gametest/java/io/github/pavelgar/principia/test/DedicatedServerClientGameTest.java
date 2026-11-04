package io.github.pavelgar.principia.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.Properties;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;

/**
 * A client joins a dedicated server over the network and sees what the server changes. The server runs in the
 * client's JVM, so this proves the network path, not that a server starts without client classes: the server game
 * tests prove that.
 */
@SuppressWarnings("UnstableApiUsage")
public class DedicatedServerClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		// A free port instead of 25565, so client tests in several worktrees can run side by side.
		Properties properties = new Properties();
		properties.setProperty("server-port", Integer.toString(freePort()));

		try (TestDedicatedServerContext server = context.worldBuilder().createServer(properties);
				TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksRender();

			// A block placed by the server reaches the client, three blocks in front of the player.
			BlockPos pos = server.computeOnServer(s -> {
				Player player = connection.getServerPlayer();
				return player.blockPosition().relative(player.getDirection(), 3);
			});
			server.runCommand("setblock %d %d %d minecraft:gold_block".formatted(pos.getX(), pos.getY(), pos.getZ()));
			context.waitTick(); // the server batches block changes and sends them later in the tick
			connection.waitForClientboundPackets();

			BlockState seen = context.computeOnClient(client -> connection.getClientLevel().getBlockState(pos));
			if (!seen.is(Blocks.GOLD_BLOCK)) {
				throw new AssertionError("the client sees " + seen + " at " + pos + ", not a Block of Gold");
			}
			context.waitTicks(20); // the chunk re-meshes a few ticks after the block change
			context.takeScreenshot("dedicated-server-gold-block");
		}
	}

	private static int freePort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		} catch (IOException e) {
			throw new UncheckedIOException("no free port for the dedicated server", e);
		}
	}
}
