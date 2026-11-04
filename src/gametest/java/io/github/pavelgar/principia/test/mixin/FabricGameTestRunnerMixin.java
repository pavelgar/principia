package io.github.pavelgar.principia.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.fabricmc.fabric.impl.gametest.FabricGameTestRunner;

/**
 * Runs each server game test {@code principia.gametest.repeat} times ({@code -Pgametest_repeat=N}), unrotated, to find
 * flaky tests. Fabric API leaves the count vanilla's game test server takes at 0 ("TODO add an option for this?").
 * Vanilla's verify mode ({@code -Pgametest_verify}) also rotates each test, which only tests written to turn with
 * their structure survive.
 */
@Mixin(FabricGameTestRunner.class)
abstract class FabricGameTestRunnerMixin {
	@ModifyVariable(method = "runHeadlessServer", at = @At("STORE"), name = "repeat")
	private static int principia$repeat(int repeat) {
		return Integer.getInteger("principia.gametest.repeat", repeat);
	}
}
