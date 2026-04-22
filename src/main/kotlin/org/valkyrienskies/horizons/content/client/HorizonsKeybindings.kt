package org.valkyrienskies.horizons.content.client

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.common.util.Lazy
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import org.lwjgl.glfw.GLFW

@Mod.EventBusSubscriber
object HorizonsKeybindings {
    @JvmStatic
    val GRAB_MODE_BIND: Lazy<KeyMapping> = Lazy.of ({ KeyMapping(
        "key.horizons.grab_mode",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_G,
        "key.categories.misc"
    ) })

    @JvmStatic
    @SubscribeEvent
    fun register(event: RegisterKeyMappingsEvent) {
        event.register(GRAB_MODE_BIND.get())
    }
}
