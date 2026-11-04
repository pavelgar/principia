package io.github.pavelgar.principia.test.mixin;

import java.util.Locale;
import java.util.function.BooleanSupplier;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.MultipleTestTracker;

/**
 * Makes the server game test run (`./gradlew runGameTest`) reproducible and quicker.
 *
 * <ul>
 * <li>Vanilla lays the tests out from a random position up to 15 million blocks out, so every run puts them in
 * different chunks and a failure can't be rerun as it was. Here they start at {@code principia.gametest.origin}
 * ({@code x,z}, by default {@code 0,0}; {@code random} for vanilla's), which is logged.</li>
 * <li>Once the results are logged and the JUnit report written, the server exits instead of saving its world for
 * about 7 seconds: the run directory is deleted before the next run anyway.</li>
 * </ul>
 */
@Mixin(GameTestServer.class)
abstract class GameTestServerMixin {
	private static final Logger PRINCIPIA$LOGGER = LoggerFactory.getLogger("principia-test");

	@Shadow
	private @Nullable MultipleTestTracker testTracker;

	@ModifyExpressionValue(method = "startTests", at = @At(value = "NEW", target = "net/minecraft/core/BlockPos"))
	private BlockPos principia$fixedOrigin(BlockPos random) {
		String origin = System.getProperty("principia.gametest.origin", "0,0").trim();
		BlockPos start = origin.toLowerCase(Locale.ROOT).equals("random") ? random : parse(origin, random.getY());
		PRINCIPIA$LOGGER.info("Game tests start at {} (-Pgametest_origin=x,z or random to change)",
				start.toShortString());
		return start;
	}

	@Inject(method = "tickServer", at = @At("TAIL"))
	private void principia$exitWithoutSaving(BooleanSupplier haveTime, CallbackInfo ci) {
		MultipleTestTracker tracker = testTracker;
		if (tracker != null && tracker.isDone()) {
			PRINCIPIA$LOGGER.info("Game test server exiting without saving its world");
			System.exit(tracker.getFailedRequiredCount());
		}
	}

	private static BlockPos parse(String origin, int y) {
		String[] xz = origin.split(",", -1);
		if (xz.length != 2) {
			throw new IllegalArgumentException("principia.gametest.origin is 'x,z' or 'random', not '" + origin + "'");
		}
		return new BlockPos(Integer.parseInt(xz[0].trim()), y, Integer.parseInt(xz[1].trim()));
	}
}
