package com.diseasemod;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Сервер -> клиент: список болезней игрока и таймеры для интерфейса.
 * ticksToNext = -1 означает, что новых болезней уже не будет (получены все).
 */
public record DiseaseSyncPayload(List<String> ids, int ticksToNext, int ticksToReset) implements CustomPacketPayload {
    public static final Identifier PAYLOAD_ID = Identifier.fromNamespaceAndPath(Diseases.MOD_ID, "sync");
    public static final CustomPacketPayload.Type<DiseaseSyncPayload> ID = new CustomPacketPayload.Type<>(PAYLOAD_ID);
    public static final StreamCodec<RegistryFriendlyByteBuf, DiseaseSyncPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()),
            DiseaseSyncPayload::ids,
            ByteBufCodecs.INT,
            DiseaseSyncPayload::ticksToNext,
            ByteBufCodecs.INT,
            DiseaseSyncPayload::ticksToReset,
            DiseaseSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
