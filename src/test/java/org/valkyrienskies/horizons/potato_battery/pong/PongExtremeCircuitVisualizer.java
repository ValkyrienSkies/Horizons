package org.valkyrienskies.horizons.potato_battery.pong;

import org.valkyrienskies.horizons.potato_battery.CircuitComponents.GroundNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.ComparatorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.CounterNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.DeltaNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.EdgePulsingStepperNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.FixedVoltageNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.GainNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.AndNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.BouncingStepperNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.LatchNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.MaxNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.ModCounterNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.MuxNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.MultiplyNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.NotNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.OscillatorNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.OrNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.OneShotNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.PaddleCollisionLogicNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.PulseAccumulatingScaleNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.QuantizedSlewNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.RectangleRasterNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.ReflectClampNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.SafeDivideNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.StripeRasterNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.SumClampNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.ToneBurstNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.VerticalMeterNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.WindowComparatorNode;
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

public final class PongExtremeCircuitVisualizer {
  private static final double V = 5.0, DT = 1.0 / 240.0, PH = 0.17, PM = 0.06, BS = 0.024, VX0 = 0.55, VY0 = 0.35, PS = 1.25, SERVE = 0.65;
  private static final int WIN = 11, HX = 64, VY = 48, AUDIO_RATE = 24000;
  private static final double AUDIO_MAX_VOLTS = 1.25;
  private static final double AUDIO_MAX_NORMALIZED = 0.28;
  private static final double AUDIO_MAX_SLEW_PER_SAMPLE = 0.05;
  private static final double AUDIO_LOWPASS_ALPHA = 0.18;
  private static final int BALL_RADIUS_CELLS_X = 0;
  private static final int BALL_RADIUS_CELLS_Y = 0;
  private static final double PADDLE_HIT_PADDING = 0.035;
  private PongExtremeCircuitVisualizer() {}
  public static void main(String[] args) { CircuitVisualizer.launch(new Scene()); }
  private static double c01(double v) { return Math.max(0.0, Math.min(1.0, v)); }

  private static final class Scene implements Scenario {
    private final VariableVoltageNode leftManual = new VariableVoltageNode(), leftAiEnable = new VariableVoltageNode(), resetTrigger = new VariableVoltageNode(), rc = new VariableVoltageNode();
    private final GroundNode g = new GroundNode();
    private final FixedVoltageNode fullScale = new FixedVoltageNode(V);
    private final QuantizedSlewNode lp = new QuantizedSlewNode(VY - 1, V), rp = new QuantizedSlewNode(VY - 1, V);
    private final DeltaNode leftAiDx = new DeltaNode(V), leftAiDy = new DeltaNode(V);
    private final FixedVoltageNode leftAiZero = new FixedVoltageNode(0.0), leftAiWallOffset = new FixedVoltageNode(-PM * V);
    private final ComparatorNode leftAiMovingLeft = new ComparatorNode(0.0, V, 0.0);
    private final SumClampNode leftAiXOffset = new SumClampNode(2, V * 8.0), leftAiProjectedY = new SumClampNode(2, V * 8.0);
    private final GainNode leftAiNegDx = new GainNode(-1.0);
    private final SafeDivideNode leftAiTravel = new SafeDivideNode(1.0e-6, 64.0);
    private final MultiplyNode leftAiYAdvance = new MultiplyNode(1.0);
    private final ReflectClampNode leftAiReflectedY = new ReflectClampNode(0.0, V);
    private final MuxNode leftAiTargetMux = new MuxNode();
    private final MuxNode leftMux = new MuxNode();
    private final WindowComparatorNode ready = new WindowComparatorNode(V * 0.05, V * 0.95, V);
    private final OneShotNode reset = new OneShotNode(DT * 3.0, V, true);
    private final OrNode scorePulse = new OrNode();
    private final OrNode serveStart = new OrNode();
    private final OneShotNode serveTimer = new OneShotNode(SERVE, V, true);
    private final NotNode serveTimerDone = new NotNode(V * 0.5, V);
    private final AndNode serveRelease = new AndNode(V * 0.5, V);
    private final LatchNode serveActive = new LatchNode(V);
    private final OrNode serveDirectionSet = new OrNode();
    private final LatchNode serveDirection = new LatchNode(V);
    private final CounterNode ls = new CounterNode(WIN, V), rs = new CounterNode(WIN, V);
    private final PulseAccumulatingScaleNode speed = new PulseAccumulatingScaleNode(0.08, 0.8, V);
    private final ComparatorNode leftWin = new ComparatorNode(V * ((WIN - 0.5) / WIN), V, 0.0);
    private final ComparatorNode rightWin = new ComparatorNode(V * ((WIN - 0.5) / WIN), V, 0.0);
    private final OrNode runSet = new OrNode();
    private final LatchNode run = new LatchNode(V);
    private final BouncingStepperNode vball = new BouncingStepperNode(VY, VY0, V, 1);
    private final EdgePulsingStepperNode hball = new EdgePulsingStepperNode(HX, VX0, V, 1);
    private final PaddleCollisionLogicNode hballCollision = new PaddleCollisionLogicNode(PH / 2.0, PADDLE_HIT_PADDING, V);
    private final OrNode hballHold = new OrNode();
    private final OrNode hballReset = new OrNode();
    private final OrNode hballDirectionSet = new OrNode();
    private final MuxNode hballDirectionValue = new MuxNode();
    private final OscillatorNode clk = new OscillatorNode(120.0, 0.5, V);
    private final ModCounterNode hscan = new ModCounterNode(HX, V);
    private final ModCounterNode vscan = new ModCounterNode(VY, V);
    private final FixedVoltageNode leftPaddleX = new FixedVoltageNode(PM * V), rightPaddleX = new FixedVoltageNode((1.0 - PM) * V);
    private final RectangleRasterNode lpv = new RectangleRasterNode(0.02, PH / 2.0, V), rpv = new RectangleRasterNode(0.02, PH / 2.0, V);
    private final RectangleRasterNode bv = new RectangleRasterNode(BS, BS, V);
    private final VerticalMeterNode lsv = new VerticalMeterNode(0.24, 0.36, 0.12, 0.08, V), rsv = new VerticalMeterNode(0.64, 0.76, 0.12, 0.08, V);
    private final StripeRasterNode net = new StripeRasterNode(0.5, 0.01, 20, V);
    private final MaxNode mix = new MaxNode(6, V);
    private final ToneBurstNode hitSound = new ToneBurstNode(980.0, 0.085, 2.2);
    private final OrNode bounceInhibit = new OrNode();
    private final ToneBurstNode bounceSound = new ToneBurstNode(490.0, 0.12, 2.0);
    private final ToneBurstNode scoreSound = new ToneBurstNode(246.0, 0.22, 2.4);
    private final SumClampNode audioMix = new SumClampNode(3, AUDIO_MAX_VOLTS);
    private final AudioSink audio = new AudioSink();
    private volatile double left = 0.5, right = 0.5, speedScale = 1.0;
    private volatile boolean autoLeft, autoRight = true, lu, ld, ru, rd;
    private volatile boolean resetRequested = true;
    private double lpy, rpy, bx, by, beamX, beamY, video, ballSpeed, audioLevel;
    private double serveTimerLevel, holdLevel, resetLevel, directionSetLevel, directionValueLevel, speedLevel, runLevel, collisionHitLevel;
    private double hscanCount, vscanCount, hdirLevel, vdirLevel;
    private double solverIterations, solverSubsteps, solverLimitHit, solverFailed;
    private double solverUnknowns, solverNonZeros, solverZeroRows, solverZeroColumns, solverMissingDiagonals;
    private boolean over;
    private final boolean[][] raster = new boolean[VY][HX];

    @Override public String title() { return "Pong Extreme Circuit Visualizer"; }
    @Override public double timeStep() { return DT; }

    @Override public void build(CircuitBuilder b) {
      b.add(leftManual).add(leftAiEnable).add(resetTrigger).add(rc).add(g).add(fullScale).add(lp).add(rp).add(leftAiDx).add(leftAiDy).add(leftAiZero).add(leftAiWallOffset).add(leftAiMovingLeft).add(leftAiXOffset).add(leftAiNegDx).add(leftAiTravel).add(leftAiYAdvance).add(leftAiProjectedY).add(leftAiReflectedY).add(leftAiTargetMux).add(leftMux).add(ready).add(reset).add(scorePulse).add(serveStart).add(serveTimer).add(serveTimerDone).add(serveRelease).add(serveActive).add(serveDirectionSet).add(serveDirection).add(ls).add(rs).add(speed).add(leftWin).add(rightWin).add(runSet).add(run).add(vball).add(hball).add(hballCollision).add(hballHold).add(hballReset).add(hballDirectionSet).add(hballDirectionValue)
          .add(leftPaddleX).add(rightPaddleX)
          .add(clk).add(hscan).add(vscan).add(lpv).add(rpv).add(bv).add(lsv).add(rsv).add(net).add(mix)
          .add(hitSound).add(bounceSound).add(scoreSound).add(audioMix)
          .connect(leftManual,0,leftMux,0).connect(leftManual,1,g,0).connect(leftAiEnable,0,leftMux,2).connect(leftAiEnable,1,g,0)
          .connect(resetTrigger,0,reset,0).connect(resetTrigger,1,g,0)
          .connect(hball,5,leftAiDx,0).connect(reset,0,leftAiDx,1)
          .connect(vball,5,leftAiDy,0).connect(reset,0,leftAiDy,1)
          .connect(leftAiZero,0,leftAiMovingLeft,0).connect(leftAiDx,2,leftAiMovingLeft,1)
          .connect(hball,5,leftAiXOffset,0).connect(leftAiWallOffset,0,leftAiXOffset,1)
          .connect(leftAiDx,2,leftAiNegDx,0)
          .connect(leftAiXOffset,2,leftAiTravel,0).connect(leftAiNegDx,1,leftAiTravel,1)
          .connect(leftAiDy,2,leftAiYAdvance,0).connect(leftAiTravel,2,leftAiYAdvance,1)
          .connect(vball,5,leftAiProjectedY,0).connect(leftAiYAdvance,2,leftAiProjectedY,1)
          .connect(leftAiProjectedY,2,leftAiReflectedY,0)
          .connect(lp,1,leftAiTargetMux,0).connect(leftAiReflectedY,1,leftAiTargetMux,1).connect(leftAiMovingLeft,2,leftAiTargetMux,2)
          .connect(leftAiTargetMux,3,leftMux,1).connect(leftMux,3,lp,0)
          .connect(rc,0,rp,0).connect(rc,1,g,0)
          .connect(lp,1,ready,0)
          .connect(fullScale,0,vball,0).connect(hballCollision,5,vball,2).connect(hballCollision,6,vball,3).connect(reset,0,vball,4)
          .connect(lp,1,hballCollision,0).connect(rp,1,hballCollision,1).connect(vball,5,hballCollision,2).connect(hball,6,hballCollision,3).connect(hball,7,hballCollision,4)
          .connect(hballCollision,9,scorePulse,0).connect(hballCollision,10,scorePulse,1)
          .connect(scorePulse,2,serveStart,0).connect(reset,0,serveStart,1)
          .connect(serveStart,2,serveTimer,0).connect(g,0,serveTimer,1)
          .connect(serveTimer,2,serveTimerDone,0)
          .connect(ready,1,serveRelease,0).connect(serveTimerDone,1,serveRelease,1)
          .connect(serveStart,2,serveActive,0).connect(serveRelease,2,serveActive,1)
          .connect(hballCollision,10,serveDirectionSet,0).connect(reset,0,serveDirectionSet,1)
          .connect(serveDirectionSet,2,serveDirection,0).connect(hballCollision,9,serveDirection,1)
          .connect(hballCollision,9,ls,0).connect(reset,0,ls,1).connect(hballCollision,10,rs,0).connect(reset,0,rs,1)
          .connect(hballCollision,5,speed,0).connect(serveTimer,2,speed,1)
          .connect(ls,2,leftWin,0).connect(g,0,leftWin,1).connect(rs,2,rightWin,0).connect(g,0,rightWin,1)
          .connect(leftWin,2,runSet,0).connect(rightWin,2,runSet,1).connect(runSet,2,run,0).connect(reset,0,run,1)
          .connect(run,2,hballHold,0).connect(serveTimer,2,hballHold,1)
          .connect(reset,0,hballReset,0).connect(hballCollision,11,hballReset,1)
          .connect(serveTimer,2,hballDirectionSet,0).connect(hballCollision,7,hballDirectionSet,1)
          .connect(hballCollision,8,hballDirectionValue,0).connect(serveDirection,2,hballDirectionValue,1).connect(serveTimer,2,hballDirectionValue,2)
          .connect(speed,2,hball,0).connect(hballHold,2,hball,1).connect(hballDirectionSet,2,hball,2).connect(hballDirectionValue,3,hball,3).connect(hballReset,2,hball,4)
          .connect(clk,0,hscan,0).connect(reset,0,hscan,1).connect(hscan,3,vscan,0).connect(reset,0,vscan,1)
          .connect(hscan,2,lpv,0).connect(vscan,2,lpv,1).connect(leftPaddleX,0,lpv,2).connect(lp,1,lpv,3)
          .connect(hscan,2,rpv,0).connect(vscan,2,rpv,1).connect(rightPaddleX,0,rpv,2).connect(rp,1,rpv,3)
          .connect(hscan,2,bv,0).connect(vscan,2,bv,1).connect(hball,5,bv,2).connect(vball,5,bv,3)
          .connect(hscan,2,lsv,0).connect(vscan,2,lsv,1).connect(ls,2,lsv,2)
          .connect(hscan,2,rsv,0).connect(vscan,2,rsv,1).connect(rs,2,rsv,2)
          .connect(hscan,2,net,0).connect(vscan,2,net,1)
          .connect(lpv,4,mix,0).connect(rpv,4,mix,1).connect(bv,4,mix,2).connect(lsv,3,mix,3).connect(rsv,3,mix,4).connect(net,2,mix,5)
          .connect(serveTimer,2,bounceInhibit,0).connect(run,2,bounceInhibit,1).connect(bounceInhibit,2,vball,1);
      b.connect(hballCollision,5,hitSound,0).connect(g,0,hitSound,1).connect(run,2,hitSound,2)
          .connect(vball,6,bounceSound,0).connect(g,0,bounceSound,1).connect(bounceInhibit,2,bounceSound,2)
          .connect(hballCollision,9,scoreSound,0).connect(hballCollision,10,scoreSound,1).connect(run,2,scoreSound,2)
          .connect(hitSound,3,audioMix,0).connect(bounceSound,3,audioMix,1).connect(scoreSound,3,audioMix,2);
    }

    @Override public void beforeStep(double time) {
      if (!autoLeft) {
        left = steer(left, lu, ld);
      }
      right = autoRight ? by : steer(right, ru, rd);
      leftManual.setVoltage(left * V);
      leftAiEnable.setVoltage(autoLeft ? V : 0.0);
      resetTrigger.setVoltage(resetRequested ? V : 0.0);
      resetRequested = false;
      rc.setVoltage(right * V);
      speed.setManualScale(speedScale);
    }

    @Override public void afterStep(double time, PowerNetworkServer network) {
      lpy = network.getVoltageAt(lp, 1) / V; rpy = network.getVoltageAt(rp, 1) / V; bx = network.getVoltageAt(hball, 5) / V; by = network.getVoltageAt(vball, 5) / V;
      beamX = hscan.getCount() / (double) (HX - 1); beamY = vscan.getCount() / (double) (VY - 1); video = network.getVoltageAt(mix, 6); over = network.getVoltageAt(run, 2) > V * 0.5;
      ballSpeed = Math.hypot(network.getVoltageAt(hball, 8) > V * 0.5 ? VX0 : -VX0, network.getVoltageAt(vball, 7) > V * 0.5 ? VY0 : -VY0) * speed.scale();
      audioLevel = network.getVoltageAt(audioMix, 3);
      serveTimerLevel = network.getVoltageAt(serveTimer, 2);
      holdLevel = network.getVoltageAt(hballHold, 2);
      resetLevel = network.getVoltageAt(hballReset, 2);
      directionSetLevel = network.getVoltageAt(hballDirectionSet, 2);
      directionValueLevel = network.getVoltageAt(hballDirectionValue, 3);
      speedLevel = network.getVoltageAt(speed, 2);
      runLevel = network.getVoltageAt(run, 2);
      collisionHitLevel = network.getVoltageAt(hballCollision, 5);
      hscanCount = hscan.getCount();
      vscanCount = vscan.getCount();
      hdirLevel = network.getVoltageAt(hball, 8);
      vdirLevel = network.getVoltageAt(vball, 7);
      solverIterations = network.getLastNonlinearIterations();
      solverSubsteps = network.getLastRequestedSubsteps();
      solverLimitHit = network.wasLastIterationLimitHit() ? 1.0 : 0.0;
      solverFailed = network.didLastSolveFail() ? 1.0 : 0.0;
      solverUnknowns = network.getLastUnknownCount();
      solverNonZeros = network.getLastNonZeroCount();
      solverZeroRows = network.getLastZeroRowCount();
      solverZeroColumns = network.getLastZeroColumnCount();
      solverMissingDiagonals = network.getLastMissingDiagonalCount();
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
          new Readout("Audio: %.2f V",()->audioLevel), new Readout("Attract: %.1f",()->runOutput()),
          new Readout("Left Score: %.0f",()->ls.getCount()*1.0), new Readout("Right Score: %.0f",()->rs.getCount()*1.0),
          new Readout("Serve Timer: %.2f V",()->serveTimerLevel), new Readout("Hold: %.2f V",()->holdLevel),
          new Readout("Reset: %.2f V",()->resetLevel), new Readout("Dir Set: %.2f V",()->directionSetLevel),
          new Readout("Dir Value: %.2f V",()->directionValueLevel), new Readout("Speed V: %.2f V",()->speedLevel),
          new Readout("Run Latch: %.2f V",()->runLevel), new Readout("Hit Pulse: %.2f V",()->collisionHitLevel),
          new Readout("HScan: %.0f",()->hscanCount), new Readout("VScan: %.0f",()->vscanCount),
          new Readout("HDir: %.2f V",()->hdirLevel), new Readout("VDir: %.2f V",()->vdirLevel),
          new Readout("Solve Iters: %.0f",()->solverIterations), new Readout("Substeps: %.0f",()->solverSubsteps),
          new Readout("Limit Hit: %.0f",()->solverLimitHit), new Readout("Solve Fail: %.0f",()->solverFailed),
          new Readout("Unknowns: %.0f",()->solverUnknowns), new Readout("NonZeros: %.0f",()->solverNonZeros),
          new Readout("Zero Rows: %.0f",()->solverZeroRows), new Readout("Zero Cols: %.0f",()->solverZeroColumns),
          new Readout("Missing Diag: %.0f",()->solverMissingDiagonals)
      );
    }

    @Override public void drawSchematic(Graphics2D gg, int leftX, int top, int width, int height) {
      Rectangle f = new Rectangle(leftX + 70, top + 20, width - 140, height - 70);
      gg.setColor(new Color(18,22,28)); gg.fillRoundRect(f.x,f.y,f.width,f.height,24,24);
      gg.setColor(new Color(222,228,236)); gg.setStroke(new BasicStroke(2.5f)); gg.drawRoundRect(f.x,f.y,f.width,f.height,24,24);
      drawRaster(gg, f);
      gg.setFont(new Font(Font.MONOSPACED,Font.PLAIN,14));
      gg.drawString("Authentic copy: reset pulse, paddle-ready serve gate, stop/attract logic, clock, scan counters, and raster video gates.", f.x, f.y + f.height + 24);
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

    private void triggerReset() { resetRequested = true; speed.reset(); lp.reset(); rp.reset(); clk.reset(); hscan.reset(); vscan.reset(); serveTimer.reset(); serveActive.reset(); serveDirection.reset(); hitSound.reset(); bounceSound.reset(); scoreSound.reset(); audio.reset(); over = false; }
    private static double steer(double cur, boolean up, boolean down) { return c01(cur + ((down == up) ? 0.0 : (down ? 1 : -1) * PS * DT)); }
    private double runOutput() { return over ? 1.0 : 0.0; }
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
      drawScoreBars(ls.getCount(), true);
      drawScoreBars(rs.getCount(), false);
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
