package com.diseasemod.client;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;

public final class StutterTracker {
    public static volatile Set<UUID> ids = Set.of();
    public static volatile UUID currentSender = null;

    private StutterTracker() {}

    public static boolean isStuttering(UUID uuid) {
        return uuid != null && ids != null && ids.contains(uuid);
    }

    public static boolean isStuttering(int entityId) {
        return false;
    }

    public static String stutter(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : "";
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder();
        String[] words = text.split(" ", -1);
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            String w = words[i];
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

    public static Component processComponent(Component message) {
        if (message == null || ids == null || ids.isEmpty()) {
            return message;
        }

        UUID sender = currentSender;

        // If message is TranslatableContents (vanilla chat format: chat.type.text, etc.)
        if (message.getContents() instanceof TranslatableContents tc) {
            Object[] args = tc.getArgs();
            if (sender == null && args.length >= 1 && args[0] instanceof Component senderComp) {
                String name = senderComp.getString();
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.getConnection() != null) {
                    var info = mc.getConnection().getPlayerInfo(name);
                    if (info != null && isStuttering(info.getProfile().id())) {
                        sender = info.getProfile().id();
                    }
                }
            }

            if (sender != null && isStuttering(sender)) {
                if (args.length >= 2) {
                    Object[] newArgs = args.clone();
                    int targetIndex = newArgs.length - 1;
                    if (newArgs[targetIndex] instanceof Component contentComp) {
                        newArgs[targetIndex] = Component.literal(stutter(contentComp.getString()))
                                .withStyle(contentComp.getStyle());
                    } else if (newArgs[targetIndex] instanceof String contentStr) {
                        newArgs[targetIndex] = stutter(contentStr);
                    }
                    MutableComponent mutable = MutableComponent.create(
                            new TranslatableContents(tc.getKey(), tc.getFallback(), newArgs))
                            .withStyle(message.getStyle());
                    for (Component sibling : message.getSiblings()) {
                        mutable.append(sibling);
                    }
                    return mutable;
                }
            }
        }

        String fullText = message.getString();
        if (sender == null) {
            if (fullText.startsWith("<") && fullText.contains("> ")) {
                int end = fullText.indexOf("> ");
                String name = fullText.substring(1, end);
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.getConnection() != null) {
                    var info = mc.getConnection().getPlayerInfo(name);
                    if (info != null && isStuttering(info.getProfile().id())) {
                        sender = info.getProfile().id();
                    }
                }
            }
        }

        if (sender != null && isStuttering(sender)) {
            int prefixEnd = fullText.indexOf("> ");
            if (prefixEnd != -1) {
                String prefix = fullText.substring(0, prefixEnd + 2);
                String body = fullText.substring(prefixEnd + 2);
                return Component.literal(prefix + stutter(body)).withStyle(message.getStyle());
            }
            int colonIndex = fullText.indexOf(": ");
            if (colonIndex != -1) {
                String prefix = fullText.substring(0, colonIndex + 2);
                String body = fullText.substring(colonIndex + 2);
                return Component.literal(prefix + stutter(body)).withStyle(message.getStyle());
            }
            return Component.literal(stutter(fullText)).withStyle(message.getStyle());
        }

        return message;
    }
}
