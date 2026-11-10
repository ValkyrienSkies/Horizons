package org.valkyrienskies.horizons.potato_battery.api.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.valkyrienskies.horizons.content.circuit.simulation.CircuitBoardNode;
import org.valkyrienskies.horizons.potato_battery.api.network.node.IPowerNode;
import org.valkyrienskies.horizons.potato_battery.impl.nodes.ResistorNode;
import org.valkyrienskies.horizons.potato_battery.impl.nodes.SwitchNode;
import org.valkyrienskies.horizons.potato_battery.impl.nodes.VoltageSourceNode;
import org.valkyrienskies.horizons.potato_battery.impl.nodes.AmmeterNode;
import org.valkyrienskies.horizons.potato_battery.impl.nodes.FiniteBatteryNode;
import org.valkyrienskies.horizons.potato_battery.impl.nodes.ACVoltageSourceNode;
import org.valkyrienskies.horizons.potato_battery.impl.nodes.BatterySocketNode;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Register codecs during mod initialization. Unknown types remain archived without losing their payload. */
public final class PowerNodeTypes {
  private static final Map<ResourceLocation, PowerNodeType<?>> BY_ID = new HashMap<>();
  private static final Map<Class<?>, PowerNodeType<?>> BY_CLASS = new HashMap<>();
  static {
    builtin("circuit_board", CircuitBoardNode.class, CircuitBoardNode::readState,
        (node, tag) -> tag.merge(node.writeState()));
    builtin("battery_socket", BatterySocketNode.class, tag -> {
      BatterySocketNode node = new BatterySocketNode();
      if (tag.contains("Cell")) {
        IPowerNode cell = decode(tag.getCompound("Cell"));
        if (!(cell instanceof FiniteBatteryNode battery)) throw new IllegalArgumentException("Invalid socket cell");
        node.retain(tag.getString("CellId"), battery);
      }
      return node;
    }, (node, tag) -> {
      if (node.getBattery() != null) {
        tag.put("Cell", encode(node.getBattery()));
        tag.putString("CellId", node.getBatteryId());
      }
    });
    builtin("ac_voltage_source", ACVoltageSourceNode.class,
        tag -> new ACVoltageSourceNode(tag.getDouble("PeakVoltage"), tag.getDouble("FrequencyHz"), tag.getDouble("PhaseCycles")),
        (node, tag) -> {
          tag.putDouble("PeakVoltage", node.getPeakVoltage());
          tag.putDouble("FrequencyHz", node.getFrequencyHz());
          tag.putDouble("PhaseCycles", node.getPhaseCycles());
        });
    builtin("finite_battery", FiniteBatteryNode.class,
        tag -> new FiniteBatteryNode(tag.getDouble("MaxVoltage"), tag.getDouble("MaxCurrent"),
            tag.getDouble("MaxEnergyJoules"), tag.getDouble("StateOfCharge")),
        (node, tag) -> {
          tag.putDouble("MaxVoltage", node.getMaxVoltage());
          tag.putDouble("MaxCurrent", node.getMaxCurrent());
          tag.putDouble("MaxEnergyJoules", node.getMaxEnergyJoules());
          tag.putDouble("StateOfCharge", node.getStateOfCharge());
        });
    builtin("ammeter", AmmeterNode.class, tag -> new AmmeterNode(), (node, tag) -> {});
    builtin("resistor", ResistorNode.class, tag -> new ResistorNode(tag.getDouble("Resistance")),
        (node, tag) -> tag.putDouble("Resistance", node.getResistance()));
    builtin("voltage_source", VoltageSourceNode.class, tag -> new VoltageSourceNode(tag.getDouble("Voltage")),
        (node, tag) -> tag.putDouble("Voltage", node.getVoltage()));
    builtin("switch", SwitchNode.class, tag -> new SwitchNode(tag.getBoolean("Closed")),
        (node, tag) -> tag.putBoolean("Closed", node.isClosed()));
  }
  private PowerNodeTypes() {}

  private static <T extends IPowerNode> void builtin(String name, Class<T> type,
      Function<CompoundTag, T> reader, BiConsumer<T, CompoundTag> writer) {
    register(new PowerNodeType<>(ResourceLocation.fromNamespaceAndPath("potato_battery", name), type, 1,
        (version, tag) -> {
          if (version != 1) throw new IllegalArgumentException("Unsupported " + name + " version " + version);
          return reader.apply(tag);
        }, writer));
  }

  public static synchronized <T extends IPowerNode> PowerNodeType<T> register(PowerNodeType<T> type) {
    if (BY_ID.containsKey(type.id()) || BY_CLASS.containsKey(type.nodeClass())) {
      throw new IllegalArgumentException("Duplicate power node type " + type.id());
    }
    BY_ID.put(type.id(), type);
    BY_CLASS.put(type.nodeClass(), type);
    return type;
  }

  public static synchronized CompoundTag encode(IPowerNode node) {
    PowerNodeType<?> type = BY_CLASS.get(node.getClass());
    if (type == null) throw new IllegalArgumentException("No power node codec for " + node.getClass().getName());
    return type.encode(node);
  }

  public static synchronized @Nullable IPowerNode decode(CompoundTag tag) {
    ResourceLocation id = ResourceLocation.tryParse(tag.getString("Type"));
    PowerNodeType<?> type = BY_ID.get(id);
    return type == null ? null : type.decode(tag);
  }
}
