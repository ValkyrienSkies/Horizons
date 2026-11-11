package org.valkyrienskies.horizons.content.circuit.client

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.math.Axis
import net.minecraft.Util
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.client.settings.KeyConflictContext
import org.lwjgl.glfw.GLFW
import org.valkyrienskies.horizons.api.circuits.CircuitComponentType
import org.valkyrienskies.horizons.api.circuits.CircuitComponents
import org.valkyrienskies.horizons.content.HorizonsNetworking
import org.valkyrienskies.horizons.content.circuit.CircuitBoxMenu
import org.valkyrienskies.horizons.content.circuit.CircuitDesign
import org.valkyrienskies.horizons.content.circuit.CircuitDesign.Placed
import org.valkyrienskies.horizons.content.circuit.CircuitDesign.Point
import org.valkyrienskies.horizons.content.circuit.CircuitDesign.Wire
import org.valkyrienskies.horizons.content.circuit.CircuitEditPacket
import org.valkyrienskies.horizons.content.circuit.CircuitFeedbackPacket
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

class CircuitBoxScreen(menu: CircuitBoxMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<CircuitBoxMenu>(menu, inventory, title) {
    private var uiScale = 1f
    private var rotation = 0
    private var wireColor = 0
    private var selected: CircuitComponentType? = null
    private var wireMode = false
    private var wireStart: Point? = null
    private lateinit var wireButton: Button
    private lateinit var colorButton: Button
    private lateinit var clearButton: Button
    private lateinit var confirmButton: Button
    private lateinit var cancelButton: Button
    private var clearRevision: Int? = null
    private var feedback: Component? = null
    private var feedbackUntil = 0L
    private var editorMouseDown = false

    init {
        imageWidth = CircuitBoxMenu.WIDTH
        imageHeight = CircuitBoxMenu.HEIGHT
        inventoryLabelX = CircuitBoxMenu.INVENTORY_X
        inventoryLabelY = CircuitBoxMenu.INVENTORY_Y - 12
        titleLabelX = 8
        titleLabelY = 8
    }

    override fun init() {
        uiScale = minOf(1f, (width - 8f) / imageWidth, (height - 8f) / imageHeight)
        width = (width / uiScale).toInt()
        height = (height / uiScale).toInt()
        super.init()
        wireButton = button(28, wireLabel()) {
            wireMode = !wireMode
            wireStart = null
            wireButton.message = wireLabel()
        }
        colorButton = button(51, colorLabel()) {
            wireColor = (wireColor + 1) % COLORS.size
            colorButton.message = colorLabel()
        }
        colorButton.tooltip = Tooltip.create(tr("color_hint"))
        button(128, tr("apply")) { send(CircuitBoxMenu.APPLY_SCHEMATIC) }.tooltip = Tooltip.create(tr("schematic_hint"))
        clearButton = button(164, tr("clear")) {
            clearRevision = menu.design.hashCode()
            wireStart = null
            focused = cancelButton
        }
        button(210, tr("close"), ::onClose)
        confirmButton = addWidget(Button.builder(tr("clear")) {
            clearRevision?.let { revision ->
                send(CircuitBoxMenu.CLEAR, CompoundTag().apply { putInt("Revision", revision) })
            }
            closeConfirmation()
        }.bounds(leftPos + 30, topPos + 200, 110, 20).build())
        cancelButton = addWidget(Button.builder(CommonComponents.GUI_CANCEL) { closeConfirmation() }
            .bounds(leftPos + 154, topPos + 200, 110, 20).build())
        refreshSelection()
        if (clearRevision != null) focused = cancelButton
    }

    private fun closeConfirmation() {
        clearRevision = null
        confirmButton.visible = false
        cancelButton.visible = false
        focused = clearButton
    }
    private fun button(y: Int, text: Component, action: () -> Unit): Button =
        addRenderableWidget(Button.builder(text) { action() }.bounds(leftPos + 212, topPos + y, 74, 20).build())
    private fun wireLabel() = tr(if (wireMode) "wire_on" else "wire_off")
    private fun colorLabel() = tr("color.${COLOR_NAMES[wireColor]}")

    private fun refreshSelection() {
        val carriedType = CircuitComponents.find(menu.carried)
        if (selected != carriedType) {
            selected = carriedType
            rotation = 0
            wireStart = null
            if (carriedType != null) wireMode = false
            wireButton.message = wireLabel()
        }
        clearButton.active = menu.design.components.isNotEmpty() || menu.design.wires.isNotEmpty()
        confirmButton.visible = clearRevision != null
        cancelButton.visible = clearRevision != null
        if (clearRevision != null && focused !== confirmButton && focused !== cancelButton) focused = cancelButton
    }
    override fun containerTick() {
        super.containerTick()
        refreshSelection()
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        refreshSelection()
        graphics.fill(0, 0, (width * uiScale).roundToInt(), (height * uiScale).roundToInt(), 0xB010121A.toInt())
        graphics.pose().pushPose()
        graphics.pose().scale(uiScale, uiScale, 1f)
        val x = (mouseX / uiScale).toInt()
        val y = (mouseY / uiScale).toInt()
        // Vanilla's floating-item renderer is private. Hide only this frame's carried rendering;
        // restore before tooltips/input so normal inventory pickup, dragging and counts still work.
        val carried = menu.carried
        val showGhost = selected != null && !wireMode && inBoard(x.toDouble(), y.toDouble()) && clearRevision == null
        if (showGhost || clearRevision != null) menu.carried = ItemStack.EMPTY
        try {
            super.render(graphics, if (clearRevision == null) x else -1000, if (clearRevision == null) y else -1000, partialTick)
        } finally {
            menu.carried = carried
        }
        graphics.pose().pushPose()
        graphics.pose().translate(0.0, 0.0, 300.0)
        if (clearRevision == null) {
            renderGhost(graphics, x, y, showGhost)
        } else {
            renderConfirmation(graphics, x, y, partialTick)
        }
        graphics.pose().popPose()
        graphics.pose().popPose()
        // Tooltip placement clamps against the real window dimensions, so draw tooltips after
        // leaving the editor's scaled coordinate system.
        if (clearRevision == null) {
            renderTooltip(graphics, mouseX, mouseY)
            renderEditorTooltip(graphics, x, y, mouseX, mouseY)
        }
    }

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF202632.toInt())
        graphics.fill(leftPos + 8, topPos + 24, leftPos + 200, topPos + 236, 0xFF121A24.toInt())
        for (x in 0 until CircuitDesign.SIZE) for (y in 0 until CircuitDesign.SIZE)
            dot(graphics, Point(x, y), 1, 0xFF526173.toInt())
        for (port in 0..7) {
            dot(graphics, CircuitDesign.socket(port), 4, 0xFF465263.toInt())
            dot(graphics, CircuitDesign.socket(port), 2, 0xFFFFCF70.toInt())
        }
        val design = menu.design
        design.wires.forEach { drawWire(graphics, it.from, it.to, COLORS[it.color]) }
        val joins = linkedMapOf<Point, Int>()
        design.wires.forEach { joins.merge(it.from, 1, Int::plus); joins.merge(it.to, 1, Int::plus) }
        joins.forEach { (point, count) -> dot(graphics, point, if (count > 1) 3 else 2, 0xFFF1E5B8.toInt()) }
        design.components.forEach { placed ->
            drawComponent(graphics, placed.definition, screenX(placed.x), screenY(placed.y), placed.rotation)
            CircuitDesign.pins(placed).forEach { dot(graphics, it, 2, 0xFFFFCF70.toInt()) }
        }
        graphics.fill(leftPos + 214, topPos + 75, leftPos + 284, topPos + 79, COLORS[wireColor])
        graphics.drawString(font, tr("schematic"), leftPos + 212, topPos + 87, TEXT, false)
        menu.slots.forEachIndexed { index, slot ->
            val x = leftPos + slot.x
            val y = topPos + slot.y
            val component = index > 0 && CircuitComponents.find(slot.item) != null
            graphics.fill(x - 1, y - 1, x + 17, y + 17, if (component) 0xFF87CAA0.toInt() else 0xFF627087.toInt())
            graphics.fill(x, y, x + 16, y + 16, if (component) 0xFF243E36.toInt() else 0xFF111720.toInt())
        }
        font.split(tr("remove_hint"), 74).take(2).forEachIndexed { index, line ->
            graphics.drawString(font, line, leftPos + 212, topPos + 187 + index * 10, TEXT, false)
        }
    }

    override fun renderLabels(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, 0xFFE3EDF9.toInt(), false)
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, TEXT, false)
        val hint = when {
            wireMode -> tr("wire_hint")
            selected != null -> tr("place_hint", ROTATE_LEFT.translatedKeyMessage, ROTATE_RIGHT.translatedKeyMessage)
            else -> tr("select_hint")
        }
        font.split(hint, imageWidth - 16).take(2).forEachIndexed { index, line ->
            graphics.drawString(font, line, 8, 238 + index * 10, TEXT, false)
        }
        feedback?.takeIf { Util.getMillis() < feedbackUntil }?.let { message ->
            graphics.fill(194, 260, 286, 348, 0xF0151C27.toInt())
            font.split(message, 84).take(8).forEachIndexed { index, line ->
                graphics.drawString(font, line, 198, 264 + index * 10, 0xFFE3EDF9.toInt(), false)
            }
        }
    }

    private fun renderConfirmation(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        graphics.fill(0, 0, width, height, 0xB010121A.toInt())
        graphics.fill(leftPos + 20, topPos + 116, leftPos + 274, topPos + 230, 0xFF35465A.toInt())
        graphics.fill(leftPos + 21, topPos + 117, leftPos + 273, topPos + 229, 0xFF202632.toInt())
        graphics.drawCenteredString(font, tr("clear_title"), leftPos + imageWidth / 2, topPos + 128, 0xFFE3EDF9.toInt())
        font.split(tr("clear_confirm"), 228).forEachIndexed { index, line ->
            graphics.drawString(font, line, leftPos + 33, topPos + 148 + index * 11, TEXT, false)
        }
        confirmButton.render(graphics, mouseX, mouseY, partialTick)
        cancelButton.render(graphics, mouseX, mouseY, partialTick)
    }

    private fun renderGhost(graphics: GuiGraphics, mouseX: Int, mouseY: Int, showComponent: Boolean) {
        val point = pointAt(mouseX.toDouble(), mouseY.toDouble())
        val start = wireStart
        if (wireMode && start != null) {
            dot(graphics, start, 4, 0xFFFFFFFF.toInt())
            if (point != null) drawWire(graphics, start, point, if (validWireEnd(point)) COLORS[wireColor] else 0xFFFF5555.toInt())
            else if (inBoard(mouseX.toDouble(), mouseY.toDouble()))
                drawArc(graphics, screenX(start.x).toDouble(), screenY(start.y).toDouble(), mouseX.toDouble(), mouseY.toDouble(), COLORS[wireColor])
        }
        val type = selected ?: return
        if (!showComponent) return
        val ghost = point?.takeIf(CircuitDesign::inGrid)?.let { Placed(type.id(), it.x, it.y, rotation) }
        val valid = ghost != null && validPlacement(ghost)
        val x = ghost?.let { screenX(it.x) } ?: mouseX
        val y = ghost?.let { screenY(it.y) } ?: mouseY
        RenderSystem.enableBlend()
        graphics.setColor(if (valid) 0.45f else 1f, if (valid) 1f else 0.35f, 0.45f, 0.75f)
        drawComponent(graphics, type, x, y, rotation)
        graphics.setColor(1f, 1f, 1f, 1f)
        RenderSystem.disableBlend()
        ghost?.let { CircuitDesign.pins(it).forEach { pin -> dot(graphics, pin, 3, if (valid) 0xFF79ED95.toInt() else 0xFFFF5555.toInt()) } }
        graphics.drawString(font, menu.carried.count.toString(), x + 10, y + 10, 0xFFFFFFFF.toInt(), true)
    }

    private fun validPlacement(placed: Placed) = try { menu.design.withComponent(placed); true } catch (_: IllegalArgumentException) { false }
    private fun validWireEnd(point: Point): Boolean {
        if (menu.design.components.any { point in CircuitDesign.bodies(it) }) return false
        val start = wireStart ?: return true
        return try { menu.design.withWire(Wire(start, point, wireColor)); true } catch (_: IllegalArgumentException) { false }
    }

    private fun drawComponent(graphics: GuiGraphics, type: CircuitComponentType, x: Int, y: Int, turns: Int) {
        val sprite = type.sprite()
        graphics.pose().pushPose()
        graphics.pose().translate(x.toDouble(), y.toDouble(), 0.0)
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(Math.floorMod(turns, 4) * 90f))
        graphics.blit(sprite.texture(), -sprite.originX(), -sprite.originY(), 0f, 0f,
            sprite.width(), sprite.height(), sprite.width(), sprite.height())
        graphics.pose().popPose()
    }
    private fun dot(graphics: GuiGraphics, point: Point, radius: Int, color: Int) {
        val x = screenX(point.x)
        val y = screenY(point.y)
        graphics.fill(x - radius, y - radius, x + radius + 1, y + radius + 1, color)
    }
    private fun drawWire(graphics: GuiGraphics, from: Point, to: Point, color: Int) =
        drawArc(graphics, screenX(from.x).toDouble(), screenY(from.y).toDouble(), screenX(to.x).toDouble(), screenY(to.y).toDouble(), color)
    private fun drawArc(graphics: GuiGraphics, fromX: Double, fromY: Double, toX: Double, toY: Double, color: Int) {
        val steps = maxOf(12, ceil(hypot(toX - fromX, toY - fromY) * 2).toInt())
        for (step in 0..steps) {
            val p = step.toDouble() / steps
            val x = arcX(fromX, fromY, toX, toY, p).roundToInt()
            val y = arcY(fromX, fromY, toX, toY, p).roundToInt()
            graphics.fill(x, y, x + 2, y + 2, color)
        }
    }
    private fun wireAt(mouseX: Double, mouseY: Double): Int {
        for (index in menu.design.wires.indices.reversed()) {
            val wire = menu.design.wires[index]
            val fromX = screenX(wire.from.x).toDouble()
            val fromY = screenY(wire.from.y).toDouble()
            val toX = screenX(wire.to.x).toDouble()
            val toY = screenY(wire.to.y).toDouble()
            val steps = maxOf(12, ceil(hypot(toX - fromX, toY - fromY) * 2).toInt())
            if ((0..steps).any { step ->
                val p = step.toDouble() / steps
                hypot(mouseX - arcX(fromX, fromY, toX, toY, p), mouseY - arcY(fromX, fromY, toX, toY, p)) <= 3
            }) return index
        }
        return -1
    }
    private fun componentAt(mouseX: Double, mouseY: Double): Int {
        val point = pointAt(mouseX, mouseY) ?: return -1
        return menu.design.components.indexOfLast { point in CircuitDesign.bodies(it) || point in CircuitDesign.pins(it) }
    }
    private fun renderEditorTooltip(graphics: GuiGraphics, mouseX: Int, mouseY: Int, tooltipX: Int, tooltipY: Int) {
        if (!inBoard(mouseX.toDouble(), mouseY.toDouble()) || selected != null || wireStart != null) return
        val point = pointAt(mouseX.toDouble(), mouseY.toDouble())
        val index = componentAt(mouseX.toDouble(), mouseY.toDouble())
        if (index >= 0) {
            val placed = menu.design.components[index]
            val lines = mutableListOf<Component>(Component.translatable(placed.definition.translationKey()))
            CircuitDesign.pins(placed).forEachIndexed { pinIndex, pin ->
                if (pin == point) lines.add(Component.translatable(placed.definition.pins()[pinIndex].translationKey()))
            }
            lines.add(tr("remove_hint"))
            graphics.renderComponentTooltip(font, lines, tooltipX, tooltipY)
        } else if (wireAt(mouseX.toDouble(), mouseY.toDouble()) >= 0) {
            graphics.renderTooltip(font, tr("wire_tooltip"), tooltipX, tooltipY)
        } else if (point != null) {
            (0..7).firstOrNull { CircuitDesign.socket(it) == point }?.let { port ->
                graphics.renderTooltip(font, tr(if (port < 4) "top_socket" else "bottom_socket", port), tooltipX, tooltipY)
            }
        }
    }

    private fun screenX(x: Int) = leftPos + GRID_X + x * SPACING
    private fun screenY(y: Int) = topPos + GRID_Y + y * SPACING
    private fun pointAt(mouseX: Double, mouseY: Double): Point? {
        val point = Point(((mouseX - leftPos - GRID_X) / SPACING).roundToInt(), ((mouseY - topPos - GRID_Y) / SPACING).roundToInt())
        return point.takeIf(CircuitDesign::endpoint)
    }
    private fun inBoard(mouseX: Double, mouseY: Double) =
        mouseX >= leftPos + 8 && mouseX < leftPos + 200 && mouseY >= topPos + 24 && mouseY < topPos + 236

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val x = mouseX / uiScale
        val y = mouseY / uiScale
        if (clearRevision != null) {
            confirmButton.mouseClicked(x, y, button)
            cancelButton.mouseClicked(x, y, button)
            editorMouseDown = true
            return true
        }
        refreshSelection()
        if (inBoard(x, y)) {
            editorMouseDown = true
            val point = pointAt(x, y)
            if (button == 1) {
                if (wireStart != null) wireStart = null
                else if (menu.carried.isEmpty) {
                    val component = componentAt(x, y)
                    val wire = wireAt(x, y)
                    when {
                        wireMode && wire >= 0 -> remove(CircuitBoxMenu.REMOVE_WIRE, wire)
                        component >= 0 -> remove(CircuitBoxMenu.REMOVE_COMPONENT, component)
                        wire >= 0 -> remove(CircuitBoxMenu.REMOVE_WIRE, wire)
                    }
                }
            } else if (button == 0 && point != null) {
                val type = selected
                if (wireMode && validWireEnd(point)) {
                    val start = wireStart
                    if (start == null) wireStart = point
                    else {
                        send(CircuitBoxMenu.ADD_WIRE, CompoundTag().apply {
                            putInt("FromX", start.x); putInt("FromY", start.y)
                            putInt("ToX", point.x); putInt("ToY", point.y); putInt("Color", wireColor)
                        })
                        wireStart = null
                    }
                } else if (!wireMode && type != null && validPlacement(Placed(type.id(), point.x, point.y, rotation))) {
                    send(CircuitBoxMenu.PLACE, CompoundTag().apply {
                        putString("Type", type.id().toString()); putInt("X", point.x); putInt("Y", point.y); putInt("Rotation", rotation)
                    })
                }
            }
            return true
        }
        return super.mouseClicked(x, y, button)
    }
    private fun remove(action: Int, index: Int) = send(action, CompoundTag().apply { putInt("Index", index) })
    private fun send(action: Int, data: CompoundTag = CompoundTag()) {
        if (!data.contains("Revision")) data.putInt("Revision", menu.design.hashCode())
        HorizonsNetworking.sendToServer(CircuitEditPacket(menu.containerId, action, data))
    }
    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (editorMouseDown || clearRevision != null) { editorMouseDown = false; return true }
        return super.mouseReleased(mouseX / uiScale, mouseY / uiScale, button)
    }
    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        if (editorMouseDown || clearRevision != null) return true
        return super.mouseDragged(mouseX / uiScale, mouseY / uiScale, button, deltaX / uiScale, deltaY / uiScale)
    }
    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        super.mouseMoved(mouseX / uiScale, mouseY / uiScale)
    }
    override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double) =
        clearRevision != null || super.mouseScrolled(mouseX / uiScale, mouseY / uiScale, delta)

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (clearRevision != null) {
            when (keyCode) {
                GLFW.GLFW_KEY_ESCAPE -> closeConfirmation()
                GLFW.GLFW_KEY_TAB -> focused = if (focused === cancelButton) confirmButton else cancelButton
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> focused?.keyPressed(keyCode, scanCode, modifiers)
            }
            return true
        }
        if (selected != null && !wireMode && keyCode != GLFW.GLFW_KEY_ESCAPE) {
            if (ROTATE_LEFT.matches(keyCode, scanCode)) { rotation = Math.floorMod(rotation - 1, 4); return true }
            if (ROTATE_RIGHT.matches(keyCode, scanCode)) { rotation = (rotation + 1) % 4; return true }
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    companion object {
        private const val GRID_X = 24
        private const val GRID_Y = 46
        private const val SPACING = 16
        private val TEXT = 0xFFBECBDD.toInt()
        private val COLORS = intArrayOf(0xFFE57373.toInt(), 0xFFFFB74D.toInt(), 0xFFFFF176.toInt(), 0xFF81C784.toInt(),
            0xFF4DD0E1.toInt(), 0xFF64B5F6.toInt(), 0xFFBA68C8.toInt(), 0xFFEEEEEE.toInt())
        private val COLOR_NAMES = arrayOf("red", "orange", "yellow", "green", "cyan", "blue", "purple", "white")
        private val ROTATE_LEFT = KeyMapping("key.horizons.circuit.rotate_left", KeyConflictContext.GUI,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Q, "key.categories.horizons.circuit")
        private val ROTATE_RIGHT = KeyMapping("key.horizons.circuit.rotate_right", KeyConflictContext.GUI,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_E, "key.categories.horizons.circuit")
        private fun tr(key: String, vararg args: Any): Component = Component.translatable("gui.horizons.circuit.$key", *args)
        fun registerKeys(event: RegisterKeyMappingsEvent) { event.register(ROTATE_LEFT); event.register(ROTATE_RIGHT) }
        fun feedback(packet: CircuitFeedbackPacket) {
            val screen = Minecraft.getInstance().screen as? CircuitBoxScreen ?: return
            if (screen.menu.containerId == packet.containerId) {
                screen.feedback = packet.message
                screen.feedbackUntil = Util.getMillis() + 8000
            }
        }
        private fun arcX(fromX: Double, fromY: Double, toX: Double, toY: Double, p: Double): Double {
            val distance = maxOf(1.0, hypot(toX - fromX, toY - fromY))
            return fromX + (toX - fromX) * p + (toY - fromY) / distance * 4 * minOf(14.0, distance * 0.18) * p * (1 - p)
        }
        private fun arcY(fromX: Double, fromY: Double, toX: Double, toY: Double, p: Double): Double {
            val distance = maxOf(1.0, hypot(toX - fromX, toY - fromY))
            return fromY + (toY - fromY) * p - (toX - fromX) / distance * 4 * minOf(14.0, distance * 0.18) * p * (1 - p)
        }
    }
}
