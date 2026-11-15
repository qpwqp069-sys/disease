package com.diseasemod;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

public final class DiseaseManager {
    // ===== Настройки (в тиках: 20 тиков = 1 секунда) =====
    private static final int MIN_INTERVAL = 6 * 60 * 20;   // новая болезнь не раньше чем через 6 минут
    private static final int MAX_INTERVAL = 8 * 60 * 20;   // и не позже чем через 8
    private static final int RESET_TICKS = 5 * 24000;      // сброс болезней раз в 5 игровых дней
    private static final int OCD_MIN = 60 * 20;            // задание ОКР раз в 1-2 минуты
    private static final int OCD_MAX = 120 * 20;
    private static final int OCD_TIME = 20 * 20;           // на выполнение 20 секунд
    private static final int ASTHMA_SPRINT_LIMIT = 5 * 20; // после 5 секунд бега - приступ астмы
    private static final float PANIC_DAMAGE = 2.0f;       // урон паники раз в секунду (2.0 = 1 сердце)
    private static final int PANIC_AFTER = 8;              // через 8 секунд в "страшном месте" - урон
    private static final double BEE_RADIUS = 16.0;         // радиус поиска пчёл для "Неуважения к пчёлам"
    private static final int BEE_MAX_PER_SEC = 4;          // не более 4 новых пчёл в секунду на игрока

    private static final Map<UUID, PlayerState> STATES = new HashMap<>();
    private static int sinceReset = 0;
    private static int tickCounter = 0;
    private static int globalTimer = 0; // общий таймер до следующей болезни: у всех игроков одинаковый
    private static final Identifier DWARF_ID = Identifier.fromNamespaceAndPath(Diseases.MOD_ID, "dwarfism");
    private static final Identifier ANEMIA_ID = Identifier.fromNamespaceAndPath(Diseases.MOD_ID, "anemia");
    private static final Identifier GIANT_ID = Identifier.fromNamespaceAndPath(Diseases.MOD_ID, "gigantism");
    private static final Identifier LIMP_ID = Identifier.fromNamespaceAndPath(Diseases.MOD_ID, "limp");
    private static final Identifier SHORT_ARMS_BLOCK_ID = Identifier.fromNamespaceAndPath(Diseases.MOD_ID, "short_arms_block");
    private static final Identifier SHORT_ARMS_ENTITY_ID = Identifier.fromNamespaceAndPath(Diseases.MOD_ID, "short_arms_entity");
    private static final Identifier MUSCLE_WEAK_BREAK_ID = Identifier.fromNamespaceAndPath(Diseases.MOD_ID, "muscle_weak_break");
    private static final Identifier HEART_DISEASE_ID = Identifier.fromNamespaceAndPath(Diseases.MOD_ID, "heart_disease");
    private static final ResourceKey<DamageType> OSTEOPOROSIS_DAMAGE = ResourceKey.create(
            Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath(Diseases.MOD_ID, "osteoporosis"));

    private DiseaseManager() {}

    private static final class PlayerState {
        final LinkedHashSet<Disease> diseases = new LinkedHashSet<>();
        int cureCooldown = 0;
        int ocdCooldown = roll(OCD_MIN, OCD_MAX);
        int sprintTicks = 0;
        int exposure = 0;
        boolean panicking = false;
        int dropCooldown = 0;
        int weakSprintTicks = 0;
        OcdTask task = null;

        boolean has(Disease d) {
            return diseases.contains(d);
        }
    }

    private static final class OcdTask {
        boolean jump;
        int needed;
        int progress;
        int ticksLeft = OCD_TIME;
        int lastJumps;
        boolean lastSneak;
    }

    private static int roll(int min, int max) {
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private static PlayerState state(ServerPlayer p) {
        return STATES.computeIfAbsent(p.getUUID(), u -> new PlayerState());
    }

    // ===== Подключение событий =====
    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(DiseaseManager::load);

        // Остеопороз: урон от падения удваивается (даже смертельное падение даёт кастомное сообщение о смерти)
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer sp && amount > 0f
                    && source.is(DamageTypeTags.IS_FALL)
                    && !source.is(OSTEOPOROSIS_DAMAGE)
                    && state(sp).has(Disease.OSTEOPOROSIS)) {
                sp.hurtServer((ServerLevel) sp.level(), diseaseSource(sp, "osteoporosis"), amount * 2f);
                return false;
            }
            return true;
        });
        // Слабые руки: шанс выронить предмет из главной руки при ударе моба или получении урона
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClientSide() && player instanceof ServerPlayer sp && hand == InteractionHand.MAIN_HAND) {
                tryWeakHandsDrop(sp, 22);
            }
            return InteractionResult.PASS;
        });

        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
            if (entity instanceof ServerPlayer sp && damageTaken >= 1.0f && !source.is(OSTEOPOROSIS_DAMAGE)) {
                tryWeakHandsDrop(sp, 25);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(DiseaseManager::save);
        ServerTickEvents.END_SERVER_TICK.register(DiseaseManager::tick);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer p = handler.getPlayer();
            syncAttributes(p, state(p));
            sync(p);
            broadcastArmless(server);
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            PlayerState st = state(newPlayer);
            st.task = null;
            st.sprintTicks = 0;
            st.weakSprintTicks = 0;
            st.dropCooldown = 0;
            st.exposure = 0;
            st.panicking = false;
            st.ocdCooldown = roll(OCD_MIN, OCD_MAX);
            syncAttributes(newPlayer, st);
            sync(newPlayer);
            broadcastArmless(((ServerLevel) newPlayer.level()).getServer());
        });

        // Немота: сообщения в чат блокируются
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            if (state(sender).has(Disease.MUTENESS)) {
                sender.displayClientMessage(
                        Component.literal("Ты нем и не можешь говорить!").withStyle(ChatFormatting.RED), true);
                return false;
            }
            // Заикание: сообщение заменяется на заикающуюся версию
            if (state(sender).has(Disease.STUTTER)) {
                MinecraftServer server = ((ServerLevel) sender.level()).getServer();
                String text = stutter(message.decoratedContent().getString());
                server.getPlayerList().broadcastSystemMessage(
                        Component.literal("<" + sender.getName().getString() + "> " + text), false);
                return false;
            }
            return true;
        });

        // Немота: блокируем и команды, рассылающие сообщения в чат (/msg, /tell, /me, /say, /teammsg).
        // Событие срабатывает только для команд с чат-выводом, поэтому белый список не нужен.
        ServerMessageEvents.ALLOW_COMMAND_MESSAGE.register((message, source, params) -> {
            ServerPlayer sp = source.getPlayer();
            if (sp != null && state(sp).has(Disease.MUTENESS)) {
                sp.displayClientMessage(
                        Component.literal("Ты нем и не можешь говорить!").withStyle(ChatFormatting.RED), true);
                return false;
            }
            return true;
        });

        registerCommands();
    }

    // ===== Основной тик =====
    private static void tick(MinecraftServer server) {
        tickCounter++;
        sinceReset++;
        if (sinceReset >= RESET_TICKS) {
            resetAll(server);
        }

        // Общий таймер: новая болезнь выдаётся ВСЕМ игрокам одновременно
        if (--globalTimer <= 0) {
            globalTimer = roll(MIN_INTERVAL, MAX_INTERVAL);
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                giveRandom(p);
            }
        }

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.isAlive()) {
                continue;
            }
            PlayerState st = state(p);

            applyEffects(p, st);
            checkCure(p, st);
            tickOcd(server, p, st);

            if (tickCounter % 20 == 0) {
                sync(p); // обновляем таймеры в интерфейсе раз в секунду
            }
        }

        if (tickCounter % 20 == 0) {
            broadcastArmless(server);
        }

        if (tickCounter % 6000 == 0) {
            save(server);
        }
    }

    // ===== Выдача болезней =====
    /**
     * Проверяет совместимость болезни с уже имеющимися (5 пар несовместимостей):
     * 1. Карликовость / Гигантизм
     * 2. Алексия / Дислексия
     * 3. Немота / Заикание
     * 4. Слепота / Туннельное зрение
     * 5. Слепота / Куриная слепота
     */
    private static boolean isCompatible(PlayerState st, Disease candidate) {
        if (candidate == Disease.DWARFISM && st.has(Disease.GIGANTISM)) return false;
        if (candidate == Disease.GIGANTISM && st.has(Disease.DWARFISM)) return false;

        if (candidate == Disease.ALEXIA && st.has(Disease.DYSLEXIA)) return false;
        if (candidate == Disease.DYSLEXIA && st.has(Disease.ALEXIA)) return false;

        if (candidate == Disease.MUTENESS && st.has(Disease.STUTTER)) return false;
        if (candidate == Disease.STUTTER && st.has(Disease.MUTENESS)) return false;

        if (candidate == Disease.BLINDNESS && (st.has(Disease.TUNNEL_VISION) || st.has(Disease.NIGHT_BLINDNESS))) return false;
        if ((candidate == Disease.TUNNEL_VISION || candidate == Disease.NIGHT_BLINDNESS) && st.has(Disease.BLINDNESS)) return false;

        if (candidate == Disease.ANEMIA && st.has(Disease.HEART_DISEASE)) return false;
        if (candidate == Disease.HEART_DISEASE && st.has(Disease.ANEMIA)) return false;

        return true;
    }

    private static String incompatibleReason(PlayerState st, Disease d) {
        if ((d == Disease.DWARFISM && st.has(Disease.GIGANTISM)) || (d == Disease.GIGANTISM && st.has(Disease.DWARFISM))) {
            return "Карликовость и Гигантизм взаимоисключаемы.";
        }
        if ((d == Disease.ALEXIA && st.has(Disease.DYSLEXIA)) || (d == Disease.DYSLEXIA && st.has(Disease.ALEXIA))) {
            return "Алексия и Дислексия несовместимы (Алексия уже заменяет весь текст).";
        }
        if ((d == Disease.MUTENESS && st.has(Disease.STUTTER)) || (d == Disease.STUTTER && st.has(Disease.MUTENESS))) {
            return "Немота и Заикание несовместимы (при Немоте чат заблокирован).";
        }
        if ((d == Disease.BLINDNESS && st.has(Disease.TUNNEL_VISION)) || (d == Disease.TUNNEL_VISION && st.has(Disease.BLINDNESS))) {
            return "Слепота и Туннельное зрение несовместимы (Слепота уже затемняет обзор).";
        }
        if ((d == Disease.BLINDNESS && st.has(Disease.NIGHT_BLINDNESS)) || (d == Disease.NIGHT_BLINDNESS && st.has(Disease.BLINDNESS))) {
            return "Слепота и Куриная слепота несовместимы.";
        }
        if ((d == Disease.ANEMIA && st.has(Disease.HEART_DISEASE)) || (d == Disease.HEART_DISEASE && st.has(Disease.ANEMIA))) {
            return "Анемия и Порок сердца несовместимы (обе болезни уменьшают здоровье).";
        }
        return "Несовместимо с уже имеющейся болезнью.";
    }

    private static List<Disease> availableDiseases(PlayerState st) {
        List<Disease> free = new ArrayList<>();
        for (Disease d : Disease.values()) {
            if (!st.has(d) && isCompatible(st, d)) {
                free.add(d);
            }
        }
        return free;
    }

    public static boolean grant(ServerPlayer p, Disease d) {
        PlayerState st = state(p);
        if (!isCompatible(st, d) || !st.diseases.add(d)) {
            return false;
        }
        if (d == Disease.OCD) {
            st.ocdCooldown = roll(OCD_MIN, OCD_MAX);
            st.task = null;
        }
        syncAttributes(p, st);
        p.sendSystemMessage(Component.literal("[Болезнь] ").withStyle(ChatFormatting.RED)
                .append(Component.literal(d.title).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
                .append(Component.literal(" - " + d.description).withStyle(ChatFormatting.GRAY)));
        sync(p);
        if (d == Disease.NO_LEFT_HAND) {
            broadcastArmless(((ServerLevel) p.level()).getServer());
        }
        return true;
    }

    private static boolean giveRandom(ServerPlayer p) {
        List<Disease> free = availableDiseases(state(p));
        if (free.isEmpty()) {
            return false;
        }
        return grant(p, free.get(ThreadLocalRandom.current().nextInt(free.size())));
    }

    private static void clearDiseases(ServerPlayer p) {
        PlayerState st = state(p);
        st.diseases.clear();
        st.task = null;
        st.sprintTicks = 0;
        st.weakSprintTicks = 0;
        st.dropCooldown = 0;
        st.exposure = 0;
        st.panicking = false;
        removeDiseaseEffects(p);
        syncAttributes(p, st);
        sync(p);
        broadcastArmless(((ServerLevel) p.level()).getServer());
    }

    /** Снимает эффект, только если ни одна из оставшихся болезней его больше не вызывает.
     *  WEAKNESS и WITHER мод не накладывает — чужие эффекты не трогаем. */
    private static void removeDiseaseEffects(ServerPlayer p) {
        PlayerState st = state(p);
        if (!st.has(Disease.BLINDNESS) && !st.has(Disease.ASTHMA) && !st.has(Disease.ALLERGY)) {
            p.removeEffect(MobEffects.BLINDNESS);
        }
        if (!st.has(Disease.DIABETES)) {
            p.removeEffect(MobEffects.HUNGER);
        }
        if (!st.has(Disease.NIGHT_BLINDNESS) && !st.has(Disease.ANEMIA)
                && !st.has(Disease.AQUAPHOBIA) && !st.has(Disease.CLAUSTROPHOBIA)
                && !st.has(Disease.ACROPHOBIA) && !st.has(Disease.HELIOPHOBIA)) {
            p.removeEffect(MobEffects.DARKNESS);
        }
        if (!st.has(Disease.HEMOPHILIA)) {
            p.removeEffect(MobEffects.POISON);
        }
    }

    private static void resetAll(MinecraftServer server) {
        sinceReset = 0;
        for (PlayerState st : STATES.values()) {
            st.diseases.clear();
            st.task = null;
            st.sprintTicks = 0;
            st.weakSprintTicks = 0;
            st.dropCooldown = 0;
            st.exposure = 0;
            st.panicking = false;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            removeDiseaseEffects(p);
            syncAttributes(p, state(p));
            sync(p);
        }
        broadcastArmless(server);
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("Прошло 5 дней: все болезни отступили.").withStyle(ChatFormatting.GREEN), false);
    }

    // ===== Синхронизация с клиентом =====
    public static void sync(ServerPlayer p) {
        if (!ServerPlayNetworking.canSend(p, DiseaseSyncPayload.ID)) {
            return;
        }
        PlayerState st = state(p);
        List<String> ids = new ArrayList<>();
        for (Disease d : st.diseases) {
            ids.add(d.id);
        }
        boolean all = availableDiseases(st).isEmpty();
        ServerPlayNetworking.send(p, new DiseaseSyncPayload(
                ids, all ? -1 : Math.max(0, globalTimer), Math.max(0, RESET_TICKS - sinceReset), st.panicking));
    }

    private static void syncAttributes(ServerPlayer p, PlayerState st) {
        syncModifier(p, Attributes.SCALE, DWARF_ID, -0.25, AttributeModifier.Operation.ADD_VALUE,
                st.has(Disease.DWARFISM));
        syncModifier(p, Attributes.MAX_HEALTH, ANEMIA_ID, -4.0, AttributeModifier.Operation.ADD_VALUE,
                st.has(Disease.ANEMIA));
        syncModifier(p, Attributes.SCALE, GIANT_ID, 0.3, AttributeModifier.Operation.ADD_VALUE,
                st.has(Disease.GIGANTISM));
        syncModifier(p, Attributes.MOVEMENT_SPEED, LIMP_ID, -0.33, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                st.has(Disease.LIMP));
        syncModifier(p, Attributes.BLOCK_INTERACTION_RANGE, SHORT_ARMS_BLOCK_ID, -2.0, AttributeModifier.Operation.ADD_VALUE,
                st.has(Disease.SHORT_ARMS));
        syncModifier(p, Attributes.ENTITY_INTERACTION_RANGE, SHORT_ARMS_ENTITY_ID, -1.5, AttributeModifier.Operation.ADD_VALUE,
                st.has(Disease.SHORT_ARMS));
        syncModifier(p, Attributes.BLOCK_BREAK_SPEED, MUSCLE_WEAK_BREAK_ID, -0.5, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                st.has(Disease.MUSCLE_WEAKNESS));
        syncModifier(p, Attributes.MAX_HEALTH, HEART_DISEASE_ID, -6.0, AttributeModifier.Operation.ADD_VALUE,
                st.has(Disease.HEART_DISEASE));
    }

    // ===== Эффекты болезней (серверные) =====
    private static void applyEffects(ServerPlayer p, PlayerState st) {
        // Болезни не действуют в креативе и спектаторе
        if (p.isCreative() || p.isSpectator()) {
            return;
        }

        boolean refresh = p.tickCount % 40 == 0;

        if (st.has(Disease.BLINDNESS) && refresh) {
            p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 400, 0, false, false, false));
        }
        if (st.has(Disease.DIABETES) && refresh) {
            p.addEffect(new MobEffectInstance(MobEffects.HUNGER, 100, 3, false, false, false));
        }
        // Рост (карликовость/гигантизм), здоровье (анемия) и скорость (хромота) меняются через атрибуты
        if (p.tickCount % 10 == 0) {
            syncAttributes(p, st);
        }

        // Астма: после 5 секунд бега - приступ (темнота и невозможность бежать)
        if (st.has(Disease.ASTHMA)) {
            if (p.isSprinting()) {
                st.sprintTicks++;
                p.causeFoodExhaustion(0.03f);
                if (st.sprintTicks >= ASTHMA_SPRINT_LIMIT) {
                    st.sprintTicks = 0;
                    p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 80, 0, false, false, false));
                    p.displayClientMessage(
                            Component.literal("Приступ астмы! Нечем дышать...").withStyle(ChatFormatting.RED), true);
                }
            } else if (st.sprintTicks > 0 && p.tickCount % 10 == 0) {
                st.sprintTicks--;
            }
        }

        // Слабые руки: откат выпадения и шанс выронить предмет при затяжном беге
        if (st.dropCooldown > 0) {
            st.dropCooldown--;
        }
        if (st.has(Disease.WEAK_HANDS)) {
            if (p.isSprinting()) {
                st.weakSprintTicks++;
                if (st.weakSprintTicks >= 60 && p.tickCount % 20 == 0) {
                    tryWeakHandsDrop(p, 20);
                }
            } else if (st.weakSprintTicks > 0 && p.tickCount % 10 == 0) {
                st.weakSprintTicks--;
            }
        } else {
            st.weakSprintTicks = 0;
        }

        // Неуважение к пчёлам: раз в секунду пчёлы на виду агрятся на игрока
        if (st.has(Disease.BEE_DISRESPECT) && p.tickCount % 20 == 0 && !p.isCreative() && !p.isSpectator()) {
            tickBeeDisrespect(p);
        }

        if (st.has(Disease.NO_LEFT_HAND)) {
            ItemStack off = p.getOffhandItem();
            if (!off.isEmpty()) {
                ItemStack copy = off.copy();
                p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                if (!p.getInventory().add(copy)) {
                    p.drop(copy, false);
                }
                p.displayClientMessage(
                        Component.literal("У тебя нет левой руки!").withStyle(ChatFormatting.RED), true);
            }
        }

        // Куриная слепота: в темноте накатывает тьма
        if (st.has(Disease.NIGHT_BLINDNESS) && refresh) {
            int light = p.level().getMaxLocalRawBrightness(p.blockPosition());
            if (light < 7) {
                p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 100, 0, false, false, false));
            }
        }

        // Бессонница: в кровати не уснуть
        if (st.has(Disease.INSOMNIA) && p.isSleeping()) {
            p.stopSleeping();
            p.displayClientMessage(
                    Component.literal("Ты не можешь уснуть...").withStyle(ChatFormatting.GRAY), true);
        }

        // Фобии и аллергия (темнота, а при долгом пребывании - урон)
        applyFears(p, st);

        // Гемофилия: периодически открывается кровотечение
        if (st.has(Disease.HEMOPHILIA) && p.tickCount % 2400 == 0) {
            p.addEffect(new MobEffectInstance(MobEffects.POISON, 60, 0, false, false, false));
            p.displayClientMessage(
                    Component.literal("Открылось кровотечение!").withStyle(ChatFormatting.DARK_RED), true);
        }

        // Анемия: слабость и периодическая темнота в глазах
        if (st.has(Disease.ANEMIA)) {
            if (p.tickCount % 800 == 0) {
                p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 80, 0, false, false, false));
                p.displayClientMessage(
                        Component.literal("Темнеет в глазах...").withStyle(ChatFormatting.GRAY), true);
            }
        }

    }

    /**
     * Фобии и аллергия. Пока игрок в "опасном месте", копится паника: сначала темнеет в глазах,
     * а если остаться дольше PANIC_AFTER секунд - начинается паническая атака с уроном и сердцебиением.
     */
    private static void applyFears(ServerPlayer p, PlayerState st) {
        if (p.isCreative() || p.isSpectator()) {
            st.exposure = 0;
            if (st.panicking) {
                st.panicking = false;
                sync(p); // гасим сердцебиение/виньетку на клиенте
            }
            return;
        }

        if (p.tickCount % 20 != 0) {
            return;
        }
        boolean water = st.has(Disease.AQUAPHOBIA) && p.isInWaterOrRain();
        boolean rainOnly = water && !p.isInWater();
        boolean tight = st.has(Disease.CLAUSTROPHOBIA) && !p.isInWater() && cramped(p);
        boolean height = st.has(Disease.ACROPHOBIA) && !p.getAbilities().flying && nearDrop(p);
        boolean flowers = st.has(Disease.ALLERGY) && (nearFlowers(p) || holdsFlower(p));
        boolean sun = st.has(Disease.HELIOPHOBIA) && inDirectSunlight(p);

        if (!(water || tight || height || flowers || sun)) {
            st.exposure = Math.max(0, st.exposure - 2);
            if (st.panicking && st.exposure < PANIC_AFTER) {
                st.panicking = false;
                sync(p);
            }
            return;
        }
        st.exposure = Math.min(st.exposure + 1, 30);

        if (water) {
            p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 80, 0, false, false, false));
            p.displayClientMessage(Component.literal(rainOnly ? "Дождь! Ты боишься воды..." : "Ты боишься воды!")
                    .withStyle(ChatFormatting.RED), true);
        }
        if (tight) {
            p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 80, 0, false, false, false));
            p.displayClientMessage(
                    Component.literal("Слишком тесно! Нечем дышать...").withStyle(ChatFormatting.RED), true);
        }
        if (height) {
            p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 80, 0, false, false, false));
            p.displayClientMessage(
                    Component.literal("Так высоко! Кружится голова...").withStyle(ChatFormatting.RED), true);
        }
        if (flowers) {
            p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0, false, false, false));
            p.displayClientMessage(
                    Component.literal("Апчхи! Аллергия на цветы...").withStyle(ChatFormatting.YELLOW), true);
        }
        if (sun) {
            p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 80, 0, false, false, false));
            p.displayClientMessage(
                    Component.literal("Слишком ярко! Боишься солнца...").withStyle(ChatFormatting.RED), true);
        }

        boolean panicNow = st.exposure >= PANIC_AFTER;
        if (panicNow != st.panicking) {
            st.panicking = panicNow;
            sync(p);
        }

        if (panicNow) {
            p.displayClientMessage(
                    Component.literal("Паническая атака! Уйди отсюда!").withStyle(ChatFormatting.DARK_RED), true);
            String cause = water ? (rainOnly ? "panic_rain" : "panic_water")
                    : tight ? "panic_tight"
                    : height ? "panic_height"
                    : sun ? "panic_sun"
                    : "panic_flowers";
            p.hurtServer((ServerLevel) p.level(), diseaseSource(p, cause), PANIC_DAMAGE);
        }
    }

    /** Находится ли игрок днём в ясную погоду под открытым небом. */
    private static boolean inDirectSunlight(ServerPlayer p) {
        ServerLevel lvl = (ServerLevel) p.level();
        if (!lvl.dimensionType().hasSkyLight() || !lvl.isBrightOutside()
                || lvl.isRaining() || lvl.isThundering() || p.isInWater()) {
            return false;
        }
        BlockPos eyePos = BlockPos.containing(p.getX(), p.getEyeY(), p.getZ());
        return lvl.canSeeSky(eyePos);
    }

    /** Шанс выронить предмет из главной руки при «Слабых руках» с откатом 15–20 секунд. */
    private static void tryWeakHandsDrop(ServerPlayer p, int chancePercent) {
        if (p.isSpectator() || !p.isAlive()) {
            return;
        }
        PlayerState st = state(p);
        if (!st.has(Disease.WEAK_HANDS) || st.dropCooldown > 0) {
            return;
        }
        ItemStack held = p.getMainHandItem();
        if (held.isEmpty() || ThreadLocalRandom.current().nextInt(100) >= chancePercent) {
            return;
        }
        st.dropCooldown = roll(300, 400);
        ItemStack dropped = held.copy();
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.drop(dropped, false, true);

        ServerLevel lvl = (ServerLevel) p.level();
        lvl.playSound(null, p.getX(), p.getY() + 1.0, p.getZ(),
                SoundEvents.BUNDLE_DROP_CONTENTS, SoundSource.PLAYERS, 0.9f, 0.9f);
        lvl.sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 1.0, p.getZ(), 6, 0.2, 0.15, 0.2, 0.02);
        p.displayClientMessage(
                Component.literal("Предмет выскользнул из рук!").withStyle(ChatFormatting.RED), true);
    }

    /**
     * Неуважение к пчёлам: пчёлы в радиусе 16 блоков при прямой видимости
     * мгновенно злятся на игрока и летят жалить без удара (до 4 новых пчёл в секунду).
     */
    private static void tickBeeDisrespect(ServerPlayer p) {
        ServerLevel lvl = (ServerLevel) p.level();
        List<Bee> bees = lvl.getEntitiesOfClass(Bee.class, p.getBoundingBox().inflate(BEE_RADIUS),
                b -> b.isAlive() && !b.hasStung() && b.getTarget() != p);
        if (bees.isEmpty()) {
            return;
        }
        int provoked = 0;
        for (Bee bee : bees) {
            if (bee.hasLineOfSight(p)) {
                bee.setPersistentAngerTarget(EntityReference.of(p));
                bee.startPersistentAngerTimer();
                bee.setTarget(p);
                provoked++;
                if (provoked >= BEE_MAX_PER_SEC) {
                    break;
                }
            }
        }
    }

    /** Держит ли игрок цветок в любой руке. */
    private static boolean holdsFlower(ServerPlayer p) {
        return p.getMainHandItem().is(ItemTags.FLOWERS) || p.getOffhandItem().is(ItemTags.FLOWERS);
    }

    /** Зачарованное золотое яблоко (Поглощение IV) лечит одну случайную болезнь. */
    private static void checkCure(ServerPlayer p, PlayerState st) {
        if (st.cureCooldown > 0) {
            st.cureCooldown--;
            return;
        }
        if (st.diseases.isEmpty()) {
            return;
        }
        MobEffectInstance abs = p.getEffect(MobEffects.ABSORPTION);
        // Окно: первые 400 тиков (20 сек) из 2400. Кулдаун 400 НЕ даёт вылечить две болезни одним яблоком.
        if (abs != null && abs.getAmplifier() >= 3 && abs.getDuration() > 2000) {
            st.cureCooldown = 400;
            List<Disease> owned = new ArrayList<>(st.diseases);
            Disease cured = owned.get(ThreadLocalRandom.current().nextInt(owned.size()));
            st.diseases.remove(cured);
            if (cured == Disease.OCD) {
                st.task = null;
            }
            if (cured == Disease.WEAK_HANDS) {
                st.weakSprintTicks = 0;
                st.dropCooldown = 0;
            }
            st.exposure = 0;
            st.panicking = false;
            removeDiseaseEffects(p);
            syncAttributes(p, st);
            sync(p);
            if (cured == Disease.NO_LEFT_HAND) {
                broadcastArmless(((ServerLevel) p.level()).getServer());
            }
            p.sendSystemMessage(Component.literal("Зачарованное золотое яблоко вылечило: ")
                    .withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(cured.title).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)));
        }
    }

    /** Раз в секунду сообщает всем клиентам, у кого сейчас нет левой руки (для отрисовки модели). */
    private static void broadcastArmless(MinecraftServer server) {
        List<Integer> ids = new ArrayList<>();
        for (ServerPlayer q : server.getPlayerList().getPlayers()) {
            if (state(q).has(Disease.NO_LEFT_HAND)) {
                ids.add(q.getId());
            }
        }
        ArmlessSyncPayload payload = new ArmlessSyncPayload(ids);
        for (ServerPlayer q : server.getPlayerList().getPlayers()) {
            if (ServerPlayNetworking.canSend(q, ArmlessSyncPayload.ID)) {
                ServerPlayNetworking.send(q, payload);
            }
        }
    }

    /** Источник урона болезни: своё "тип урона" даёт нормальное сообщение о смерти (см. lang-файлы). */
    private static DamageSource diseaseSource(ServerPlayer p, String id) {
        var registry = ((ServerLevel) p.level()).registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE);
        return new DamageSource(registry.getOrThrow(ResourceKey.create(
                Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath(Diseases.MOD_ID, id))));
    }

    /** Добавляет или убирает модификатор атрибута (рост, здоровье) в зависимости от болезни. */
    private static void syncModifier(ServerPlayer p, Holder<Attribute> attribute, Identifier id,
                                     double amount, AttributeModifier.Operation op, boolean wanted) {
        AttributeInstance inst = p.getAttribute(attribute);
        if (inst == null) {
            return;
        }
        boolean present = inst.getModifier(id) != null;
        if (wanted && !present) {
            inst.addTransientModifier(new AttributeModifier(id, amount, op));
        } else if (!wanted && present) {
            inst.removeModifier(id);
        }
    }

    /** Есть ли цветы в радиусе 2 блоков. */
    private static boolean nearFlowers(ServerPlayer p) {
        BlockPos c = p.blockPosition();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (p.level().getBlockState(c.offset(dx, dy, dz)).is(BlockTags.FLOWERS)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Тесно ли игроку: от 4 твёрдых блоков вокруг ног/головы И низкий потолок над головой. */
    private static boolean cramped(ServerPlayer p) {
        BlockPos feet = p.blockPosition();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int filled = 0;
        for (int[] d : dirs) {
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos pos = feet.offset(d[0], dy, d[1]);
                BlockState bs = p.level().getBlockState(pos);
                if (bs.blocksMotion() && !bs.getCollisionShape(p.level(), pos).isEmpty()) {
                    filled++;
                }
            }
        }
        if (filled < 4) {
            return false;
        }
        BlockPos ceiling = feet.above(2);
        BlockState cs = p.level().getBlockState(ceiling);
        return cs.blocksMotion() && !cs.getCollisionShape(p.level(), ceiling).isEmpty();
    }

    /** Заикание: к части слов добавляется повтор первой буквы. */
    private static String stutter(String text) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder();
        for (String w : text.split(" ")) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (w.length() > 1 && Character.isLetter(w.charAt(0)) && rnd.nextInt(100) < 60) {
                String first = w.substring(0, 1);
                sb.append(first).append('-');
                if (rnd.nextBoolean()) {
                    sb.append(first).append('-');
                }
            }
            sb.append(w);
        }
        return sb.toString();
    }

    /** Есть ли рядом (в 2 блоках) обрыв глубиной 9+ блоков. Вода, лава и растения не считаются "полом". */
    private static boolean nearDrop(ServerPlayer p) {
        BlockPos base = p.blockPosition();
        int[][] dirs = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}};
        for (int[] d : dirs) {
            BlockPos pos = base.offset(d[0], 0, d[1]);
            int air = 0;
            for (int i = 1; i <= 10; i++) {
                BlockState bs = p.level().getBlockState(pos.below(i));
                if (bs.isAir() || !bs.blocksMotion() || !bs.getFluidState().isEmpty()) {
                    air++;
                } else {
                    break;
                }
            }
            if (air >= 9) {
                return true;
            }
        }
        return false;
    }

    // ===== ОКР =====
    private static int jumpCount(ServerPlayer p) {
        return p.getStats().getValue(Stats.CUSTOM.get(Stats.JUMP));
    }

    private static void tickOcd(MinecraftServer server, ServerPlayer p, PlayerState st) {
        if (!st.has(Disease.OCD) || p.isCreative() || p.isSpectator()) {
            st.task = null;
            return;
        }

        if (st.task == null) {
            if (--st.ocdCooldown <= 0) {
                OcdTask t = new OcdTask();
                t.jump = ThreadLocalRandom.current().nextBoolean();
                t.needed = roll(3, 6);
                t.lastJumps = jumpCount(p);
                t.lastSneak = p.isShiftKeyDown();
                st.task = t;
                p.sendSystemMessage(Component.literal("[ОКР] " + taskText(t) + " Срочно! Иначе смерть.")
                        .withStyle(ChatFormatting.DARK_RED));
            }
            return;
        }

        OcdTask t = st.task;
        if (t.jump) {
            int now = jumpCount(p);
            t.progress += Math.max(0, now - t.lastJumps);
            t.lastJumps = now;
        } else {
            boolean sneak = p.isShiftKeyDown();
            if (sneak && !t.lastSneak) {
                t.progress++;
            }
            t.lastSneak = sneak;
        }
        t.ticksLeft--;

        if (t.progress >= t.needed) {
            st.task = null;
            st.ocdCooldown = roll(OCD_MIN, OCD_MAX);
            p.displayClientMessage(Component.literal("Отпустило.").withStyle(ChatFormatting.GREEN), true);
        } else if (t.ticksLeft <= 0) {
            st.task = null;
            st.ocdCooldown = roll(OCD_MIN, OCD_MAX);
            if (!p.hurtServer((ServerLevel) p.level(), diseaseSource(p, "ocd"), Float.MAX_VALUE)) {
                p.kill((ServerLevel) p.level());
            }
        } else if (t.ticksLeft % 10 == 0) {
            p.displayClientMessage(Component.literal("ОКР: " + taskText(t) + " (" + t.progress + "/" + t.needed
                    + ") - " + ((t.ticksLeft + 19) / 20) + " c").withStyle(ChatFormatting.RED), true);
        }
    }

    private static String taskText(OcdTask t) {
        String word = (t.needed % 10 >= 2 && t.needed % 10 <= 4) ? "раза" : "раз";
        return (t.jump ? "Прыгни " : "Присядь ") + t.needed + " " + word + "!";
    }

    // ===== Команды =====
    /** Выдавать, снимать и очищать болезни может только оператор / хост с включёнными читами. */
    private static boolean isAdmin(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR);
    }

    private static List<String> diseaseNames() {
        List<String> names = new ArrayList<>();
        for (Disease d : Disease.values()) {
            names.add(d.title);
        }
        return names;
    }

    /** Ищет болезнь по id (blindness) или по русскому названию (Слепота). */
    private static Disease findDisease(String query) {
        String q = query.trim();
        for (Disease d : Disease.values()) {
            if (d.id.equalsIgnoreCase(q) || d.title.equalsIgnoreCase(q)) {
                return d;
            }
        }
        return null;
    }

    /** Подробное описание механики каждой болезни (для /disease info). */
    private static String details(Disease d) {
        return switch (d) {
            case BLINDNESS -> "постоянная слепота: экран почти чёрный, бегать нельзя.";
            case MUTENESS -> "все твои сообщения в чат блокируются.";
            case NO_LEFT_HAND -> "всё, что кладёшь во вторую руку, возвращается в инвентарь: щит и смена предметов недоступны.";
            case ASTHMA -> "после 5 секунд непрерывного бега приступ: 4 секунды темноты и бежать нельзя. Бег быстрее тратит сытость.";
            case DIABETES -> "постоянный голод (уровень 4): еда заканчивается намного быстрее.";
            case FEVER -> "раз в 20-50 секунд озноб на 6 секунд: камеру трясёт. Бить и ломать можно.";
            case OCD -> "раз в 1-2 минуты задание: прыгнуть или присесть 3-6 раз за 20 секунд. Не успел - смерть.";
            case NIGHT_BLINDNESS -> "в тёмных местах (низкий уровень света) накатывает тьма.";
            case INSOMNIA -> "в кровати уснуть нельзя: тебя сразу будит.";
            case ACROPHOBIA -> "рядом с обрывом от 9 блоков темнеет в глазах, через 8 секунд паническая атака с уроном.";
            case TREMOR -> "прицел постоянно дрожит.";
            case CEREBRAL_PALSY -> "дрожь камеры, шаткая походка и редкие судороги (рывок камеры и тела).";
            case LIMP -> "скорость ходьбы ниже примерно на треть.";
            case SCHIZOPHRENIA -> "раз в 20-70 секунд рядом звучат несуществующие звуки (зомби, крипер, скелет, эндермен, сердцебиение). Слышишь только ты.";
            case EPILEPSY -> "раз в 1-4 минуты приступ: 4 секунды тебя трясёт и двигаться нельзя. Экран при этом не мигает.";
            case NARCOLEPSY -> "раз в 1-4 минуты засыпаешь на 5 секунд: замираешь и клонишь голову.";
            case VERTIGO -> "камера плавно покачивается.";
            case DEAFNESS -> "общая громкость игры падает до 8%.";
            case HEMOPHILIA -> "раз в 2 минуты кровотечение: теряешь 1 сердце.";
            case ANEMIA -> "максимум здоровья меньше на 2 сердца, раз в 40 секунд тьма в глазах на 4 секунды.";
            case ALLERGY -> "цветы рядом (в 2 блоках) или в руке: слезятся глаза, через 8 секунд паника с уроном.";
            case AQUAPHOBIA -> "в воде и под дождём (на открытом небе) темнеет в глазах, через 8 секунд паническая атака с уроном.";
            case CLAUSTROPHOBIA -> "в тесном месте темнеет в глазах, через 8 секунд паническая атака с уроном.";
            case HICCUPS -> "раз в 15-45 секунд толчок камеры и звук.";
            case STUTTER -> "твои сообщения в чате заикаются.";
            case ALEXIA -> "весь текст в игре (меню, чат, таблички, книги, подсказки) превращается в случайные буквы, они меняются каждые 1-2 секунды. Цифры и знаки остаются.";
            case DYSLEXIA -> "часть букв в тексте путается (примерно треть): читать трудно, но разобрать можно.";
            case OSTEOPOROSIS -> "урон от падения вдвое больше обычного.";
            case PARANOIA -> "раз в 1-3 минуты тебе мерещится: ложное сообщение, что кто-то зашёл в игру, и звук рядом. Видишь и слышишь только ты.";
            case TUNNEL_VISION -> "края экрана затемнены: видишь только узкий центр.";
            case DWARFISM -> "рост на четверть меньше (было 2 м - стало 1.5 м): модель и хитбокс уменьшаются целиком, текстура не растягивается. Друг видит тебя маленьким.";
            case GIGANTISM -> "рост на 30% больше (хитбокс примерно 2.3 блока): в двери высотой 2 блока не пролезть, нужны проходы в 3 блока. Друг видит тебя большим.";
            case HELIOPHOBIA -> "днём в ясную погоду под открытым небом темнеет в глазах, через 8 секунд паническая атака с уроном и сердцебиением. Под крышей, в дождь и ночью — безопасно.";
            case WEAK_HANDS -> "при ударе моба, получении урона или долгом беге предмет из главной руки может выскользнуть перед тобой (откат 15-20 секунд).";
            case BEE_DISRESPECT -> "пчёлы в радиусе 16 блоков при прямой видимости мгновенно злятся и летят жалить без удара (до 4 пчёл в секунду).";
            case SHORT_ARMS -> "дистанция добычи блоков снижена на 2 блока, дистанция удара по сущностям — на 1.5 блока: нужно подходить вплотную.";
            case MUSCLE_WEAKNESS -> "скорость копания снижена на 50%: блоки ломаются вдвое дольше. На возможность и силу ударов не влияет.";
            case HEART_DISEASE -> "максимальное здоровье снижено до 7 сердец (14 HP).";
        };
    }

    private static List<String> giveNames() {
        List<String> names = diseaseNames();
        names.add("all");
        return names;
    }

    private static boolean isAllQuery(String s) {
        return s.equalsIgnoreCase("all") || s.equalsIgnoreCase("все");
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private static ServerPlayer findOnlinePlayer(MinecraftServer server, String name) {
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (sp.getScoreboardName().equalsIgnoreCase(name)) {
                return sp;
            }
        }
        return null;
    }

    private record ParsedTarget(ServerPlayer target, String query) {}

    /** Разбирает "<болезнь> [игрок]": если игрока в конце нет - выбирается сам отправитель команды. */
    private static ParsedTarget parseTargetAndQuery(CommandContext<CommandSourceStack> ctx, String raw, boolean allowAll)
            throws CommandSyntaxException {
        String trimmed = raw.trim();
        String unquoted = stripQuotes(trimmed);
        if (findDisease(unquoted) != null || (allowAll && isAllQuery(unquoted))) {
            return new ParsedTarget(ctx.getSource().getPlayerOrException(), unquoted);
        }
        int lastSpace = trimmed.lastIndexOf(' ');
        if (lastSpace > 0) {
            String head = stripQuotes(trimmed.substring(0, lastSpace).trim());
            String tail = trimmed.substring(lastSpace + 1).trim();
            if (findDisease(head) != null || (allowAll && isAllQuery(head))) {
                ServerPlayer target = tail.equals("@s")
                        ? ctx.getSource().getPlayerOrException()
                        : findOnlinePlayer(ctx.getSource().getServer(), tail);
                if (target == null) {
                    ctx.getSource().sendFailure(Component.literal("Игрок не найден: " + tail));
                    return null;
                }
                return new ParsedTarget(target, head);
            }
        }
        ctx.getSource().sendFailure(Component.literal("Нет такой болезни. Список: /disease list"));
        return null;
    }

    private static CompletableFuture<Suggestions> suggestDiseaseOrPlayer(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder, boolean allowAll) {
        String rem = builder.getRemaining();
        int lastSpace = rem.lastIndexOf(' ');
        if (lastSpace > 0) {
            String prefix = stripQuotes(rem.substring(0, lastSpace).trim());
            if (findDisease(prefix) != null || (allowAll && isAllQuery(prefix))) {
                SuggestionsBuilder playerBuilder = builder.createOffset(builder.getStart() + lastSpace + 1);
                List<String> players = new ArrayList<>();
                for (ServerPlayer sp : ctx.getSource().getServer().getPlayerList().getPlayers()) {
                    players.add(sp.getScoreboardName());
                }
                return SharedSuggestionProvider.suggest(players, playerBuilder);
            }
        }
        return SharedSuggestionProvider.suggest(allowAll ? giveNames() : diseaseNames(), builder);
    }

    private static boolean isSelf(CommandSourceStack source, ServerPlayer target) {
        return source.getEntity() == target;
    }

    /** Выдаёт все доступные болезни сразу (без спама в чат, с учётом взаимоисключений). Возвращает число новых. */
    private static int grantAll(ServerPlayer p) {
        PlayerState st = state(p);
        int count = 0;
        for (Disease d : Disease.values()) {
            if (isCompatible(st, d) && st.diseases.add(d)) {
                count++;
                if (d == Disease.OCD) {
                    st.ocdCooldown = roll(OCD_MIN, OCD_MAX);
                    st.task = null;
                }
            }
        }
        syncAttributes(p, st);
        sync(p);
        broadcastArmless(((ServerLevel) p.level()).getServer());
        return count;
    }

    private static int executeClear(CommandSourceStack source, ServerPlayer target) {
        clearDiseases(target);
        if (isSelf(source, target)) {
            source.sendSuccess(() -> Component.literal("Все болезни сняты."), false);
        } else {
            target.sendSystemMessage(Component.literal("Все твои болезни сняты.").withStyle(ChatFormatting.GREEN));
            source.sendSuccess(() -> Component.literal("Все болезни сняты у " + target.getScoreboardName() + "."), false);
        }
        return 1;
    }

    private static int executeRandom(CommandSourceStack source, ServerPlayer target) {
        if (!giveRandom(target)) {
            source.sendFailure(Component.literal(isSelf(source, target)
                    ? "У тебя уже есть все доступные болезни."
                    : "У игрока " + target.getScoreboardName() + " уже есть все доступные болезни."));
            return 0;
        }
        if (!isSelf(source, target)) {
            source.sendSuccess(() -> Component.literal("Игроку " + target.getScoreboardName() + " выдана случайная болезнь."), false);
        }
        return 1;
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("disease")
                        // /disease list - список ВСЕХ болезней мода ([+] = у тебя есть)
                        .then(Commands.literal("list").executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            PlayerState st = state(p);
                            ctx.getSource().sendSuccess(() -> Component
                                    .literal("Все болезни мода (" + Disease.values().length + "), [+] = у тебя есть:")
                                    .withStyle(ChatFormatting.YELLOW), false);
                            for (Disease d : Disease.values()) {
                                boolean has = st.has(d);
                                Component line = Component.literal(has ? "[+] " : "[ ] ")
                                        .withStyle(has ? ChatFormatting.RED : ChatFormatting.DARK_GRAY)
                                        .append(Component.literal(d.title).withStyle(ChatFormatting.WHITE))
                                        .append(Component.literal(" - " + d.description)
                                                .withStyle(ChatFormatting.GRAY));
                                ctx.getSource().sendSuccess(() -> line, false);
                            }
                            return 1;
                        }))
                        // /disease info <название> - как именно работает болезнь (доступно всем)
                        .then(Commands.literal("info")
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .suggests((ctx, builder) ->
                                                SharedSuggestionProvider.suggest(diseaseNames(), builder))
                                        .executes(ctx -> {
                                            Disease d = findDisease(StringArgumentType.getString(ctx, "name"));
                                            if (d == null) {
                                                ctx.getSource().sendFailure(Component.literal(
                                                        "Нет такой болезни. Список: /disease list"));
                                                return 0;
                                            }
                                            ctx.getSource().sendSuccess(() -> Component
                                                    .literal(d.title + " (" + d.id + ")")
                                                    .withStyle(ChatFormatting.YELLOW), false);
                                            ctx.getSource().sendSuccess(() -> Component
                                                    .literal(d.description).withStyle(ChatFormatting.GRAY), false);
                                            ctx.getSource().sendSuccess(() -> Component
                                                    .literal("Как работает: " + details(d))
                                                    .withStyle(ChatFormatting.WHITE), false);
                                            return 1;
                                        })))
                        // /disease give <название | all> [игрок] - выдать болезнь или все сразу (оператор)
                        .then(Commands.literal("give").requires(DiseaseManager::isAdmin)
                                .then(Commands.argument("args", StringArgumentType.greedyString())
                                        .suggests((ctx, builder) -> suggestDiseaseOrPlayer(ctx, builder, true))
                                        .executes(ctx -> {
                                            ParsedTarget parsed = parseTargetAndQuery(
                                                    ctx, StringArgumentType.getString(ctx, "args"), true);
                                            if (parsed == null) {
                                                return 0;
                                            }
                                            ServerPlayer target = parsed.target();
                                            String query = parsed.query();
                                            boolean self = isSelf(ctx.getSource(), target);
                                            if (isAllQuery(query)) {
                                                int given = grantAll(target);
                                                ctx.getSource().sendSuccess(() -> Component.literal(self
                                                        ? "Выдано болезней: " + given
                                                        : "Игроку " + target.getScoreboardName() + " выдано болезней: " + given), false);
                                                return 1;
                                            }
                                            Disease d = findDisease(query);
                                            if (d == null) {
                                                ctx.getSource().sendFailure(Component.literal(
                                                        "Нет такой болезни. Список: /disease list"));
                                                return 0;
                                            }
                                            PlayerState st = state(target);
                                            if (!isCompatible(st, d)) {
                                                String reason = incompatibleReason(st, d);
                                                ctx.getSource().sendFailure(Component.literal(self
                                                        ? "Нельзя совместить: " + reason
                                                        : "У " + target.getScoreboardName() + " несовместимая болезнь: " + reason));
                                                return 0;
                                            }
                                            if (!grant(target, d)) {
                                                ctx.getSource().sendFailure(Component.literal(self
                                                        ? "Эта болезнь у тебя уже есть."
                                                        : "Эта болезнь уже есть у " + target.getScoreboardName() + "."));
                                                return 0;
                                            }
                                            if (!self) {
                                                ctx.getSource().sendSuccess(() -> Component.literal(
                                                        "Игроку " + target.getScoreboardName() + " выдана болезнь: " + d.title), false);
                                            }
                                            return 1;
                                        })))
                        // /disease take <название> [игрок] - снять одну болезнь (оператор)
                        .then(Commands.literal("take").requires(DiseaseManager::isAdmin)
                                .then(Commands.argument("args", StringArgumentType.greedyString())
                                        .suggests((ctx, builder) -> suggestDiseaseOrPlayer(ctx, builder, false))
                                        .executes(ctx -> {
                                            ParsedTarget parsed = parseTargetAndQuery(
                                                    ctx, StringArgumentType.getString(ctx, "args"), false);
                                            if (parsed == null) {
                                                return 0;
                                            }
                                            ServerPlayer target = parsed.target();
                                            boolean self = isSelf(ctx.getSource(), target);
                                            Disease d = findDisease(parsed.query());
                                            if (d == null) {
                                                ctx.getSource().sendFailure(Component.literal(
                                                        "Нет такой болезни. Список: /disease list"));
                                                return 0;
                                            }
                                            PlayerState st = state(target);
                                            if (!st.diseases.remove(d)) {
                                                ctx.getSource().sendFailure(Component.literal(self
                                                        ? "У тебя нет этой болезни."
                                                        : "У " + target.getScoreboardName() + " нет этой болезни."));
                                                return 0;
                                            }
                                            if (d == Disease.OCD) {
                                                st.task = null;
                                            }
                                            if (d == Disease.WEAK_HANDS) {
                                                st.weakSprintTicks = 0;
                                                st.dropCooldown = 0;
                                            }
                                            st.exposure = 0;
                                            st.panicking = false;
                                            removeDiseaseEffects(target);
                                            syncAttributes(target, st);
                                            sync(target);
                                            if (d == Disease.NO_LEFT_HAND) {
                                                broadcastArmless(((ServerLevel) target.level()).getServer());
                                            }
                                            if (self) {
                                                ctx.getSource().sendSuccess(
                                                        () -> Component.literal("Болезнь снята: " + d.title), false);
                                            } else {
                                                target.sendSystemMessage(Component.literal("Болезнь снята: " + d.title)
                                                        .withStyle(ChatFormatting.GREEN));
                                                ctx.getSource().sendSuccess(() -> Component.literal(
                                                        "У " + target.getScoreboardName() + " снята болезнь: " + d.title), false);
                                            }
                                            return 1;
                                        })))
                        // /disease clear [игрок] - снять все болезни (оператор)
                        .then(Commands.literal("clear").requires(DiseaseManager::isAdmin)
                                .executes(ctx -> executeClear(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> executeClear(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                        // /disease random [игрок] - выдать случайную болезнь (оператор)
                        .then(Commands.literal("random").requires(DiseaseManager::isAdmin)
                                .executes(ctx -> executeRandom(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> executeRandom(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))));
    }

    // ===== Сохранение в файл мира (world/diseases.json) =====
    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("diseases.json");
    }

    private static void load(MinecraftServer server) {
        STATES.clear();
        sinceReset = 0;
        tickCounter = 0;
        globalTimer = roll(MIN_INTERVAL, MAX_INTERVAL);
        Path file = file(server);
        if (!Files.exists(file)) {
            return;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            if (root.has("sinceReset")) {
                sinceReset = root.get("sinceReset").getAsInt();
            }
            if (root.has("nextIn")) {
                globalTimer = Math.max(20, root.get("nextIn").getAsInt());
            }
            JsonObject players = root.has("players") ? root.getAsJsonObject("players") : new JsonObject();
            for (Map.Entry<String, JsonElement> e : players.entrySet()) {
                try {
                    PlayerState st = new PlayerState();
                    for (JsonElement el : e.getValue().getAsJsonArray()) {
                        Disease d = Disease.byId(el.getAsString());
                        if (d != null) {
                            st.diseases.add(d);
                        }
                    }
                    STATES.put(UUID.fromString(e.getKey()), st);
                } catch (Exception ex) {
                    Diseases.LOGGER.warn("Битая запись в diseases.json, пропущена: {}", e.getKey());
                }
            }
        } catch (Exception ex) {
            Diseases.LOGGER.error("Не удалось загрузить diseases.json", ex);
        }
    }

    private static void save(MinecraftServer server) {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("sinceReset", sinceReset);
            root.addProperty("nextIn", globalTimer);
            JsonObject players = new JsonObject();
            for (Map.Entry<UUID, PlayerState> e : STATES.entrySet()) {
                JsonArray arr = new JsonArray();
                for (Disease d : e.getValue().diseases) {
                    arr.add(d.id);
                }
                players.add(e.getKey().toString(), arr);
            }
            root.add("players", players);
            Path file = file(server);
            Path tmp = file.resolveSibling("diseases.json.tmp");
            Files.writeString(tmp, new Gson().toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ex) {
            Diseases.LOGGER.error("Не удалось сохранить diseases.json", ex);
        }
    }
}
