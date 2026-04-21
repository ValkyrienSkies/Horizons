package org.valkyrienskies.horizons.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.primitives.AABBd;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.horizons.api.devices.components.ISocket;
import org.valkyrienskies.horizons.api.devices.components.SocketedBlockEntity;
import org.valkyrienskies.horizons.api.foundation.mixin.SocketHitProvider;

import static org.valkyrienskies.horizons.content.client.HorizonsClient.SOCKET_RENDERER;

@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {

    @Shadow @Final private RenderBuffers renderBuffers;

    @Shadow @Final private Minecraft minecraft;

    @Shadow private @Nullable ClientLevel level;

    @Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", ordinal = 11), method = "renderLevel")
    private void render(PoseStack poseStack,
                        float partialTick, long finishNanoTime, boolean renderBlockOutline,
                        Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
                        Matrix4f projectionMatrix, CallbackInfo ci) {
        double camX =  camera.getPosition().x, camY = camera.getPosition().y, camZ = camera.getPosition().z;

        var hitResult = this.minecraft.hitResult;
        if (hitResult.getType() == HitResult.Type.BLOCK) {
            var bResult = ((BlockHitResult) hitResult);
            BlockEntity be = this.level.getBlockEntity(bResult.getBlockPos());

            if (be instanceof SocketedBlockEntity) {
                // Calculate the socket hit
                ISocket hit = null;
                for (ISocket socket : ((SocketedBlockEntity) be).getSockets()) {
                    if (socket.getBounds().translate(bResult.getBlockPos().getX(), bResult.getBlockPos().getY(), bResult.getBlockPos().getZ(), new AABBd()).containsPoint(bResult.getLocation().x, bResult.getLocation().y, bResult.getLocation().z)) {
                        hit = socket;
                        break;
                    }
                }

                ((SocketHitProvider) this.minecraft).setSocketHit(hit);

                poseStack.pushPose();

                SOCKET_RENDERER.render((SocketedBlockEntity) be, poseStack, bResult.getBlockPos(),
                        camX, camY, camZ, this.renderBuffers.bufferSource(), partialTick);

                poseStack.popPose();
            }
        }
    }
}
