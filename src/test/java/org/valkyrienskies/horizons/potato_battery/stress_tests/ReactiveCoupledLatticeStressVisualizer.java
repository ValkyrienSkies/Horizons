package org.valkyrienskies.horizons.potato_battery.stress_tests;

import org.valkyrienskies.horizons.potato_battery.CircuitComponents.CapacitorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.GroundNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.InductorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.JunctionNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.ResistorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.VariableCurrentSourceNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.VariableVoltageNode;
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
import java.util.List;

public final class ReactiveCoupledLatticeStressVisualizer {
  public static void main(String[] args) {
    CircuitVisualizer.launch(new ReactiveScenario());
  }

  private static final class ReactiveScenario implements Scenario {
    private static final double DT = 1.0 / 240.0;
    private static final double TWO_PI = Math.PI * 2.0;
    private static final int LANES = 4;
    private static final int STAGES = 5;

    private final VariableVoltageNode source = new VariableVoltageNode();
    private final VariableCurrentSourceNode inject = new VariableCurrentSourceNode();
    private final GroundNode ground = new GroundNode();
    private final JunctionNode[][] nodes = new JunctionNode[LANES][STAGES + 1];
    private final ResistorNode[][] resistors = new ResistorNode[LANES][STAGES];
    private final InductorNode[][] inductors = new InductorNode[LANES][STAGES];
    private final CapacitorNode[][] capacitors = new CapacitorNode[LANES][STAGES];
    private final ResistorNode[][] couplers = new ResistorNode[LANES - 1][STAGES];

    private volatile double amplitude = 8.0;
    private volatile double frequency = 2.5;
    private volatile double injection = 0.0;
    private final double[][] nodeVoltages = new double[LANES][STAGES + 1];
    private double sourceVoltage;
    private double centerVoltage;
    private double sourceCurrent;

    private ReactiveScenario() {
      for (int lane = 0; lane < LANES; lane++) {
        for (int stage = 0; stage <= STAGES; stage++) {
          nodes[lane][stage] = new JunctionNode(6);
          if (stage < STAGES) {
            resistors[lane][stage] = new ResistorNode(8.0 + lane * 2.0 + stage);
            inductors[lane][stage] = new InductorNode(0.020 + lane * 0.004 + stage * 0.002);
            capacitors[lane][stage] = new CapacitorNode(0.0012 + lane * 0.0002 + stage * 0.00015);
            if (lane + 1 < LANES) {
              couplers[lane][stage] = new ResistorNode(42.0 + lane * 8.0 + stage * 3.0);
            }
          }
        }
      }
    }

    @Override
    public String title() {
      return "Reactive Coupled Lattice Stress";
    }

    @Override
    public double timeStep() {
      return DT;
    }

    @Override
    public void build(CircuitBuilder b) {
      b.add(source).add(inject).add(ground);
      for (int lane = 0; lane < LANES; lane++) {
        for (int stage = 0; stage <= STAGES; stage++) {
          b.add(nodes[lane][stage]);
          if (stage < STAGES) {
            b.add(resistors[lane][stage]).add(inductors[lane][stage]).add(capacitors[lane][stage]);
            if (lane + 1 < LANES) {
              b.add(couplers[lane][stage]);
            }
          }
        }
      }

      b.connect(source, 1, ground, 0).connect(inject, 1, ground, 0);
      for (int lane = 0; lane < LANES; lane++) {
        b.connect(source, 0, nodes[lane][0], 0).connect(inject, 0, nodes[lane][0], 1);
        for (int stage = 0; stage < STAGES; stage++) {
          b.connect(nodes[lane][stage], 2, resistors[lane][stage], 0).connect(resistors[lane][stage], 1, nodes[lane][stage + 1], 0);
          b.connect(nodes[lane][stage], 3, inductors[lane][stage], 0).connect(inductors[lane][stage], 1, nodes[lane][stage + 1], 1);
          b.connect(nodes[lane][stage + 1], 2, capacitors[lane][stage], 0).connect(capacitors[lane][stage], 1, ground, 0);
          if (lane + 1 < LANES) {
            b.connect(nodes[lane][stage + 1], 3, couplers[lane][stage], 0).connect(couplers[lane][stage], 1, nodes[lane + 1][stage + 1], 4);
          }
        }
      }
    }

    @Override
    public void beforeStep(double time) {
      source.setVoltage(amplitude * Math.sin(TWO_PI * frequency * time));
      inject.setCurrent(injection);
    }

    @Override
    public void afterStep(double time, PowerNetworkServer network) {
      sourceVoltage = network.getVoltageAt(source, 0) - network.getVoltageAt(source, 1);
      centerVoltage = 0.0;
      for (int lane = 0; lane < LANES; lane++) {
        for (int stage = 0; stage <= STAGES; stage++) {
          nodeVoltages[lane][stage] = averageNodeVoltage(network, nodes[lane][stage]);
        }
        centerVoltage += nodeVoltages[lane][STAGES / 2];
      }
      centerVoltage /= LANES;
      sourceCurrent = Math.abs(network.getCurrentOver(source, nodes[0][0], 0, 0));
    }

    @Override
    public List<Trace> traces() {
      return List.of(
          new Trace("source", new Color(255, 208, 102), 12.0, () -> sourceVoltage),
          new Trace("lane0 mid", new Color(120, 220, 255), 12.0, () -> nodeVoltages[0][STAGES / 2]),
          new Trace("lane1 mid", new Color(172, 235, 149), 12.0, () -> nodeVoltages[1][STAGES / 2]),
          new Trace("lane2 end", new Color(255, 150, 120), 12.0, () -> nodeVoltages[2][STAGES]),
          new Trace("lane3 end", new Color(220, 160, 255), 12.0, () -> nodeVoltages[3][STAGES]),
          new Trace("source current", new Color(240, 240, 240), 0.35, () -> sourceCurrent)
      );
    }

    @Override
    public List<Readout> readouts() {
      return List.of(
          new Readout("Amp: %.2f V", () -> amplitude),
          new Readout("Freq: %.2f Hz", () -> frequency),
          new Readout("Inject: %+.3f A", () -> injection),
          new Readout("Center: %.2f V", () -> centerVoltage),
          new Readout("Source I: %.4f A", () -> sourceCurrent),
          new Readout("Lane4 End: %.2f V", () -> nodeVoltages[3][STAGES])
      );
    }

    @Override
    public void populateControls(JPanel controls) {
      JLabel ampLabel = CircuitVisualizer.createValueLabel(String.format("%.1f V", amplitude));
      JSlider ampSlider = new JSlider(0, 200, (int) Math.round(amplitude * 10.0));
      ampSlider.addChangeListener(event -> {
        amplitude = ampSlider.getValue() / 10.0;
        ampLabel.setText(String.format("%.1f V", amplitude));
      });
      controls.add(CircuitVisualizer.labeledControl("Amplitude", ampSlider, ampLabel));

      JLabel freqLabel = CircuitVisualizer.createValueLabel(String.format("%.1f Hz", frequency));
      JSlider freqSlider = new JSlider(1, 120, (int) Math.round(frequency * 10.0));
      freqSlider.addChangeListener(event -> {
        frequency = freqSlider.getValue() / 10.0;
        freqLabel.setText(String.format("%.1f Hz", frequency));
      });
      controls.add(CircuitVisualizer.labeledControl("Frequency", freqSlider, freqLabel));

      JLabel injectLabel = CircuitVisualizer.createValueLabel(String.format("%+.3f A", injection));
      JSlider injectSlider = new JSlider(-200, 200, (int) Math.round(injection * 1000.0));
      injectSlider.addChangeListener(event -> {
        injection = injectSlider.getValue() / 1000.0;
        injectLabel.setText(String.format("%+.3f A", injection));
      });
      controls.add(CircuitVisualizer.labeledControl("Injection", injectSlider, injectLabel));
    }

    @Override
    public void drawSchematic(Graphics2D g, int left, int top, int width, int height) {
      g.setColor(new Color(226, 230, 236));
      g.drawString("Four coupled reactive ladders with shared source drive, shunt capacitors, series inductors, and cross-lane resistive coupling.", left, top + 20);
      g.drawString("This stresses dynamic linear substepping and state propagation through a moderately dense transient network.", left, top + 42);
      int gridTop = top + 74;
      int stageWidth = width / (STAGES + 1);
      int laneHeight = 26;
      for (int lane = 0; lane < LANES; lane++) {
        for (int stage = 0; stage <= STAGES; stage++) {
          int x = left + stage * stageWidth + 10;
          int y = gridTop + lane * 34;
          int shade = 80 + (int) Math.round(Math.max(0.0, Math.min(1.0, Math.abs(nodeVoltages[lane][stage]) / Math.max(amplitude, 1.0))) * 150.0);
          g.setColor(new Color(shade, 120, 255 - (shade - 80)));
          g.fillRoundRect(x, y, stageWidth - 20, laneHeight, 10, 10);
        }
      }
    }

    private static double averageNodeVoltage(PowerNetworkServer network, JunctionNode node) {
      double total = 0.0;
      for (int port = 0; port < node.getPorts(); port++) {
        total += network.getVoltageAt(node, port);
      }
      return total / node.getPorts();
    }
  }
}
