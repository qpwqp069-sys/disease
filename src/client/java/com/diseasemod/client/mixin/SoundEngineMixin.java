package com.diseasemod.client.mixin;

import com.diseasemod.client.DiseasesClient;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Глухота: снижает громкость всех звуков на клиенте до 8%, не трогая настройки игры. */
@Mixin(SoundEngine.class)
public class SoundEngineMixin {
    @Inject(method = "calculateVolume(FLnet/minecraft/sounds/SoundSource;)F",
            at = @At("RETURN"), cancellable = true)
    private void diseases$muffleDeafness(float volume, SoundSource source, CallbackInfoReturnable<Float> cir) {
        if (DiseasesClient.isDeaf()) {
            cir.setReturnValue(cir.getReturnValueF() * 0.08f);
        }
    }
}
