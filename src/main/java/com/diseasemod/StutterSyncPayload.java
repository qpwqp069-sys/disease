package com.diseasemod;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record StutterSyncPayload(List<UUID> ids) implements CustomPacketPayload {
    public static final Identifier PAYLOAD_ID = Identifier.fromNamespaceAndPath(Diseases.MOD_ID, "stutter");
    public static final CustomPacketPayload.Type<StutterSyncPayload> ID = new CustomPacketPayload.Type<>(PAYLOAD_ID);
    public static final StreamCodec<RegistryFriendlyByteBuf, StutterSyncPayload> CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC.<RegistryFriendlyByteBuf>cast().apply(ByteBufCodecs.list()),
            StutterSyncPayload::ids,
            StutterSyncPayload::new);

    public StutterSyncPayload {
        ids = ids == null ? List.of() : List.copyOf(ids);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
