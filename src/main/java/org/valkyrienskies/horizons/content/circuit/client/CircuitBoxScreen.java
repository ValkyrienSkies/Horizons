package org.valkyrienskies.horizons.content.circuit.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;
import org.valkyrienskies.horizons.content.HorizonsNetworking;
import org.valkyrienskies.horizons.content.circuit.CircuitBoxMenu;
import org.valkyrienskies.horizons.content.circuit.CircuitDesign;
import org.valkyrienskies.horizons.content.circuit.CircuitDesign.Placed;
import org.valkyrienskies.horizons.content.circuit.CircuitDesign.Point;
import org.valkyrienskies.horizons.content.circuit.CircuitDesign.Wire;
import org.valkyrienskies.horizons.content.circuit.CircuitEditPacket;
import org.valkyrienskies.horizons.content.circuit.CircuitFeedbackPacket;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CircuitBoxScreen extends AbstractContainerScreen<CircuitBoxMenu> {
    private static final int GRID_X = 96;
    private static final int GRID_Y = 46;
    private static final int SPACING = 16;
    private static final int LIST_X = 8;
    private static final int LIST_Y = 28;
    private static final int LIST_WIDTH = 72;
    private static final int ROW_HEIGHT = 25;
    private static final int VISIBLE_ROWS = 8;
    private static final int[] COLORS = {
        0xFFE57373, 0xFFFFB74D, 0xFFFFF176, 0xFF81C784,
        0xFF4DD0E1, 0xFF64B5F6, 0xFFBA68C8, 0xFFEEEEEE
    };
    private static final String[] COLOR_NAMES = {
        "Red", "Orange", "Yellow", "Green", "Cyan", "Blue", "Purple", "White"
    };
    private static final KeyMapping ROTATE_LEFT = new KeyMapping("key.horizons.circuit.rotate_left",
        KeyConflictContext.GUI, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Q, "key.categories.horizons.circuit");
    private static final KeyMapping ROTATE_RIGHT = new KeyMapping("key.horizons.circuit.rotate_right",
        KeyConflictContext.GUI, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_E, "key.categories.horizons.circuit");

    private final Inventory inventory;
    private final List<PaletteEntry> palette = new ArrayList<>();
    private float uiScale = 1;
    private int scroll;
    private int rotation;
    private int wireColor;
    private String selectedType;
    private boolean wireMode;
    private Point wireStart;
    private Button wireButton;
    private Button colorButton;
    private Component feedback;
    private long feedbackUntil;

    public static void feedback(CircuitFeedbackPacket packet) {
        if (net.minecraft.client.Minecraft.getInstance().screen instanceof CircuitBoxScreen screen
            && screen.menu.containerId == packet.containerId()) {
            screen.feedback = packet.message();
            screen.feedbackUntil = net.minecraft.Util.getMillis() + 8000;
        }
    }

    public CircuitBoxScreen(CircuitBoxMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.inventory = inventory;
        imageWidth = 370;
        imageHeight = 342;
        inventoryLabelX = 87;
        inventoryLabelY = 248;
        titleLabelX = 8;
        titleLabelY = 8;
    }

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(ROTATE_LEFT);
        event.register(ROTATE_RIGHT);
    }

    @Override
    protected void init() {
        uiScale = Math.min(1.0F, Math.min((width - 8.0F) / imageWidth, (height - 8.0F) / imageHeight));
        width = (int) (width / uiScale);
        height = (int) (height / uiScale);
        super.init();
        wireButton = button(292, 28, "Wire: " + (wireMode ? "ON" : "OFF"), () -> {
            wireMode = !wireMode;
            selectedType = null;
            wireStart = null;
            wireButton.setMessage(Component.literal("Wire: " + (wireMode ? "ON" : "OFF")));
        });
        colorButton = button(292, 51, COLOR_NAMES[wireColor], () -> {
            wireColor = (wireColor + 1) % COLORS.length;
            colorButton.setMessage(Component.literal(COLOR_NAMES[wireColor]));
        });
        button(292, 128, "Print", () -> send(4, new CompoundTag()));
        button(292, 151, "Apply", () -> send(5, new CompoundTag()));
        button(292, 210, "Close", this::onClose);
        refreshPalette();
    }

    private Button button(int offsetX, int offsetY, String text, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), button -> action.run())
            .bounds(leftPos + offsetX, topPos + offsetY, 70, 20).build());
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        refreshPalette();
    }

    private void refreshPalette() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int index = 0; index < inventory.getContainerSize(); index++) {
            ItemStack stack = inventory.getItem(index);
            String type = CircuitBoxMenu.componentType(stack);
            if (type != null && !stack.isEmpty()) {
                counts.merge(type, stack.getCount(), Integer::sum);
            }
        }
        palette.clear();
        counts.forEach((type, count) -> palette.add(new PaletteEntry(type, count)));
        scroll = Math.max(0, Math.min(scroll, palette.size() - VISIBLE_ROWS));
        if (selectedType != null && !counts.containsKey(selectedType)) {
            selectedType = null;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, Math.round(width * uiScale), Math.round(height * uiScale), 0xB010121A);
        graphics.pose().pushPose();
        graphics.pose().scale(uiScale, uiScale, 1);
        int logicalX = (int) (mouseX / uiScale);
        int logicalY = (int) (mouseY / uiScale);
        super.render(graphics, logicalX, logicalY, partialTick);
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 300);
        renderGhost(graphics, logicalX, logicalY);
        graphics.pose().popPose();
        renderTooltip(graphics, logicalX, logicalY);
        renderEditorTooltip(graphics, logicalX, logicalY);
        graphics.pose().popPose();
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF202632);
        graphics.fill(leftPos + 85, topPos + 24, leftPos + 280, topPos + 236, 0xFF121A24);
        graphics.fill(leftPos + LIST_X, topPos + LIST_Y, leftPos + LIST_X + LIST_WIDTH,
            topPos + LIST_Y + VISIBLE_ROWS * ROW_HEIGHT, 0xFF151C27);
        renderPalette(graphics);
        for (int gridX = 0; gridX <= 10; gridX++) {
            for (int gridY = 0; gridY <= 10; gridY++) {
                dot(graphics, new Point(gridX, gridY), 1, 0xFF526173);
            }
        }
        for (int socketX : new int[]{1, 4, 6, 9}) {
            for (int socketY : new int[]{-1, 11}) {
                Point socket = new Point(socketX, socketY);
                dot(graphics, socket, 4, 0xFF465263);
                dot(graphics, socket, 2, 0xFFFFCF70);
            }
        }
        CircuitDesign design = menu.design();
        for (Wire wire : design.wires()) {
            drawWire(graphics, wire.from(), wire.to(), COLORS[Math.floorMod(wire.color(), COLORS.length)], 2);
        }
        Map<Point, Integer> joins = new LinkedHashMap<>();
        for (Wire wire : design.wires()) {
            joins.merge(wire.from(), 1, Integer::sum);
            joins.merge(wire.to(), 1, Integer::sum);
        }
        joins.forEach((point, count) -> dot(graphics, point, count > 1 ? 3 : 2, 0xFFF1E5B8));
        for (Placed placed : design.components()) {
            drawComponent(graphics, placed.type(), screenX(placed.x()), screenY(placed.y()), placed.rotation());
            for (Point pin : CircuitDesign.pins(placed)) {
                dot(graphics, pin, 2, 0xFFFFCF70);
            }
        }
        graphics.fill(leftPos + 294, topPos + 75, leftPos + 360, topPos + 79, COLORS[wireColor]);
        graphics.drawString(font, "Schematic", leftPos + 292, topPos + 87, 0xFFBECBDD, false);
        for (var slot : menu.slots) {
            int slotX = leftPos + slot.x;
            int slotY = topPos + slot.y;
            graphics.fill(slotX - 1, slotY - 1, slotX + 17, slotY + 17, 0xFF627087);
            graphics.fill(slotX, slotY, slotX + 16, slotY + 16, 0xFF111720);
        }
        graphics.drawString(font, "R-click", leftPos + 292, topPos + 181, 0xFFBECBDD, false);
        graphics.drawString(font, "to remove", leftPos + 292, topPos + 192, 0xFFBECBDD, false);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, 0xFFE3EDF9, false);
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xFFBECBDD, false);
        String hint = wireMode ? "Click endpoints; right-click cancels" : selectedType == null
            ? "Choose a component or enable Wire" : ROTATE_LEFT.getTranslatedKeyMessage().getString() + "/"
            + ROTATE_RIGHT.getTranslatedKeyMessage().getString() + " rotate | click to place";
        graphics.drawString(font, font.plainSubstrByWidth(hint, 350), 8, 237, 0xFFBECBDD, false);
        if (feedback != null && net.minecraft.Util.getMillis() < feedbackUntil) {
            var lines = font.split(feedback, 100);
            int messageY = 260;
            graphics.fill(257, 256, 365, 339, 0xF0151C27);
            for (var line : lines) {
                if (messageY > 330) break;
                graphics.drawString(font, line, 260, messageY, 0xFFE3EDF9, false);
                messageY += 10;
            }
        }
    }

    private void renderPalette(GuiGraphics graphics) {
        if (palette.isEmpty()) {
            graphics.drawString(font, "No parts", leftPos + 12, topPos + 34, 0xFF9EADBF, false);
            graphics.drawString(font, "in inventory", leftPos + 10, topPos + 46, 0xFF9EADBF, false);
        }
        for (int row = 0; row < VISIBLE_ROWS && row + scroll < palette.size(); row++) {
            PaletteEntry entry = palette.get(row + scroll);
            int rowY = topPos + LIST_Y + row * ROW_HEIGHT;
            if (entry.type().equals(selectedType)) {
                graphics.fill(leftPos + LIST_X, rowY, leftPos + LIST_X + LIST_WIDTH, rowY + ROW_HEIGHT - 1, 0xFF37546D);
            }
            graphics.renderItem(new ItemStack(CircuitBoxMenu.componentItem(entry.type())), leftPos + LIST_X + 2, rowY + 3);
            graphics.drawString(font, Integer.toString(entry.count()), leftPos + LIST_X + 21, rowY + 3, 0xFFE3EDF9, false);
            graphics.drawString(font, font.plainSubstrByWidth(displayName(entry.type()), LIST_WIDTH - 5),
                leftPos + LIST_X + 2, rowY + 15, 0xFFBECBDD, false);
        }
        if (palette.size() > VISIBLE_ROWS) {
            int thumbY = topPos + LIST_Y + scroll * (VISIBLE_ROWS * ROW_HEIGHT - 12) / (palette.size() - VISIBLE_ROWS);
            graphics.fill(leftPos + 81, thumbY, leftPos + 83, thumbY + 12, 0xFF9EADBF);
        }
    }

    private void renderGhost(GuiGraphics graphics, int mouseX, int mouseY) {
        Point point = pointAt(mouseX, mouseY);
        if (wireMode && wireStart != null) {
            dot(graphics, wireStart, 4, 0xFFFFFFFF);
            if (point != null) {
                drawWire(graphics, wireStart, point, validWireEnd(point) ? COLORS[wireColor] : 0xFFFF5555, 2);
            } else if (inBoard(mouseX, mouseY)) {
                drawArc(graphics, screenX(wireStart.x()), screenY(wireStart.y()), mouseX, mouseY, COLORS[wireColor], 2);
            }
        }
        if (selectedType == null || !menu.getCarried().isEmpty()) {
            return;
        }
        Placed ghost = point == null ? null : new Placed(selectedType, point.x(), point.y(), rotation);
        boolean valid = ghost != null && validPlacement(ghost);
        int centerX = point == null ? mouseX : screenX(point.x());
        int centerY = point == null ? mouseY : screenY(point.y());
        RenderSystem.enableBlend();
        graphics.setColor(valid ? 0.45F : 1, valid ? 1 : 0.35F, 0.45F, 0.65F);
        drawComponent(graphics, selectedType, centerX, centerY, rotation);
        graphics.setColor(1, 1, 1, 1);
        RenderSystem.disableBlend();
        if (ghost != null && ghost.y() >= 0 && ghost.y() <= 10) {
            for (Point pin : CircuitDesign.pins(ghost)) {
                dot(graphics, pin, 3, valid ? 0xFF79ED95 : 0xFFFF5555);
            }
        }
    }

    private boolean validPlacement(Placed placed) {
        try {
            menu.design().withComponent(placed);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean validWireEnd(Point point) {
        if (point.equals(wireStart)) {
            return false;
        }
        for (Placed placed : menu.design().components()) {
            if (CircuitDesign.bodies(placed).contains(point)) {
                return false;
            }
        }
        if (wireStart != null) {
            for (Wire wire : menu.design().wires()) {
                if ((wire.from().equals(wireStart) && wire.to().equals(point))
                    || (wire.to().equals(wireStart) && wire.from().equals(point))) {
                    return false;
                }
            }
        }
        return true;
    }

    private void drawComponent(GuiGraphics graphics, String type, int centerX, int centerY, int turns) {
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, centerY, 0);
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(Math.floorMod(turns, 4) * 90));
        graphics.blit(ResourceLocation.fromNamespaceAndPath("horizons", "textures/gui/circuit/" + type + ".png"),
            -24, -24, 0, 0, 48, 48, 48, 48);
        graphics.pose().popPose();
    }

    private void dot(GuiGraphics graphics, Point point, int radius, int color) {
        int centerX = screenX(point.x());
        int centerY = screenY(point.y());
        graphics.fill(centerX - radius, centerY - radius, centerX + radius + 1, centerY + radius + 1, color);
    }

    private void drawWire(GuiGraphics graphics, Point from, Point to, int color, int thickness) {
        drawArc(graphics, screenX(from.x()), screenY(from.y()), screenX(to.x()), screenY(to.y()), color, thickness);
    }

    private void drawArc(GuiGraphics graphics, double fromX, double fromY, double toX, double toY, int color, int thickness) {
        int steps = Math.max(12, (int) Math.ceil(Math.hypot(toX - fromX, toY - fromY) * 2));
        for (int step = 0; step <= steps; step++) {
            double progress = (double) step / steps;
            int pixelX = (int) Math.round(arcX(fromX, fromY, toX, toY, progress));
            int pixelY = (int) Math.round(arcY(fromX, fromY, toX, toY, progress));
            graphics.fill(pixelX, pixelY, pixelX + thickness, pixelY + thickness, color);
        }
    }

    private static double arcY(double fromX, double fromY, double toX, double toY, double progress) {
        double distance = Math.max(1, Math.hypot(toX - fromX, toY - fromY));
        double lift = Math.min(14, distance * 0.18);
        return fromY + (toY - fromY) * progress - (toX - fromX) / distance * 4 * lift * progress * (1 - progress);
    }

    private static double arcX(double fromX, double fromY, double toX, double toY, double progress) {
        double distance = Math.max(1, Math.hypot(toX - fromX, toY - fromY));
        double lift = Math.min(14, distance * 0.18);
        return fromX + (toX - fromX) * progress + (toY - fromY) / distance * 4 * lift * progress * (1 - progress);
    }

    private int wireAt(double mouseX, double mouseY) {
        List<Wire> wires = menu.design().wires();
        for (int index = wires.size() - 1; index >= 0; index--) {
            Wire wire = wires.get(index);
            double fromX = screenX(wire.from().x());
            double fromY = screenY(wire.from().y());
            double toX = screenX(wire.to().x());
            double toY = screenY(wire.to().y());
            int steps = Math.max(12, (int) Math.ceil(Math.hypot(toX - fromX, toY - fromY) * 2));
            for (int step = 0; step <= steps; step++) {
                double progress = (double) step / steps;
                if (Math.hypot(mouseX - arcX(fromX, fromY, toX, toY, progress),
                    mouseY - arcY(fromX, fromY, toX, toY, progress)) <= 3) {
                    return index;
                }
            }
        }
        return -1;
    }

    private int componentAt(double mouseX, double mouseY) {
        Point point = pointAt(mouseX, mouseY);
        List<Placed> components = menu.design().components();
        for (int index = components.size() - 1; index >= 0; index--) {
            Placed placed = components.get(index);
            if (point != null && (CircuitDesign.bodies(placed).contains(point) || CircuitDesign.pins(placed).contains(point))) {
                return index;
            }
        }
        return -1;
    }

    private void renderEditorTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        int row = paletteRow(mouseX, mouseY);
        if (row >= 0) {
            PaletteEntry entry = palette.get(row);
            graphics.renderTooltip(font, Component.literal(displayName(entry.type()) + " ×" + entry.count()), mouseX, mouseY);
            return;
        }
        if (!inBoard(mouseX, mouseY) || selectedType != null || wireStart != null) {
            return;
        }
        int index = componentAt(mouseX, mouseY);
        if (index >= 0) {
            Placed placed = menu.design().components().get(index);
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal(displayName(placed.type())));
            Point hovered = pointAt(mouseX, mouseY);
            List<Point> pins = new ArrayList<>(CircuitDesign.pins(placed));
            for (int pinIndex = 0; pinIndex < pins.size(); pinIndex++) {
                if (pins.get(pinIndex).equals(hovered)) {
                    lines.add(Component.literal(pinLabel(placed.type(), pinIndex)));
                }
            }
            lines.add(Component.literal("Right-click to remove"));
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
        } else {
            int wireIndex = wireAt(mouseX, mouseY);
            if (wireIndex >= 0) {
                graphics.renderTooltip(font, Component.literal("Wire · right-click to remove"), mouseX, mouseY);
            } else {
                Point point = pointAt(mouseX, mouseY);
                if (point != null && (point.y() == -1 || point.y() == 11)) {
                    for (int port = 0; port < 8; port++) {
                        if (CircuitDesign.socket(port).equals(point)) {
                            graphics.renderTooltip(font, Component.literal((point.y() == -1 ? "Top" : "Bottom")
                                + " socket · cable port " + port + " (viewed from front)"), mouseX, mouseY);
                            break;
                        }
                    }
                }
            }
        }
    }

    private static String pinLabel(String type, int index) {
        return switch (type) {
            case "nmos" -> new String[]{"Gate", "Drain", "Source"}[Math.min(index, 2)];
            case "and_gate", "or_gate" -> new String[]{"Input A", "Input B", "Output"}[Math.min(index, 2)];
            default -> "Pin " + (index + 1);
        };
    }

    private static String displayName(String type) {
        return type.replace('_', ' ');
    }

    private int screenX(int gridX) {
        return leftPos + GRID_X + gridX * SPACING;
    }

    private int screenY(int gridY) {
        return topPos + GRID_Y + gridY * SPACING;
    }

    private Point pointAt(double mouseX, double mouseY) {
        int gridX = (int) Math.round((mouseX - leftPos - GRID_X) / SPACING);
        int gridY = (int) Math.round((mouseY - topPos - GRID_Y) / SPACING);
        if (gridX < 0 || gridX > 10 || gridY < -1 || gridY > 11) {
            return null;
        }
        if ((gridY == -1 || gridY == 11) && gridX != 1 && gridX != 4 && gridX != 6 && gridX != 9) {
            return null;
        }
        return new Point(gridX, gridY);
    }

    private boolean inBoard(double mouseX, double mouseY) {
        return mouseX >= leftPos + 85 && mouseX < leftPos + 280 && mouseY >= topPos + 24 && mouseY < topPos + 236;
    }

    private int paletteRow(double mouseX, double mouseY) {
        if (mouseX < leftPos + LIST_X || mouseX >= leftPos + LIST_X + LIST_WIDTH
            || mouseY < topPos + LIST_Y || mouseY >= topPos + LIST_Y + ROW_HEIGHT * VISIBLE_ROWS) {
            return -1;
        }
        int index = scroll + (int) ((mouseY - topPos - LIST_Y) / ROW_HEIGHT);
        return index < palette.size() ? index : -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double logicalX = mouseX / uiScale;
        double logicalY = mouseY / uiScale;
        int row = paletteRow(logicalX, logicalY);
        if (button == 0 && row >= 0 && menu.getCarried().isEmpty()) {
            selectedType = palette.get(row).type();
            wireMode = false;
            wireStart = null;
            wireButton.setMessage(Component.literal("Wire: OFF"));
            return true;
        }
        if (inBoard(logicalX, logicalY) && menu.getCarried().isEmpty()) {
            Point point = pointAt(logicalX, logicalY);
            if (button == 1) {
                if (wireStart != null) {
                    wireStart = null;
                    return true;
                }
                int componentIndex = componentAt(logicalX, logicalY);
                int wireIndex = wireAt(logicalX, logicalY);
                if (wireMode && wireIndex >= 0) {
                    remove(3, wireIndex);
                } else if (componentIndex >= 0) {
                    remove(1, componentIndex);
                } else if (wireIndex >= 0) {
                    remove(3, wireIndex);
                } else {
                    selectedType = null;
                }
                return true;
            }
            if (button == 0 && point != null) {
                if (wireMode && validWireEnd(point)) {
                    if (wireStart == null) {
                        wireStart = point;
                    } else {
                        CompoundTag data = new CompoundTag();
                        data.putInt("FromX", wireStart.x());
                        data.putInt("FromY", wireStart.y());
                        data.putInt("ToX", point.x());
                        data.putInt("ToY", point.y());
                        data.putInt("Color", wireColor);
                        send(2, data);
                        wireStart = null;
                    }
                } else if (!wireMode && selectedType != null
                    && validPlacement(new Placed(selectedType, point.x(), point.y(), rotation))) {
                    CompoundTag data = new CompoundTag();
                    data.putString("Type", selectedType);
                    data.putInt("X", point.x());
                    data.putInt("Y", point.y());
                    data.putInt("Rotation", rotation);
                    send(0, data);
                }
                return true;
            }
        }
        return super.mouseClicked(logicalX, logicalY, button);
    }

    private void remove(int action, int index) {
        CompoundTag data = new CompoundTag();
        data.putInt("Index", index);
        send(action, data);
    }

    private void send(int action, CompoundTag data) {
        data.putInt("Revision", menu.design().hashCode());
        HorizonsNetworking.sendToServer(new CircuitEditPacket(menu.containerId, action, data));
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return super.mouseReleased(mouseX / uiScale, mouseY / uiScale, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        return super.mouseDragged(mouseX / uiScale, mouseY / uiScale, button, deltaX / uiScale, deltaY / uiScale);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(mouseX / uiScale, mouseY / uiScale);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        double logicalX = mouseX / uiScale;
        double logicalY = mouseY / uiScale;
        if (logicalX >= leftPos + LIST_X && logicalX <= leftPos + 83
            && logicalY >= topPos + LIST_Y && logicalY < topPos + LIST_Y + ROW_HEIGHT * VISIBLE_ROWS) {
            scroll = Math.max(0, Math.min(Math.max(0, palette.size() - VISIBLE_ROWS), scroll - (int) Math.signum(delta)));
            return true;
        }
        return super.mouseScrolled(logicalX, logicalY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (selectedType != null) {
            if (ROTATE_LEFT.matches(keyCode, scanCode)) {
                rotation = Math.floorMod(rotation - 1, 4);
                return true;
            }
            if (ROTATE_RIGHT.matches(keyCode, scanCode)) {
                rotation = (rotation + 1) % 4;
                return true;
            }
            if (minecraft.options.keyInventory.matches(keyCode, scanCode)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private record PaletteEntry(String type, int count) {
    }
}
