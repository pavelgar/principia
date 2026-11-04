package io.github.pavelgar.principia.test.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;

/** Reads what the action bar says, which vanilla keeps to itself, for client game tests to assert. */
@Mixin(Hud.class)
public interface HudAccessor {
	@Accessor("overlayMessageString")
	@Nullable
	Component principia$overlayMessage();
}
