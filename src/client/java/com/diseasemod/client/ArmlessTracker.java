package com.diseasemod.client;

import java.util.Set;

/** Какие игроки (по номеру сущности) сейчас без левой руки. Данные приходят с сервера. */
public final class ArmlessTracker {
    public static volatile Set<Integer> ids = Set.of();

    private ArmlessTracker() {}

    public static boolean isArmless(int entityId) {
        return ids.contains(entityId);
    }
}
