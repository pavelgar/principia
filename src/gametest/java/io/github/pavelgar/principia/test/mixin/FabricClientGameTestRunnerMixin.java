package io.github.pavelgar.principia.test.mixin;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.impl.client.gametest.FabricClientGameTestRunner;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

/**
 * Runs only the client game tests {@code scripts/client-gametest.sh --only=<Class>,...} names, by their classes'
 * simple names, which it passes in {@code PRINCIPIA_CLIENT_TESTS}. Fabric's own filter goes by mod, and every
 * Principia client game test is in the one test mod.
 */
// The client game test API is marked unstable, and these are its types.
@SuppressWarnings("UnstableApiUsage")
@Mixin(FabricClientGameTestRunner.class)
abstract class FabricClientGameTestRunnerMixin {
	@ModifyReturnValue(method = "getTestToRun", at = @At("RETURN"))
	private static List<EntrypointContainer<FabricClientGameTest>> principia$onlyTheNamed(
			List<EntrypointContainer<FabricClientGameTest>> tests) {
		String only = System.getenv("PRINCIPIA_CLIENT_TESTS");
		if (only == null) {
			return tests;
		}
		Set<String> named = Arrays.stream(only.split(",")).map(String::trim).collect(Collectors.toSet());
		List<EntrypointContainer<FabricClientGameTest>> kept = tests.stream()
				.filter(test -> named.contains(simpleName(test)))
				.toList();
		Set<String> found = kept.stream().map(test -> simpleName(test)).collect(Collectors.toSet());
		if (!found.containsAll(named)) {
			throw new IllegalArgumentException("--only=" + only + " names no client game test of "
					+ tests.stream().map(test -> simpleName(test)).toList());
		}
		return kept;
	}

	private static String simpleName(EntrypointContainer<FabricClientGameTest> test) {
		String definition = test.getDefinition();
		return definition.substring(definition.lastIndexOf('.') + 1);
	}
}
