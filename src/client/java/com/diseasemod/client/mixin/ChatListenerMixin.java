package com.diseasemod.client.mixin;

import com.diseasemod.client.StutterTracker;
import com.mojang.authlib.GameProfile;
import java.time.Instant;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatListener.class)
public class ChatListenerMixin {
    @Inject(method = "showMessageToPlayer", at = @At("HEAD"))
    private void diseases$beforeShowMessage(ChatType.Bound bound, PlayerChatMessage playerChatMessage, Component component, GameProfile gameProfile, boolean bl, Instant instant, CallbackInfoReturnable<Boolean> cir) {
        StutterTracker.currentSender = playerChatMessage != null ? playerChatMessage.sender() : (gameProfile != null ? gameProfile.id() : null);
    }

    @Inject(method = "showMessageToPlayer", at = @At("RETURN"))
    private void diseases$afterShowMessage(ChatType.Bound bound, PlayerChatMessage playerChatMessage, Component component, GameProfile gameProfile, boolean bl, Instant instant, CallbackInfoReturnable<Boolean> cir) {
        StutterTracker.currentSender = null;
    }
}
