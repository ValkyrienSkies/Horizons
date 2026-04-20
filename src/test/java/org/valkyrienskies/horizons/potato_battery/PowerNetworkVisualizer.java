package org.valkyrienskies.horizons.potato_battery;

import net.minecraft.core.BlockPos;
import org.valkyrienskies.horizons.potato_battery.api.network.CircuitStampContext;
import org.valkyrienskies.horizons.potato_battery.api.network.IPBSolver;
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNodeSimulationMode;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;
import org.valkyrienskies.horizons.potato_battery.impl.network.node.PowerNode;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.EJMLSolver;
import org.valkyrienskies.horizons.potato_battery.impl.network.solver.JKLUSolver;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.concurrent.locks.LockSupport;

public final class PowerNetworkVisualizer {
  private static final int GRID_WIDTH = 16;
  private static final int GRID_HEIGHT = 10;
  private static final double CELL_RESISTANCE = 12.0;
  private static final double WIRE_RESISTANCE = 1.0e-6;
  private static final int TIMER_MS = 33;
  private static final double TIME_STEP_SECONDS = PowerNetworkServer.DEFAULT_TIME_STEP;

  public static void main(String[] args) {
    SwingUtilities.invokeLater(() -> {
      VisualizerModel model = new VisualizerModel(selectSolver());
      JFrame frame = new JFrame("Potato Battery Visualizer");
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
    private final AnimatedBatteryNode battery = new AnimatedBatteryNode();
    private final GroundNode ground = new GroundNode();
    private final JunctionNode sourceBus = new JunctionNode(GRID_HEIGHT + 1);
    private final JunctionNode groundBus = new JunctionNode(GRID_HEIGHT + 1);
    private final JunctionNode[][] grid = new JunctionNode[GRID_WIDTH][GRID_HEIGHT];
    private final PowerNetworkServer network;
    private final Object stateLock = new Object();
    private double sourceCurrent;
    private double centerVoltage;
    private double batteryVoltage;
    private long ticks;
    private final long stepNanos = Math.max(1L, Math.round(TIME_STEP_SECONDS * 1_000_000_000.0));
    private final int maxStepsPerBurst = Math.max(1, (int) Math.ceil((TIMER_MS / 1000.0) / TIME_STEP_SECONDS) + 1);
    private final long maxAccumulatedStepNanos = stepNanos * maxStepsPerBurst;
    private volatile boolean running;
    private Thread simulationThread;

    private VisualizerModel(IPBSolver solver) {
      this.network = new PowerNetworkServer(null, null, solver);
      buildNetwork();
      step();
    }

    private void start(JFrame frame) {
      running = true;
      simulationThread = new Thread(() -> runSimulationLoop(frame), "potato-power-grid-sim");
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

    private void buildNetwork() {
      addNode(0, battery);
      addNode(1, sourceBus);
      addNode(2, ground);
      addNode(3, groundBus);

      connectBidirectional(battery, 0, sourceBus, 0, WIRE_RESISTANCE);
      connectBidirectional(battery, 1, ground, 0, WIRE_RESISTANCE);
      connectBidirectional(ground, 0, groundBus, 0, WIRE_RESISTANCE);

      int nextX = 4;
      for (int x = 0; x < GRID_WIDTH; x++) {
        for (int y = 0; y < GRID_HEIGHT; y++) {
          grid[x][y] = new JunctionNode(6);
          addNode(nextX++, grid[x][y]);
        }
      }

      for (int y = 0; y < GRID_HEIGHT; y++) {
        connectBidirectional(sourceBus, y + 1, grid[0][y], 3, WIRE_RESISTANCE);
        connectBidirectional(groundBus, y + 1, grid[GRID_WIDTH - 1][y], 2, WIRE_RESISTANCE);
      }

      for (int x = 0; x < GRID_WIDTH; x++) {
        for (int y = 0; y < GRID_HEIGHT; y++) {
          JunctionNode node = grid[x][y];
          if (x + 1 < GRID_WIDTH) {
            connectBidirectional(node, 2, grid[x + 1][y], 3, CELL_RESISTANCE);
          }
          if (y + 1 < GRID_HEIGHT) {
            connectBidirectional(node, 4, grid[x][y + 1], 5, CELL_RESISTANCE);
          }
        }
      }
    }

    private void step() {
      synchronized (stateLock) {
        ticks++;
        batteryVoltage = 7.5 + Math.sin(ticks * 0.05) * 2.5;
        battery.setVoltage(batteryVoltage);
        network.physTick();
        sourceCurrent = Math.abs(network.getCurrentOver(battery, sourceBus, 0, 0));
        centerVoltage = averageVoltage(grid[GRID_WIDTH / 2][GRID_HEIGHT / 2]);
      }
    }

    private double averageVoltage(PowerNode node) {
      double total = 0.0;
      for (int port = 0; port < node.getPorts(); port++) {
        total += network.getVoltageAt(node, port);
      }
      return total / node.getPorts();
    }

    private double voltageAt(int x, int y) {
      return averageVoltage(grid[x][y]);
    }

    private void addNode(int x, PowerNode node) {
      network.addNode(new BlockPos(x, 0, 0), node);
    }

    private void connectBidirectional(PowerNode a, int aPort, PowerNode b, int bPort, double resistance) {
      a.addConnection(b, aPort, bPort, resistance);
      b.addConnection(a, bPort, aPort, resistance);
    }
  }

  private static final class VisualizerPanel extends JPanel {
    private static final Font LABEL_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 14);
    private final VisualizerModel model;

    private VisualizerPanel(VisualizerModel model) {
      this.model = model;
      setPreferredSize(new Dimension(1100, 720));
      setBackground(new Color(16, 18, 24));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      super.paintComponent(graphics);
      Graphics2D g = (Graphics2D) graphics.create();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setFont(LABEL_FONT);

      synchronized (model.stateLock) {
        int left = 80;
        int top = 110;
        int stepX = 56;
        int stepY = 48;
        int radius = 18;

        g.setColor(new Color(220, 225, 235));
        g.drawString("Solver: " + model.solverName, 40, 36);
        g.drawString(String.format("Battery: %.3f V", model.batteryVoltage), 40, 60);
        g.drawString(String.format("Source current: %.6f A", model.sourceCurrent), 260, 36);
        g.drawString(String.format("Center voltage: %.6f V", model.centerVoltage), 260, 60);
        g.drawString("Blue = low voltage, red = high voltage", 540, 36);

        for (int x = 0; x < GRID_WIDTH; x++) {
          for (int y = 0; y < GRID_HEIGHT; y++) {
            int px = left + x * stepX;
            int py = top + y * stepY;
            double voltage = model.voltageAt(x, y);
            g.setColor(colorForVoltage(voltage, model.batteryVoltage));
            g.fillOval(px - radius, py - radius, radius * 2, radius * 2);
            g.setColor(new Color(30, 34, 42));
            g.drawOval(px - radius, py - radius, radius * 2, radius * 2);
            g.setColor(Color.WHITE);
            g.drawString(String.format("%.1f", voltage), px - 14, py + 5);
          }
        }

        int busX = left - 48;
        int groundX = left + (GRID_WIDTH - 1) * stepX + 48;
        g.setColor(new Color(255, 204, 96));
        g.fillRoundRect(busX - 10, top - 24, 20, (GRID_HEIGHT - 1) * stepY + 48, 10, 10);
        g.drawString("+", busX - 4, top - 36);
        g.setColor(new Color(120, 180, 255));
        g.fillRoundRect(groundX - 10, top - 24, 20, (GRID_HEIGHT - 1) * stepY + 48, 10, 10);
        g.drawString("-", groundX - 4, top - 36);
      }

      g.setColor(new Color(220, 225, 235));
      g.drawString("Close the window to stop the simulation.", 40, getHeight() - 32);
      g.dispose();
    }

    private static Color colorForVoltage(double voltage, double maxVoltage) {
      double normalized = maxVoltage <= 0.0 ? 0.0 : Math.max(0.0, Math.min(1.0, voltage / maxVoltage));
      int red = (int) (80 + 175 * normalized);
      int green = (int) (80 + 60 * (1.0 - Math.abs(normalized - 0.5) * 2.0));
      int blue = (int) (255 - 170 * normalized);
      return new Color(red, green, blue);
    }
  }

  private static final class AnimatedBatteryNode extends PowerNode {
    private volatile double voltage;

    private AnimatedBatteryNode() {
      super(2);
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      return Double.doubleToLongBits(voltage);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 0, 1, voltage);
    }

    private void setVoltage(double voltage) {
      this.voltage = voltage;
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

  private static final class JunctionNode extends PowerNode {
    private JunctionNode(int ports) {
      super(ports);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      for (int port = 1; port < getPorts(); port++) {
        context.stampResistance(0, port, 1.0e-9);
      }
    }
  }
}
