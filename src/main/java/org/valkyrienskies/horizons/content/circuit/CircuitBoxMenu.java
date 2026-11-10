package org.valkyrienskies.horizons.content.circuit;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.valkyrienskies.horizons.content.HorizonsNetworking;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CircuitBoxMenu extends AbstractContainerMenu {
  public static final List<String> TYPES = List.of("resistor", "capacitor", "nmos", "and_gate", "or_gate");
  public final CircuitBoxBlockEntity box;
  private final Player player;
  private ItemStack previousPaper;
  private boolean paperEdited;
  private long lastEditTick = Long.MIN_VALUE;
  private int editsThisTick;

  public CircuitBoxMenu(int id, Inventory inventory, BlockPos pos) {
    this(id, inventory, requireBox(inventory, pos));
  }

  private static CircuitBoxBlockEntity requireBox(Inventory inventory, BlockPos pos) {
    if (inventory.player.level().getBlockEntity(pos) instanceof CircuitBoxBlockEntity box) return box;
    throw new IllegalArgumentException("Missing circuit box");
  }

  public CircuitBoxMenu(int id, Inventory inventory, CircuitBoxBlockEntity box) {
    super(CircuitContent.MENU.get(), id);
    this.box = box;
    player = inventory.player;
    previousPaper = box.paper.getItem(0).copy();
    addSlot(new Slot(box.paper, 0, 310, 100) {
      @Override public boolean mayPlace(ItemStack stack) { return stack.is(Items.PAPER) || stack.is(CircuitContent.SCHEMATIC.get()); }
      @Override public int getMaxStackSize() { return 1; }
      @Override public void setChanged() {
        super.setChanged();
        paperEdited = true;
      }
    });
    for (int row = 0; row < 3; row++) {
      for (int column = 0; column < 9; column++) addSlot(new Slot(inventory, column + row * 9 + 9, 87 + column * 18, 260 + row * 18));
    }
    for (int column = 0; column < 9; column++) addSlot(new Slot(inventory, column, 87 + column * 18, 318));
  }

  public CircuitDesign design() { return box.getDesign(); }

  public static Item componentItem(String type) {
    return switch (type) {
      case "resistor" -> CircuitContent.RESISTOR.get();
      case "capacitor" -> CircuitContent.CAPACITOR.get();
      case "nmos" -> CircuitContent.NMOS.get();
      case "and_gate" -> CircuitContent.AND.get();
      case "or_gate" -> CircuitContent.OR.get();
      default -> throw new IllegalArgumentException("Unknown circuit component");
    };
  }

  public static String componentType(ItemStack stack) {
    for (String type : TYPES) if (stack.is(componentItem(type))) return type;
    return null;
  }

  @Override public boolean stillValid(Player player) {
    return !box.isRemoved() && box.getLevel() == player.level()
        && player.level().getBlockEntity(box.getBlockPos()) == box
        && player.distanceToSqr(box.getBlockPos().getX() + 0.5, box.getBlockPos().getY() + 0.5, box.getBlockPos().getZ() + 0.5) <= 64;
  }

  @Override public void broadcastChanges() {
    if (!player.level().isClientSide && stillValid(player)) {
      if (paperEdited && !ItemStack.matches(previousPaper, box.paper.getItem(0))) {
        ItemStack paper = box.paper.getItem(0);
        if (paper.is(Items.PAPER)) printSchematic();
        else if (paper.is(CircuitContent.SCHEMATIC.get())) applySchematic();
      }
      paperEdited = false;
      previousPaper = box.paper.getItem(0).copy();
    }
    super.broadcastChanges();
  }

  public void edit(int action, CompoundTag data) {
    if (player.level().isClientSide || player.isSpectator() || !stillValid(player)) return;
    if (!data.contains("Revision", 3) || data.getInt("Revision") != design().hashCode()) {
      error("message.horizons.circuit.changed");
      box.syncDevice();
      return;
    }
    long tick = player.level().getGameTime();
    if (tick != lastEditTick) { lastEditTick = tick; editsThisTick = 0; }
    if (++editsThisTick > 16) return;
    try {
      switch (action) {
        case 0 -> place(new CircuitDesign.Placed(data.getString("Type"), data.getInt("X"), data.getInt("Y"), data.getInt("Rotation")));
        case 1 -> removeComponent(data.getInt("Index"));
        case 2 -> box.setDesign(design().withWire(new CircuitDesign.Wire(
            new CircuitDesign.Point(data.getInt("FromX"), data.getInt("FromY")),
            new CircuitDesign.Point(data.getInt("ToX"), data.getInt("ToY")), data.getInt("Color"))));
        case 3 -> box.setDesign(design().withoutWire(data.getInt("Index")));
        case 4 -> printSchematic();
        case 5 -> applySchematic();
        default -> throw new IllegalArgumentException("Unknown circuit action");
      }
    } catch (IllegalArgumentException | IndexOutOfBoundsException invalid) {
      error("message.horizons.circuit.invalid");
    }
    previousPaper = box.paper.getItem(0).copy();
    broadcastChanges();
  }

  private void place(CircuitDesign.Placed placed) {
    CircuitDesign next = design().withComponent(placed);
    if (!player.getAbilities().instabuild && count(placed.type()) == 0) {
      error("message.horizons.circuit.missing", componentItem(placed.type()).getDescription());
      return;
    }
    box.setDesign(next);
    if (!player.getAbilities().instabuild) consume(placed.type(), 1);
  }

  private void removeComponent(int index) {
    CircuitDesign.Placed removed = design().components().get(index);
    box.setDesign(design().withoutComponent(index));
    if (!player.getAbilities().instabuild) refund(removed.type(), 1);
  }

  private int count(String type) {
    int count = 0;
    for (int index = 0; index < player.getInventory().getContainerSize(); index++) {
      ItemStack stack = player.getInventory().getItem(index);
      if (stack.is(componentItem(type))) count += stack.getCount();
    }
    return count;
  }

  private void consume(String type, int count) {
    for (int index = 0; index < player.getInventory().getContainerSize(); index++) {
      ItemStack stack = player.getInventory().getItem(index);
      if (!stack.is(componentItem(type))) continue;
      int taken = Math.min(count, stack.getCount());
      stack.shrink(taken);
      count -= taken;
      if (count == 0) break;
    }
    player.getInventory().setChanged();
  }

  private void refund(String type, int count) {
    while (count > 0) {
      int amount = Math.min(64, count);
      ItemStack stack = new ItemStack(componentItem(type), amount);
      if (!player.getInventory().add(stack)) player.drop(stack, false);
      count -= amount;
    }
    player.getInventory().setChanged();
  }

  private void printSchematic() {
    if (!box.paper.getItem(0).is(Items.PAPER)) { error("message.horizons.circuit.paper"); return; }
    ItemStack schematic = new ItemStack(CircuitContent.SCHEMATIC.get());
    schematic.getOrCreateTag().put("Design", design().write());
    box.paper.setItem(0, schematic);
    feedback(Component.translatable("message.horizons.circuit.printed"));
  }

  private void applySchematic() {
    ItemStack schematic = box.paper.getItem(0);
    if (!schematic.is(CircuitContent.SCHEMATIC.get()) || schematic.getTag() == null || !schematic.getTag().contains("Design", 10)) {
      error("message.horizons.circuit.schematic");
      return;
    }
    CircuitDesign next;
    try { next = CircuitDesign.read(schematic.getTag().getCompound("Design")); }
    catch (IllegalArgumentException invalid) { error("message.horizons.circuit.schematic"); return; }
    Map<String, Integer> balance = new LinkedHashMap<>();
    for (String type : TYPES) balance.put(type, 0);
    for (CircuitDesign.Placed placed : next.components()) balance.merge(placed.type(), 1, Integer::sum);
    for (CircuitDesign.Placed placed : design().components()) balance.merge(placed.type(), -1, Integer::sum);
    if (!player.getAbilities().instabuild) {
      for (var entry : balance.entrySet()) {
        int missing = entry.getValue() - count(entry.getKey());
        if (missing > 0) {
          error("message.horizons.circuit.missing_count", missing, componentItem(entry.getKey()).getDescription());
          return;
        }
      }
    }
    box.setDesign(next);
    if (!player.getAbilities().instabuild) {
      balance.forEach((type, needed) -> { if (needed > 0) consume(type, needed); });
      balance.forEach((type, needed) -> { if (needed < 0) refund(type, -needed); });
    }
    feedback(Component.translatable("message.horizons.circuit.applied"));
  }

  private void error(String key, Object... args) {
    feedback(Component.translatable(key, args).withStyle(net.minecraft.ChatFormatting.RED));
  }

  private void feedback(Component message) {
    player.displayClientMessage(message, false);
    if (player instanceof ServerPlayer server) HorizonsNetworking.sendToClient(new CircuitFeedbackPacket(containerId, message), server);
  }

  @Override public ItemStack quickMoveStack(Player player, int index) {
    if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
    Slot slot = slots.get(index);
    if (!slot.hasItem()) return ItemStack.EMPTY;
    ItemStack stack = slot.getItem();
    ItemStack original = stack.copy();
    if (index == 0) {
      if (!moveItemStackTo(stack, 1, slots.size(), true)) return ItemStack.EMPTY;
    } else if (slots.get(0).mayPlace(stack)) {
      if (!moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
    } else if (index < 28) {
      if (!moveItemStackTo(stack, 28, 37, false)) return ItemStack.EMPTY;
    } else if (!moveItemStackTo(stack, 1, 28, false)) return ItemStack.EMPTY;
    if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
    else slot.setChanged();
    slot.onTake(player, stack);
    return original;
  }
}
