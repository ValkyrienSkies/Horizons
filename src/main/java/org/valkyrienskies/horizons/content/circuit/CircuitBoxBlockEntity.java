package org.valkyrienskies.horizons.content.circuit;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.primitives.AABBd;
import org.valkyrienskies.horizons.api.devices.DeviceBlockEntity;
import org.valkyrienskies.horizons.api.devices.PowerNetworks;
import org.valkyrienskies.horizons.api.devices.components.ISocket;
import org.valkyrienskies.horizons.impl.devices.components.StandardSocket;
import org.valkyrienskies.horizons.potato_battery.api.network.node.IPowerNode;
import org.valkyrienskies.horizons.content.circuit.simulation.CircuitBoardNode;
import java.util.ArrayList;
import java.util.List;

public final class CircuitBoxBlockEntity extends DeviceBlockEntity implements MenuProvider {
  private CircuitDesign design = CircuitDesign.empty();
  public final SimpleContainer paper = new SimpleContainer(1) {
    @Override public void setChanged() {
      super.setChanged();
      CircuitBoxBlockEntity.this.syncDevice();
    }
    @Override public int getMaxStackSize() { return 1; }
  };

  public CircuitBoxBlockEntity(BlockPos pos, BlockState state) { super(CircuitContent.ENTITY.get(), pos, state); }

  public CircuitDesign getDesign() { return design; }

  public void setDesign(CircuitDesign next) {
    if (level == null || level.isClientSide) return;
    loadDevice();
    PowerNetworks.get(level).withState(() -> {
      ((CircuitBoardNode) getPowerNode()).setDesign(next);
      return null;
    });
    design = next;
    syncDevice();
  }

  @Override public IPowerNode createPowerNode() { return new CircuitBoardNode(design); }

  @Override public void onLoad() {
    super.onLoad();
    if (getPowerNode() instanceof CircuitBoardNode board) {
      design = board.getDesign();
      syncDevice();
    }
  }

  @Override public List<ISocket> createSockets() {
    double yaw = switch (getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING)) {
      case EAST -> -Math.PI / 2;
      case SOUTH -> Math.PI;
      case WEST -> Math.PI / 2;
      default -> 0;
    };
    Matrix4d transform = new Matrix4d().translation(0.5, 0, 0.5).rotateY(yaw).translate(-0.5, 0, -0.5);
    List<ISocket> sockets = new ArrayList<>();
    for (int port = 0; port < 8; port++) {
      double centerX = (3.5 + 3 * (port % 4)) / 16;
      double centerY = port < 4 ? 1 : 0;
      Vector3d center = transform.transformPosition(new Vector3d(centerX, centerY, 13.5 / 16));
      Vector3d normal = transform.transformDirection(new Vector3d(0, port < 4 ? 1 : -1, 0));
      AABBd bounds = new AABBd(centerX - 1.0 / 16, port < 4 ? 15.0 / 16 : 0, 12.5 / 16,
          centerX + 1.0 / 16, port < 4 ? 1 : 1.0 / 16, 14.5 / 16).transform(transform);
      sockets.add(new StandardSocket(port, Integer.MAX_VALUE, this, center, normal, bounds));
    }
    return sockets;
  }

  private void writeContents(CompoundTag tag) {
    tag.put("Design", design.write());
    tag.put("Paper", paper.getItem(0).save(new CompoundTag()));
  }

  @Override protected void saveAdditional(CompoundTag tag) {
    super.saveAdditional(tag);
    writeContents(tag);
  }

  @Override public void load(CompoundTag tag) {
    super.load(tag);
    design = tag.contains("Design") ? CircuitDesign.read(tag.getCompound("Design")) : CircuitDesign.empty();
    paper.setItem(0, ItemStack.of(tag.getCompound("Paper")));
  }

  @Override public CompoundTag getUpdateTag() {
    CompoundTag tag = super.getUpdateTag();
    writeContents(tag);
    return tag;
  }

  @Override public Component getDisplayName() { return Component.translatable("block.horizons.circuit_box"); }
  @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) { return new CircuitBoxMenu(id, inventory, this); }
}
