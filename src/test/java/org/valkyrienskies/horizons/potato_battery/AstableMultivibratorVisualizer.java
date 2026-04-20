package org.valkyrienskies.horizons.potato_battery;

import org.valkyrienskies.horizons.potato_battery.CircuitComponents.BatteryNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.CapacitorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.DiodeNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.GroundNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.NPNTransistorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.ResistorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitVisualizer.CircuitBuilder;
import org.valkyrienskies.horizons.potato_battery.CircuitVisualizer.Readout;
import org.valkyrienskies.horizons.potato_battery.CircuitVisualizer.Scenario;
import org.valkyrienskies.horizons.potato_battery.CircuitVisualizer.Trace;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.util.List;

public final class AstableMultivibratorVisualizer {
  public static void main(String[] args) {
    if (System.getProperty("power.solver") == null) {
      System.setProperty("power.solver", "ejml");
    }
    CircuitVisualizer.launch(new AstableScenario());
  }

  private static final class AstableScenario implements Scenario {
    private static final double TIME_STEP = 1.0 / 120.0;
    private static final double VCC = 5.0;

    private final BatteryNode battery = new BatteryNode(VCC);
    private final ResistorNode rc1 = new ResistorNode(1.0e3);
    private final ResistorNode rc2 = new ResistorNode(1.0e3);
    private final ResistorNode rb1 = new ResistorNode(47.0e3);
    private final ResistorNode rb2 = new ResistorNode(56.0e3);
    private final CapacitorNode c1 = new CapacitorNode(4.7e-6);
    private final CapacitorNode c2 = new CapacitorNode(4.7e-6);
    private final NPNTransistorNode q1 = new NPNTransistorNode();
    private final NPNTransistorNode q2 = new NPNTransistorNode();
    private final DiodeNode q1BaseEmitterReverseClamp = new DiodeNode();
    private final DiodeNode q2BaseEmitterReverseClamp = new DiodeNode();
    private final DiodeNode q1BaseCollectorForwardClamp = new DiodeNode();
    private final DiodeNode q2BaseCollectorForwardClamp = new DiodeNode();
    private final GroundNode ground = new GroundNode();

    private double vc1;
    private double vc2;
    private double vb1;
    private double vb2;

    @Override
    public String title() {
      return "Astable Multivibrator Visualizer";
    }

    @Override
    public double timeStep() {
      return TIME_STEP;
    }

    @Override
    public void build(CircuitBuilder b) {
      b.add(battery).add(rc1).add(rc2).add(rb1).add(rb2)
          .add(c1).add(c2).add(q1).add(q2)
          .add(q1BaseEmitterReverseClamp).add(q2BaseEmitterReverseClamp)
          .add(q1BaseCollectorForwardClamp).add(q2BaseCollectorForwardClamp)
          .add(ground)
          .connect(battery, 0, rc1, 0)
          .connect(rc1, 0, rc2, 0)
          .connect(rc2, 0, rb1, 0)
          .connect(rb1, 0, rb2, 0)
          .connect(battery, 1, ground, 0)
          .connect(ground, 0, q1, 2)
          .connect(q1, 2, q2, 2)
          .connect(rc1, 1, q1, 1)
          .connect(q1, 1, c1, 0)
          .connect(rc2, 1, q2, 1)
          .connect(q2, 1, c2, 0)
          .connect(rb1, 1, q1, 0)
          .connect(q1, 0, c2, 1)
          .connect(rb2, 1, q2, 0)
          .connect(q2, 0, c1, 1)
          .connect(q1, 2, q1BaseEmitterReverseClamp, 0)
          .connect(q1BaseEmitterReverseClamp, 1, q1, 0)
          .connect(q2, 2, q2BaseEmitterReverseClamp, 0)
          .connect(q2BaseEmitterReverseClamp, 1, q2, 0)
          .connect(q1, 0, q1BaseCollectorForwardClamp, 0)
          .connect(q1BaseCollectorForwardClamp, 1, q1, 1)
          .connect(q2, 0, q2BaseCollectorForwardClamp, 0)
          .connect(q2BaseCollectorForwardClamp, 1, q2, 1);
    }

    @Override
    public void beforeStep(double time) {
    }

    @Override
    public void afterStep(double time, PowerNetworkServer network) {
      vc1 = network.getVoltageAt(q1, 1);
      vc2 = network.getVoltageAt(q2, 1);
      vb1 = network.getVoltageAt(q1, 0);
      vb2 = network.getVoltageAt(q2, 0);
    }

    @Override
    public List<Trace> traces() {
      return List.of(
          new Trace("yellow: Vc1", new Color(255, 208, 102), 6.0, () -> vc1),
          new Trace("orange: Vc2", new Color(255, 150, 120), 6.0, () -> vc2),
          new Trace("green: Vb1", new Color(172, 235, 149), 6.0, () -> vb1),
          new Trace("blue: Vb2", new Color(120, 200, 255), 6.0, () -> vb2)
      );
    }

    @Override
    public List<Readout> readouts() {
      return List.of(
          new Readout("Vc1: %+.3f V", () -> vc1),
          new Readout("Vc2: %+.3f V", () -> vc2),
          new Readout("Vb1: %+.3f V", () -> vb1),
          new Readout("Vb2: %+.3f V", () -> vb2)
      );
    }

    @Override
    public void drawSchematic(Graphics2D g, int left, int top, int width, int height) {
      int yVcc = top + 10;
      int yCol = top + 60;
      int yBase = top + 110;
      int yEmit = top + 180;

      int xL = left + 260;
      int xR = left + 780;
      int xLc = xL - 60;
      int xLb = xL + 60;
      int xRc = xR + 60;
      int xRb = xR - 60;

      Color rail = new Color(200, 205, 214);
      Color yellow = new Color(255, 208, 102);
      Color orange = new Color(255, 150, 120);
      Color green = new Color(172, 235, 149);
      Color blue = new Color(120, 200, 255);
      Color wire = new Color(150, 156, 168);

      g.setStroke(new BasicStroke(2.5f));
      g.setColor(rail);
      g.drawLine(left + 60, yVcc, left + width - 60, yVcc);
      g.drawString("Vcc = 5.0 V", left + 60, yVcc - 6);
      g.drawLine(left + 60, yEmit, left + width - 60, yEmit);
      g.drawString("GND", left + 60, yEmit + 18);

      g.setStroke(new BasicStroke(2f));
      g.setColor(wire);
      drawResistor(g, xLc, yVcc, xLc, yCol, "Rc1 1k");
      drawResistor(g, xRc, yVcc, xRc, yCol, "Rc2 1k");
      drawResistor(g, xLb, yVcc, xLb, yBase, "Rb1 47k");
      drawResistor(g, xRb, yVcc, xRb, yBase, "Rb2 56k");

      g.setColor(rail);
      g.fillOval(xLc - 3, yVcc - 3, 6, 6);
      g.fillOval(xLb - 3, yVcc - 3, 6, 6);
      g.fillOval(xRc - 3, yVcc - 3, 6, 6);
      g.fillOval(xRb - 3, yVcc - 3, 6, 6);

      g.setColor(yellow);
      g.drawLine(xLc, yCol, xL - 12, yCol);
      g.drawString(String.format("Vc1 %+.2f", vc1), xLc - 30, yCol - 6);
      g.setColor(orange);
      g.drawLine(xRc, yCol, xR + 12, yCol);
      g.drawString(String.format("Vc2 %+.2f", vc2), xRc + 6, yCol - 6);

      g.setColor(green);
      g.drawLine(xLb, yBase, xL + 12, yBase);
      g.drawString(String.format("Vb1 %+.2f", vb1), xLb + 6, yBase - 6);
      g.setColor(blue);
      g.drawLine(xRb, yBase, xR - 12, yBase);
      g.drawString(String.format("Vb2 %+.2f", vb2), xRb - 74, yBase - 6);

      drawTransistor(g, xL, yCol, yBase, yEmit, "Q1", vc1 - vb1 > 0.55);
      drawTransistor(g, xR, yCol, yBase, yEmit, "Q2", vc2 - vb2 > 0.55);

      drawCapacitor(g, xL - 12, yCol, xR - 12, yBase, "C1 4.7uF");
      drawCapacitor(g, xR + 12, yCol, xL + 12, yBase, "C2 4.7uF");

      g.setColor(rail);
      g.drawString("BJT switching model plus explicit clamp paths keep the oscillator bounded.",
          left + 60, top + height - 8);
    }

    private static void drawResistor(Graphics2D g, int x0, int y0, int x1, int y1, String label) {
      g.drawLine(x0, y0, x1, y0 + 8);
      int top = y0 + 8;
      int bot = y1 - 8;
      int segs = 6;
      int prevX = x0;
      int prevY = top;
      for (int i = 1; i <= segs; i++) {
        int sy = top + (bot - top) * i / segs;
        int sx = x0 + ((i % 2 == 0) ? -6 : 6);
        g.drawLine(prevX, prevY, sx, sy);
        prevX = sx;
        prevY = sy;
      }
      g.drawLine(prevX, prevY, x1, y1);
      g.drawString(label, x0 + 10, (y0 + y1) / 2);
    }

    private static void drawTransistor(Graphics2D g, int x, int yCol, int yBase, int yEmit,
                                       String label, boolean conducting) {
      Color body = conducting ? new Color(255, 208, 102) : new Color(150, 156, 168);
      g.setColor(body);
      g.setStroke(new BasicStroke(2f));

      int yBody0 = yCol + 6;
      int yBody1 = yEmit - 6;
      int bodyY = (yCol + yEmit) / 2;
      g.drawLine(x, yBody0, x, yBody1);
      g.drawLine(x - 12, yBase, x, yBase);
      g.drawLine(x, yCol, x, yBody0);
      g.drawLine(x, yBody1, x, yEmit);

      int[] arrowX = {x, x - 4, x};
      int[] arrowY = {yEmit - 4, yEmit - 10, yEmit - 12};
      g.fillPolygon(arrowX, arrowY, 3);
      g.drawString(label, x - 8, bodyY + 4);
    }

    private static void drawCapacitor(Graphics2D g, int x0, int y0, int x1, int y1, String label) {
      g.setColor(new Color(180, 200, 230));
      g.setStroke(new BasicStroke(2f));
      double dx = x1 - x0;
      double dy = y1 - y0;
      double len = Math.hypot(dx, dy);
      double ux = dx / len;
      double uy = dy / len;
      double px = -uy;
      double py = ux;

      double midX = (x0 + x1) / 2.0;
      double midY = (y0 + y1) / 2.0;
      double plateGap = 6.0;
      double plateHalf = 12.0;

      double pa0x = midX - ux * plateGap;
      double pa0y = midY - uy * plateGap;
      double pb0x = midX + ux * plateGap;
      double pb0y = midY + uy * plateGap;

      g.drawLine(x0, y0, (int) pa0x, (int) pa0y);
      g.drawLine(x1, y1, (int) pb0x, (int) pb0y);
      g.drawLine((int) (pa0x - px * plateHalf), (int) (pa0y - py * plateHalf),
          (int) (pa0x + px * plateHalf), (int) (pa0y + py * plateHalf));
      g.drawLine((int) (pb0x - px * plateHalf), (int) (pb0y - py * plateHalf),
          (int) (pb0x + px * plateHalf), (int) (pb0y + py * plateHalf));
      g.drawString(label, (int) (midX + px * 18 - 24), (int) (midY + py * 18));
    }
  }
}
