package org.valkyrienskies.horizons.content.ship_grabbing

import org.valkyrienskies.horizons.api.foundation.mixin.GrabbedObjectTarget

class GrabbingState {
    var grabbedObjectId: Long = -1L
    var grabbedObjectTarget: GrabbedObjectTarget = GrabbedObjectTarget()
    var grabMode: Boolean = true
}