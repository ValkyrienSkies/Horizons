package org.valkyrienskies.horizons.potato_battery.api.network;

import org.valkyrienskies.horizons.potato_battery.api.IPowerNetwork;

public interface IPBSolver {
    void step(IPowerNetwork<?> network, int subSteps);

    /** Release topology references after graph membership changes. Called between solves. */
    default void invalidateTopology(IPowerNetwork<?> network) {}
}
