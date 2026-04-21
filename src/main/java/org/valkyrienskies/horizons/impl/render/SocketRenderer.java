package org.valkyrienskies.horizons.impl.render;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import org.joml.Vector3dc;
import org.joml.primitives.AABBdc;
import org.valkyrienskies.horizons.api.devices.components.ISocket;
import org.valkyrienskies.horizons.api.devices.components.SocketedBlockEntity;
import org.valkyrienskies.horizons.api.foundation.mixin.SocketHitProvider;

public class SocketRenderer {
  private final Cache<ISocket, ISocketRenderer> rendererCache = CacheBuilder.newBuilder()
      .maximumSize(100)
      .build();


  public ISocketRenderer getRenderer(ISocket socket) {
    return rendererCache.getIfPresent(socket);
  }

  public interface ISocketRenderer {
    void render(PoseStack poseStack, VertexConsumer vc, ISocket socket);
  }

  public boolean isHighlighted(ISocket socket) {
    return SocketHitProvider.getHit() == socket;
  }

  public void render(
    SocketedBlockEntity be,
    PoseStack poseStack,
    BlockPos pos,
    double camX,
    double camY,
    double camZ,
    MultiBufferSource.BufferSource bufferSource
  ) {
    poseStack.translate(-camX + (double)pos.getX(), -camY + (double)pos.getY(), -camZ + (double)pos.getZ());
    be.getSockets().forEach(it -> {
      render(it, poseStack, bufferSource);
    });
  }

  private void render(
    ISocket socket,
    PoseStack poseStack,
    MultiBufferSource.BufferSource bufferSource
  ) {
    try {
      this.rendererCache.get(socket, socket::createRenderer).render(poseStack, bufferSource.getBuffer(RenderType.cutout()), socket);
    } catch (Exception e) {
      //do nothing?
    }
  }

  public static class AABBSocketRenderer implements ISocketRenderer {
    private final AABBdc bounds;

    public AABBSocketRenderer(AABBdc bounds) {
      this.bounds = bounds;
    }

    public AABBdc getBounds() {
      return bounds;
    }

    @Override
    public void render(PoseStack poseStack, VertexConsumer vc, ISocket socket) {

    }
  }

  public static class PlaneSocketRenderer implements ISocketRenderer {
    private final Vector3dc normal;
    private final Vector3dc[] corners;

    public PlaneSocketRenderer(Vector3dc normal, Vector3dc... corners) {
      this.normal = normal;
      this.corners = corners;
    }

    public Vector3dc[] getCorners() {
      return corners;
    }
    public Vector3dc getNormal() {
      return normal;
    }

    @Override
    public void render(PoseStack poseStack, VertexConsumer vc, ISocket socket) {

    }
  }
}
