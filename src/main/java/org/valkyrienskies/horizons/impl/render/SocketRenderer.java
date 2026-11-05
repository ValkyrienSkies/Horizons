package org.valkyrienskies.horizons.impl.render;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import org.joml.Vector3dc;
import org.joml.Vector3d;
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
    void render(PoseStack poseStack, VertexConsumer vc, ISocket socket, double partialTick);
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
    MultiBufferSource.BufferSource bufferSource,
    double partialTick
  ) {
    poseStack.pushPose();
    be.getSockets().forEach(it -> {
      render(it, poseStack, bufferSource, partialTick);
    });
    poseStack.popPose();
  }

  private void render(
    ISocket socket,
    PoseStack poseStack,
    MultiBufferSource.BufferSource bufferSource,
    double partialTick
  ) {
    try {
      this.rendererCache.get(socket, socket::createRenderer).render(poseStack, bufferSource.getBuffer(RenderType.lines()), socket, partialTick);
    } catch (Exception e) {
      throw new IllegalStateException("Unable to render socket " + socket, e);
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
    public void render(PoseStack poseStack, VertexConsumer vc, ISocket socket, double partialTick) {
      float r = SocketHitProvider.getHit() == socket ? 1f : 0.2f;
      float g = SocketHitProvider.getHit() == socket ? 0.85f : 0.9f;
      Vector3d[] points = new Vector3d[8];
      for (int i = 0; i < 8; i++) {
        points[i] = SocketRenderSpace.toWorld(socket, new Vector3d(
            (i & 1) == 0 ? bounds.minX() - 0.002 : bounds.maxX() + 0.002,
            (i & 2) == 0 ? bounds.minY() - 0.002 : bounds.maxY() + 0.002,
            (i & 4) == 0 ? bounds.minZ() - 0.002 : bounds.maxZ() + 0.002));
      }
      for (int i = 0; i < 8; i++) {
        for (int axis = 1; axis <= 4; axis <<= 1) {
          if ((i & axis) == 0) line(poseStack, vc, points[i], points[i | axis], r, g, 0.25f);
        }
      }
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
    public void render(PoseStack poseStack, VertexConsumer vc, ISocket socket, double partialTick) {
      for (int i = 0; i < corners.length; i++) {
        line(poseStack, vc, SocketRenderSpace.toWorld(socket, corners[i]),
            SocketRenderSpace.toWorld(socket, corners[(i + 1) % corners.length]), 0.2f, 0.9f, 0.25f);
      }
    }
  }

  public static void line(PoseStack poseStack, VertexConsumer consumer, Vector3dc from, Vector3dc to, float r, float g, float b) {
    Vector3d normal = new Vector3d(to).sub(from);
    if (normal.lengthSquared() < 1e-16) return;
    normal.normalize();
    var camera = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
    var pose = poseStack.last();
    consumer.vertex(pose.pose(), (float) (from.x() - camera.x), (float) (from.y() - camera.y), (float) (from.z() - camera.z)).color(r, g, b, 1f)
        .normal(pose.normal(), (float) normal.x, (float) normal.y, (float) normal.z).endVertex();
    consumer.vertex(pose.pose(), (float) (to.x() - camera.x), (float) (to.y() - camera.y), (float) (to.z() - camera.z)).color(r, g, b, 1f)
        .normal(pose.normal(), (float) normal.x, (float) normal.y, (float) normal.z).endVertex();
  }
}
