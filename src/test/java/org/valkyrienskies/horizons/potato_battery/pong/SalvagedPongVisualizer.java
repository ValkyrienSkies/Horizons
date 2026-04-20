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

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.List;

public final class SalvagedPongVisualizer {
  private static final double SUPPLY_VOLTAGE = 5.0;
  private static final double TIME_STEP = 1.0 / 240.0;
  private static final double PADDLE_HEIGHT = 0.17;
  private static final double PADDLE_MARGIN = 0.06;
  private static final double BALL_SIZE = 0.024;
  private static final double BASE_BALL_SPEED_X = 0.55;
  private static final double BASE_BALL_SPEED_Y = 0.35;
  private static final double PADDLE_SPEED = 1.25;
  private static final double SERVE_TIME_SECONDS = 0.65;
  private static final int WIN_SCORE = 11;
  private static final int H_CELLS = 64;
  private static final int V_CELLS = 48;

  private SalvagedPongVisualizer() {
  }

  public static void main(String[] args) {
    CircuitVisualizer.launch(new PongRasterScenario());
  }

  private static double clamp01(double value) {
    return Math.max(0.0, Math.min(1.0, value));
  }

  private static final class PongRasterScenario implements Scenario {
    private final VariableVoltageNode leftControl = new VariableVoltageNode();
    private final VariableVoltageNode rightControl = new VariableVoltageNode();
    private final GroundNode ground = new GroundNode();

    private final PaddleRegisterNode leftPaddle = new PaddleRegisterNode();
    private final PaddleRegisterNode rightPaddle = new PaddleRegisterNode();
    private final ServeTimerNode serveTimer = new ServeTimerNode();
    private final ScoreCounterNode leftScore = new ScoreCounterNode();
    private final ScoreCounterNode rightScore = new ScoreCounterNode();
    private final BallSpeedNode ballSpeedNode = new BallSpeedNode();
    private final GameControlNode gameControl = new GameControlNode();
    private final VerticalBallNode verticalBall = new VerticalBallNode();
    private final HorizontalBallNode horizontalBall = new HorizontalBallNode();

    private final MasterClockNode masterClock = new MasterClockNode();
    private final HorizontalScanNode horizontalScan = new HorizontalScanNode();
    private final VerticalScanNode verticalScan = new VerticalScanNode();
    private final PaddleVideoNode leftPaddleVideo = new PaddleVideoNode(true);
    private final PaddleVideoNode rightPaddleVideo = new PaddleVideoNode(false);
    private final BallVideoNode ballVideo = new BallVideoNode();
    private final ScoreVideoNode leftScoreVideo = new ScoreVideoNode(true);
    private final ScoreVideoNode rightScoreVideo = new ScoreVideoNode(false);
    private final NetVideoNode netVideo = new NetVideoNode();
    private final VideoMixerNode videoMixer = new VideoMixerNode();

    private volatile double leftTarget = 0.5;
    private volatile double rightTarget = 0.5;
    private volatile double speedScale = 1.0;
    private volatile boolean autoRight = true;
    private volatile boolean leftUp;
    private volatile boolean leftDown;
    private volatile boolean rightUp;
    private volatile boolean rightDown;

    private double leftPaddleY;
    private double rightPaddleY;
    private double ballX;
    private double ballY;
    private double ballSpeed;
    private boolean gameOver;
    private double beamX;
    private double beamY;
    private double videoLevel;
    private double clockLevel;

    @Override
    public String title() {
      return "Pong Raster Circuit Visualizer";
    }

    @Override
    public double timeStep() {
      return TIME_STEP;
    }

    @Override
    public void build(CircuitBuilder b) {
      b.add(leftControl).add(rightControl).add(ground)
        .add(leftPaddle).add(rightPaddle)
        .add(serveTimer).add(leftScore).add(rightScore)
        .add(ballSpeedNode).add(gameControl)
        .add(verticalBall).add(horizontalBall)
        .add(masterClock).add(horizontalScan).add(verticalScan)
        .add(leftPaddleVideo).add(rightPaddleVideo).add(ballVideo)
        .add(leftScoreVideo).add(rightScoreVideo).add(netVideo).add(videoMixer)
        .connect(leftControl, 0, leftPaddle, 0)
        .connect(rightControl, 0, rightPaddle, 0)
        .connect(leftControl, 1, ground, 0)
        .connect(rightControl, 1, ground, 0)
        .connect(leftPaddle, 1, verticalBall, 0)
        .connect(rightPaddle, 1, verticalBall, 1)
        .connect(leftPaddle, 1, horizontalBall, 0)
        .connect(rightPaddle, 1, horizontalBall, 1)
        .connect(verticalBall, 4, horizontalBall, 2)
        .connect(horizontalBall, 6, verticalBall, 2)
        .connect(horizontalBall, 7, verticalBall, 3)
        .connect(horizontalBall, 8, serveTimer, 0)
        .connect(horizontalBall, 9, serveTimer, 1)
        .connect(horizontalBall, 8, leftScore, 0)
        .connect(horizontalBall, 9, rightScore, 0)
        .connect(horizontalBall, 6, ballSpeedNode, 0)
        .connect(serveTimer, 2, ballSpeedNode, 1)
        .connect(ballSpeedNode, 2, horizontalBall, 10)
        .connect(leftScore, 1, gameControl, 0)
        .connect(rightScore, 1, gameControl, 1)
        .connect(gameControl, 2, horizontalBall, 11)
        .connect(gameControl, 2, verticalBall, 5)
        .connect(serveTimer, 2, horizontalBall, 3)
        .connect(serveTimer, 3, horizontalBall, 4)
        .connect(masterClock, 0, horizontalScan, 0)
        .connect(horizontalScan, 2, verticalScan, 0)
        .connect(horizontalScan, 1, leftPaddleVideo, 0)
        .connect(horizontalScan, 1, rightPaddleVideo, 0)
        .connect(horizontalScan, 1, ballVideo, 0)
        .connect(horizontalScan, 1, leftScoreVideo, 0)
        .connect(horizontalScan, 1, rightScoreVideo, 0)
        .connect(horizontalScan, 1, netVideo, 0)
        .connect(verticalScan, 1, leftPaddleVideo, 1)
        .connect(verticalScan, 1, rightPaddleVideo, 1)
        .connect(verticalScan, 1, ballVideo, 1)
        .connect(verticalScan, 1, leftScoreVideo, 1)
        .connect(verticalScan, 1, rightScoreVideo, 1)
        .connect(verticalScan, 1, netVideo, 1)
        .connect(leftPaddle, 1, leftPaddleVideo, 2)
        .connect(rightPaddle, 1, rightPaddleVideo, 2)
        .connect(horizontalBall, 5, ballVideo, 2)
        .connect(verticalBall, 4, ballVideo, 3)
        .connect(leftScore, 1, leftScoreVideo, 2)
        .connect(rightScore, 1, rightScoreVideo, 2)
        .connect(leftPaddleVideo, 3, videoMixer, 0)
        .connect(rightPaddleVideo, 3, videoMixer, 1)
        .connect(ballVideo, 4, videoMixer, 2)
        .connect(leftScoreVideo, 3, videoMixer, 3)
        .connect(rightScoreVideo, 3, videoMixer, 4)
        .connect(netVideo, 2, videoMixer, 5);
    }

    @Override
    public void beforeStep(double time) {
      leftTarget = updateManualTarget(leftTarget, leftUp, leftDown);
      if (autoRight) {
        rightTarget = ballY;
      } else {
        rightTarget = updateManualTarget(rightTarget, rightUp, rightDown);
      }
      leftControl.setVoltage(leftTarget * SUPPLY_VOLTAGE);
      rightControl.setVoltage(rightTarget * SUPPLY_VOLTAGE);
      ballSpeedNode.setManualScale(speedScale);
    }

    @Override
    public void afterStep(double time, PowerNetworkServer network) {
      leftPaddleY = leftPaddle.position();
      rightPaddleY = rightPaddle.position();
      ballX = horizontalBall.position();
      ballY = verticalBall.position();
      ballSpeed = Math.hypot(horizontalBall.velocity(), verticalBall.velocity()) * ballSpeedNode.speedScale();
      gameOver = gameControl.gameOver();
      beamX = horizontalScan.position();
      beamY = verticalScan.position();
      videoLevel = videoMixer.videoLevel();
      clockLevel = masterClock.clockLevel();
    }

    @Override
    public List<Trace> traces() {
      return List.of(
        new Trace("yellow: ball x", new Color(255, 208, 102), 1.2, () -> (ballX - 0.5) * 2.0),
        new Trace("orange: ball y", new Color(255, 150, 120), 1.2, () -> (ballY - 0.5) * 2.0),
        new Trace("teal: beam x", new Color(120, 236, 220), 1.0, () -> (beamX - 0.5) * 2.0),
        new Trace("violet: video", new Color(196, 156, 255), 1.0, () -> videoLevel / SUPPLY_VOLTAGE)
      );
    }

    @Override
    public List<Readout> readouts() {
      return List.of(
        new Readout("Ball X: %.3f", () -> ballX),
        new Readout("Ball Y: %.3f", () -> ballY),
        new Readout("Beam X: %.3f", () -> beamX),
        new Readout("Beam Y: %.3f", () -> beamY),
        new Readout("Video: %.2f V", () -> videoLevel),
        new Readout("Clock: %.1f", () -> clockLevel > 2.5 ? 1.0 : 0.0),
        new Readout("Speed: %.3f", () -> ballSpeed),
        new Readout("Left Score: %.0f", () -> leftScore.score() * 1.0),
        new Readout("Right Score: %.0f", () -> rightScore.score() * 1.0)
      );
    }

    @Override
    public void drawSchematic(Graphics2D g, int left, int top, int width, int height) {
      Rectangle field = new Rectangle(left + 70, top + 20, width - 140, height - 70);
      g.setColor(new Color(18, 22, 28));
      g.fillRoundRect(field.x, field.y, field.width, field.height, 24, 24);
      g.setColor(new Color(222, 228, 236));
      g.setStroke(new BasicStroke(2.5f));
      g.drawRoundRect(field.x, field.y, field.width, field.height, 24, 24);

      int midX = field.x + field.width / 2;
      for (int y = field.y + 10; y < field.y + field.height - 10; y += 20) {
        g.drawLine(midX, y, midX, y + 10);
      }

      drawPaddle(g, field, leftPaddleY, true, new Color(172, 235, 149));
      drawPaddle(g, field, rightPaddleY, false, new Color(120, 200, 255));
      drawBall(g, field, ballX, ballY);
      drawBeam(g, field, beamX, beamY, videoLevel);

      g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 28));
      g.setColor(new Color(240, 244, 248));
      g.drawString(Integer.toString(leftScore.score()), field.x + field.width / 2 - 80, field.y + 38);
      g.drawString(Integer.toString(rightScore.score()), field.x + field.width / 2 + 50, field.y + 38);

      g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
      g.drawString("Authentic variant: master clock, scan counters, raster video gates, score decode, and gameplay graph all coexist in the network.",
        field.x, field.y + field.height + 24);

      if (gameOver) {
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 22));
        String text = leftScore.score() > rightScore.score() ? "LEFT PLAYER WINS" : "RIGHT PLAYER WINS";
        g.drawString(text, field.x + field.width / 2 - 120, field.y + field.height / 2);
      }
    }

    @Override
    public void populateControls(JPanel controls) {
      JLabel leftLabel = CircuitVisualizer.createValueLabel(String.format("%.0f%%", leftTarget * 100.0));
      JSlider leftSlider = new JSlider(0, 100, (int) Math.round(leftTarget * 100.0));
      leftSlider.addChangeListener(event -> {
        leftTarget = leftSlider.getValue() / 100.0;
        leftLabel.setText(String.format("%.0f%%", leftTarget * 100.0));
      });
      controls.add(CircuitVisualizer.labeledControl("Left Paddle", leftSlider, leftLabel));

      JLabel rightLabel = CircuitVisualizer.createValueLabel(String.format("%.0f%%", rightTarget * 100.0));
      JSlider rightSlider = new JSlider(0, 100, (int) Math.round(rightTarget * 100.0));
      rightSlider.addChangeListener(event -> {
        if (!autoRight) {
          rightTarget = rightSlider.getValue() / 100.0;
        }
        double shown = autoRight ? rightTarget : rightSlider.getValue() / 100.0;
        rightLabel.setText(String.format("%.0f%%", shown * 100.0));
      });
      controls.add(CircuitVisualizer.labeledControl("Right Paddle", rightSlider, rightLabel));

      JLabel speedLabel = CircuitVisualizer.createValueLabel(String.format("%.2fx", speedScale));
      JSlider speedSlider = new JSlider(50, 250, (int) Math.round(speedScale * 100.0));
      speedSlider.addChangeListener(event -> {
        speedScale = speedSlider.getValue() / 100.0;
        speedLabel.setText(String.format("%.2fx", speedScale));
      });
      controls.add(CircuitVisualizer.labeledControl("Ball Speed", speedSlider, speedLabel));

      controls.setFocusable(true);
      controls.addKeyListener(new KeyAdapter() {
        @Override
        public void keyPressed(KeyEvent event) {
          switch (event.getKeyCode()) {
            case KeyEvent.VK_W -> leftUp = true;
            case KeyEvent.VK_S -> leftDown = true;
            case KeyEvent.VK_UP -> {
              autoRight = false;
              rightUp = true;
            }
            case KeyEvent.VK_DOWN -> {
              autoRight = false;
              rightDown = true;
            }
            case KeyEvent.VK_A -> autoRight = !autoRight;
            case KeyEvent.VK_R -> resetGame();
            default -> {
            }
          }
        }

        @Override
        public void keyReleased(KeyEvent event) {
          switch (event.getKeyCode()) {
            case KeyEvent.VK_W -> leftUp = false;
            case KeyEvent.VK_S -> leftDown = false;
            case KeyEvent.VK_UP -> rightUp = false;
            case KeyEvent.VK_DOWN -> rightDown = false;
            default -> {
            }
          }
        }
      });
    }

    private void resetGame() {
      leftScore.reset();
      rightScore.reset();
      serveTimer.reset(true);
      ballSpeedNode.reset();
      gameControl.reset();
      horizontalBall.reset();
      verticalBall.reset();
      masterClock.reset();
      horizontalScan.reset();
      verticalScan.reset();
      gameOver = false;
    }

    private static double updateManualTarget(double current, boolean up, boolean down) {
      double next = current;
      if (up && !down) {
        next -= PADDLE_SPEED * TIME_STEP;
      } else if (down && !up) {
        next += PADDLE_SPEED * TIME_STEP;
      }
      return clamp01(next);
    }

    private static void drawPaddle(Graphics2D g, Rectangle field, double position, boolean left, Color color) {
      int paddleHeight = (int) Math.round(field.height * PADDLE_HEIGHT);
      int paddleWidth = 12;
      int x = left ? field.x + (int) Math.round(field.width * PADDLE_MARGIN) : field.x + field.width - (int) Math.round(field.width * PADDLE_MARGIN) - paddleWidth;
      int y = field.y + (int) Math.round((field.height - paddleHeight) * clamp01(position));
      g.setColor(color);
      g.fillRoundRect(x, y, paddleWidth, paddleHeight, 10, 10);
    }

    private static void drawBall(Graphics2D g, Rectangle field, double x, double y) {
      int size = Math.max(8, (int) Math.round(field.height * BALL_SIZE));
      int px = field.x + (int) Math.round((field.width - size) * clamp01(x));
      int py = field.y + (int) Math.round((field.height - size) * clamp01(y));
      g.setColor(new Color(255, 208, 102));
      g.fillOval(px, py, size, size);
    }

    private static void drawBeam(Graphics2D g, Rectangle field, double beamX, double beamY, double videoLevel) {
      int px = field.x + (int) Math.round(clamp01(beamX) * field.width);
      int py = field.y + (int) Math.round(clamp01(beamY) * field.height);
      g.setColor(videoLevel > 2.5 ? new Color(255, 255, 255, 190) : new Color(80, 200, 255, 120));
      g.fillOval(px - 4, py - 4, 8, 8);
    }
  }

  private static final class PaddleRegisterNode extends PowerNode {
    private double position = 0.5;

    private PaddleRegisterNode() {
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
      return Double.doubleToLongBits(position);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 1, CircuitStampContext.GROUND, position * SUPPLY_VOLTAGE);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double target = clamp01(network.getVoltageAt(this, 0) / SUPPLY_VOLTAGE);
      double maxDelta = PADDLE_SPEED * timeStepSeconds;
      if (target > position) {
        position = Math.min(position + maxDelta, target);
      } else {
        position = Math.max(position - maxDelta, target);
      }
    }

    double position() {
      return position;
    }
  }

  private static final class ServeTimerNode extends PowerNode {
    private double remaining = SERVE_TIME_SECONDS;
    private boolean serveToRight = true;
    private double previousLeftPulse;
    private double previousRightPulse;

    private ServeTimerNode() {
      super(4);
    }

    @Override
    public int getVoltageSourceCount() {
      return 2;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      long fingerprint = Double.doubleToLongBits(remaining);
      return 31 * fingerprint + (serveToRight ? 1 : 0);
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 2, CircuitStampContext.GROUND, remaining > 0.0 ? SUPPLY_VOLTAGE : 0.0);
      context.stampVoltageSource(1, 3, CircuitStampContext.GROUND, serveToRight ? SUPPLY_VOLTAGE : 0.0);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double leftPulse = network.getVoltageAt(this, 0);
      double rightPulse = network.getVoltageAt(this, 1);
      if (leftPulse > 2.5 && previousLeftPulse <= 2.5) {
        remaining = SERVE_TIME_SECONDS;
        serveToRight = false;
      }
      if (rightPulse > 2.5 && previousRightPulse <= 2.5) {
        remaining = SERVE_TIME_SECONDS;
        serveToRight = true;
      }
      if (remaining > 0.0) {
        remaining = Math.max(0.0, remaining - timeStepSeconds);
      }
      previousLeftPulse = leftPulse;
      previousRightPulse = rightPulse;
    }

    void reset(boolean serveToRight) {
      this.serveToRight = serveToRight;
      remaining = SERVE_TIME_SECONDS;
      previousLeftPulse = 0.0;
      previousRightPulse = 0.0;
    }
  }

  private static final class ScoreCounterNode extends PowerNode {
    private int score;
    private double previousPulse;

    private ScoreCounterNode() {
      super(2);
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      double normalized = Math.min(score, WIN_SCORE) / (double) WIN_SCORE;
      context.stampVoltageSource(0, 1, CircuitStampContext.GROUND, normalized * SUPPLY_VOLTAGE);
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double pulse = network.getVoltageAt(this, 0);
      if (pulse > 2.5 && previousPulse <= 2.5) {
        score = Math.min(score + 1, WIN_SCORE);
      }
      previousPulse = pulse;
    }

    int score() {
      return score;
    }

    void reset() {
      score = 0;
      previousPulse = 0.0;
    }
  }

  private static final class BallSpeedNode extends PowerNode {
    private double manualScale = 1.0;
    private double accumulatedHits;
    private double previousHitPulse;

    private BallSpeedNode() {
      super(3);
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
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 2, CircuitStampContext.GROUND, speedScale() * SUPPLY_VOLTAGE);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double hitPulse = network.getVoltageAt(this, 0);
      double serveActive = network.getVoltageAt(this, 1);
      if (serveActive > 2.5) {
        accumulatedHits = 0.0;
      } else if (hitPulse > 2.5 && previousHitPulse <= 2.5) {
        accumulatedHits = Math.min(accumulatedHits + 0.08, 0.8);
      }
      previousHitPulse = hitPulse;
    }

    void setManualScale(double manualScale) {
      this.manualScale = Math.max(0.25, manualScale);
    }

    void reset() {
      accumulatedHits = 0.0;
      previousHitPulse = 0.0;
    }

    double speedScale() {
      return manualScale * (1.0 + accumulatedHits);
    }
  }

  private static final class GameControlNode extends PowerNode {
    private boolean gameOver;

    private GameControlNode() {
      super(3);
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
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 2, CircuitStampContext.GROUND, gameOver ? SUPPLY_VOLTAGE : 0.0);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      int left = (int) Math.round((network.getVoltageAt(this, 0) / SUPPLY_VOLTAGE) * WIN_SCORE);
      int right = (int) Math.round((network.getVoltageAt(this, 1) / SUPPLY_VOLTAGE) * WIN_SCORE);
      gameOver = left >= WIN_SCORE || right >= WIN_SCORE;
    }

    void reset() {
      gameOver = false;
    }

    boolean gameOver() {
      return gameOver;
    }
  }

  private static final class VerticalBallNode extends PowerNode {
    private double y = 0.5;
    private double vy = BASE_BALL_SPEED_Y;

    private VerticalBallNode() {
      super(6);
    }

    @Override
    public int getVoltageSourceCount() {
      return 1;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_NONLINEAR;
    }

    @Override
    public double getSuggestedMaxTimeStepSeconds() {
      return 1.0 / 3000.0;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 4, CircuitStampContext.GROUND, y * SUPPLY_VOLTAGE);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      if (network.getVoltageAt(this, 5) > 2.5) {
        return;
      }
      double hitPulse = network.getVoltageAt(this, 2);
      if (hitPulse > 2.5) {
        double deflection = (network.getVoltageAt(this, 3) / SUPPLY_VOLTAGE) * 2.0 - 1.0;
        vy = BASE_BALL_SPEED_Y * Math.max(-1.5, Math.min(1.5, deflection));
      }
      y += vy * timeStepSeconds;
      if (y <= 0.0) {
        y = 0.0;
        vy = Math.abs(vy);
      } else if (y >= 1.0) {
        y = 1.0;
        vy = -Math.abs(vy);
      }
    }

    void reset() {
      y = 0.5;
      vy = BASE_BALL_SPEED_Y;
    }

    double position() {
      return y;
    }

    double velocity() {
      return vy;
    }
  }

  private static final class HorizontalBallNode extends PowerNode {
    private double x = 0.5;
    private double vx = BASE_BALL_SPEED_X;
    private boolean hitPulse;
    private boolean leftScorePulse;
    private boolean rightScorePulse;
    private double deflectionSignal = 0.5;

    private HorizontalBallNode() {
      super(12);
    }

    @Override
    public int getVoltageSourceCount() {
      return 5;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_NONLINEAR;
    }

    @Override
    public double getSuggestedMaxTimeStepSeconds() {
      return 1.0 / 3000.0;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 5, CircuitStampContext.GROUND, x * SUPPLY_VOLTAGE);
      context.stampVoltageSource(1, 6, CircuitStampContext.GROUND, hitPulse ? SUPPLY_VOLTAGE : 0.0);
      context.stampVoltageSource(2, 7, CircuitStampContext.GROUND, deflectionSignal * SUPPLY_VOLTAGE);
      context.stampVoltageSource(3, 8, CircuitStampContext.GROUND, leftScorePulse ? SUPPLY_VOLTAGE : 0.0);
      context.stampVoltageSource(4, 9, CircuitStampContext.GROUND, rightScorePulse ? SUPPLY_VOLTAGE : 0.0);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      hitPulse = false;
      leftScorePulse = false;
      rightScorePulse = false;
      if (network.getVoltageAt(this, 11) > 2.5) {
        return;
      }
      if (network.getVoltageAt(this, 3) > 2.5) {
        vx = network.getVoltageAt(this, 4) > 2.5 ? Math.abs(BASE_BALL_SPEED_X) : -Math.abs(BASE_BALL_SPEED_X);
        x = 0.5;
        return;
      }
      double speedScale = Math.max(0.25, network.getVoltageAt(this, 10) / SUPPLY_VOLTAGE);
      x += vx * speedScale * timeStepSeconds;
      double leftPaddle = clamp01(network.getVoltageAt(this, 0) / SUPPLY_VOLTAGE);
      double rightPaddle = clamp01(network.getVoltageAt(this, 1) / SUPPLY_VOLTAGE);
      double ballY = clamp01(network.getVoltageAt(this, 2) / SUPPLY_VOLTAGE);
      if (x <= PADDLE_MARGIN) {
        if (intersectsPaddle(ballY, leftPaddle)) {
          x = PADDLE_MARGIN;
          vx = Math.abs(vx) * 1.04;
          deflectionSignal = clamp01(0.5 + (ballY - leftPaddle) / PADDLE_HEIGHT);
          hitPulse = true;
        } else {
          rightScorePulse = true;
          x = 0.5;
        }
      } else if (x >= 1.0 - PADDLE_MARGIN) {
        if (intersectsPaddle(ballY, rightPaddle)) {
          x = 1.0 - PADDLE_MARGIN;
          vx = -Math.abs(vx) * 1.04;
          deflectionSignal = clamp01(0.5 + (ballY - rightPaddle) / PADDLE_HEIGHT);
          hitPulse = true;
        } else {
          leftScorePulse = true;
          x = 0.5;
        }
      }
    }

    void reset() {
      x = 0.5;
      vx = BASE_BALL_SPEED_X;
      hitPulse = false;
      leftScorePulse = false;
      rightScorePulse = false;
      deflectionSignal = 0.5;
    }

    double position() {
      return x;
    }

    double velocity() {
      return vx;
    }

    private boolean intersectsPaddle(double ballY, double paddleY) {
      double top = paddleY - PADDLE_HEIGHT / 2.0;
      double bottom = paddleY + PADDLE_HEIGHT / 2.0;
      return ballY >= top && ballY <= bottom;
    }
  }

  private static final class MasterClockNode extends PowerNode {
    private boolean high;

    private MasterClockNode() {
      super(2);
    }

    @Override
    public int getVoltageSourceCount() {
      return 2;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 0, CircuitStampContext.GROUND, high ? SUPPLY_VOLTAGE : 0.0);
      context.stampVoltageSource(1, 1, CircuitStampContext.GROUND, 0.0);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      high = !high;
    }

    void reset() {
      high = false;
    }

    double clockLevel() {
      return high ? SUPPLY_VOLTAGE : 0.0;
    }
  }

  private static final class HorizontalScanNode extends PowerNode {
    private int index;
    private double previousClock;
    private boolean hResetPulse;

    private HorizontalScanNode() {
      super(3);
    }

    @Override
    public int getVoltageSourceCount() {
      return 2;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 1, CircuitStampContext.GROUND, position() * SUPPLY_VOLTAGE);
      context.stampVoltageSource(1, 2, CircuitStampContext.GROUND, hResetPulse ? SUPPLY_VOLTAGE : 0.0);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double clock = network.getVoltageAt(this, 0);
      hResetPulse = false;
      if (clock > 2.5 && previousClock <= 2.5) {
        index++;
        if (index >= H_CELLS) {
          index = 0;
          hResetPulse = true;
        }
      }
      previousClock = clock;
    }

    void reset() {
      index = 0;
      previousClock = 0.0;
      hResetPulse = false;
    }

    double position() {
      return index / (double) (H_CELLS - 1);
    }
  }

  private static final class VerticalScanNode extends PowerNode {
    private int index;
    private double previousHReset;
    private boolean vResetPulse;

    private VerticalScanNode() {
      super(3);
    }

    @Override
    public int getVoltageSourceCount() {
      return 2;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 1, CircuitStampContext.GROUND, position() * SUPPLY_VOLTAGE);
      context.stampVoltageSource(1, 2, CircuitStampContext.GROUND, vResetPulse ? SUPPLY_VOLTAGE : 0.0);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double hReset = network.getVoltageAt(this, 0);
      vResetPulse = false;
      if (hReset > 2.5 && previousHReset <= 2.5) {
        index++;
        if (index >= V_CELLS) {
          index = 0;
          vResetPulse = true;
        }
      }
      previousHReset = hReset;
    }

    void reset() {
      index = 0;
      previousHReset = 0.0;
      vResetPulse = false;
    }

    double position() {
      return index / (double) (V_CELLS - 1);
    }
  }

  private static final class PaddleVideoNode extends PowerNode {
    private final boolean left;
    private double outputLevel;

    private PaddleVideoNode(boolean left) {
      super(4);
      this.left = left;
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
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 3, CircuitStampContext.GROUND, outputLevel);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double beamX = clamp01(network.getVoltageAt(this, 0) / SUPPLY_VOLTAGE);
      double beamY = clamp01(network.getVoltageAt(this, 1) / SUPPLY_VOLTAGE);
      double paddleY = clamp01(network.getVoltageAt(this, 2) / SUPPLY_VOLTAGE);
      double x = left ? PADDLE_MARGIN : 1.0 - PADDLE_MARGIN;
      boolean activeX = Math.abs(beamX - x) <= 0.02;
      boolean activeY = Math.abs(beamY - paddleY) <= PADDLE_HEIGHT / 2.0;
      outputLevel = activeX && activeY ? SUPPLY_VOLTAGE : 0.0;
    }
  }

  private static final class BallVideoNode extends PowerNode {
    private double outputLevel;

    private BallVideoNode() {
      super(5);
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
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 4, CircuitStampContext.GROUND, outputLevel);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double beamX = clamp01(network.getVoltageAt(this, 0) / SUPPLY_VOLTAGE);
      double beamY = clamp01(network.getVoltageAt(this, 1) / SUPPLY_VOLTAGE);
      double ballX = clamp01(network.getVoltageAt(this, 2) / SUPPLY_VOLTAGE);
      double ballY = clamp01(network.getVoltageAt(this, 3) / SUPPLY_VOLTAGE);
      boolean inside = Math.abs(beamX - ballX) <= BALL_SIZE && Math.abs(beamY - ballY) <= BALL_SIZE;
      outputLevel = inside ? SUPPLY_VOLTAGE : 0.0;
    }
  }

  private static final class ScoreVideoNode extends PowerNode {
    private final boolean left;
    private double outputLevel;

    private ScoreVideoNode(boolean left) {
      super(4);
      this.left = left;
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
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 3, CircuitStampContext.GROUND, outputLevel);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double beamX = clamp01(network.getVoltageAt(this, 0) / SUPPLY_VOLTAGE);
      double beamY = clamp01(network.getVoltageAt(this, 1) / SUPPLY_VOLTAGE);
      int score = (int) Math.round((network.getVoltageAt(this, 2) / SUPPLY_VOLTAGE) * WIN_SCORE);
      double minX = left ? 0.24 : 0.64;
      double maxX = left ? 0.36 : 0.76;
      double litHeight = score / (double) WIN_SCORE;
      boolean active = beamX >= minX && beamX <= maxX && beamY <= 0.12 && beamY >= 0.12 - litHeight * 0.08;
      outputLevel = active ? SUPPLY_VOLTAGE : 0.0;
    }
  }

  private static final class NetVideoNode extends PowerNode {
    private double outputLevel;

    private NetVideoNode() {
      super(3);
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
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 2, CircuitStampContext.GROUND, outputLevel);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double beamX = clamp01(network.getVoltageAt(this, 0) / SUPPLY_VOLTAGE);
      double beamY = clamp01(network.getVoltageAt(this, 1) / SUPPLY_VOLTAGE);
      boolean active = Math.abs(beamX - 0.5) <= 0.01 && ((int) Math.floor(beamY * 20.0) % 2 == 0);
      outputLevel = active ? SUPPLY_VOLTAGE * 0.6 : 0.0;
    }
  }

  private static final class VideoMixerNode extends PowerNode {
    private double videoLevel;

    private VideoMixerNode() {
      super(6);
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_LINEAR;
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      double mixed = 0.0;
      for (int i = 0; i < 6; i++) {
        mixed = Math.max(mixed, network.getVoltageAt(this, i));
      }
      videoLevel = mixed;
    }

    double videoLevel() {
      return videoLevel;
    }
  }
}
