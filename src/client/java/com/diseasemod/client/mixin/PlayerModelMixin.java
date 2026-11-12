package com.diseasemod.client.mixin;

import com.diseasemod.client.ArmlessTracker;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Болезнь "Без левой руки": после того как модель игрока выставила позу,
 * прячем левую руку (и рукав), если у этого игрока она отсутствует.
 */
@Mixin(PlayerModel.class)
public class PlayerModelMixin {
    /** Прятали ли мы руку у предыдущего игрока на этой же модели (модель общая для многих игроков). */
    @Unique
    private boolean diseases$hidden = false;

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V",
            at = @At("TAIL"))
    private void diseases$hideLeftArm(AvatarRenderState state, CallbackInfo ci) {
        PlayerModel model = (PlayerModel) (Object) this;
        if (ArmlessTracker.isArmless(state.id)) {
            model.leftArm.visible = false;
            model.leftSleeve.visible = false;
            this.diseases$hidden = true;
        } else if (this.diseases$hidden) {
            model.leftArm.visible = true;
            model.leftSleeve.visible = state.showLeftSleeve;
            this.diseases$hidden = false;
        }
    }
}
