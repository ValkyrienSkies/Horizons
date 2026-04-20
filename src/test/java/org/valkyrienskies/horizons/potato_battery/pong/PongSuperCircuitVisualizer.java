package org.valkyrienskies.horizons.potato_battery.pong;

import org.valkyrienskies.horizons.potato_battery.CircuitComponents.GroundNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.VariableVoltageNode;
import org.valkyrienskies.horizons.potato_battery.CircuitVisualizer;
import org.valkyrienskies.horizons.potato_battery.CircuitVisualizer.CircuitBuilder;
import org.valkyrienskies.horizons.potato_battery.CircuitVisualizer.Readout;
import org.valkyrienskies.horizons.potato_battery.CircuitVisualizer.Scenario;
import org.valkyrienskies.horizons.potato_battery.CircuitVisualizer.Trace;
import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork;
import org.valkyrienskies.horizons.potato_battery.api.network.CircuitStampContext;
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNode;
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNodeSimulationMode;
import org.valkyrienskies.horizons.potato_battery.impl.PowerNetworkServer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.List;

public final class PongSuperCircuitVisualizer {
  private static final double V = 5.0, DT = 1.0 / 240.0, PH = 0.17, PM = 0.06, BS = 0.024, VX0 = 0.55, VY0 = 0.35, PS = 1.25, SERVE = 0.65;
  private static final int WIN = 11, HX = 64, VY = 48, AUDIO_RATE = 24000;
  private static final double AUDIO_MAX_VOLTS = 1.25;
  private static final double AUDIO_MAX_NORMALIZED = 0.28;
  private static final double AUDIO_MAX_SLEW_PER_SAMPLE = 0.05;
  private static final double AUDIO_LOWPASS_ALPHA = 0.18;
  private static final int BALL_RADIUS_CELLS_X = 0;
  private static final int BALL_RADIUS_CELLS_Y = 0;
  private static final double PADDLE_HIT_PADDING = 0.035;
  private PongSuperCircuitVisualizer() {}
  public static void main(String[] args) { CircuitVisualizer.launch(new Scene()); }
  private static double c01(double v) { return Math.max(0.0, Math.min(1.0, v)); }

  private static final class Scene implements Scenario {
    private final VariableVoltageNode leftManual = new VariableVoltageNode(), leftAiEnable = new VariableVoltageNode(), rc = new VariableVoltageNode();
    private final GroundNode g = new GroundNode();
    private final PaddleNode lp = new PaddleNode(), rp = new PaddleNode();
    private final LeftAiNode leftAi = new LeftAiNode();
    private final LeftControlMuxNode leftMux = new LeftControlMuxNode();
    private final PaddleReadyNode ready = new PaddleReadyNode();
    private final ResetPulseNode reset = new ResetPulseNode();
    private final ServeNode serve = new ServeNode();
    private final ScoreNode ls = new ScoreNode(), rs = new ScoreNode();
    private final SpeedNode speed = new SpeedNode();
    private final RunNode run = new RunNode();
    private final VBallNode vball = new VBallNode();
    private final HBallNode hball = new HBallNode();
    private final ClockNode clk = new ClockNode();
    private final HScanNode hscan = new HScanNode();
    private final VScanNode vscan = new VScanNode();
    private final PaddleVideoNode lpv = new PaddleVideoNode(true), rpv = new PaddleVideoNode(false);
    private final BallVideoNode bv = new BallVideoNode();
    private final ScoreVideoNode lsv = new ScoreVideoNode(true), rsv = new ScoreVideoNode(false);
    private final NetVideoNode net = new NetVideoNode();
    private final MixNode mix = new MixNode();
    private final HitSoundNode hitSound = new HitSoundNode();
    private final BounceSoundNode bounceSound = new BounceSoundNode();
    private final ScoreSoundNode scoreSound = new ScoreSoundNode();
    private final AudioMixNode audioMix = new AudioMixNode();
    private final AudioSink audio = new AudioSink();
    private volatile double left = 0.5, right = 0.5, speedScale = 1.0;
    private volatile boolean autoLeft, autoRight = true, lu, ld, ru, rd;
    private double lpy, rpy, bx, by, beamX, beamY, video, ballSpeed, audioLevel;
    private boolean over;
    private final boolean[][] raster = new boolean[VY][HX];

    @Override public String title() { return "Pong Super Circuit Visualizer"; }
    @Override public double timeStep() { return DT; }

    @Override public void build(CircuitBuilder b) {
      b.add(leftManual).add(leftAiEnable).add(rc).add(g).add(lp).add(rp).add(leftAi).add(leftMux).add(ready).add(reset).add(serve).add(ls).add(rs).add(speed).add(run).add(vball).add(hball)
          .add(clk).add(hscan).add(vscan).add(lpv).add(rpv).add(bv).add(lsv).add(rsv).add(net).add(mix)
          .add(hitSound).add(bounceSound).add(scoreSound).add(audioMix)
          .connect(leftManual,0,leftMux,0).connect(leftManual,1,g,0).connect(leftAiEnable,0,leftMux,2).connect(leftAiEnable,1,g,0)
          .connect(leftAi,3,leftMux,1).connect(leftMux,3,lp,0)
          .connect(rc,0,rp,0).connect(rc,1,g,0)
          .connect(hball,5,leftAi,0).connect(vball,4,leftAi,1).connect(lp,1,leftAi,2)
          .connect(lp,1,ready,0)
          .connect(lp,1,vball,0).connect(rp,1,vball,1).connect(lp,1,hball,0).connect(rp,1,hball,1)
          .connect(vball,4,hball,2).connect(hball,6,vball,2).connect(hball,7,vball,3)
          .connect(hball,8,serve,0).connect(hball,9,serve,1).connect(ready,1,serve,4)
          .connect(hball,8,ls,0).connect(reset,0,ls,2).connect(hball,9,rs,0).connect(reset,0,rs,2)
          .connect(hball,6,speed,0).connect(serve,2,speed,1).connect(speed,2,hball,10)
          .connect(ls,1,run,0).connect(rs,1,run,1).connect(reset,0,run,2).connect(run,3,hball,11).connect(run,3,vball,5)
          .connect(serve,2,hball,3).connect(serve,3,hball,4).connect(reset,0,hball,12).connect(reset,0,vball,7)
          .connect(clk,0,hscan,0).connect(hscan,2,vscan,0)
          .connect(hscan,1,lpv,0).connect(vscan,1,lpv,1).connect(lp,1,lpv,2)
          .connect(hscan,1,rpv,0).connect(vscan,1,rpv,1).connect(rp,1,rpv,2)
          .connect(hscan,1,bv,0).connect(vscan,1,bv,1).connect(hball,5,bv,2).connect(vball,4,bv,3)
          .connect(hscan,1,lsv,0).connect(vscan,1,lsv,1).connect(ls,1,lsv,2)
          .connect(hscan,1,rsv,0).connect(vscan,1,rsv,1).connect(rs,1,rsv,2)
          .connect(hscan,1,net,0).connect(vscan,1,net,1)
          .connect(lpv,3,mix,0).connect(rpv,3,mix,1).connect(bv,4,mix,2).connect(lsv,3,mix,3).connect(rsv,3,mix,4).connect(net,2,mix,5);
      b.connect(hball,6,hitSound,0).connect(run,3,hitSound,1)
          .connect(vball,6,bounceSound,0).connect(serve,2,bounceSound,1).connect(run,3,bounceSound,2)
          .connect(hball,8,scoreSound,0).connect(hball,9,scoreSound,1).connect(run,3,scoreSound,2)
          .connect(hitSound,2,audioMix,0).connect(bounceSound,3,audioMix,1).connect(scoreSound,3,audioMix,2);
    }

    @Override public void beforeStep(double time) {
      if (!autoLeft) {
        left = steer(left, lu, ld);
      }
      right = autoRight ? by : steer(right, ru, rd);
      leftManual.setVoltage(left * V);
      leftAiEnable.setVoltage(autoLeft ? V : 0.0);
      rc.setVoltage(right * V);
      speed.setManualScale(speedScale);
    }

    @Override public void afterStep(double time, PowerNetworkServer network) {
      lpy = lp.pos(); rpy = rp.pos(); bx = hball.pos(); by = vball.pos();
      beamX = hscan.pos(); beamY = vscan.pos(); video = mix.level(); over = run.over();
      ballSpeed = Math.hypot(hball.vel(), vball.vel()) * speed.scale();
      audioLevel = audioMix.level();
      updateRaster();
      audio.push(audioLevel);
    }

    @Override public List<Trace> traces() {
      return List.of(
          new Trace("yellow: ball x", new Color(255,208,102),1.2,()->(bx-0.5)*2.0),
          new Trace("orange: ball y", new Color(255,150,120),1.2,()->(by-0.5)*2.0),
          new Trace("teal: beam x", new Color(120,236,220),1.0,()->(beamX-0.5)*2.0),
          new Trace("violet: video", new Color(196,156,255),1.0,()->video / V),
          new Trace("red: audio", new Color(255,120,120),1.0,()->audioLevel / V)
      );
    }

    @Override public List<Readout> readouts() {
      return List.of(
          new Readout("Ball X: %.3f",()->bx), new Readout("Ball Y: %.3f",()->by),
          new Readout("Beam X: %.3f",()->beamX), new Readout("Beam Y: %.3f",()->beamY),
          new Readout("Video: %.2f V",()->video), new Readout("Speed: %.3f",()->ballSpeed),
          new Readout("Audio: %.2f V",()->audioLevel), new Readout("Attract: %.1f",()->run.attract() ? 1.0 : 0.0),
          new Readout("Left Score: %.0f",()->ls.score()*1.0), new Readout("Right Score: %.0f",()->rs.score()*1.0)
      );
    }

    @Override public void drawSchematic(Graphics2D gg, int leftX, int top, int width, int height) {
      Rectangle f = new Rectangle(leftX + 70, top + 20, width - 140, height - 70);
      gg.setColor(new Color(18,22,28)); gg.fillRoundRect(f.x,f.y,f.width,f.height,24,24);
      gg.setColor(new Color(222,228,236)); gg.setStroke(new BasicStroke(2.5f)); gg.drawRoundRect(f.x,f.y,f.width,f.height,24,24);
      drawRaster(gg, f);
      gg.setFont(new Font(Font.MONOSPACED,Font.PLAIN,14));
    }

    @Override public void populateControls(JPanel controls) {
      JLabel ll = CircuitVisualizer.createValueLabel(String.format("%.0f%%", left * 100.0));
      JSlider lsli = new JSlider(0,100,(int)Math.round(left * 100.0));
      lsli.addChangeListener(e -> {
        if (!autoLeft) left = lsli.getValue() / 100.0;
        double shown = autoLeft ? left : lsli.getValue() / 100.0;
        ll.setText(String.format("%.0f%%", shown * 100.0));
      });
      controls.add(CircuitVisualizer.labeledControl("Left Paddle", lsli, ll));
      JLabel rl = CircuitVisualizer.createValueLabel(String.format("%.0f%%", right * 100.0));
      JSlider rsli = new JSlider(0,100,(int)Math.round(right * 100.0));
      rsli.addChangeListener(e -> { if (!autoRight) right = rsli.getValue() / 100.0; rl.setText(String.format("%.0f%%", (autoRight ? right : rsli.getValue() / 100.0) * 100.0)); });
      controls.add(CircuitVisualizer.labeledControl("Right Paddle", rsli, rl));
      JLabel sl = CircuitVisualizer.createValueLabel(String.format("%.2fx", speedScale));
      JSlider ssli = new JSlider(50,250,(int)Math.round(speedScale * 100.0));
      ssli.addChangeListener(e -> { speedScale = ssli.getValue() / 100.0; sl.setText(String.format("%.2fx", speedScale)); });
      controls.add(CircuitVisualizer.labeledControl("Ball Speed", ssli, sl));
      controls.setFocusable(true);
      controls.addKeyListener(new KeyAdapter() {
        @Override public void keyPressed(KeyEvent e) {
          switch (e.getKeyCode()) {
            case KeyEvent.VK_W -> lu = true; case KeyEvent.VK_S -> ld = true;
            case KeyEvent.VK_UP -> { autoRight = false; ru = true; }
            case KeyEvent.VK_DOWN -> { autoRight = false; rd = true; }
            case KeyEvent.VK_Q -> autoLeft = !autoLeft;
            case KeyEvent.VK_A -> autoRight = !autoRight; case KeyEvent.VK_R -> triggerReset();
            default -> {}
          }
        }
        @Override public void keyReleased(KeyEvent e) {
          switch (e.getKeyCode()) {
            case KeyEvent.VK_W -> lu = false; case KeyEvent.VK_S -> ld = false;
            case KeyEvent.VK_UP -> ru = false; case KeyEvent.VK_DOWN -> rd = false;
            default -> {}
          }
        }
      });
    }

    private void triggerReset() { reset.trigger(); serve.arm(true); speed.reset(); run.reset(); clk.reset(); hscan.reset(); vscan.reset(); hitSound.reset(); bounceSound.reset(); scoreSound.reset(); audio.reset(); over = false; }
    private static double steer(double cur, boolean up, boolean down) { return c01(cur + ((down == up) ? 0.0 : (down ? 1 : -1) * PS * DT)); }
    private void updateRaster() {
      clearRaster(raster);
      int leftX = Math.max(0, (int)Math.round(PM * (HX - 1)));
      int rightX = Math.min(HX - 1, (int)Math.round((1.0 - PM) * (HX - 1)));
      int paddleRadius = Math.max(2, (int)Math.ceil(PH * (VY - 1) * 0.5));
      int leftY = Math.max(0, Math.min(VY - 1, (int)Math.round(lpy * (VY - 1))));
      int rightY = Math.max(0, Math.min(VY - 1, (int)Math.round(rpy * (VY - 1))));
      int ballX = Math.max(0, Math.min(HX - 1, (int)Math.round(bx * (HX - 1))));
      int ballY = Math.max(0, Math.min(VY - 1, (int)Math.round(by * (VY - 1))));
      int netX = HX / 2;
      for (int y = 0; y < VY; y++) {
        if (Math.abs(y - leftY) <= paddleRadius) raster[y][leftX] = true;
        if (Math.abs(y - rightY) <= paddleRadius) raster[y][rightX] = true;
        if (((y / 2) % 2) == 0) raster[y][netX] = true;
      }
      for (int y = Math.max(0, ballY - BALL_RADIUS_CELLS_Y); y <= Math.min(VY - 1, ballY + BALL_RADIUS_CELLS_Y); y++) {
        for (int x = Math.max(0, ballX - BALL_RADIUS_CELLS_X); x <= Math.min(HX - 1, ballX + BALL_RADIUS_CELLS_X); x++) {
          raster[y][x] = true;
        }
      }
      drawScoreBars(ls.score(), true);
      drawScoreBars(rs.score(), false);
    }
    private void drawScoreBars(int score, boolean leftSide) {
      int litRows = Math.max(0, Math.min(8, (int)Math.round((score / (double)WIN) * 8.0)));
      int startX = leftSide ? 15 : HX - 20;
      for (int y = 2; y < 2 + litRows; y++) {
        for (int x = startX; x < startX + 4; x++) {
          raster[y][x] = true;
        }
      }
    }
    private void drawRaster(Graphics2D g, Rectangle f) {
      int cell = Math.max(2, Math.min(f.width / HX, f.height / VY));
      int drawW = cell * HX;
      int drawH = cell * VY;
      int ox = f.x + (f.width - drawW) / 2;
      int oy = f.y + (f.height - drawH) / 2;
      g.setColor(new Color(238, 242, 246));
      for (int y = 0; y < VY; y++) {
        for (int x = 0; x < HX; x++) {
          if (raster[y][x]) {
            g.fillRect(ox + x * cell, oy + y * cell, cell, cell);
          }
        }
      }
    }
    private static void clearRaster(boolean[][] raster) {
      for (int y = 0; y < raster.length; y++) {
        for (int x = 0; x < raster[y].length; x++) {
          raster[y][x] = false;
        }
      }
    }
  }

  private static final class PaddleNode extends PowerNode {
    private int cell = (VY - 1) / 2; private PaddleNode() { super(2); }
    @Override public int getVoltageSourceCount() { return 1; }
    @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_LINEAR; }
    @Override public void stamp(CircuitStampContext c) { c.stampVoltageSource(0,1,CircuitStampContext.GROUND,pos() * V); }
    @Override public void onSubstepComplete(IPowerNetwork<?> n, double dt) { int target = (int)Math.round(c01(n.getVoltageAt(this,0) / V) * (VY - 1)); if (target > cell) cell++; else if (target < cell) cell--; }
    double pos() { return cell / (double)(VY - 1); }
  }
  private static final class LeftAiNode extends PowerNode {
    private double target = 0.5, prevX = 0.5, prevY = 0.5; private LeftAiNode() { super(4); }
    @Override public int getVoltageSourceCount() { return 1; }
    @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_LINEAR; }
    @Override public void stamp(CircuitStampContext c) { c.stampVoltageSource(0,3,CircuitStampContext.GROUND,target * V); }
    @Override public void onSubstepComplete(IPowerNetwork<?> n, double dt) {
      double x = c01(n.getVoltageAt(this,0) / V), y = c01(n.getVoltageAt(this,1) / V), paddle = c01(n.getVoltageAt(this,2) / V);
      double vx = (x - prevX) / Math.max(1.0e-6, dt), vy = (y - prevY) / Math.max(1.0e-6, dt);
      prevX = x; prevY = y;
      if (vx >= 0.0) {
        target = paddle;
        return;
      }
      double travel = (x - c01(PM)) / Math.max(1.0e-6, -vx);
      double projected = y + vy * travel;
      while (projected < 0.0 || projected > 1.0) {
        if (projected < 0.0) projected = -projected;
        if (projected > 1.0) projected = 2.0 - projected;
      }
      target = c01(projected);
    }
  }
  private static final class LeftControlMuxNode extends PowerNode {
    private double out; private LeftControlMuxNode() { super(4); }
    @Override public int getVoltageSourceCount() { return 1; }
    @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_LINEAR; }
    @Override public void stamp(CircuitStampContext c) { c.stampVoltageSource(0,3,CircuitStampContext.GROUND,out); }
    @Override public void onSubstepComplete(IPowerNetwork<?> n, double dt) { out = n.getVoltageAt(this,2) > 2.5 ? n.getVoltageAt(this,1) : n.getVoltageAt(this,0); }
  }
  private static final class PaddleReadyNode extends PowerNode {
    private double out; private PaddleReadyNode() { super(2); }
    @Override public int getVoltageSourceCount() { return 1; }
    @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_LINEAR; }
    @Override public void stamp(CircuitStampContext c) { c.stampVoltageSource(0,1,CircuitStampContext.GROUND,out); }
    @Override public void onSubstepComplete(IPowerNetwork<?> n, double dt) { double p = c01(n.getVoltageAt(this,0) / V); out = p >= 0.05 && p <= 0.95 ? V : 0.0; }
  }
  private static final class ResetPulseNode extends PowerNode {
    private int ticks; private ResetPulseNode() { super(1); }
    @Override public int getVoltageSourceCount() { return 1; }
    @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_LINEAR; }
    @Override public void stamp(CircuitStampContext c) { c.stampVoltageSource(0,0,CircuitStampContext.GROUND, ticks > 0 ? V : 0.0); }
    @Override public void onSubstepComplete(IPowerNetwork<?> n, double dt) { if (ticks > 0) ticks--; }
    void trigger() { ticks = 3; }
  }
  private static final class ServeNode extends PowerNode {
    private double rem = SERVE; private boolean toRight = true, active = true; private double pl, pr; private ServeNode() { super(5); }
    @Override public int getVoltageSourceCount() { return 2; }
    @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_LINEAR; }
    @Override public void stamp(CircuitStampContext c) { c.stampVoltageSource(0,2,CircuitStampContext.GROUND, active ? V : 0); c.stampVoltageSource(1,3,CircuitStampContext.GROUND, toRight ? V : 0); }
    @Override public void onSubstepComplete(IPowerNetwork<?> n, double dt) {
      double l = n.getVoltageAt(this,0), r = n.getVoltageAt(this,1), ready = n.getVoltageAt(this,4);
      if (l > 2.5 && pl <= 2.5) arm(false);
      if (r > 2.5 && pr <= 2.5) arm(true);
      if (active && rem > 0) rem = Math.max(0.0, rem - dt);
      if (active && rem <= 0.0 && ready > 2.5) active = false;
      pl = l; pr = r;
    }
    void arm(boolean tr) { toRight = tr; active = true; rem = SERVE; pl = 0; pr = 0; }
  }
  private static final class ScoreNode extends PowerNode {
    private int s; private double pp, pr; private ScoreNode() { super(3); }
    @Override public int getVoltageSourceCount() { return 1; }
    @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_LINEAR; }
    @Override public void stamp(CircuitStampContext c) { c.stampVoltageSource(0,1,CircuitStampContext.GROUND, Math.min(s, WIN) * (V / WIN)); }
    @Override public void onSubstepComplete(IPowerNetwork<?> n, double dt) { double p = n.getVoltageAt(this,0), r = n.getVoltageAt(this,2); if (r > 2.5 && pr <= 2.5) s = 0; else if (p > 2.5 && pp <= 2.5) s = Math.min(s + 1, WIN); pp = p; pr = r; }
    int score() { return s; }
  }
  private static final class SpeedNode extends PowerNode {
    private double manual = 1.0, hits, prev; private SpeedNode() { super(3); }
    @Override public int getVoltageSourceCount() { return 1; }
    @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_LINEAR; }
    @Override public void stamp(CircuitStampContext c) { c.stampVoltageSource(0,2,CircuitStampContext.GROUND, scale() * V); }
    @Override public void onSubstepComplete(IPowerNetwork<?> n, double dt) { double h = n.getVoltageAt(this,0), s = n.getVoltageAt(this,1); if (s > 2.5) hits = 0; else if (h > 2.5 && prev <= 2.5) hits = Math.min(hits + 0.08, 0.8); prev = h; }
    void setManualScale(double v) { manual = Math.max(0.25, v); } void reset() { hits = 0; prev = 0; } double scale() { return manual * (1.0 + hits); }
  }
  private static final class RunNode extends PowerNode {
    private boolean over; private RunNode() { super(5); }
    @Override public int getVoltageSourceCount() { return 2; }
    @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_LINEAR; }
    @Override public void stamp(CircuitStampContext c) { c.stampVoltageSource(0,3,CircuitStampContext.GROUND, over ? V : 0); c.stampVoltageSource(1,4,CircuitStampContext.GROUND, over ? V : 0); }
    @Override public void onSubstepComplete(IPowerNetwork<?> n, double dt) { int l = (int)Math.round(n.getVoltageAt(this,0) / V * WIN), r = (int)Math.round(n.getVoltageAt(this,1) / V * WIN); double reset = n.getVoltageAt(this,2); if (reset > 2.5) over = false; else if (l >= WIN || r >= WIN) over = true; }
    boolean over() { return over; } boolean attract() { return over; } void reset() { over = false; }
  }
  private static final class VBallNode extends PowerNode {
    private int cell = (VY - 1) / 2, dir = 1; private double stepAccum; private boolean bounce; private VBallNode() { super(8); }
    @Override public int getVoltageSourceCount() { return 2; }
    @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_NONLINEAR; }
    @Override public double getSuggestedMaxTimeStepSeconds() { return 1.0 / 3000.0; }
    @Override public void stamp(CircuitStampContext c) { c.stampVoltageSource(0,4,CircuitStampContext.GROUND,pos() * V); c.stampVoltageSource(1,6,CircuitStampContext.GROUND,bounce ? V : 0); }
    @Override public void onSubstepComplete(IPowerNetwork<?> n, double dt) {
      if (n.getVoltageAt(this,7) > 2.5) { reset(); return; }
      bounce = false;
      if (n.getVoltageAt(this,5) > 2.5) return;
      if (n.getVoltageAt(this,2) > 2.5) {
        double d = (n.getVoltageAt(this,3) / V) * 2.0 - 1.0;
        dir = d >= 0.0 ? 1 : -1;
      }
      stepAccum += Math.abs(VY0) * (VY - 1) * dt;
      while (stepAccum >= 1.0) {
        stepAccum -= 1.0;
        cell += dir;
        if (cell <= 0) { cell = 0; dir = 1; bounce = true; }
        else if (cell >= VY - 1) { cell = VY - 1; dir = -1; bounce = true; }
      }
    }
    void reset() { cell = (VY - 1) / 2; dir = 1; stepAccum = 0.0; bounce = false; } double pos() { return cell / (double)(VY - 1); } double vel() { return dir * VY0; }
  }
  private static final class HBallNode extends PowerNode {
    private int cell = (HX - 1) / 2, dir = 1; private double def = 0.5, stepAccum; private boolean hit, lp, rp; private HBallNode() { super(13); }
    @Override public int getVoltageSourceCount() { return 5; }
    @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_NONLINEAR; }
    @Override public double getSuggestedMaxTimeStepSeconds() { return 1.0 / 3000.0; }
    @Override public void stamp(CircuitStampContext c) { c.stampVoltageSource(0,5,CircuitStampContext.GROUND,pos() * V); c.stampVoltageSource(1,6,CircuitStampContext.GROUND,hit ? V : 0); c.stampVoltageSource(2,7,CircuitStampContext.GROUND,def * V); c.stampVoltageSource(3,8,CircuitStampContext.GROUND,lp ? V : 0); c.stampVoltageSource(4,9,CircuitStampContext.GROUND,rp ? V : 0); }
    @Override public void onSubstepComplete(IPowerNetwork<?> n, double dt) {
      if (n.getVoltageAt(this,12) > 2.5) { reset(); return; }
      hit = false; lp = false; rp = false;
      if (n.getVoltageAt(this,11) > 2.5) return;
      if (n.getVoltageAt(this,3) > 2.5) { dir = n.getVoltageAt(this,4) > 2.5 ? 1 : -1; cell = (HX - 1) / 2; stepAccum = 0.0; return; }
      double s = Math.max(0.25, n.getVoltageAt(this,10) / V), l = c01(n.getVoltageAt(this,0) / V), r = c01(n.getVoltageAt(this,1) / V), y = c01(n.getVoltageAt(this,2) / V);
      int leftCell = Math.max(0, (int)Math.round(PM * (HX - 1))) + BALL_RADIUS_CELLS_X;
      int rightCell = Math.min(HX - 1, (int)Math.round((1.0 - PM) * (HX - 1))) - BALL_RADIUS_CELLS_X;
      stepAccum += Math.abs(VX0) * s * (HX - 1) * dt;
      while (stepAccum >= 1.0) {
        stepAccum -= 1.0;
        cell += dir;
        if (cell <= leftCell) {
          if (hit(y, l)) { cell = leftCell; dir = 1; def = c01(0.5 + (y - l) / PH); hit = true; }
          else { rp = true; cell = (HX - 1) / 2; stepAccum = 0.0; break; }
        } else if (cell >= rightCell) {
          if (hit(y, r)) { cell = rightCell; dir = -1; def = c01(0.5 + (y - r) / PH); hit = true; }
          else { lp = true; cell = (HX - 1) / 2; stepAccum = 0.0; break; }
        }
      }
    }
    private boolean hit(double by, double py) { double t = py - (PH / 2.0 + PADDLE_HIT_PADDING), b = py + (PH / 2.0 + PADDLE_HIT_PADDING); return by >= t && by <= b; }
    void reset() { cell = (HX - 1) / 2; dir = 1; def = 0.5; hit = false; lp = false; rp = false; stepAccum = 0.0; } double pos() { return cell / (double)(HX - 1); } double vel() { return dir * VX0; }
  }
  private static final class ClockNode extends PowerNode {
    private boolean hi; private ClockNode() { super(1); }
    @Override public int getVoltageSourceCount() { return 1; }
    @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_LINEAR; }
    @Override public void stamp(CircuitStampContext c) { c.stampVoltageSource(0,0,CircuitStampContext.GROUND, hi ? V : 0); }
    @Override public void onSubstepComplete(IPowerNetwork<?> n, double dt) { hi = !hi; }
    void reset() { hi = false; }
  }
  private static final class HScanNode extends PowerNode {
    private int i; private double prev; private boolean reset; private HScanNode() { super(3); }
    @Override public int getVoltageSourceCount() { return 2; }
    @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_LINEAR; }
    @Override public void stamp(CircuitStampContext c) { c.stampVoltageSource(0,1,CircuitStampContext.GROUND,pos() * V); c.stampVoltageSource(1,2,CircuitStampContext.GROUND, reset ? V : 0); }
    @Override public void onSubstepComplete(IPowerNetwork<?> n, double dt) { double cl = n.getVoltageAt(this,0); reset = false; if (cl > 2.5 && prev <= 2.5) { i++; if (i >= HX) { i = 0; reset = true; } } prev = cl; }
    void reset() { i = 0; prev = 0; reset = false; } double pos() { return i / (double)(HX - 1); }
  }
  private static final class VScanNode extends PowerNode {
    private int i; private double prev; private boolean reset; private VScanNode() { super(3); }
    @Override public int getVoltageSourceCount() { return 2; }
    @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_LINEAR; }
    @Override public void stamp(CircuitStampContext c) { c.stampVoltageSource(0,1,CircuitStampContext.GROUND,pos() * V); c.stampVoltageSource(1,2,CircuitStampContext.GROUND, reset ? V : 0); }
    @Override public void onSubstepComplete(IPowerNetwork<?> n, double dt) { double hr = n.getVoltageAt(this,0); reset = false; if (hr > 2.5 && prev <= 2.5) { i++; if (i >= VY) { i = 0; reset = true; } } prev = hr; }
    void reset() { i = 0; prev = 0; reset = false; } double pos() { return i / (double)(VY - 1); }
  }
  private static final class PaddleVideoNode extends PowerNode {
    private final boolean left; private double out; private PaddleVideoNode(boolean left) { super(4); this.left = left; }
    @Override public int getVoltageSourceCount() { return 1; }
    @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_LINEAR; }
    @Override public void stamp(CircuitStampContext c) { c.stampVoltageSource(0,3,CircuitStampContext.GROUND,out); }
    @Override public void onSubstepComplete(IPowerNetwork<?> n, double dt) { double x = c01(n.getVoltageAt(this,0) / V), y = c01(n.getVoltageAt(this,1) / V), p = c01(n.getVoltageAt(this,2) / V), px = left ? PM : 1.0 - PM; out = Math.abs(x - px) <= 0.02 && Math.abs(y - p) <= PH / 2.0 ? V : 0; }
  }
  private static final class BallVideoNode extends PowerNode {
    private double out; private BallVideoNode() { super(5); }
    @Override public int getVoltageSourceCount() { return 1; }
    @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_LINEAR; }
    @Override public void stamp(CircuitStampContext c) { c.stampVoltageSource(0,4,CircuitStampContext.GROUND,out); }
    @Override public void onSubstepComplete(IPowerNetwork<?> n, double dt) { double x = c01(n.getVoltageAt(this,0) / V), y = c01(n.getVoltageAt(this,1) / V), bx = c01(n.getVoltageAt(this,2) / V), by = c01(n.getVoltageAt(this,3) / V); out = Math.abs(x - bx) <= BS && Math.abs(y - by) <= BS ? V : 0; }
  }
  private static final class ScoreVideoNode extends PowerNode {
    private final boolean left; private double out; private ScoreVideoNode(boolean left) { super(4); this.left = left; }
    @Override public int getVoltageSourceCount() { return 1; }
    @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_LINEAR; }
    @Override public void stamp(CircuitStampContext c) { c.stampVoltageSource(0,3,CircuitStampContext.GROUND,out); }
    @Override public void onSubstepComplete(IPowerNetwork<?> n, double dt) { double x = c01(n.getVoltageAt(this,0) / V), y = c01(n.getVoltageAt(this,1) / V); int s = (int)Math.round(n.getVoltageAt(this,2) / V * WIN); double min = left ? 0.24 : 0.64, max = left ? 0.36 : 0.76, lit = s / (double)WIN; out = x >= min && x <= max && y <= 0.12 && y >= 0.12 - lit * 0.08 ? V : 0; }
  }
  private static final class NetVideoNode extends PowerNode {
    private double out; private NetVideoNode() { super(3); }
    @Override public int getVoltageSourceCount() { return 1; }
    @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_LINEAR; }
    @Override public void stamp(CircuitStampContext c) { c.stampVoltageSource(0,2,CircuitStampContext.GROUND,out); }
    @Override public void onSubstepComplete(IPowerNetwork<?> n, double dt) { double x = c01(n.getVoltageAt(this,0) / V), y = c01(n.getVoltageAt(this,1) / V); out = Math.abs(x - 0.5) <= 0.01 && ((int)Math.floor(y * 20.0) % 2 == 0) ? V * 0.6 : 0; }
  }
  private static final class MixNode extends PowerNode {
    private double out; private MixNode() { super(6); }
    @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_LINEAR; }
    @Override public void onSubstepComplete(IPowerNetwork<?> n, double dt) { out = 0; for (int i = 0; i < 6; i++) out = Math.max(out, n.getVoltageAt(this,i)); }
    double level() { return out; }
  }
  private static final class HitSoundNode extends PowerNode {
    private double phase, prev, remaining, out; private boolean high; private HitSoundNode() { super(3); }
    @Override public int getVoltageSourceCount() { return 1; }
    @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_LINEAR; }
    @Override public void stamp(CircuitStampContext c) { c.stampVoltageSource(0,2,CircuitStampContext.GROUND,out); }
    @Override public void onSubstepComplete(IPowerNetwork<?> n, double dt) {
      double t = n.getVoltageAt(this,0), mute = n.getVoltageAt(this,1);
      if (t > 2.5 && prev <= 2.5) { remaining = 0.085; phase = 0.0; high = false; }
      prev = t;
      if (mute > 2.5 || remaining <= 0.0) { out = 0.0; remaining = Math.max(0.0, remaining - dt); return; }
      remaining = Math.max(0.0, remaining - dt);
      phase += 980.0 * dt;
      if (phase >= 0.5) { phase -= 0.5; high = !high; }
      double envelope = Math.min(1.0, remaining / 0.085);
      out = (high ? 1.0 : -1.0) * envelope * 2.2;
    }
    void reset() { phase = 0; prev = 0; remaining = 0; out = 0; high = false; }
  }
  private static final class BounceSoundNode extends PowerNode {
    private double phase, prev, remaining, out; private boolean high; private BounceSoundNode() { super(4); }
    @Override public int getVoltageSourceCount() { return 1; }
    @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_LINEAR; }
    @Override public void stamp(CircuitStampContext c) { c.stampVoltageSource(0,3,CircuitStampContext.GROUND,out); }
    @Override public void onSubstepComplete(IPowerNetwork<?> n, double dt) {
      double t = n.getVoltageAt(this,0), serveInhibit = n.getVoltageAt(this,1), mute = n.getVoltageAt(this,2);
      if (serveInhibit <= 2.5 && t > 2.5 && prev <= 2.5) { remaining = 0.12; phase = 0.0; high = false; }
      prev = t;
      if (mute > 2.5 || remaining <= 0.0) { out = 0.0; remaining = Math.max(0.0, remaining - dt); return; }
      remaining = Math.max(0.0, remaining - dt);
      phase += 490.0 * dt;
      if (phase >= 0.5) { phase -= 0.5; high = !high; }
      double envelope = Math.min(1.0, remaining / 0.12);
      out = (high ? 1.0 : -1.0) * envelope * 2.0;
    }
    void reset() { phase = 0; prev = 0; remaining = 0; out = 0; high = false; }
  }
  private static final class ScoreSoundNode extends PowerNode {
    private double phase, pl, pr, remaining, out; private boolean high; private ScoreSoundNode() { super(4); }
    @Override public int getVoltageSourceCount() { return 1; }
    @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_LINEAR; }
    @Override public void stamp(CircuitStampContext c) { c.stampVoltageSource(0,3,CircuitStampContext.GROUND,out); }
    @Override public void onSubstepComplete(IPowerNetwork<?> n, double dt) {
      double l = n.getVoltageAt(this,0), r = n.getVoltageAt(this,1), mute = n.getVoltageAt(this,2);
      if ((l > 2.5 && pl <= 2.5) || (r > 2.5 && pr <= 2.5)) { remaining = 0.22; phase = 0.0; high = false; }
      pl = l; pr = r;
      if (mute > 2.5 || remaining <= 0.0) { out = 0.0; remaining = Math.max(0.0, remaining - dt); return; }
      remaining = Math.max(0.0, remaining - dt);
      phase += 246.0 * dt;
      if (phase >= 0.5) { phase -= 0.5; high = !high; }
      double envelope = Math.min(1.0, remaining / 0.22);
      out = (high ? 1.0 : -1.0) * envelope * 2.4;
    }
    void reset() { phase = 0; pl = 0; pr = 0; remaining = 0; out = 0; high = false; }
  }
  private static final class AudioMixNode extends PowerNode {
    private double out; private AudioMixNode() { super(3); }
    @Override public PowerNodeSimulationMode getSimulationMode() { return PowerNodeSimulationMode.DYNAMIC_LINEAR; }
    @Override public void onSubstepComplete(IPowerNetwork<?> n, double dt) {
      double mixed = n.getVoltageAt(this,0) + n.getVoltageAt(this,1) + n.getVoltageAt(this,2);
      if (!Double.isFinite(mixed)) mixed = 0.0;
      out = Math.max(-AUDIO_MAX_VOLTS, Math.min(AUDIO_MAX_VOLTS, mixed));
    }
    double level() { return out; }
  }
  private static final class AudioSink {
    private final SourceDataLine line;
    private final byte[] frame;
    private double filteredSample;
    private double limitedSample;
    private AudioSink() {
      SourceDataLine opened = null;
      try {
        AudioFormat format = new AudioFormat(AUDIO_RATE, 16, 1, true, false);
        opened = AudioSystem.getSourceDataLine(format);
        opened.open(format, AUDIO_RATE / 2);
        opened.start();
      } catch (LineUnavailableException ignored) {
      }
      line = opened;
      frame = new byte[Math.max(2, (int)Math.round(AUDIO_RATE * DT) * 2)];
    }
    void push(double level) {
      if (line == null) return;
      double normalized = Double.isFinite(level) ? level / AUDIO_MAX_VOLTS : 0.0;
      normalized = Math.max(-1.0, Math.min(1.0, normalized));
      for (int i = 0; i < frame.length; i += 2) {
        filteredSample += (normalized - filteredSample) * AUDIO_LOWPASS_ALPHA;
        double delta = filteredSample - limitedSample;
        if (delta > AUDIO_MAX_SLEW_PER_SAMPLE) delta = AUDIO_MAX_SLEW_PER_SAMPLE;
        else if (delta < -AUDIO_MAX_SLEW_PER_SAMPLE) delta = -AUDIO_MAX_SLEW_PER_SAMPLE;
        limitedSample += delta;
        double clipped = Math.tanh(limitedSample * 1.4) * AUDIO_MAX_NORMALIZED;
        short sample = (short)Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, clipped * Short.MAX_VALUE));
        frame[i] = (byte)(sample & 0xFF);
        frame[i + 1] = (byte)((sample >>> 8) & 0xFF);
      }
      line.write(frame, 0, frame.length);
    }
    void reset() {
      if (line != null) line.flush();
      filteredSample = 0.0;
      limitedSample = 0.0;
    }
  }
}
