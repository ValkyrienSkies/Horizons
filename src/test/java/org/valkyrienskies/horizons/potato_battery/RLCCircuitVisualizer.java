package org.valkyrienskies.horizons.potato_battery;

import net.minecraft.core.BlockPos;
import org.valkyrienskies.horizons.potato_battery.api.network.CircuitStampContext;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;
import org.valkyrienskies.horizons.potato_battery.impl.network.node.PowerNode;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.EJMLSolver;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.JKLUSolver;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayDeque;
import java.util.Deque;

public final class RLCCircuitVisualizer {
  private static final int TIMER_MS = 16;
  private static final double TIME_STEP = 1.0 / 240.0;
  private static final int HISTORY = 520;

  public static void main(String[] args) {
    SwingUtilities.invokeLater(() -> {
      VisualizerModel model = new VisualizerModel(selectSolver());
      JFrame frame = new JFrame("RLC Circuit Visualizer");
      frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
      frame.setContentPane(new VisualizerPanel(model));
      frame.pack();
      frame.setLocationRelativeTo(null);
      frame.setVisible(true);
      model.start(frame);
    });
  }

  private static IPBSolver selectSolver() {
    String solverName = System.getProperty("power.solver", "jklu").trim().toLowerCase();
    return switch (solverName) {
      case "ejml" -> new EJMLSolver();
      case "jklu" -> new JKLUSolver();
      default -> throw new IllegalArgumentException("Unknown solver '" + solverName + "', expected 'ejml' or 'jklu'");
    };
  }

  private static final class VisualizerModel {
    private final String solverName = System.getProperty("power.solver", "jklu").toUpperCase();
    private final FixedStepNetwork network;
    private final ACSourceNode source = new ACSourceNode();
    private final ResistorNode resistor = new ResistorNode(12.0);
    private final InductorNode inductor = new InductorNode(0.060);
    private final CapacitorNode capacitor = new CapacitorNode(0.0015);
    private final GroundNode ground = new GroundNode();
    private final Deque<Double> sourceHistory = new ArrayDeque<>();
    private final Deque<Double> currentHistory = new ArrayDeque<>();
    private final Deque<Double> capacitorHistory = new ArrayDeque<>();
    private final Deque<Double> inductorHistory = new ArrayDeque<>();
    private double time;
    private double sourceVoltage;
    private double loopCurrent;
    private double capacitorVoltage;
    private double inductorVoltage;

    private VisualizerModel(IPBSolver solver) {
      this.network = new FixedStepNetwork(solver, TIME_STEP);
      buildCircuit();
      for (int i = 0; i < HISTORY; i++) {
        sourceHistory.addLast(0.0);
        currentHistory.addLast(0.0);
        capacitorHistory.addLast(0.0);
        inductorHistory.addLast(0.0);
      }
      step();
    }

    private void buildCircuit() {
      addNode(0, source);
      addNode(1, resistor);
      addNode(2, inductor);
      addNode(3, capacitor);
      addNode(4, ground);

      connectBidirectional(source, 0, resistor, 0, 1.0e-6);
      connectBidirectional(resistor, 1, inductor, 0, 1.0e-6);
      connectBidirectional(inductor, 1, capacitor, 0, 1.0e-6);
      connectBidirectional(capacitor, 1, ground, 0, 1.0e-6);
      connectBidirectional(source, 1, ground, 0, 1.0e-6);
    }

    private void start(JFrame frame) {
      Timer timer = new Timer(TIMER_MS, event -> {
        if (!frame.isDisplayable()) {
          ((Timer) event.getSource()).stop();
          return;
        }
        for (int i = 0; i < 4; i++) {
          step();
        }
        frame.repaint();
      });
      timer.start();
    }

    private void step() {
      time += TIME_STEP;
      source.setVoltage(10.0 * Math.sin(time * Math.PI * 2.0 * 3.0));

      network.physTick();

      sourceVoltage = network.getVoltageAt(source, 0) - network.getVoltageAt(source, 1);
      loopCurrent = network.getCurrentOver(source, resistor, 0, 0);
      capacitorVoltage = network.getVoltageAt(capacitor, 0) - network.getVoltageAt(capacitor, 1);
      inductorVoltage = network.getVoltageAt(inductor, 0) - network.getVoltageAt(inductor, 1);

      inductor.postStep(network);
      capacitor.postStep(network);

      push(sourceHistory, sourceVoltage);
      push(currentHistory, loopCurrent);
      push(capacitorHistory, capacitorVoltage);
      push(inductorHistory, inductorVoltage);
    }

    private static void push(Deque<Double> history, double value) {
      history.addLast(value);
      while (history.size() > HISTORY) {
        history.removeFirst();
      }
    }

    private void addNode(int x, PowerNode node) {
      network.addNode(new BlockPos(x, 0, 0), node);
    }

    private static void connectBidirectional(PowerNode a, int aPort, PowerNode b, int bPort, double resistance) {
      a.addConnection(b, aPort, bPort, resistance);
      b.addConnection(a, bPort, aPort, resistance);
    }
  }

  private static final class VisualizerPanel extends JPanel {
    private static final Font LABEL_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 14);
    private final VisualizerModel model;

    private VisualizerPanel(VisualizerModel model) {
      this.model = model;
      setPreferredSize(new Dimension(1180, 780));
      setBackground(new Color(14, 16, 20));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      super.paintComponent(graphics);
      Graphics2D g = (Graphics2D) graphics.create();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setFont(LABEL_FONT);

      g.setColor(new Color(226, 230, 236));
      g.drawString("Solver: " + model.solverName, 36, 32);
      g.drawString(String.format("Source: %.3f V", model.sourceVoltage), 36, 56);
      g.drawString(String.format("Loop current: %.6f A", model.loopCurrent), 280, 32);
      g.drawString(String.format("Capacitor: %.3f V", model.capacitorVoltage), 280, 56);
      g.drawString(String.format("Inductor: %.3f V", model.inductorVoltage), 520, 32);
      g.drawString(String.format("dt: %.5f s", TIME_STEP), 520, 56);

      drawSchematic(g, 70, 120);
      drawScope(g, 70, 330, 1020, 360);

      g.drawString("Manual tool. Close the window to stop.", 36, getHeight() - 24);
      g.dispose();
    }

    private void drawSchematic(Graphics2D g, int left, int top) {
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
      g.drawString(String.format("%.1f V", model.sourceVoltage - model.inductorVoltage - model.capacitorVoltage), x1 - 24, y + 44);

      g.setColor(new Color(255, 150, 120));
      for (int i = 0; i < 4; i++) {
        g.drawArc(x2 - 34 + i * 18, y - 18, 36, 36, 0, 180);
      }
      g.drawString("L", x2, y - 28);
      g.drawString(String.format("%.2f V", model.inductorVoltage), x2 - 18, y + 44);

      g.setColor(new Color(172, 235, 149));
      g.drawLine(x3 - 12, y - 28, x3 - 12, y + 28);
      g.drawLine(x3 + 12, y - 28, x3 + 12, y + 28);
      g.drawString("C", x3 - 4, y - 40);
      g.drawString(String.format("%.2f V", model.capacitorVoltage), x3 - 18, y + 44);

      g.setColor(new Color(200, 205, 214));
      g.drawString(String.format("I = %.5f A", model.loopCurrent), x4 - 28, y - 28);
    }

    private void drawScope(Graphics2D g, int left, int top, int width, int height) {
      g.setColor(new Color(22, 25, 31));
      g.fillRoundRect(left, top, width, height, 18, 18);
      g.setColor(new Color(58, 66, 80));
      g.drawRoundRect(left, top, width, height, 18, 18);

      int midY = top + height / 2;
      g.setColor(new Color(50, 56, 68));
      for (int i = 1; i < 4; i++) {
        int y = top + i * height / 4;
        g.drawLine(left + 16, y, left + width - 16, y);
      }
      g.drawLine(left + 16, midY, left + width - 16, midY);

      drawTrace(g, model.sourceHistory, left, top, width, height, 12.0, new Color(255, 208, 102));
      drawTrace(g, model.capacitorHistory, left, top, width, height, 12.0, new Color(172, 235, 149));
      drawTrace(g, model.inductorHistory, left, top, width, height, 12.0, new Color(255, 150, 120));
      drawTrace(g, model.currentHistory, left, top, width, height, 1.5, new Color(120, 200, 255));

      g.setColor(new Color(226, 230, 236));
      g.drawString("yellow: source voltage", left + 24, top + 26);
      g.drawString("green: capacitor voltage", left + 260, top + 26);
      g.drawString("orange: inductor voltage", left + 528, top + 26);
      g.drawString("blue: loop current", left + 780, top + 26);
    }

    private void drawTrace(Graphics2D g, Deque<Double> trace, int left, int top, int width, int height, double scale, Color color) {
      int index = 0;
      int[] xs = new int[trace.size()];
      int[] ys = new int[trace.size()];
      int innerLeft = left + 18;
      int innerWidth = width - 36;
      int midY = top + height / 2;

      for (double value : trace) {
        xs[index] = innerLeft + (int) ((index / (double) Math.max(1, trace.size() - 1)) * innerWidth);
        ys[index] = midY - (int) ((value / scale) * (height * 0.40));
        index++;
      }

      g.setColor(color);
      g.setStroke(new BasicStroke(2.2f));
      g.drawPolyline(xs, ys, trace.size());
    }
  }

  private static final class FixedStepNetwork extends PowerNetworkServer {
    private final double timeStepSeconds;

    private FixedStepNetwork(IPBSolver solver, double timeStepSeconds) {
      super(null, null, solver);
      this.timeStepSeconds = timeStepSeconds;
    }

    @Override
    public double getTimeStepSeconds() {
      return timeStepSeconds;
    }
  }

  private static final class ACSourceNode extends PowerNode {
    private volatile double voltage;

    private ACSourceNode() {
      super(2);
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 0, 1, voltage);
    }

    private void setVoltage(double voltage) {
      this.voltage = voltage;
    }
  }

  private static final class ResistorNode extends PowerNode {
    private final double resistance;

    private ResistorNode(double resistance) {
      super(2);
      this.resistance = resistance;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampResistance(0, 1, resistance);
    }
  }

  private static final class CapacitorNode extends PowerNode {
    private final double capacitance;
    private double previousVoltage;

    private CapacitorNode(double capacitance) {
      super(2);
      this.capacitance = capacitance;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      double conductance = capacitance / context.getTimeStep();
      context.stampConductance(0, 1, conductance);
      context.stampCurrentSource(1, 0, conductance * previousVoltage);
    }

    private void postStep(PowerNetworkServer network) {
      previousVoltage = network.getVoltageAt(this, 0) - network.getVoltageAt(this, 1);
    }
  }

  private static final class InductorNode extends PowerNode {
    private final double inductance;
    private double previousCurrent;

    private InductorNode(double inductance) {
      super(2);
      this.inductance = inductance;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      double conductance = context.getTimeStep() / inductance;
      context.stampConductance(0, 1, conductance);
      context.stampCurrentSource(0, 1, previousCurrent);
    }

    private void postStep(PowerNetworkServer network) {
      double voltage = network.getVoltageAt(this, 0) - network.getVoltageAt(this, 1);
      previousCurrent += (network.getTimeStepSeconds() / inductance) * voltage;
    }
  }

  private static final class GroundNode extends PowerNode {
    private GroundNode() {
      super(1);
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 0, CircuitStampContext.GROUND, 0.0);
    }
  }
}
