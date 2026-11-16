package com.diseasemod.client.mixin;

import com.diseasemod.client.StutterTracker;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ChatComponent.class)
public class ChatComponentMixin {
    @ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Component diseases$applyStutter(Component message) {
        try {
            return StutterTracker.processComponent(message);
        } finally {
            StutterTracker.currentSender = null;
        }
    }

    public static String stutterText(String text) {
        return StutterTracker.stutter(text);
    }
}
