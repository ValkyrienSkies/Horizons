package org.valkyrienskies.horizons.content

import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvent
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.RegistryObject
import org.valkyrienskies.horizons.Horizons

object HorizonsSounds {
    var SOUNDS: DeferredRegister<SoundEvent?> =
        DeferredRegister.create(Registries.SOUND_EVENT, Horizons.MOD_ID)

    //circuits
    val PLUG: RegistryObject<SoundEvent> = registerSoundEvent("plug")
    val UNPLUG: RegistryObject<SoundEvent> = registerSoundEvent("unplug")
    val SHORT_CIRCUIT: RegistryObject<SoundEvent> = registerSoundEvent("short")
    val PLUG_ZAP: RegistryObject<SoundEvent> = registerSoundEvent("plug_zap")
    val CLICK_IN: RegistryObject<SoundEvent> = registerSoundEvent("click_in")
    val SWITCH_ON: RegistryObject<SoundEvent> = registerSoundEvent("switch_on")
    val SWITCH_OFF: RegistryObject<SoundEvent> = registerSoundEvent("switch_off")

    private fun registerSoundEvent(name: String): RegistryObject<SoundEvent> {
        val id = ResourceLocation.tryBuild(Horizons.MOD_ID, name)
        return SOUNDS.register(name, { SoundEvent.createVariableRangeEvent(id!!) })
    }

    fun register(context: FMLJavaModLoadingContext) {
        Horizons.LOGGER.info("Registering sounds for " + Horizons.MOD_ID)
        SOUNDS.register(context.modEventBus)
    }
}

