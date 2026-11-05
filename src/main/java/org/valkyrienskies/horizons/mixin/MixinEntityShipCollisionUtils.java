package org.valkyrienskies.horizons.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.internal.collision.VsiConvexPolygonc;
import org.valkyrienskies.horizons.content.ship_grabbing.PlayerGrabbingMixinDuck;
import org.valkyrienskies.mod.common.util.EntityShipCollisionUtils;
import java.util.List;

@Mixin(EntityShipCollisionUtils.class)
public class MixinEntityShipCollisionUtils {
    // Filter the public result instead of depending on compiler-generated Kotlin lambda names.
    @Inject(method = "getShipPolygonsCollidingWithEntity(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/lang/Long;)Ljava/util/List;",
        at = @At("RETURN"), cancellable = true, remap = false)
    private void horizons$skipGrabbedShip(Entity entity, Vec3 movement, AABB bounds, Level level, Long excluded,
                                         CallbackInfoReturnable<List<VsiConvexPolygonc>> cir) {
        if (entity instanceof PlayerGrabbingMixinDuck grabber && grabber.getGrabbedObjectId() >= 0) {
            long id = grabber.getGrabbedObjectId();
            cir.setReturnValue(cir.getReturnValue().stream().filter(p -> p.getShipFrom() == null || p.getShipFrom() != id).toList());
        }
    }
}
