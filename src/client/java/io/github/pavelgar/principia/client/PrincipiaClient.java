package io.github.pavelgar.principia.client;

import io.github.pavelgar.principia.client.skyline.SkyLineClientHooks;

import net.fabricmc.api.ClientModInitializer;

public class PrincipiaClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		SkyLineClientHooks.register();
	}
}
