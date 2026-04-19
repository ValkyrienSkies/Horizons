package org.valkyrienskies.horizons.potato_battery.api.network

import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork

interface IPBSolver {
  fun step(network: IPowerNetwork<*>, subSteps: Int)
}
