package org.valkyrienskies.horizons.potato_battery;

import org.valkyrienskies.horizons.potato_battery.CircuitComponents.GroundNode;
import org.valkyrienskies.horizons.potato_battery.CircuitComponents.VariableVoltageNode;
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

public final class PongCircuitVisualizer {
  private static final double SUPPLY_VOLTAGE = 5.0;
  private static final double PADDLE_HEIGHT = 0.17;
  private static final double PADDLE_MARGIN = 0.06;
  private static final double BALL_SIZE = 0.024;
  private static final double BASE_BALL_SPEED_X = 0.55;
  private static final double BASE_BALL_SPEED_Y = 0.35;
  private static final double PADDLE_SPEED = 1.25;
  private static final int WIN_SCORE = 11;

  public static void main(String[] args) {
    CircuitVisualizer.launch(new PongScenario());
  }

  private static double clamp01(double value) {
    return Math.max(0.0, Math.min(1.0, value));
  }

  private static final class PongScenario implements Scenario {
    private static final double TIME_STEP = 1.0 / 120.0;

    private final VariableVoltageNode leftControl = new VariableVoltageNode();
    private final VariableVoltageNode rightControl = new VariableVoltageNode();
    private final GroundNode ground = new GroundNode();
    private final PaddleNode leftPaddle = new PaddleNode();
    private final PaddleNode rightPaddle = new PaddleNode();
    private final BallNode ball = new BallNode();

    private volatile double leftTarget = 0.5;
    private volatile double rightTarget = 0.5;
    private volatile double speedScale = 1.0;
    private volatile boolean autoRight = true;
    private volatile boolean started;
    private volatile boolean leftUp;
    private volatile boolean leftDown;
    private volatile boolean rightUp;
    private volatile boolean rightDown;

    private double leftPaddleY;
    private double rightPaddleY;
    private double ballX;
    private double ballY;
    private double ballSpeed;
    private int leftScoreValue;
    private int rightScoreValue;
    private boolean serveLeft;
    private boolean gameOver;

    @Override
    public String title() {
      return "Pong Circuit Visualizer";
    }

    @Override
    public double timeStep() {
      return TIME_STEP;
    }

    @Override
    public void build(CircuitBuilder b) {
      b.add(leftControl).add(rightControl).add(ground)
          .add(leftPaddle).add(rightPaddle).add(ball)
          .connect(leftControl, 0, leftPaddle, 0)
          .connect(rightControl, 0, rightPaddle, 0)
          .connect(leftControl, 1, ground, 0)
          .connect(rightControl, 1, ground, 0)
          .connect(leftPaddle, 1, ball, 0)
          .connect(rightPaddle, 1, ball, 1);
    }

    @Override
    public void beforeStep(double time) {
      if (!started) {
        started = true;
      }
      leftTarget = updateManualTarget(leftTarget, leftUp, leftDown);
      if (autoRight) {
        rightTarget = ballY;
      } else {
        rightTarget = updateManualTarget(rightTarget, rightUp, rightDown);
      }

      leftControl.setVoltage(leftTarget * SUPPLY_VOLTAGE);
      rightControl.setVoltage(rightTarget * SUPPLY_VOLTAGE);
      ball.setSpeedScale(speedScale);
      ball.setGameOver(gameOver);
      ball.setServeLeft(serveLeft);
    }

    @Override
    public void afterStep(double time, PowerNetworkServer network) {
      leftPaddleY = leftPaddle.normalizedPosition();
      rightPaddleY = rightPaddle.normalizedPosition();
      ballX = ball.normalizedX();
      ballY = ball.normalizedY();
      ballSpeed = ball.currentSpeed();

      if (ball.consumeLeftScorePulse()) {
        leftScoreValue = Math.min(leftScoreValue + 1, WIN_SCORE);
        serveLeft = false;
      } else if (ball.consumeRightScorePulse()) {
        rightScoreValue = Math.min(rightScoreValue + 1, WIN_SCORE);
        serveLeft = true;
      }

      if (leftScoreValue >= WIN_SCORE || rightScoreValue >= WIN_SCORE) {
        gameOver = true;
      }
      if (gameOver && (leftScoreValue < WIN_SCORE && rightScoreValue < WIN_SCORE)) {
        gameOver = false;
      }
    }

    @Override
    public List<Trace> traces() {
      return List.of(
          new Trace("yellow: ball x", new Color(255, 208, 102), 1.2, () -> (ballX - 0.5) * 2.0),
          new Trace("orange: ball y", new Color(255, 150, 120), 1.2, () -> (ballY - 0.5) * 2.0),
          new Trace("green: left paddle", new Color(172, 235, 149), 1.2, () -> (leftPaddleY - 0.5) * 2.0),
          new Trace("blue: right paddle", new Color(120, 200, 255), 1.2, () -> (rightPaddleY - 0.5) * 2.0)
      );
    }

    @Override
    public List<Readout> readouts() {
      return List.of(
          new Readout("Ball X: %.3f", () -> ballX),
          new Readout("Ball Y: %.3f", () -> ballY),
          new Readout("Left: %.2f", () -> leftPaddleY),
          new Readout("Right: %.2f", () -> rightPaddleY),
          new Readout("Speed: %.3f", () -> ballSpeed),
          new Readout("Left Score: %.0f", () -> leftScoreValue * 1.0),
          new Readout("Right Score: %.0f", () -> rightScoreValue * 1.0)
      );
    }

    @Override
    public void drawSchematic(Graphics2D g, int left, int top, int width, int height) {
      Rectangle field = new Rectangle(left + 70, top + 20, width - 140, height - 40);
      g.setColor(new Color(24, 28, 34));
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

      g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 28));
      g.setColor(new Color(240, 244, 248));
      g.drawString(Integer.toString(leftScoreValue), field.x + field.width / 2 - 80, field.y + 38);
      g.drawString(Integer.toString(rightScoreValue), field.x + field.width / 2 + 50, field.y + 38);

      g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
      g.drawString("Falstad-inspired subsystem build: paddles, ball, score, serve, and timing represented as network nodes.",
          field.x, field.y + field.height + 22);

      if (gameOver) {
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 22));
        String text = leftScoreValue > rightScoreValue ? "LEFT PLAYER WINS" : "RIGHT PLAYER WINS";
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
        double shownTarget = autoRight ? rightTarget : rightSlider.getValue() / 100.0;
        rightLabel.setText(String.format("%.0f%%", shownTarget * 100.0));
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
            case KeyEvent.VK_R -> resetGame();
            case KeyEvent.VK_A -> autoRight = !autoRight;
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
      leftScoreValue = 0;
      rightScoreValue = 0;
      ball.reset(true);
      serveLeft = true;
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
  }

  private static final class PaddleNode extends PowerNode {
    private double position = 0.5;

    private PaddleNode() {
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

    double normalizedPosition() {
      return position;
    }
  }

  private static final class BallNode extends PowerNode {
    private double x = 0.5;
    private double y = 0.5;
    private double vx = BASE_BALL_SPEED_X;
    private double vy = BASE_BALL_SPEED_Y;
    private double speedScale = 1.0;
    private double serveCooldown;
    private boolean leftScorePulse;
    private boolean rightScorePulse;
    private boolean serveLeft = true;
    private boolean gameOver;

    private BallNode() {
      super(6);
    }

    @Override
    public int getVoltageSourceCount() {
      return 4;
    }

    @Override
    public PowerNodeSimulationMode getSimulationMode() {
      return PowerNodeSimulationMode.DYNAMIC_NONLINEAR;
    }

    @Override
    public long getWakeFingerprint() {
      long fingerprint = Double.doubleToLongBits(x);
      fingerprint = 31 * fingerprint + Double.doubleToLongBits(y);
      fingerprint = 31 * fingerprint + Double.doubleToLongBits(vx);
      fingerprint = 31 * fingerprint + Double.doubleToLongBits(vy);
      fingerprint = 31 * fingerprint + Double.doubleToLongBits(speedScale);
      return fingerprint;
    }

    @Override
    public double getSuggestedMaxTimeStepSeconds() {
      return 1.0 / 2000.0;
    }

    @Override
    public void stamp(CircuitStampContext context) {
      context.stampVoltageSource(0, 2, CircuitStampContext.GROUND, x * SUPPLY_VOLTAGE);
      context.stampVoltageSource(1, 3, CircuitStampContext.GROUND, y * SUPPLY_VOLTAGE);
      context.stampVoltageSource(2, 4, CircuitStampContext.GROUND, leftScorePulse ? SUPPLY_VOLTAGE : 0.0);
      context.stampVoltageSource(3, 5, CircuitStampContext.GROUND, rightScorePulse ? SUPPLY_VOLTAGE : 0.0);
    }

    @Override
    public void onSubstepComplete(IPowerNetwork<?> network, double timeStepSeconds) {
      leftScorePulse = false;
      rightScorePulse = false;
      if (gameOver) {
        return;
      }

      if (serveCooldown > 0.0) {
        serveCooldown = Math.max(0.0, serveCooldown - timeStepSeconds);
        if (serveCooldown == 0.0) {
          vx = (serveLeft ? -1.0 : 1.0) * BASE_BALL_SPEED_X;
          vy = BASE_BALL_SPEED_Y * (serveLeft ? -1.0 : 1.0);
        }
        return;
      }

      x += vx * speedScale * timeStepSeconds;
      y += vy * speedScale * timeStepSeconds;

      if (y <= 0.0) {
        y = 0.0;
        vy = Math.abs(vy);
      } else if (y >= 1.0) {
        y = 1.0;
        vy = -Math.abs(vy);
      }

      double leftPaddle = clamp01(network.getVoltageAt(this, 0) / SUPPLY_VOLTAGE);
      double rightPaddle = clamp01(network.getVoltageAt(this, 1) / SUPPLY_VOLTAGE);

      if (x <= PADDLE_MARGIN) {
        if (intersectsPaddle(leftPaddle)) {
          x = PADDLE_MARGIN;
          vx = Math.abs(vx) * 1.04;
          vy = reflectVertical(y, leftPaddle);
        } else {
          rightScorePulse = true;
          reset(false);
        }
      } else if (x >= 1.0 - PADDLE_MARGIN) {
        if (intersectsPaddle(rightPaddle)) {
          x = 1.0 - PADDLE_MARGIN;
          vx = -Math.abs(vx) * 1.04;
          vy = reflectVertical(y, rightPaddle);
        } else {
          leftScorePulse = true;
          reset(true);
        }
      }
    }

    void reset(boolean nextServeLeft) {
      x = 0.5;
      y = 0.5;
      vx = 0.0;
      vy = 0.0;
      serveLeft = nextServeLeft;
      serveCooldown = 0.65;
    }

    void setSpeedScale(double speedScale) {
      this.speedScale = Math.max(0.25, speedScale);
    }

    void setServeLeft(boolean serveLeft) {
      this.serveLeft = serveLeft;
    }

    void setGameOver(boolean gameOver) {
      this.gameOver = gameOver;
    }

    boolean consumeLeftScorePulse() {
      boolean pulse = leftScorePulse;
      leftScorePulse = false;
      return pulse;
    }

    boolean consumeRightScorePulse() {
      boolean pulse = rightScorePulse;
      rightScorePulse = false;
      return pulse;
    }

    double normalizedX() {
      return x;
    }

    double normalizedY() {
      return y;
    }

    double currentSpeed() {
      return Math.hypot(vx, vy) * speedScale;
    }

    private boolean intersectsPaddle(double paddlePosition) {
      double top = paddlePosition - PADDLE_HEIGHT / 2.0;
      double bottom = paddlePosition + PADDLE_HEIGHT / 2.0;
      return y >= top && y <= bottom;
    }

    private double reflectVertical(double ballY, double paddlePosition) {
      double offset = (ballY - paddlePosition) / (PADDLE_HEIGHT / 2.0);
      return BASE_BALL_SPEED_Y * Math.max(-1.4, Math.min(1.4, offset));
    }
  }

}
