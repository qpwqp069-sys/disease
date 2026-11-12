package com.diseasemod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Diseases implements ModInitializer {
    public static final String MOD_ID = "diseases";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final Identifier HICCUP_SOUND_ID = Identifier.fromNamespaceAndPath(MOD_ID, "hiccup");
    public static final SoundEvent HICCUP_SOUND = SoundEvent.createVariableRangeEvent(HICCUP_SOUND_ID);
    public static final Identifier HEARTBEAT_SOUND_ID = Identifier.fromNamespaceAndPath(MOD_ID, "heartbeat");
    public static final SoundEvent HEARTBEAT_SOUND = SoundEvent.createVariableRangeEvent(HEARTBEAT_SOUND_ID);

    @Override
    public void onInitialize() {
        Registry.register(BuiltInRegistries.SOUND_EVENT, HICCUP_SOUND_ID, HICCUP_SOUND);
        Registry.register(BuiltInRegistries.SOUND_EVENT, HEARTBEAT_SOUND_ID, HEARTBEAT_SOUND);
        PayloadTypeRegistry.playS2C().register(DiseaseSyncPayload.ID, DiseaseSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ArmlessSyncPayload.ID, ArmlessSyncPayload.CODEC);
        DiseaseManager.init();
        LOGGER.info("Diseases mod loaded");
    }
}
