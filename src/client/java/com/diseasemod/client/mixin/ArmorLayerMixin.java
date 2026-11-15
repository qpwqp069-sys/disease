package com.diseasemod.client.mixin;

import com.diseasemod.client.ArmlessTracker;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * «Без левой руки»: прячем левый рукав брони.
 *
 * В 1.21.2+ старого setPartVisibility нет — видимость частей задаётся в запечённых
 * моделях ArmorModelSet, которые отдаёт getArmorModel. Модели общие для всех игроков,
 * поэтому видимость выставляем КАЖДЫЙ рендер заново, а не «прячем и запоминаем»:
 * левая рука брони видна только в слоте нагрудника (CHEST) и только если рука на месте.
 * Для шлема/понож/ботинок рука и так скрыта — формула это сохраняет.
 */
@Mixin(HumanoidArmorLayer.class)
public class ArmorLayerMixin {

    @SuppressWarnings("rawtypes")
    @Inject(method = "getArmorModel", at = @At("TAIL"))
    private void diseases$hideArmlessLeftArm(HumanoidRenderState state, EquipmentSlot slot,
                                             CallbackInfoReturnable<HumanoidModel> cir) {
        HumanoidModel model = cir.getReturnValue();
        if (model == null) {
            return;
        }
        boolean armless = state instanceof AvatarRenderState avatar
                && ArmlessTracker.isArmless(avatar.id);
        // Руки брони рисуются только для нагрудника; для остальных слотов — скрыты.
        model.leftArm.visible = slot == EquipmentSlot.CHEST && !armless;
    }
}
