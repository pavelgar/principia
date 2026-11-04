package io.github.pavelgar.principia.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.pavelgar.principia.Principia;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Records registrations that Principia's own code makes: into any registry, and of network payload types. Fed by the
 * mixins in {@code io.github.pavelgar.principia.test.mixin}, read by {@link RegistrationGameTest}.
 *
 * <p>Several vanilla helpers quietly put an id in the {@code minecraft} namespace ({@code Registry.register} with a
 * plain string, {@code RecipeType.register}, {@code CustomPacketPayload.createType}), so the namespace alone can't
 * say whether Principia made a registration. The call stack can.
 */
public final class RegistrationRecorder {
	private static final String PRINCIPIA_PACKAGE = "io.github.pavelgar.principia.";
	private static final String TEST_PACKAGE = PRINCIPIA_PACKAGE + "test.";
	// Principia's registration calls sit a few frames above the registry; vanilla's own go much deeper.
	private static final int MAX_FRAMES = 32;
	private static final StackWalker WALKER = StackWalker.getInstance();

	private static int principiaCount;
	private static final List<String> foreign = new ArrayList<>();

	private RegistrationRecorder() {
	}

	/** Called on every registration; {@code what} names the registry or payload direction, for the report. */
	public static synchronized void record(String what, Identifier id) {
		if (id.getNamespace().equals(Principia.MOD_ID)) {
			principiaCount++;
		} else if (!isAnotherModsRegistry(what, id)) {
			callerInPrincipia().ifPresent(caller -> foreign.add(what + " " + id + " registered by " + caller));
		}
	}

	/** How many registrations used the {@code principia} namespace. Zero means the recording mixins don't apply. */
	public static synchronized int principiaCount() {
		return principiaCount;
	}

	/** Registrations made by Principia code under another namespace. */
	public static synchronized List<String> foreign() {
		return List.copyOf(foreign);
	}

	/**
	 * Whether a registration is of a new registry named after another loaded mod, which is that mod's own: Fabric API
	 * makes some of its registries on first use, such as its entity data serializers' when Principia registers one.
	 */
	private static boolean isAnotherModsRegistry(String what, Identifier id) {
		return what.equals(Registries.ROOT_REGISTRY_NAME.toString()) && !id.getNamespace().equals("minecraft")
				&& FabricLoader.getInstance().isModLoaded(id.getNamespace());
	}

	/**
	 * The Principia method that caused this registration, if any. A static initializer of a class outside Principia
	 * on the way up means vanilla or another mod registers its own content, merely triggered by our code loading it.
	 */
	private static Optional<String> callerInPrincipia() {
		return WALKER.walk(frames -> frames.limit(MAX_FRAMES)
				.takeWhile(frame -> !(frame.getMethodName().equals("<clinit>") && !isPrincipia(frame.getClassName())))
				.filter(frame -> isPrincipia(frame.getClassName()))
				.findFirst()
				.map(frame -> frame.getClassName() + "." + frame.getMethodName()));
	}

	private static boolean isPrincipia(String className) {
		return className.startsWith(PRINCIPIA_PACKAGE) && !className.startsWith(TEST_PACKAGE);
	}
}
