package org.valkyrienskies.horizons.potato_battery;

import org.valkyrienskies.horizons.potato_battery.CircuitComponents.CapacitorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.GroundNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.InductorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.ResistorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.VariableVoltageNode;
import org.valkyrienskies.horizons.potato_battery.CircuitVisualizer.CircuitBuilder;
import org.valkyrienskies.horizons.potato_battery.CircuitVisualizer.Readout;
import org.valkyrienskies.horizons.potato_battery.CircuitVisualizer.Scenario;
import org.valkyrienskies.horizons.potato_battery.CircuitVisualizer.Trace;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.util.List;

public final class RLCCircuitVisualizer {
  public static void main(String[] args) {
    CircuitVisualizer.launch(new RLCScenario());
  }

  private static final class RLCScenario implements Scenario {
    private static final double TIME_STEP = 1.0 / 240.0;

    private final VariableVoltageNode source = new VariableVoltageNode();
    private final ResistorNode resistor = new ResistorNode(12.0);
    private final InductorNode inductor = new InductorNode(0.060);
    private final CapacitorNode capacitor = new CapacitorNode(0.0015);
    private final GroundNode ground = new GroundNode();

    private double sourceVoltage;
    private double loopCurrent;
    private double capacitorVoltage;
    private double inductorVoltage;

    @Override
    public String title() {
      return "RLC Circuit Visualizer";
    }

    @Override
    public double timeStep() {
      return TIME_STEP;
    }

    @Override
    public void build(CircuitBuilder b) {
      b.add(source).add(resistor).add(inductor).add(capacitor).add(ground)
          .connect(source, 0, resistor, 0)
          .connect(resistor, 1, inductor, 0)
          .connect(inductor, 1, capacitor, 0)
          .connect(capacitor, 1, ground, 0)
          .connect(source, 1, ground, 0);
    }

    @Override
    public void beforeStep(double time) {
      source.setVoltage(10.0 * Math.sin(time * Math.PI * 2.0 * 3.0));
    }

    @Override
    public void afterStep(double time, PowerNetworkServer network) {
      sourceVoltage = network.getVoltageAt(source, 0) - network.getVoltageAt(source, 1);
      loopCurrent = network.getCurrentOver(source, resistor, 0, 0);
      capacitorVoltage = network.getVoltageAt(capacitor, 0) - network.getVoltageAt(capacitor, 1);
      inductorVoltage = network.getVoltageAt(inductor, 0) - network.getVoltageAt(inductor, 1);
    }

    @Override
    public List<Trace> traces() {
      return List.of(
          new Trace("yellow: source voltage", new Color(255, 208, 102), 12.0, () -> sourceVoltage),
          new Trace("green: capacitor voltage", new Color(172, 235, 149), 12.0, () -> capacitorVoltage),
          new Trace("orange: inductor voltage", new Color(255, 150, 120), 12.0, () -> inductorVoltage),
          new Trace("blue: loop current", new Color(120, 200, 255), 1.5, () -> loopCurrent)
      );
    }

    @Override
    public List<Readout> readouts() {
      return List.of(
          new Readout("Source: %.3f V", () -> sourceVoltage),
          new Readout("Capacitor: %.3f V", () -> capacitorVoltage),
          new Readout("Loop current: %.6f A", () -> loopCurrent),
          new Readout("Inductor: %.3f V", () -> inductorVoltage)
      );
    }

    @Override
    public void drawSchematic(Graphics2D g, int left, int top, int width, int height) {
      int y = top + 70;
      int x0 = left;
      int x1 = left + 170;
      int x2 = left + 350;
      int x3 = left + 530;
      int x4 = left + 710;

      g.setStroke(new BasicStroke(3f));
      g.setColor(new Color(200, 205, 214));
      g.drawLine(x0, y, x1 - 36, y);
      g.drawLine(x1 + 36, y, x2 - 40, y);
      g.drawLine(x2 + 40, y, x3 - 40, y);
      g.drawLine(x3 + 40, y, x4, y);
      g.drawLine(x4, y, x4, y + 90);
      g.drawLine(x4, y + 90, x0, y + 90);
      g.drawLine(x0, y + 90, x0, y);

      g.setColor(new Color(255, 208, 102));
      g.drawLine(x0 - 10, y - 20, x0 - 10, y + 20);
      g.drawLine(x0 + 10, y - 32, x0 + 10, y + 32);
      g.drawString("AC Source", x0 - 28, y - 42);

      g.setColor(new Color(120, 200, 255));
      for (int i = -2; i <= 2; i++) {
        g.drawLine(x1 - 30 + i * 12, y - 18, x1 - 18 + i * 12, y + 18);
      }
      g.drawString("R", x1 - 4, y - 28);
      g.drawString(String.format("%.1f V", sourceVoltage - inductorVoltage - capacitorVoltage), x1 - 24, y + 44);

      g.setColor(new Color(255, 150, 120));
      for (int i = 0; i < 4; i++) {
        g.drawArc(x2 - 34 + i * 18, y - 18, 36, 36, 0, 180);
      }
      g.drawString("L", x2, y - 28);
      g.drawString(String.format("%.2f V", inductorVoltage), x2 - 18, y + 44);

      g.setColor(new Color(172, 235, 149));
      g.drawLine(x3 - 12, y - 28, x3 - 12, y + 28);
      g.drawLine(x3 + 12, y - 28, x3 + 12, y + 28);
      g.drawString("C", x3 - 4, y - 40);
      g.drawString(String.format("%.2f V", capacitorVoltage), x3 - 18, y + 44);

      g.setColor(new Color(200, 205, 214));
      g.drawString(String.format("I = %.5f A", loopCurrent), x4 - 28, y - 28);
    }
  }
}
