package com.github.kazuofficial.blockexporter.mixin;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemStackRenderState.class)
public interface ItemRenderStateAccessor {

	@Accessor("layers")
	ItemStackRenderState.LayerRenderState[] blockexporter$getLayers();
}
