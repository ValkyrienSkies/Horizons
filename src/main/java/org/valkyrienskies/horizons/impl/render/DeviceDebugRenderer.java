package org.valkyrienskies.horizons.impl.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.valkyrienskies.horizons.api.devices.DeviceBlockEntity;
import org.valkyrienskies.horizons.api.devices.components.ISocket;
import org.valkyrienskies.horizons.api.devices.components.SocketTargeting;
import org.valkyrienskies.horizons.api.devices.components.SocketedBlockEntity;
import org.valkyrienskies.horizons.api.foundation.mixin.SocketHitProvider;
import org.valkyrienskies.horizons.content.client.HorizonsClient;

public final class DeviceDebugRenderer {
  public static void render(RenderLevelStageEvent event) {
    if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
    Minecraft mc = Minecraft.getInstance();
    ((SocketHitProvider) mc).setSocketHit(null);
    if (mc.level == null || mc.player == null) return;
    var pose = event.getPoseStack();
    var camera = event.getCamera().getPosition();
    var buffers = mc.renderBuffers().bufferSource();
    if (mc.hitResult instanceof BlockHitResult hit && mc.level.getBlockEntity(hit.getBlockPos()) instanceof SocketedBlockEntity device) {
      var be = mc.level.getBlockEntity(hit.getBlockPos());
      var eye = mc.player.getEyePosition(event.getPartialTick());
      double reach = mc.player.getBlockReach() + 0.01;
      var end = eye.add(mc.player.getViewVector(event.getPartialTick()).scale(reach));
      var target = SocketTargeting.findLocal(device, SocketRenderSpace.toLocal(be, eye), SocketRenderSpace.toLocal(be, end));
      ((SocketHitProvider) mc).setSocketHit(target);
      HorizonsClient.SOCKET_RENDERER.render(device, pose, hit.getBlockPos(), camera.x, camera.y, camera.z, buffers, event.getPartialTick());
    }
    pose.pushPose();
    var consumer = buffers.getBuffer(RenderType.lines());
    for (DeviceBlockEntity device : DeviceBlockEntity.getClientDevices()) {
      if (device.getLevel() != mc.level || device.isRemoved()) continue;
      for (ISocket socket : device.getSockets()) {
        for (ISocket other : socket.getConnectedSockets()) {
          int order = socket.getBlockPos().compareTo(other.getBlockPos());
          if (order > 0 || (order == 0 && socket.getPort() >= other.getPort())) continue;
          SocketRenderer.line(pose, consumer, SocketRenderSpace.toWorld(socket, socket.getPos()),
              SocketRenderSpace.toWorld(other, other.getPos()), 0.95f, 0.55f, 0.15f);
        }
      }
    }
    pose.popPose();
    buffers.endBatch(RenderType.lines());
  }
}
