package com.github.kazuofficial.blockexporter.mixin;

import net.minecraft.client.render.item.ItemRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemRenderState.class)
public interface ItemRenderStateAccessor {

	@Accessor("layers")
	ItemRenderState.LayerRenderState[] blockexporter$getLayers();
}
