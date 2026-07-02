package com.github.kazuofficial.blockexporter;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class BlockExporterClient implements ClientModInitializer {
	private static KeyMapping exportKeybind;

	@Override
	public void onInitializeClient() {
		exportKeybind = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.blockexporter.export",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_I,
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath("blockexporter", "category"))
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (exportKeybind.consumeClick()) {
				if (client.screen == null) {
					client.setScreen(new ExportScreen());
				}
			}
		});
	}
}
