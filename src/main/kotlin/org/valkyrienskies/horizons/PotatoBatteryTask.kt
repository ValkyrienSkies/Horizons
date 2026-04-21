package org.valkyrienskies.horizons

import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.horizons.Horizons.networkRunning
import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork
import org.valkyrienskies.mod.util.logger
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.locks.LockSupport
import kotlin.math.min
import kotlin.system.measureNanoTime

class PotatoBatteryTask(val networks: HashMap<DimensionId, IPowerNetwork<*>>, var idealTPS: Int = 120) : Runnable {

    @Volatile
    var killTask = false

    private var lostTime: Long = 0

    override fun run() {
        logger.warn("Potato Battery plugged in!")
        try {
            while (true) {
                if (killTask) {
                    networks.clear()
                    break
                }
                if (networkRunning) {
                    val timeToSimulateNs = 1e9 / idealTPS
                    val timeStep = (timeToSimulateNs / 1e9)

                    val timeToRunEnergyTick = measureNanoTime {
                        networks.forEach { (_, network) ->
                            network.energyTick(timeStep)
                        }
                    }

                    sleepOnLostTime(timeToSimulateNs, timeToRunEnergyTick)
                }

            }
        } catch (e: Exception) {
            repeat(4) { logger.error("!!! ENERGY THREAD CRASHED !!!") }
            logger.error("Potato Battery has encountered an unhandled exception: ", e)
            logger.error("The energy thread has crashed. Features utilizing the electricity network will not function until a world restart.")
        }
        logger.warn("Potato Battery unplugging...")
    }

    private fun sleepOnLostTime(timeToSimulateNs: Double, timeToRunEnergyTick: Long) {
        // Ideal time minus actual time to run physics tick
        val timeDif = timeToSimulateNs - timeToRunEnergyTick

        if (timeDif < 0) {
            // Physics tick took too long, store some lost time to catch up
            lostTime = min(lostTime - timeDif.toLong(), MAX_LOST_TIME)
        } else {
            if (lostTime > timeDif) {
                // Catch up
                lostTime -= timeDif.toLong()
            } else {
                val timeToWait = timeDif - lostTime
                lostTime = 0
                sleepExact(timeToWait.toLong())
            }
        }
    }

    private fun sleepExact(sleepTimeNanos: Long) {
        val timeToSleep = sleepTimeNanos - 1_000_000
        LockSupport.parkNanos(timeToSleep)
    }

    companion object {
        private const val MAX_LOST_TIME: Long = 1e9.toLong()
        private val logger = logger("Fry Factory").logger
    }
}
