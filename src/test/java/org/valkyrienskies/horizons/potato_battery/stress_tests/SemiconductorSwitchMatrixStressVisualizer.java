package org.valkyrienskies.horizons.potato_battery.stress_tests;

import org.valkyrienskies.horizons.potato_battery.CircuitComponents.DiodeNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.GroundNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.JunctionNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.NMOSTransistorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.NPNTransistorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.ResistorNode;
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
import java.util.ArrayList;
import java.util.List;

public final class SemiconductorSwitchMatrixStressVisualizer {
  public static void main(String[] args) {
    CircuitVisualizer.launch(new SemiconductorScenario());
  }

  private static final class SemiconductorScenario implements Scenario {
    private static final double DT = 1.0 / 240.0;
    private static final int CELLS = 8;

    private final VariableVoltageNode supply = new VariableVoltageNode();
    private final VariableVoltageNode drive = new VariableVoltageNode();
    private final GroundNode ground = new GroundNode();
    private final JunctionNode[] cells = new JunctionNode[CELLS];
    private final ResistorNode[] loads = new ResistorNode[CELLS];
    private final NMOSTransistorNode[] nmos = new NMOSTransistorNode[CELLS];
    private final NPNTransistorNode[] npn = new NPNTransistorNode[CELLS];
    private final DiodeNode[] clampUp = new DiodeNode[CELLS];
    private final DiodeNode[] clampDown = new DiodeNode[CELLS];
    private final ResistorNode[] couplers = new ResistorNode[CELLS - 1];

    private volatile double supplyVoltage = 9.0;
    private volatile double driveVoltage = 0.0;
    private final double[] cellVoltages = new double[CELLS];
    private double avgVoltage;
    private double loadCurrent;

    private SemiconductorScenario() {
      for (int i = 0; i < CELLS; i++) {
        cells[i] = new JunctionNode(8);
        loads[i] = new ResistorNode(220.0 + i * 22.0);
        nmos[i] = new NMOSTransistorNode(1.0, 1.7e-3);
        npn[i] = new NPNTransistorNode();
        clampUp[i] = new DiodeNode();
        clampDown[i] = new DiodeNode();
        if (i + 1 < CELLS) {
          couplers[i] = new ResistorNode(680.0 + i * 47.0);
        }
      }
    }

    @Override
    public String title() {
      return "Semiconductor Switch Matrix Stress";
    }

    @Override
    public double timeStep() {
      return DT;
    }

    @Override
    public void build(CircuitBuilder b) {
      b.add(supply).add(drive).add(ground);
      for (int i = 0; i < CELLS; i++) {
        b.add(cells[i]).add(loads[i]).add(nmos[i]).add(npn[i]).add(clampUp[i]).add(clampDown[i]);
      }
      for (ResistorNode coupler : couplers) {
        b.add(coupler);
      }

      b.connect(supply, 1, ground, 0).connect(drive, 1, ground, 0);
      for (int i = 0; i < CELLS; i++) {
        b.connect(supply, 0, loads[i], 0);
        b.connect(loads[i], 1, cells[i], 0);
        b.connect(cells[i], 1, nmos[i], 0).connect(drive, 0, nmos[i], 1).connect(ground, 0, nmos[i], 2);
        b.connect(cells[i], 2, npn[i], 1).connect(drive, 0, npn[i], 0).connect(ground, 0, npn[i], 2);
        b.connect(cells[i], 3, clampDown[i], 0).connect(clampDown[i], 1, ground, 0);
        b.connect(ground, 0, clampUp[i], 0).connect(clampUp[i], 1, cells[i], 4);
      }
      for (int i = 0; i + 1 < CELLS; i++) {
        b.connect(cells[i], 5, couplers[i], 0).connect(couplers[i], 1, cells[i + 1], 6);
      }
    }

    @Override
    public void beforeStep(double time) {
      supply.setVoltage(supplyVoltage);
      drive.setVoltage(driveVoltage);
    }

    @Override
    public void afterStep(double time, PowerNetworkServer network) {
      avgVoltage = 0.0;
      for (int i = 0; i < CELLS; i++) {
        cellVoltages[i] = network.getVoltageAt(cells[i], 0);
        avgVoltage += cellVoltages[i];
      }
      avgVoltage /= CELLS;
      loadCurrent = Math.abs(network.getCurrentOver(supply, loads[0], 0, 0));
    }

    @Override
    public List<Trace> traces() {
      List<Trace> traces = new ArrayList<>();
      traces.add(new Trace("supply", new Color(255, 208, 102), 10.0, () -> supplyVoltage));
      traces.add(new Trace("drive", new Color(172, 235, 149), 5.0, () -> driveVoltage));
      traces.add(new Trace("avg cell", new Color(120, 200, 255), 10.0, () -> avgVoltage));
      traces.add(new Trace("load current", new Color(255, 140, 140), 0.05, () -> loadCurrent));
      for (int i = 0; i < 3; i++) {
        final int cell = i;
        traces.add(new Trace("cell " + (i + 1), new Color(220 - i * 30, 180 + i * 12, 255 - i * 20), 10.0, () -> cellVoltages[cell]));
      }
      return traces;
    }

    @Override
    public List<Readout> readouts() {
      return List.of(
          new Readout("Supply: %.2f V", () -> supplyVoltage),
          new Readout("Drive: %.2f V", () -> driveVoltage),
          new Readout("Avg Cell: %.2f V", () -> avgVoltage),
          new Readout("Load I: %.4f A", () -> loadCurrent),
          new Readout("Cell 1: %.2f V", () -> cellVoltages[0]),
          new Readout("Cell 8: %.2f V", () -> cellVoltages[7])
      );
    }

    @Override
    public void populateControls(JPanel controls) {
      JLabel supplyLabel = CircuitVisualizer.createValueLabel(String.format("%.1f V", supplyVoltage));
      JSlider supplySlider = new JSlider(0, 180, (int) Math.round(supplyVoltage * 10.0));
      supplySlider.addChangeListener(event -> {
        supplyVoltage = supplySlider.getValue() / 10.0;
        supplyLabel.setText(String.format("%.1f V", supplyVoltage));
      });
      controls.add(CircuitVisualizer.labeledControl("Supply", supplySlider, supplyLabel));

      JLabel driveLabel = CircuitVisualizer.createValueLabel(String.format("%.1f V", driveVoltage));
      JSlider driveSlider = new JSlider(0, 50, (int) Math.round(driveVoltage * 10.0));
      driveSlider.addChangeListener(event -> {
        driveVoltage = driveSlider.getValue() / 10.0;
        driveLabel.setText(String.format("%.1f V", driveVoltage));
      });
      controls.add(CircuitVisualizer.labeledControl("Drive", driveSlider, driveLabel));
    }

    @Override
    public void drawSchematic(Graphics2D g, int left, int top, int width, int height) {
      g.setColor(new Color(226, 230, 236));
      g.drawString("Eight nonlinear switch cells: resistive pull-up, NMOS pull-down, NPN assist, dual diode clamps, and neighbor coupling.", left, top + 20);
      g.drawString("This is intended to pressure-test the diode/MOS/BJT path without building another giant application circuit.", left, top + 42);
      int boxTop = top + 70;
      int cellWidth = width / CELLS;
      for (int i = 0; i < CELLS; i++) {
        int x = left + i * cellWidth + 8;
        int h = (int) Math.round(Math.max(0.0, Math.min(1.0, cellVoltages[i] / Math.max(supplyVoltage, 1.0e-9))) * 92.0);
        g.setColor(new Color(40, 46, 58));
        g.fillRoundRect(x, boxTop, cellWidth - 16, 112, 12, 12);
        g.setColor(new Color(255 - i * 12, 140 + i * 8, 100 + i * 10));
        g.fillRoundRect(x + 8, boxTop + 100 - h, cellWidth - 32, h, 10, 10);
        g.setColor(new Color(226, 230, 236));
        g.drawString("C" + (i + 1), x + 8, boxTop + 16);
      }
    }
  }
}
