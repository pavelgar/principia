package io.github.pavelgar.principia.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.pavelgar.principia.Principia;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Records part of a client game test as a video clip, for {@code scripts/client-gametest.sh --video} to stitch. Clips
 * belong in throwaway tests: the video is for watching, not something the tests keep.
 *
 * <p>A screenshot per tick would come out choppy: {@code takeScreenshot} ticks the game until the GPU hands the image
 * back, so its frames land a tick or two apart. Instead {@link #onFrame} grabs the frames the client renders anyway,
 * between ticks too, each named after the game time it shows in milliseconds since the clip began, partial tick
 * included. Game tests turn off catching up, so the client renders at least one frame a tick however slow it is, and
 * the game slows to {@link #TICK_RATE} ticks a second during a clip so that it renders {@link #FRAMES_PER_TICK}. The
 * video then plays at game speed and up to 60 frames a second.
 *
 * <p>The window and the tick rate change in every run, recorded or not, so that a test behaves the same either way.
 */
@SuppressWarnings("UnstableApiUsage")
public final class Showcase {
	private static final boolean ENABLED = System.getenv("PRINCIPIA_VIDEO") != null;
	private static final int WIDTH = 1280;
	private static final int HEIGHT = 720;
	private static final int FRAMES_PER_TICK = 3;
	private static final float TICK_RATE = 5;
	/** 10 s: the frames of a longer clip would take gigabytes before they are stitched. */
	private static final int MAX_TICKS = 200;
	private static int clips;
	private static volatile @Nullable Recording recording;

	private Showcase() {
	}

	/**
	 * Runs {@code scene} as the clip {@code name}, recorded when the run records video. The clip's window is 1280x720,
	 * so its GUI scale can differ from the test's.
	 *
	 * @throws AssertionError if the scene runs more than {@link #MAX_TICKS} ticks, or a recording got no frames
	 */
	static void record(ClientGameTestContext context, TestServerContext server, String name, Runnable scene) {
		int[] size = context.computeOnClient(client -> new int[]{client.getWindow().getScreenWidth(),
				client.getWindow().getScreenHeight()});
		float tickRate = server.computeOnServer(s -> s.tickRateManager().tickrate());
		context.runOnClient(client -> client.getWindow().setWindowed(WIDTH, HEIGHT));
		server.runOnServer(s -> s.tickRateManager().setTickRate(TICK_RATE));
		double start = context.computeOnClient(Showcase::gameTime);
		Recording clip = ENABLED ? new Recording(directory(name), start) : null;
		recording = clip;
		try {
			scene.run();
		} finally {
			recording = null;
			server.runOnServer(s -> s.tickRateManager().setTickRate(tickRate));
			context.runOnClient(client -> client.getWindow().setWindowed(size[0], size[1]));
		}
		double ticks = context.computeOnClient(Showcase::gameTime) - start;
		if (ticks > MAX_TICKS) {
			throw new AssertionError(String.format(Locale.ROOT,
					"the clip %s ran %.0f ticks, more than the %d a clip may: end it sooner or split it", name, ticks,
					MAX_TICKS));
		}
		if (clip != null) {
			if (clip.frames.get() == 0) {
				throw new AssertionError("the clip " + name + " got no frames: the window never became " + WIDTH + "x"
						+ HEIGHT);
			}
			Principia.LOGGER.info("Showcase clip {}: {} frames", name, clip.frames.get());
		}
	}

	private static Path directory(String name) {
		Path dir = FabricLoader.getInstance().getGameDir().resolve("screenshots/video")
				.resolve(String.format(Locale.ROOT, "%02d-%s", clips++, name));
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return dir;
	}

	/** Grabs the frame just rendered, if a clip is recording, the window has its size and the next frame is due. */
	public static void onFrame(Minecraft client) {
		Recording clip = recording;
		RenderTarget target = client.gameRenderer.mainRenderTarget();
		if (clip == null || client.level == null || target.width != WIDTH || target.height != HEIGHT) {
			return;
		}
		double ticks = gameTime(client) - clip.start;
		if (ticks < clip.next || ticks > MAX_TICKS) {
			return;
		}
		clip.next = (Math.floor(ticks * FRAMES_PER_TICK) + 1) / FRAMES_PER_TICK;
		clip.frames.incrementAndGet();
		Path file = clip.dir.resolve(String.format(Locale.ROOT, "%08d.png", Math.round(ticks * 50)));
		Screenshot.takeScreenshot(target, image -> {
			try {
				image.writeToFile(file);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
	}

	private static double gameTime(Minecraft client) {
		if (client.level == null) {
			throw new AssertionError("no level to record");
		}
		return client.level.getGameTime() + client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
	}

	/** A clip being recorded; {@link #next} is touched only on the render thread. */
	private static final class Recording {
		final Path dir;
		final double start;
		final AtomicInteger frames = new AtomicInteger();
		double next;

		Recording(Path dir, double start) {
			this.dir = dir;
			this.start = start;
		}
	}
}
