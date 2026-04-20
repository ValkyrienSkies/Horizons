package org.valkyrienskies.horizons.potato_battery;

import net.minecraft.core.BlockPos;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.FixedStepNetwork;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;
import org.valkyrienskies.horizons.potato_battery.impl.network.node.PowerNode;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.EJMLSolver;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.JKLUSolver;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
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
import java.util.concurrent.locks.LockSupport;

public final class CircuitVisualizer {
  private static final int TIMER_MS = 16;
  private static final int HISTORY = 520;
  private static final double WIRE_RESISTANCE = 1.0e-6;
  private static final double AUTO_SCALE_HEADROOM = 1.15;
  private static final double AUTO_SCALE_MIN = 1.0e-3;

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
      JPanel root = new JPanel(new BorderLayout());
      root.setBackground(new Color(14, 16, 20));
      root.add(new VisualizerPanel(model), BorderLayout.CENTER);
      JPanel controls = new JPanel();
      controls.setBackground(new Color(18, 21, 27));
      scenario.populateControls(controls);
      if (controls.getComponentCount() > 0) {
        root.add(controls, BorderLayout.SOUTH);
      }
      frame.setContentPane(root);
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

    default void populateControls(JPanel controls) {
    }
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

  public static JPanel labeledControl(String label, java.awt.Component control, JLabel valueLabel) {
    JPanel panel = new JPanel();
    panel.setBackground(new Color(18, 21, 27));
    panel.add(createLabel(label));
    panel.add(control);
    if (valueLabel != null) {
      valueLabel.setForeground(new Color(226, 230, 236));
      valueLabel.setHorizontalAlignment(SwingConstants.LEFT);
      panel.add(valueLabel);
    }
    return panel;
  }

  public static JLabel createValueLabel(String text) {
    JLabel label = new JLabel(text);
    label.setForeground(new Color(226, 230, 236));
    return label;
  }

  public static JLabel createLabel(String text) {
    JLabel label = new JLabel(text);
    label.setForeground(new Color(226, 230, 236));
    return label;
  }

  private static final class VisualizerModel {
    private final Scenario scenario;
    private final FixedStepNetwork network;
    private final String solverName;
    private final List<Trace> traces;
    private final List<Readout> readouts;
    private final List<Deque<Double>> histories;
    private final Object stateLock = new Object();
    private double time;
    private long statsWindowStartNanos = System.nanoTime();
    private final long stepNanos;
    private final int maxStepsPerBurst;
    private final long maxAccumulatedStepNanos;
    private volatile boolean running;
    private Thread simulationThread;
    private int stepsThisWindow;
    private int framesThisWindow;
    private volatile double ticksPerSecond;
    private volatile double framesPerSecond;

    VisualizerModel(Scenario scenario, IPBSolver solver, String solverName) {
      this.scenario = scenario;
      this.solverName = solverName;
      this.network = new FixedStepNetwork(solver, scenario.timeStep());
      scenario.build(new CircuitBuilder(network));
      this.stepNanos = Math.max(1L, Math.round(scenario.timeStep() * 1_000_000_000.0));
      this.maxStepsPerBurst = Math.max(1, (int) Math.ceil((TIMER_MS / 1000.0) / scenario.timeStep()) + 1);
      this.maxAccumulatedStepNanos = stepNanos * maxStepsPerBurst;
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
      running = true;
      simulationThread = new Thread(() -> runSimulationLoop(frame), scenario.title().replace(' ', '-') + "-sim");
      simulationThread.setDaemon(true);
      simulationThread.start();
      Timer timer = new Timer(TIMER_MS, event -> {
        if (!frame.isDisplayable()) {
          running = false;
          if (simulationThread != null) {
            simulationThread.interrupt();
          }
          ((Timer) event.getSource()).stop();
          return;
        }
        recordFrame();
        frame.repaint();
      });
      timer.start();
    }

    private void runSimulationLoop(JFrame frame) {
      long lastNanos = System.nanoTime();
      long accumulatedNanos = 0L;
      while (running && frame.isDisplayable()) {
        long now = System.nanoTime();
        accumulatedNanos = Math.min(accumulatedNanos + Math.max(0L, now - lastNanos), maxAccumulatedStepNanos);
        lastNanos = now;

        int executed = 0;
        while (executed < maxStepsPerBurst && accumulatedNanos >= stepNanos) {
          step();
          accumulatedNanos -= stepNanos;
          executed++;
        }

        if (accumulatedNanos < stepNanos) {
          LockSupport.parkNanos(Math.min(stepNanos - accumulatedNanos, 1_000_000L));
        } else {
          Thread.yield();
        }
      }
    }

    private void step() {
      synchronized (stateLock) {
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
        stepsThisWindow++;
        updateStatsIfNeeded();
      }
    }

    double ticksPerSecond() {
      return ticksPerSecond;
    }

    double idealTicksPerSecond() {
      return 1.0 / scenario.timeStep();
    }

    double slowdownFactor() {
      double actual = ticksPerSecond();
      if (actual <= 1.0e-9) {
        return Double.POSITIVE_INFINITY;
      }
      return idealTicksPerSecond() / actual;
    }

    double framesPerSecond() {
      return framesPerSecond;
    }

    Object stateLock() {
      return stateLock;
    }

    private void recordFrame() {
      framesThisWindow++;
      updateStatsIfNeeded();
    }

    private void updateStatsIfNeeded() {
      long now = System.nanoTime();
      long elapsed = now - statsWindowStartNanos;
      if (elapsed < 250_000_000L) {
        return;
      }
      double seconds = elapsed / 1_000_000_000.0;
      ticksPerSecond = stepsThisWindow / seconds;
      framesPerSecond = framesThisWindow / seconds;
      stepsThisWindow = 0;
      framesThisWindow = 0;
      statsWindowStartNanos = now;
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

      synchronized (model.stateLock()) {
        g.setColor(new Color(226, 230, 236));
        g.drawString("Solver: " + model.solverName, 36, 32);
        g.drawString(String.format("dt: %.5f s", model.scenario.timeStep()), 36, 56);
        g.drawString(String.format("TPS: %.1f", model.ticksPerSecond()), 160, 32);
        g.drawString(String.format("FPS: %.1f", model.framesPerSecond()), 160, 56);
        g.drawString(String.format("Ideal TPS: %.1f", model.idealTicksPerSecond()), 36, 84);
        g.drawString(String.format("Slowdown: %.2fx", model.slowdownFactor()), 160, 84);

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
      }

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

      double scaleMultiplier = autoScaleMultiplier(model);
      for (int i = 0; i < model.traces.size(); i++) {
        Trace trace = model.traces.get(i);
        drawTrace(g, model.histories.get(i), left, top, width, height, trace.scale() * scaleMultiplier, trace.color());
      }

      FontMetrics metrics = g.getFontMetrics();
      int legendX = left + 24;
      for (Trace trace : model.traces) {
        g.setColor(trace.color());
        g.drawString(trace.label(), legendX, top + 26);
        legendX += metrics.stringWidth(trace.label()) + 24;
      }
      g.setColor(new Color(226, 230, 236));
      g.drawString(String.format("scale x%.2f", scaleMultiplier), left + width - 160, top + height - 14);
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

    private double autoScaleMultiplier(VisualizerModel model) {
      double peak = AUTO_SCALE_MIN;
      for (int i = 0; i < model.traces.size(); i++) {
        Trace trace = model.traces.get(i);
        for (double value : model.histories.get(i)) {
          peak = Math.max(peak, Math.abs(value) / Math.max(trace.scale(), AUTO_SCALE_MIN));
        }
      }
      return Math.max(peak * AUTO_SCALE_HEADROOM, 1.0);
    }
  }
}
