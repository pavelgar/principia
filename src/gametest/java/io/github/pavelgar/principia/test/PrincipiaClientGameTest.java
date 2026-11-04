package io.github.pavelgar.principia.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Client game tests: drive a real client (headless under Xvfb) and take screenshots.
 * Run with {@code ./gradlew runClientGameTest}; screenshots land in {@code build/run/clientGameTest/screenshots}.
 */
@SuppressWarnings("UnstableApiUsage")
public class PrincipiaClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		context.waitTicks(40); // the title screen fades in; screenshots taken earlier show a translucent menu
		context.takeScreenshot("title-screen");

		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			context.takeScreenshot("singleplayer-world");
		}
	}
}
