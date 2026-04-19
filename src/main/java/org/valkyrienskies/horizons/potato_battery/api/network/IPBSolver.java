package org.valkyrienskies.horizons.potato_battery.api.network;

import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork;

public interface IPBSolver {
    void step(IPowerNetwork<?> network, int subSteps);
}
