package io.github.pavelgar.principia;

import io.github.pavelgar.principia.content.PrincipiaCreativeTab;
import io.github.pavelgar.principia.skyline.SkyLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.Identifier;

import net.fabricmc.api.ModInitializer;

public class Principia implements ModInitializer {
	public static final String MOD_ID = "principia";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Feature classes register their content in static initializers; give each an empty initialize() method and
		// call it here to load the class. Their order here is the creative tab's order, so the tab comes last.
		SkyLine.initialize();
		PrincipiaCreativeTab.initialize();
		LOGGER.info("Principia initialized");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
