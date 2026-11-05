package org.valkyrienskies.horizons.content.rigging

import net.minecraft.core.BlockPos
import net.minecraft.world.level.EmptyBlockGetter
import org.joml.primitives.AABBi
import org.joml.primitives.AABBic
import org.valkyrienskies.horizons.content.blocks.HorizonsBlocks
import org.valkyrienskies.mod.api.vsApi
import org.valkyrienskies.mod.common.vsCore
import kotlin.math.roundToInt

object AccumulatorSocketPhysics {
    fun register() {
        vsApi.registerBlockStateEvent.on { event ->
            for (state in HorizonsBlocks.ACCUMULATOR_SOCKET.get().stateDefinition.possibleStates) {
                val boxes = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs()
                val positive = boxes.map { box -> AABBi(
                    (box.minX*16).roundToInt(), (box.minY*16).roundToInt(), (box.minZ*16).roundToInt(),
                    (box.maxX*16).roundToInt()-1, (box.maxY*16).roundToInt()-1, (box.maxZ*16).roundToInt()-1) }
                var negative = mutableListOf<AABBic>(AABBi(0,0,0,15,15,15))
                positive.forEach { negative = vsCore.solidShapeUtils.cutBoxes(negative, it) }
                vsCore.solidShapeUtils.mergeBoxes(negative)
                val shape = vsCore.newSolidStateBoxesShapeBuilder().addPositiveBoxes(positive).addNegativeBoxes(negative)
                // Automatic conversion fills thin plates with hundreds of tiny spheres and falls back
                // to a full cube. Use a bounded set of contact samples while retaining the exact boxes.
                boxes.forEach { box ->
                    val radius = minOf(box.xsize, box.ysize, box.zsize).toFloat() / 2
                    val xs = if (box.xsize > 0.5) listOf(0.25,0.75) else listOf(0.5)
                    val zs = if (box.zsize > 0.5) listOf(0.25,0.75) else listOf(0.5)
                    for (x in xs) for (z in zs) shape.addCollisionPoint(
                        (box.minX+box.xsize*x).toFloat(), ((box.minY+box.maxY)/2).toFloat(), (box.minZ+box.zsize*z).toFloat(), radius)
                }
                event.register(state, event.newSolidStateBuilder().shape(shape.build()).friction(0.6).elasticity(0.0).hardness(1.0).build())
            }
        }
    }
}
