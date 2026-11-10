package org.valkyrienskies.horizons.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.horizons.content.client.PDADebugWires;

/** Captures the held model's real transform, including hand animations and baked display transforms. */
@Mixin(ItemRenderer.class)
public abstract class MixinItemRenderer {
  @Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V", ordinal = 0))
  private void horizons$capturePdaAnchor(ItemStack stack, ItemDisplayContext context, boolean leftHand,
      PoseStack pose, MultiBufferSource buffers, int light, int overlay, BakedModel model, CallbackInfo ci) {
    PDADebugWires.capture(stack, context, leftHand, pose);
  }
}
