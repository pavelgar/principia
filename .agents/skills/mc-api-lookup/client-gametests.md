# Client game tests

A client game test drives a real Minecraft client (headless under Xvfb), sets a scene through the embedded server, and takes screenshots for the human to look at. `src/gametest/java/io/github/pavelgar/principia/test/PrincipiaClientGameTest.java` opens a singleplayer world; `DedicatedServerClientGameTest` shows a client joining a dedicated server. The API is in `.references/sources/libs/net/fabricmc/fabric/api/client/gametest/v1/`: `ClientGameTestContext`, `TestSingleplayerContext`, `TestServerContext`, `TestServerConnection`, `TestInput`, `TestWorldBuilder`, and `screenshot/`.

## Writing one

```java
try (TestSingleplayerContext sp = context.worldBuilder().create()) {
	sp.getServer().runCommand("gamemode spectator @p"); // hovers at the camera spot
	sp.getServer().runCommand("tp @p 0.5 116 4.5 0 90");
	sp.getConnection().waitForChunksRender();
	sp.getServer().runCommand("setblock 0 106 4 principia:example_block");
	context.waitTicks(20); // the chunk re-meshes a few ticks after a block change
	context.takeScreenshot("example-block-from-above");
}
```

- Server commands have no position of their own: use absolute coordinates, or `execute at @p run ...`. For arbitrary server-side setup, `sp.getServer().runOnServer(server -> ...)`.
- Wait on the connection: `sp.getConnection().waitForChunksRender()`, `waitForClientboundPackets()` and their siblings in `TestServerConnection`. The Fabric docs' example still calls `getClientLevel().waitForChunksRender()` and sets `-Dfabric.client.gametest.disableNetworkSynchronizer`; Fabric API 0.161 moved the first and removed the second along with the synchronizer.
- Read client state with `context.computeOnClient(client -> ...)`; the client's level is `sp.getConnection().getClientLevel()`, a plain `ClientLevel`.
- The test runs after the client handles its packets and before it ticks: what the client works out each tick or frame (a target it picks, an entity's interpolated position) still shows the tick before. After waiting for a value the server syncs, `context.waitTick()` before asserting what the client derives from it; a read without it passes locally and fails on CI.
- For screens: `context.setScreen(() -> new SomeScreen(...))`, or simulate input with `context.getInput()`, then `context.waitForScreen(SomeScreen.class)`.
- Name each screenshot after what it shows; the files get a counter prefix.
- `context.assertScreenshotEquals("<name>")` compares against a golden image under `src/gametest/resources/templates/`. Fonts, animations and world generation make images noisy, so it suits only static scenes.
- Register the class under `fabric-client-gametest` in `src/gametest/resources/fabric.mod.json`.
- A test that starts a dedicated server sets `server-port` to a free port (`freePort()` in `DedicatedServerClientGameTest`): other worktrees' tests may hold 25565.

## Recording video

Motion (riding, an animation) shows in video, not screenshots. Record it from a throwaway client test, registered like any other, and delete the test and its registration afterwards. Wrap each stretch that moves in a clip named after what it shows:

```java
Showcase.record(context, singleplayer.getServer(), "letting-go", () -> {
	context.getInput().holdKey(options -> options.keyShift);
	context.waitFor(client -> client.player != null && client.player.onGround(), 40);
});
```

- `scripts/client-gametest.sh --video` (needs ffmpeg with libx264) records the clips and stitches them, in recording order and captioned with their names, into `showcase.mp4` in the evidence folder. A clip runs in a 1280x720 window with the game at a quarter speed, so recording takes about four times as long as the clip; the video plays at game speed. A clip longer than 10 s of game time fails.
- Watch a block from a fixed spectator camera, set up as the screenshot tests do. Follow a player who acts or moves with their own third-person camera (`CameraType.THIRD_PERSON_BACK`): a camera on another entity (`Minecraft.setCameraEntity`) doesn't draw the local player, and the player's aim comes from it. A ride ignores the look, so once the player has acted, turn their view across the motion (`TestInput.lookAt`) to see it from the side; `client.gui.hud.toggle()` hides the HUD. Start a clip just before the action and end it once the motion settles.
- You can't watch the video: read each clip's sheet of eight frames (`showcase-<clip>.png`) and the stillness the script prints. A clip that is mostly still shows nothing: frame the motion against something fixed (a camera travelling with the subject over a flat world shows almost nothing moving), or end it sooner.
- Animations driven by real time (`Util.getMillis()`, as vanilla's enchantment glint is) play four times too fast in a clip. Drive them by game time and partial tick.

## Running

- `scripts/client-gametest.sh` runs them on the dev classpath; `--production` runs the built jar with Fabric API, as CI does. Both copy the screenshots and the client log to `build/evidence/<mode>-<timestamp>/` and print that path: report it, since other run tasks (`runDatagen`) wipe `build/run/clientGameTest/`. `--only=<Class>[,<Class>]` runs only those test classes, by simple name, in either mode and with `--video`.
- The script sets up the display (24-bit Xvfb, SDL on X11, audio muted); `scripts/agent-guard.sh` points at it if `xvfb-run` is called directly.
- Client game tests from different worktrees run in parallel, at about 2 GB of RAM each.
- A failed test exits non-zero; the stack trace is in the Gradle output and in the copied `client.log`.
- Harmless log noise: authlib `401` and Realms errors (dev accounts are offline), `Illegal option value 0 for Anisotropic Filtering`, `Requested post effect does not exist: minecraft:end_of_frame`, a GPU that `does not have a graphics queue`, and narrator/flite errors in containers.
- In CI and cloud sessions OpenGL can't get a GLX visual under Xvfb, so the client falls back to Vulkan on lavapipe (`mesa-vulkan-drivers`). Without that package the run hangs instead of failing.
