package org.valkyrienskies.horizons.potato_battery;

import net.minecraft.core.BlockPos;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.FixedStepNetwork;
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
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.DoubleSupplier;

public final class CircuitVisualizer {
  private static final int TIMER_MS = 16;
  private static final int SUBSTEPS_PER_FRAME = 4;
  private static final int HISTORY = 520;
  private static final double WIRE_RESISTANCE = 1.0e-6;

  private CircuitVisualizer() {}

  public static void launch(Scenario scenario) {
    SwingUtilities.invokeLater(() -> {
      String solverProp = System.getProperty("power.solver", "jklu").trim().toLowerCase();
      IPBSolver solver = switch (solverProp) {
        case "ejml" -> new EJMLSolver();
        case "jklu" -> new JKLUSolver();
        default -> throw new IllegalArgumentException("Unknown solver '" + solverProp + "', expected 'ejml' or 'jklu'");
      };
      VisualizerModel model = new VisualizerModel(scenario, solver, solverProp.toUpperCase());
      JFrame frame = new JFrame(scenario.title());
      frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
      frame.setContentPane(new VisualizerPanel(model));
      frame.pack();
      frame.setLocationRelativeTo(null);
      frame.setVisible(true);
      model.start(frame);
    });
  }

  public interface Scenario {
    String title();

    double timeStep();

    void build(CircuitBuilder builder);

    void beforeStep(double time);

    void afterStep(double time, PowerNetworkServer network);

    List<Trace> traces();

    List<Readout> readouts();

    void drawSchematic(Graphics2D g, int left, int top, int width, int height);
  }

  public static final class CircuitBuilder {
    private final FixedStepNetwork network;
    private int nextX;

    private CircuitBuilder(FixedStepNetwork network) {
      this.network = network;
    }

    public CircuitBuilder add(PowerNode node) {
      network.addNode(new BlockPos(nextX++, 0, 0), node);
      return this;
    }

    public CircuitBuilder connect(PowerNode a, int aPort, PowerNode b, int bPort) {
      return connect(a, aPort, b, bPort, WIRE_RESISTANCE);
    }

    public CircuitBuilder connect(PowerNode a, int aPort, PowerNode b, int bPort, double resistance) {
      a.addConnection(b, aPort, bPort, resistance);
      b.addConnection(a, bPort, aPort, resistance);
      return this;
    }
  }

  public record Trace(String label, Color color, double scale, DoubleSupplier value) {}

  public record Readout(String format, DoubleSupplier value) {}

  private static final class VisualizerModel {
    private final Scenario scenario;
    private final FixedStepNetwork network;
    private final String solverName;
    private final List<Trace> traces;
    private final List<Readout> readouts;
    private final List<Deque<Double>> histories;
    private double time;

    VisualizerModel(Scenario scenario, IPBSolver solver, String solverName) {
      this.scenario = scenario;
      this.solverName = solverName;
      this.network = new FixedStepNetwork(solver, scenario.timeStep());
      scenario.build(new CircuitBuilder(network));
      this.traces = List.copyOf(scenario.traces());
      this.readouts = List.copyOf(scenario.readouts());
      this.histories = new ArrayList<>(traces.size());
      for (int i = 0; i < traces.size(); i++) {
        Deque<Double> deque = new ArrayDeque<>();
        for (int j = 0; j < HISTORY; j++) {
          deque.addLast(0.0);
        }
        histories.add(deque);
      }
      step();
    }

    void start(JFrame frame) {
      Timer timer = new Timer(TIMER_MS, event -> {
        if (!frame.isDisplayable()) {
          ((Timer) event.getSource()).stop();
          return;
        }
        for (int i = 0; i < SUBSTEPS_PER_FRAME; i++) {
          step();
        }
        frame.repaint();
      });
      timer.start();
    }

    private void step() {
      time += scenario.timeStep();
      scenario.beforeStep(time);
      network.physTick();
      scenario.afterStep(time, network);
      for (int i = 0; i < traces.size(); i++) {
        Deque<Double> history = histories.get(i);
        history.addLast(traces.get(i).value().getAsDouble());
        while (history.size() > HISTORY) {
          history.removeFirst();
        }
      }
    }
  }

  private static final class VisualizerPanel extends JPanel {
    private static final Font LABEL_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 14);
    private final VisualizerModel model;

    VisualizerPanel(VisualizerModel model) {
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
      g.drawString(String.format("dt: %.5f s", model.scenario.timeStep()), 36, 56);

      for (int i = 0; i < model.readouts.size(); i++) {
        Readout r = model.readouts.get(i);
        int col = 1 + i / 2;
        int row = i % 2;
        int x = 36 + col * 240;
        int y = 32 + row * 24;
        g.drawString(String.format(r.format(), r.value().getAsDouble()), x, y);
      }

      model.scenario.drawSchematic(g, 70, 120, 1040, 200);
      drawScope(g, 70, 330, 1020, 360);

      g.setColor(new Color(226, 230, 236));
      g.drawString("Manual tool. Close the window to stop.", 36, getHeight() - 24);
      g.dispose();
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

      for (int i = 0; i < model.traces.size(); i++) {
        Trace trace = model.traces.get(i);
        drawTrace(g, model.histories.get(i), left, top, width, height, trace.scale(), trace.color());
      }

      FontMetrics metrics = g.getFontMetrics();
      int legendX = left + 24;
      for (Trace trace : model.traces) {
        g.setColor(trace.color());
        g.drawString(trace.label(), legendX, top + 26);
        legendX += metrics.stringWidth(trace.label()) + 24;
      }
    }

    private void drawTrace(Graphics2D g, Deque<Double> trace, int left, int top, int width, int height, double scale, Color color) {
      int size = trace.size();
      int[] xs = new int[size];
      int[] ys = new int[size];
      int innerLeft = left + 18;
      int innerWidth = width - 36;
      int midY = top + height / 2;
      int index = 0;

      for (double value : trace) {
        xs[index] = innerLeft + (int) ((index / (double) Math.max(1, size - 1)) * innerWidth);
        ys[index] = midY - (int) ((value / scale) * (height * 0.40));
        index++;
      }

      g.setColor(color);
      g.setStroke(new BasicStroke(2.2f));
      g.drawPolyline(xs, ys, size);
    }
  }
}
