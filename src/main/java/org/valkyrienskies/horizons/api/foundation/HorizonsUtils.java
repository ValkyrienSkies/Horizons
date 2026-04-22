package org.valkyrienskies.horizons.api.foundation;

import net.minecraft.network.FriendlyByteBuf;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

public class HorizonsUtils {

    public static void writeVector3d(Vector3dc vector, FriendlyByteBuf buf) {
        buf.writeDouble(vector.x());
        buf.writeDouble(vector.y());
        buf.writeDouble(vector.z());
    }

    public static Vector3dc readVector3d(FriendlyByteBuf buf) {
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        return new Vector3d(x,y,z);
    }

    public static void writeQuaterniond(Quaterniondc quat, FriendlyByteBuf buf) {
        buf.writeDouble(quat.x());
        buf.writeDouble(quat.y());
        buf.writeDouble(quat.z());
        buf.writeDouble(quat.w());
    }

    public static Quaterniondc readQuaterniond(FriendlyByteBuf buf) {
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        double w = buf.readDouble();
        return new Quaterniond(x,y,z,w);
    }
}
