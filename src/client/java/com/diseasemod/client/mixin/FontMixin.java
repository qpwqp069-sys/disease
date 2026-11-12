package com.diseasemod.client.mixin;

import com.diseasemod.client.AlexiaScrambler;
import net.minecraft.client.gui.Font;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Через Font.getGlyph(int, Style) игра берёт картинку каждой буквы для ЛЮБОГО текста
 * (меню, чат, таблички, книги, подсказки). Подменяем номер буквы - подменяется всё.
 */
@Mixin(Font.class)
public class FontMixin {
    @ModifyVariable(method = "getGlyph", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int diseases$scrambleGlyph(int codepoint) {
        return AlexiaScrambler.scramble(codepoint);
    }
}
