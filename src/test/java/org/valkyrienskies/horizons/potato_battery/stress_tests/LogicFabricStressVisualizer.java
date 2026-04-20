package org.valkyrienskies.horizons.potato_battery.stress_tests;

import org.valkyrienskies.horizons.potato_battery.CircuitComponents.AndNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.ClockDividerNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.CounterNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.GroundNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.MuxNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.NandNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.NotNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.OscillatorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.SumClampNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.VariableVoltageNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.XorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitVisualizer;
import org.valkyrienskies.horizons.potato_battery.CircuitVisualizer.CircuitBuilder;
import org.valkyrienskies.horizons.potato_battery.CircuitVisualizer.Readout;
import org.valkyrienskies.horizons.potato_battery.CircuitVisualizer.Scenario;
import org.valkyrienskies.horizons.potato_battery.CircuitVisualizer.Trace;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import java.awt.Color;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;

public final class LogicFabricStressVisualizer {
  public static void main(String[] args) {
    CircuitVisualizer.launch(new LogicFabricScenario());
  }

  private static final class LogicFabricScenario implements Scenario {
    private static final double DT = 1.0 / 240.0;
    private static final double V = 5.0;
    private static final int LANES = 6;

    private final GroundNode ground = new GroundNode();
    private final VariableVoltageNode reset = new VariableVoltageNode();
    private final VariableVoltageNode enable = new VariableVoltageNode();
    private final OscillatorNode master = new OscillatorNode(96.0, 0.45, V);
    private final ClockDividerNode[] dividerA = new ClockDividerNode[LANES];
    private final ClockDividerNode[] dividerB = new ClockDividerNode[LANES];
    private final XorNode[] xor = new XorNode[LANES];
    private final AndNode[] gate = new AndNode[LANES];
    private final NandNode[] feedback = new NandNode[LANES];
    private final NotNode[] invert = new NotNode[LANES];
    private final CounterNode[] counter = new CounterNode[LANES];
    private final MuxNode[] mux = new MuxNode[LANES];
    private final SumClampNode aggregate = new SumClampNode(LANES, V);

    private final double[] laneVoltages = new double[LANES];
    private final double[] counterVoltages = new double[LANES];
    private volatile double enableLevel = V;
    private volatile boolean resetRequested;
    private double aggregateVoltage;

    private LogicFabricScenario() {
      for (int i = 0; i < LANES; i++) {
        dividerA[i] = new ClockDividerNode(2 + i, V);
        dividerB[i] = new ClockDividerNode(3 + i, V);
        xor[i] = new XorNode(2.5, V);
        gate[i] = new AndNode(2.5, V);
        feedback[i] = new NandNode(2.5, V);
        invert[i] = new NotNode(2.5, V);
        counter[i] = new CounterNode(7 + i * 2, V);
        mux[i] = new MuxNode();
      }
    }

    @Override
    public String title() {
      return "Logic Fabric Stress";
    }

    @Override
    public double timeStep() {
      return DT;
    }

    @Override
    public void build(CircuitBuilder b) {
      b.add(ground).add(reset).add(enable).add(master);
      for (int i = 0; i < LANES; i++) {
        b.add(dividerA[i]).add(dividerB[i]).add(xor[i]).add(gate[i]).add(feedback[i]).add(invert[i]).add(counter[i]).add(mux[i]);
      }
      b.add(aggregate);

      b.connect(reset, 1, ground, 0);
      b.connect(enable, 1, ground, 0);
      for (int i = 0; i < LANES; i++) {
        b.connect(master, 0, dividerA[i], 0).connect(reset, 0, dividerA[i], 1);
        b.connect(master, 0, dividerB[i], 0).connect(reset, 0, dividerB[i], 1);
        b.connect(dividerA[i], 2, xor[i], 0).connect(dividerB[i], 2, xor[i], 1);
        b.connect(xor[i], 2, gate[i], 0).connect(enable, 0, gate[i], 1);
        b.connect(gate[i], 2, feedback[i], 0).connect(dividerA[i], 2, feedback[i], 1);
        b.connect(feedback[i], 2, invert[i], 0);
        b.connect(invert[i], 1, counter[i], 0).connect(reset, 0, counter[i], 1);
        b.connect(gate[i], 2, mux[i], 0).connect(counter[i], 2, mux[i], 1).connect(dividerB[i], 2, mux[i], 2);
        b.connect(mux[i], 3, aggregate, i);
      }
    }

    @Override
    public void beforeStep(double time) {
      enable.setVoltage(enableLevel);
      reset.setVoltage(resetRequested ? V : 0.0);
      resetRequested = false;
    }

    @Override
    public void afterStep(double time, PowerNetworkServer network) {
      aggregateVoltage = network.getVoltageAt(aggregate, LANES);
      for (int i = 0; i < LANES; i++) {
        laneVoltages[i] = network.getVoltageAt(mux[i], 3);
        counterVoltages[i] = network.getVoltageAt(counter[i], 2);
      }
    }

    @Override
    public List<Trace> traces() {
      List<Trace> traces = new ArrayList<>();
      Color[] colors = {
          new Color(255, 208, 102), new Color(120, 220, 255), new Color(255, 150, 120),
          new Color(172, 235, 149), new Color(220, 160, 255), new Color(255, 120, 180)
      };
      for (int i = 0; i < LANES; i++) {
        final int lane = i;
        traces.add(new Trace("lane " + (i + 1), colors[i], V, () -> laneVoltages[lane]));
      }
      traces.add(new Trace("aggregate", new Color(240, 240, 240), V, () -> aggregateVoltage));
      return traces;
    }

    @Override
    public List<Readout> readouts() {
      return List.of(
          new Readout("Aggregate: %.2f V", () -> aggregateVoltage),
          new Readout("Enable: %.2f V", () -> enableLevel),
          new Readout("Lane 1 Cnt: %.2f V", () -> counterVoltages[0]),
          new Readout("Lane 2 Cnt: %.2f V", () -> counterVoltages[1]),
          new Readout("Lane 3 Cnt: %.2f V", () -> counterVoltages[2]),
          new Readout("Lane 4 Cnt: %.2f V", () -> counterVoltages[3])
      );
    }

    @Override
    public void populateControls(JPanel controls) {
      JLabel enableLabel = CircuitVisualizer.createValueLabel(String.format("%.1f V", enableLevel));
      JSlider enableSlider = new JSlider(0, 50, (int) Math.round(enableLevel * 10.0));
      enableSlider.addChangeListener(event -> {
        enableLevel = enableSlider.getValue() / 10.0;
        enableLabel.setText(String.format("%.1f V", enableLevel));
      });
      controls.add(CircuitVisualizer.labeledControl("Enable", enableSlider, enableLabel));

      JSlider resetButton = new JSlider(0, 1, 0);
      resetButton.addChangeListener(event -> {
        if (resetButton.getValue() > 0) {
          resetRequested = true;
          resetButton.setValue(0);
        }
      });
      controls.add(CircuitVisualizer.labeledControl("Reset Pulse", resetButton, CircuitVisualizer.createValueLabel("tap")));
    }

    @Override
    public void drawSchematic(Graphics2D g, int left, int top, int width, int height) {
      g.setColor(new Color(226, 230, 236));
      g.drawString("Six parallel logic lanes: divider pair -> xor -> gate -> nand/invert -> counter -> mux -> aggregate.", left, top + 20);
      g.drawString("This stresses behavioral primitives and fast state propagation without transistor-level device cost.", left, top + 42);
      int rowTop = top + 72;
      int laneWidth = width / LANES;
      for (int i = 0; i < LANES; i++) {
        int x = left + i * laneWidth + 10;
        int barHeight = (int) Math.round((laneVoltages[i] / V) * 86.0);
        g.setColor(new Color(46, 52, 64));
        g.fillRoundRect(x, rowTop, laneWidth - 22, 110, 12, 12);
        g.setColor(new Color(120 + i * 18, 180, 220 - i * 18));
        g.fillRoundRect(x + 8, rowTop + 98 - barHeight, laneWidth - 38, barHeight, 10, 10);
        g.setColor(new Color(226, 230, 236));
        g.drawString("Lane " + (i + 1), x + 8, rowTop + 16);
        g.drawString(String.format("%.2f V", laneVoltages[i]), x + 8, rowTop + 34);
      }
    }
  }
}
