package org.valkyrienskies.horizons.content.circuit.simulation

import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork
import org.valkyrienskies.horizons.potato_battery.api.network.CircuitStampContext
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNode
import org.valkyrienskies.horizons.potato_battery.api.network.node.PowerNodeSimulationMode
import kotlin.math.max
import kotlin.math.min

internal class CircuitCapacitor : PowerNode(2) {
    var voltage = 0.0
        set(value) {
            require(value.isFinite()) { "Invalid capacitor state" }
            field = value
        }

    override fun stamp(context: CircuitStampContext) {
        val conductance = 0.001 / context.timeStep
        context.stampConductance(0, 1, conductance)
        context.stampCurrentSource(1, 0, conductance * voltage)
    }
    override fun getSimulationMode() = PowerNodeSimulationMode.DYNAMIC_LINEAR
    override fun getSuggestedMaxTimeStepSeconds() = 0.005
    override fun getWakeFingerprint() = voltage.toBits()
    override fun onSubstepComplete(network: IPowerNetwork<*>, timeStepSeconds: Double) {
        voltage = network.getVoltageAt(this, 0) - network.getVoltageAt(this, 1)
    }
}

internal class CircuitNmos : PowerNode(3) {
    override fun getSimulationMode() = PowerNodeSimulationMode.DYNAMIC_NONLINEAR
    override fun stamp(context: CircuitStampContext) {
        val drain = if (context.getPreviousVoltage(1) < context.getPreviousVoltage(2)) 2 else 1
        val source = if (drain == 1) 2 else 1
        val vds = context.getPreviousVoltage(drain) - context.getPreviousVoltage(source)
        val vgs = context.getPreviousVoltage(0) - context.getPreviousVoltage(source)
        val overdrive = vgs - 1.0
        val current: Double
        val gm: Double
        val gds: Double
        if (overdrive <= 0) {
            current = 1e-8 * vds
            gm = 0.0
            gds = 1e-8
        } else {
            val base = if (vds < overdrive) 0.001 * (2 * overdrive * vds - vds * vds) else 0.001 * overdrive * overdrive
            current = base * (1 + 0.02 * vds)
            gm = 0.002 * min(vds, overdrive) * (1 + 0.02 * vds)
            gds = max(1e-8, (if (vds < overdrive) 0.002 * (overdrive - vds) * (1 + 0.02 * vds) else 0.0) + 0.02 * base)
        }
        context.stampConductance(0, 1, 1e-12)
        context.stampConductance(0, 2, 1e-12)
        context.stampConductance(drain, source, gds)
        context.stampVCCS(drain, source, 0, source, gm)
        context.stampCurrentSource(drain, source, current - gds * vds - gm * vgs)
    }
}

internal class CircuitGate(private val and: Boolean) : PowerNode(3) {
    override fun usesImplicitVoltageReference() = true
    override fun getReferenceTraversalPorts(enteredPort: Int) = intArrayOf(0, 1)
    override fun getSimulationMode() = PowerNodeSimulationMode.DYNAMIC_NONLINEAR
    override fun stamp(context: CircuitStampContext) {
        val first = context.getPreviousVoltage(0) >= 2.5
        val second = context.getPreviousVoltage(1) >= 2.5
        val high = if (and) first && second else first || second
        val output = if (high) 5.0 else 0.0
        context.stampConductance(0, CircuitStampContext.GROUND, 1e-6)
        context.stampConductance(1, CircuitStampContext.GROUND, 1e-6)
        context.stampConductance(2, CircuitStampContext.GROUND, 100.0)
        context.stampCurrentSource(CircuitStampContext.GROUND, 2, output * 100)
    }
}
