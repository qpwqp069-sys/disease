package com.diseasemod.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import com.diseasemod.ArmlessSyncPayload;
import com.diseasemod.Disease;
import com.diseasemod.DiseaseSyncPayload;
import com.diseasemod.Diseases;
import com.diseasemod.StutterSyncPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public class DiseasesClient implements ClientModInitializer {
    private static volatile List<Disease> diseases = List.of();
    private static volatile boolean synced = false;
    private static volatile int ticksToNext = 0;
    private static volatile int ticksToReset = 0;

    private static final Random RNG = new Random();
    private static int spasmCooldown = 100;
    private static int schizoCooldown = 400;
    private static int epilepsyCooldown = 2400;
    private static int seizureTicks = 0;
    private static int narcoCooldown = 2400;
    private static int sleepTicks = 0;
    private static int hiccupCooldown = 400;
    private static int tickAge = 0;
    private static final int BANNER_TOTAL = 100; // баннер висит 5 секунд
    private static String bannerTitle = "";
    private static int bannerTicks = 0;
    private static int paranoiaCooldown = 1500;
    private static int feverCooldown = 900;
    private static int feverTicks = 0;
    private static final String[] PARANOIA_NAMES = {"Herobrine", "Entity_303", "Он", "Кто-то"};
    private static boolean lastDeaf = false;
    private static volatile boolean panicking = false;
    private static int panicBeatTicks = 0;
    private static int panicPulse = 0;

    public static boolean isDeaf() {
        return diseases.contains(Disease.DEAFNESS);
    }

    // Звуки для шизофрении (слышит только сам игрок)
    private static final SoundEvent[] PHANTOM_SOUNDS = {
            SoundEvents.ZOMBIE_AMBIENT,
            SoundEvents.CREEPER_PRIMED,
            SoundEvents.SKELETON_AMBIENT,
            SoundEvents.ENDERMAN_STARE,
            SoundEvents.WARDEN_HEARTBEAT
    };

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(DiseaseSyncPayload.ID, (payload, context) -> {
            List<Disease> out = new ArrayList<>();
            for (String id : payload.ids()) {
                Disease d = Disease.byId(id);
                if (d != null) {
                    out.add(d);
                }
            }
            // Баннер при получении новой болезни (не при входе в мир)
            List<Disease> old = diseases;
            if (synced) {
                for (Disease d : out) {
                    if (!old.contains(d)) {
                        bannerTitle = d.title;
                        bannerTicks = BANNER_TOTAL;
                    }
                }
            }
            diseases = out;
            AlexiaScrambler.partial = !out.contains(Disease.ALEXIA);
            AlexiaScrambler.active = out.contains(Disease.ALEXIA) || out.contains(Disease.DYSLEXIA);
            ticksToNext = payload.ticksToNext();
            ticksToReset = payload.ticksToReset();
            panicking = payload.panicking();
            if (!panicking) {
                panicBeatTicks = 0;
            }
            synced = true;
        });

        // Кто из игроков без левой руки (чтобы не рисовать руку на модели)
        ClientPlayNetworking.registerGlobalReceiver(ArmlessSyncPayload.ID, (payload, context) ->
                ArmlessTracker.ids = new java.util.HashSet<>(payload.ids()));

        // Кто из игроков заикается (для отображения в чате)
        ClientPlayNetworking.registerGlobalReceiver(StutterSyncPayload.ID, (payload, context) ->
                StutterTracker.ids = new java.util.HashSet<>(payload.ids()));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            diseases = List.of();
            synced = false;
            AlexiaScrambler.active = false;
            ArmlessTracker.ids = java.util.Set.of();
            StutterTracker.ids = java.util.Set.of();
            StutterTracker.currentSender = null;
            bannerTicks = 0;
            panicking = false;
            panicBeatTicks = 0;
            panicPulse = 0;
            client.execute(() -> updateDeafness(client));
        });

        ClientTickEvents.END_CLIENT_TICK.register(DiseasesClient::clientTick);

        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(Diseases.MOD_ID, "disease_hud"),
                DiseasesClient::render);
    }

    // ===== Клиентские эффекты болезней =====
    private static void clientTick(Minecraft mc) {
        updateDeafness(mc);
        if (bannerTicks > 0) {
            bannerTicks--;
        }
        if (panicPulse > 0) {
            panicPulse--;
        }
        LocalPlayer player = mc.player;
        List<Disease> list = diseases;
        if (player == null || list.isEmpty() || mc.isPaused()
                || (mc.screen != null && !(mc.screen instanceof ChatScreen))) {
            return;
        }

        // Тремор: дрожит прицел
        if (list.contains(Disease.TREMOR)) {
            shake(player, 0.5f);
        }

        // ДЦП: лёгкая дрожь, шаткая походка и редкие непроизвольные судороги
        if (list.contains(Disease.CEREBRAL_PALSY)) {
            shake(player, 0.3f);
            Vec3 v = player.getDeltaMovement();
            if (v.horizontalDistanceSqr() > 0.0004) {
                player.setDeltaMovement(
                        v.x + (RNG.nextFloat() - 0.5) * 0.03,
                        v.y,
                        v.z + (RNG.nextFloat() - 0.5) * 0.03);
            }
            if (--spasmCooldown <= 0) {
                spasmCooldown = 80 + RNG.nextInt(160);
                player.setYRot(player.getYRot() + (RNG.nextFloat() - 0.5f) * 30f);
                player.setXRot(Mth.clamp(player.getXRot() + (RNG.nextFloat() - 0.5f) * 14f, -90f, 90f));
                Vec3 v2 = player.getDeltaMovement();
                player.setDeltaMovement(
                        v2.x + (RNG.nextFloat() - 0.5) * 0.3,
                        v2.y,
                        v2.z + (RNG.nextFloat() - 0.5) * 0.3);
            }
        }

        // Шизофрения: звуки, которых нет у других игроков
        if (list.contains(Disease.SCHIZOPHRENIA) && --schizoCooldown <= 0) {
            schizoCooldown = 400 + RNG.nextInt(1000);
            ClientLevel level = mc.level;
            if (level != null) {
                double angle = RNG.nextDouble() * Math.PI * 2;
                double dist = 4 + RNG.nextDouble() * 6;
                double x = player.getX() + Math.cos(angle) * dist;
                double z = player.getZ() + Math.sin(angle) * dist;
                SoundEvent sound = PHANTOM_SOUNDS[RNG.nextInt(PHANTOM_SOUNDS.length)];
                level.playLocalSound(x, player.getY(), z, sound, SoundSource.AMBIENT,
                        1.0f, 0.8f + RNG.nextFloat() * 0.4f, false);
            }
        }

        tickAge++;

        // Головокружение: камера плавно "плывёт"
        if (list.contains(Disease.VERTIGO)) {
            player.setYRot(player.getYRot() + (float) Math.sin(tickAge * 0.07) * 0.35f);
            player.setXRot(Mth.clamp(player.getXRot() + (float) Math.cos(tickAge * 0.05) * 0.25f, -90f, 90f));
        }

        // Эпилепсия: редкие приступы (без мигания): замирание и сильная тряска
        if (list.contains(Disease.EPILEPSY)) {
            if (seizureTicks > 0) {
                seizureTicks--;
                shake(player, 6f);
                Vec3 v = player.getDeltaMovement();
                player.setDeltaMovement(0, v.y, 0);
            } else if (--epilepsyCooldown <= 0) {
                epilepsyCooldown = 1200 + RNG.nextInt(3600);
                seizureTicks = 80;
                player.displayClientMessage(
                        Component.literal("Приступ эпилепсии!").withStyle(ChatFormatting.RED), true);
            }
        }

        // Нарколепсия: внезапно засыпаешь, замираешь и клонишь голову
        if (list.contains(Disease.NARCOLEPSY)) {
            if (sleepTicks > 0) {
                sleepTicks--;
                Vec3 v = player.getDeltaMovement();
                player.setDeltaMovement(0, v.y, 0);
                player.setXRot(Mth.lerp(0.08f, player.getXRot(), 60f));
            } else if (--narcoCooldown <= 0) {
                narcoCooldown = 1200 + RNG.nextInt(3600);
                sleepTicks = 100;
                player.displayClientMessage(
                        Component.literal("Ты внезапно засыпаешь...").withStyle(ChatFormatting.GRAY), true);
            }
        }

        // Икота: периодический толчок камеры и звук
        if (list.contains(Disease.HICCUPS) && --hiccupCooldown <= 0) {
            hiccupCooldown = 300 + RNG.nextInt(600);
            player.setXRot(Mth.clamp(player.getXRot() - 5f, -90f, 90f));
            ClientLevel level = mc.level;
            if (level != null) {
                level.playLocalSound(player.getX(), player.getY(), player.getZ(),
                        Diseases.HICCUP_SOUND, SoundSource.PLAYERS, 0.9f, 0.95f + RNG.nextFloat() * 0.1f, false);
            }
        }

        // Паранойя: ложное сообщение "кто-то присоединился к игре" и звук рядом
        if (list.contains(Disease.PARANOIA) && --paranoiaCooldown <= 0) {
            paranoiaCooldown = 1200 + RNG.nextInt(2400);
            String who = PARANOIA_NAMES[RNG.nextInt(PARANOIA_NAMES.length)];
            player.displayClientMessage(
                    Component.literal(who + " присоединился к игре").withStyle(ChatFormatting.YELLOW), false);
            ClientLevel lvl = mc.level;
            if (lvl != null) {
                double ang = RNG.nextDouble() * Math.PI * 2;
                SoundEvent snd = PHANTOM_SOUNDS[RNG.nextInt(PHANTOM_SOUNDS.length)];
                lvl.playLocalSound(player.getX() + Math.cos(ang) * 3, player.getY(), player.getZ() + Math.sin(ang) * 3,
                        snd, SoundSource.AMBIENT, 0.5f, 0.8f + RNG.nextFloat() * 0.4f, false);
            }
        }

        // Лихорадка: периодический озноб - камеру трясёт (бить и ломать при этом можно)
        if (list.contains(Disease.FEVER)) {
            if (feverTicks > 0) {
                feverTicks--;
                shake(player, 2.2f);
            } else if (--feverCooldown <= 0) {
                feverCooldown = 400 + RNG.nextInt(600);
                feverTicks = 120;
                player.displayClientMessage(
                        Component.literal("Тебя бьёт озноб...").withStyle(ChatFormatting.GRAY), true);
            }
        }

        // Паническая атака: быстрое сердцебиение, пульсация краёв экрана и лёгкая дрожь в такт ударам
        if (panicking) {
            if (panicBeatTicks <= 0) {
                panicBeatTicks = 10;
                panicPulse = 9;
                ClientLevel lvl = mc.level;
                if (lvl != null) {
                    lvl.playLocalSound(player.getX(), player.getY(), player.getZ(),
                            Diseases.HEARTBEAT_SOUND, SoundSource.PLAYERS,
                            1.0f, 0.97f + RNG.nextFloat() * 0.06f, false);
                }
                shake(player, 0.7f);
            } else {
                panicBeatTicks--;
            }
        } else {
            panicBeatTicks = 0;
        }
    }

    // Глухота: громкость звуков снижается до 8% в SoundEngineMixin (настройки игры не трогаются)
    private static void updateDeafness(Minecraft mc) {
        boolean deaf = isDeaf();
        if (deaf != lastDeaf) {
            lastDeaf = deaf;
            mc.getSoundManager().refreshCategoryVolume(SoundSource.MASTER);
        }
    }

    private static void shake(LocalPlayer p, float amount) {
        p.setYRot(p.getYRot() + (RNG.nextFloat() - 0.5f) * amount * 2f);
        p.setXRot(Mth.clamp(p.getXRot() + (RNG.nextFloat() - 0.5f) * amount * 2f, -90f, 90f));
    }

    // ===== Интерфейс =====
    private static final int PANEL_BG = 0xA0101010;
    private static final int PANEL_BORDER = 0xFF7A1F1F;
    // Желаемый размер "пикселя" шрифта на экране. Меньше число = мельче текст.
    private static final float TARGET_TEXT_PIXELS = 1.7f;

    private static void panel(GuiGraphics g, int x1, int y1, int x2, int y2) {
        g.fill(x1, y1, x2, y2, PANEL_BG);
        g.fill(x1, y1, x2, y1 + 1, PANEL_BORDER);
        g.fill(x1, y2 - 1, x2, y2, PANEL_BORDER);
        g.fill(x1, y1, x1 + 1, y2, PANEL_BORDER);
        g.fill(x2 - 1, y1, x2, y2, PANEL_BORDER);
    }

    private static void render(GuiGraphics graphics, DeltaTracker tickCounter) {
        Minecraft mc = Minecraft.getInstance();
        if (!synced) {
            return;
        }

        // Виньетки — часть "зрения", а не интерфейса: F1 их не отключает.
        if (diseases.contains(Disease.TUNNEL_VISION)) {
            drawTunnelVision(graphics);
        }
        if (panicPulse > 0) {
            drawPanicPulse(graphics);
        }

        if (mc.options.hideGui) {
            return;
        }

        // Масштаб подстраивается под "Масштаб интерфейса" в настройках игры
        float guiScale = (float) mc.getWindow().getGuiScale();
        float s = Mth.clamp(TARGET_TEXT_PIXELS / guiScale, 0.45f, 1.0f);

        graphics.pose().pushMatrix();
        graphics.pose().scale(s, s);
        AlexiaScrambler.exempt = true;
        try {
            drawHud(graphics, mc.font, s);
        } finally {
            AlexiaScrambler.exempt = false;
            graphics.pose().popMatrix();
        }

        drawBanner(graphics, mc.font);
    }

    // Эксперимент: баннер "Новая болезнь" сверху по центру, плавно появляется и гаснет
    private static void drawBanner(GuiGraphics graphics, Font font) {
        if (bannerTicks <= 0) {
            return;
        }
        float alpha = Math.min(1f, Math.min(bannerTicks / 25f, (BANNER_TOTAL - bannerTicks) / 8f + 0.15f));
        int a = Mth.clamp((int) (alpha * 255), 0, 255);
        if (a < 8) {
            return;
        }
        String head = "Новая болезнь";
        int w = Math.max(font.width(head), font.width(bannerTitle)) + 28;
        int cx = graphics.guiWidth() / 2;
        int x1 = cx - w / 2;
        int y1 = 22;
        int y2 = y1 + 31;
        int bg = ((a * 160 / 255) << 24) | 0x101010;
        int border = (a << 24) | 0x7A1F1F;
        graphics.fill(x1, y1, x1 + w, y2, bg);
        graphics.fill(x1, y1, x1 + w, y1 + 1, border);
        graphics.fill(x1, y2 - 1, x1 + w, y2, border);
        graphics.fill(x1, y1, x1 + 1, y2, border);
        graphics.fill(x1 + w - 1, y1, x1 + w, y2, border);
        AlexiaScrambler.exempt = true;
        try {
            graphics.drawString(font, head, cx - font.width(head) / 2, y1 + 6, (a << 24) | 0xAAAAAA, true);
            graphics.drawString(font, bannerTitle, cx - font.width(bannerTitle) / 2, y1 + 17,
                    (a << 24) | 0xFF5555, true);
        } finally {
            AlexiaScrambler.exempt = false;
        }
    }

    // Туннельное зрение: края экрана затемнены (виньетка из полупрозрачных полос)
    private static void drawTunnelVision(GuiGraphics graphics) {
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        int depth = Math.min(w, h) / 3;
        for (int i = 0; i < depth; i += 2) {
            int a = (int) (235 * (1f - i / (float) depth));
            if (a < 8) {
                continue;
            }
            int col = a << 24;
            graphics.fill(0, i, w, i + 2, col);
            graphics.fill(0, h - i - 2, w, h - i, col);
            graphics.fill(i, 0, i + 2, h, col);
            graphics.fill(w - i - 2, 0, w - i, h, col);
        }
    }

    // Пульсирующая бордово-красная виньетка по краям экрана в такт сердцебиению при панической атаке
    private static void drawPanicPulse(GuiGraphics graphics) {
        float strength = panicPulse / 9f;
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        int depth = Math.min(w, h) / 6;
        for (int i = 0; i < depth; i += 2) {
            int a = (int) (90 * strength * (1f - i / (float) depth));
            if (a < 4) {
                continue;
            }
            int col = (a << 24) | 0x7A1212;
            graphics.fill(0, i, w, i + 2, col);
            graphics.fill(0, h - i - 2, w, h - i, col);
            graphics.fill(i, 0, i + 2, h, col);
            graphics.fill(w - i - 2, 0, w - i, h, col);
        }
    }

    private static void drawHud(GuiGraphics graphics, Font font, float s) {
        List<Disease> list = diseases;
        int virtualW = (int) (graphics.guiWidth() / s);
        int virtualH = (int) ((graphics.guiHeight() - 60) / s);

        // ----- Слева сверху: панель со списком болезней (колонками, если не влезает) -----
        String header = list.isEmpty() ? "Ваши болезни: нет" : "Ваши болезни:";
        int rowH = 10;
        int maxRows = Math.max(4, (virtualH - 24) / rowH);
        int rows = Math.min(list.size(), maxRows);
        int cols = list.isEmpty() ? 0 : (list.size() + maxRows - 1) / maxRows;

        int textW = 0;
        for (Disease d : list) {
            textW = Math.max(textW, font.width(d.title));
        }
        int colW = textW + 7 + 10; // маркер + текст + отступ
        int contentW = Math.max(font.width(header), cols * colW - 10);

        int x1 = 4;
        int y1 = 4;
        int x2 = x1 + 6 + contentW + 6;
        int y2 = rows > 0 ? y1 + 20 + rows * rowH + 3 : y1 + 19;
        panel(graphics, x1, y1, x2, y2);
        graphics.drawString(font, header, x1 + 6, y1 + 5, 0xFFFFFF55, true);

        for (int i = 0; i < list.size(); i++) {
            int col = i / maxRows;
            int row = i % maxRows;
            int x = x1 + 6 + col * colW;
            int y = y1 + 20 + row * rowH;
            graphics.fill(x, y + 2, x + 3, y + 7, 0xFFFF5555);
            graphics.drawString(font, list.get(i).title, x + 7, y, 0xFFFF7777, true);
        }

        // ----- Справа сверху: панель с таймерами -----
        String next = ticksToNext < 0 ? "Все болезни получены" : "Новая болезнь через " + formatTime(ticksToNext);
        String reset = "Сброс болезней через " + String.format(Locale.ROOT, "%.1f", ticksToReset / 24000.0) + " дн.";
        int rw = Math.max(font.width(next), font.width(reset)) + 12;
        int rx2 = virtualW - 4;
        int rx1 = rx2 - rw;
        panel(graphics, rx1, 4, rx2, 36);
        graphics.drawString(font, next, rx1 + 6, 9, 0xFFFFAA00, true);
        graphics.drawString(font, reset, rx1 + 6, 22, 0xFFAAAAAA, true);
    }

    private static String formatTime(int ticks) {
        int seconds = (ticks + 19) / 20;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
