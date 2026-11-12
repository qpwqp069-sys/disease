package com.diseasemod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Diseases implements ModInitializer {
    public static final String MOD_ID = "diseases";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playS2C().register(DiseaseSyncPayload.ID, DiseaseSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ArmlessSyncPayload.ID, ArmlessSyncPayload.CODEC);
        DiseaseManager.init();
        LOGGER.info("Diseases mod loaded");
    }
}
