package com.diseasemod;

import com.diseasemod.client.StutterTracker;
import com.diseasemod.client.mixin.ChatComponentMixin;
import com.diseasemod.client.mixin.ChatListenerMixin;
import com.diseasemod.mixin.EnchantedAppleCureMixin;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import static org.junit.jupiter.api.Assertions.*;

public class ModLogicTest {

    @Test
    public void testStutterTrackerBasic() {
        assertEquals("", StutterTracker.stutter(null), "Null text stutter");
        assertEquals("", StutterTracker.stutter(""), "Empty text stutter");
        assertEquals("a", StutterTracker.stutter("a"), "Single char stutter");
        assertEquals("123", StutterTracker.stutter("123"), "Numbers stutter");
        assertEquals("!@#$", StutterTracker.stutter("!@#$"), "Punctuation stutter");

        String whitespaceInput = "Hello   world  ";
        String stutteredWhitespace = StutterTracker.stutter(whitespaceInput);
        assertTrue(stutteredWhitespace.endsWith("  "), "Trailing whitespace must be preserved: '" + stutteredWhitespace + "'");

        String original = "This is a comprehensive test sentence with several words that should be affected by stuttering logic";
        boolean sawStutter = false;
        for (int i = 0; i < 20; i++) {
            String res = StutterTracker.stutter(original);
            assertTrue(res.length() >= original.length(), "Stuttered text length must be >= original length");
            if (res.contains("-")) {
                sawStutter = true;
            }
        }
        assertTrue(sawStutter, "Stutter should produce hyphenated prefixes over 20 iterations");

        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();

        assertFalse(StutterTracker.isStuttering(null), "Null UUID is not stuttering");
        assertFalse(StutterTracker.isStuttering(u1), "Unregistered UUID is not stuttering");

        StutterTracker.ids = Set.of(u1);
        assertTrue(StutterTracker.isStuttering(u1), "Registered UUID is stuttering");
        assertFalse(StutterTracker.isStuttering(u2), "Unregistered UUID is not stuttering");

        StutterTracker.ids = Set.of();
    }

    @Test
    public void testDiseaseManagerStutterParity() {
        assertEquals("", DiseaseManager.stutter(null), "Null text in DiseaseManager.stutter");
        assertEquals("", DiseaseManager.stutter(""), "Empty text in DiseaseManager.stutter");
        assertEquals("123", DiseaseManager.stutter("123"));

        String input = "testing trailing spaces   ";
        String out = DiseaseManager.stutter(input);
        assertTrue(out.endsWith("   "), "DiseaseManager.stutter must preserve trailing whitespace");
    }

    @Test
    public void testStutterTrackerComponentProcessing() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();

        Component normalMsg = Component.literal("<Player> Hello world");

        StutterTracker.ids = Set.of();
        StutterTracker.currentSender = u1;
        Component out = StutterTracker.processComponent(normalMsg);
        assertEquals(normalMsg.getString(), out.getString(), "Empty ids tracker returns same string");

        StutterTracker.ids = Set.of(u1);
        StutterTracker.currentSender = u1;
        Component stuttered = StutterTracker.processComponent(normalMsg);
        assertTrue(stuttered.getString().startsWith("<Player> "), "Prefix <Player> must be preserved: " + stuttered.getString());

        Component colonMsg = Component.literal("Player: Hello world");
        Component stutteredColon = StutterTracker.processComponent(colonMsg);
        assertTrue(stutteredColon.getString().startsWith("Player: "), "Prefix Player: must be preserved: " + stutteredColon.getString());

        StutterTracker.currentSender = u2;
        Component untouched = StutterTracker.processComponent(normalMsg);
        assertEquals(normalMsg.getString(), untouched.getString(), "Non-stuttering player message should not be modified");

        StutterTracker.ids = Set.of();
        StutterTracker.currentSender = null;
    }

    @Test
    public void testStutterTrackerTranslatableContentsPreservation() {
        UUID u1 = UUID.randomUUID();
        StutterTracker.ids = Set.of(u1);
        StutterTracker.currentSender = u1;

        Style senderStyle = Style.EMPTY
                .withColor(ChatFormatting.GOLD)
                .withClickEvent(new ClickEvent.SuggestCommand("/tell Player "))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to whisper")));

        Component senderComp = Component.literal("Player").withStyle(senderStyle);
        Component bodyComp = Component.literal("This is a long test message designed to verify that chat body stutters properly");

        MutableComponent chatMessage = Component.translatable("chat.type.text", senderComp, bodyComp);

        Component result = StutterTracker.processComponent(chatMessage);
        assertNotNull(result);
        assertTrue(result.getContents() instanceof TranslatableContents, "Processed component must retain TranslatableContents");

        TranslatableContents resultTc = (TranslatableContents) result.getContents();
        assertEquals("chat.type.text", resultTc.getKey());
        assertEquals(2, resultTc.getArgs().length);

        // Sender component in arg 0 must have intact style, click event, and hover event!
        Object arg0 = resultTc.getArgs()[0];
        assertTrue(arg0 instanceof Component, "Arg 0 must be Component");
        Component resSender = (Component) arg0;
        assertEquals("Player", resSender.getString());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GOLD), resSender.getStyle().getColor());
        assertNotNull(resSender.getStyle().getClickEvent(), "ClickEvent must be preserved on sender");
        assertTrue(resSender.getStyle().getClickEvent() instanceof ClickEvent.SuggestCommand);
        assertEquals("/tell Player ", ((ClickEvent.SuggestCommand) resSender.getStyle().getClickEvent()).command());
        assertNotNull(resSender.getStyle().getHoverEvent(), "HoverEvent must be preserved on sender");

        // Arg 1 must be stuttered body
        Object arg1 = resultTc.getArgs()[1];
        assertTrue(arg1 instanceof Component, "Arg 1 must be Component");
        Component resBody = (Component) arg1;
        assertTrue(resBody.getString().length() >= bodyComp.getString().length(), "Body must be stuttered");

        StutterTracker.ids = Set.of();
        StutterTracker.currentSender = null;
    }

    @Test
    public void testStutterSyncPayload() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        List<UUID> list = List.of(u1, u2);
        StutterSyncPayload payload = new StutterSyncPayload(list);

        assertEquals(list, payload.ids(), "Payload ids must match");
        assertEquals(StutterSyncPayload.ID, payload.type(), "Payload type must match ID");
        assertEquals("diseases", StutterSyncPayload.PAYLOAD_ID.getNamespace(), "Namespace");
        assertEquals("stutter", StutterSyncPayload.PAYLOAD_ID.getPath(), "Path");
        assertNotNull(StutterSyncPayload.CODEC, "Codec must not be null");

        // Null safe constructor test
        StutterSyncPayload nullPayload = new StutterSyncPayload(null);
        assertNotNull(nullPayload.ids(), "Null ids should normalize to empty list");
        assertTrue(nullPayload.ids().isEmpty(), "Empty list for null ids");
    }

    @Test
    public void testPlayerStateFields() throws Exception {
        Class<?>[] declaredClasses = DiseaseManager.class.getDeclaredClasses();
        Class<?> playerStateClass = null;
        for (Class<?> c : declaredClasses) {
            if (c.getSimpleName().equals("PlayerState")) {
                playerStateClass = c;
                break;
            }
        }
        assertNotNull(playerStateClass, "PlayerState class must exist");

        boolean oldExposureFound = false;
        try {
            playerStateClass.getDeclaredField("exposure");
            oldExposureFound = true;
        } catch (NoSuchFieldException e) {
            // expected
        }
        assertFalse(oldExposureFound, "Old 'exposure' field must not exist in PlayerState");

        String[] requiredFields = {
                "exposureWater", "exposureTight", "exposureHeight", "exposureFlowers", "exposureSun"
        };
        for (String fName : requiredFields) {
            Field f = playerStateClass.getDeclaredField(fName);
            assertEquals(int.class, f.getType(), "Field " + fName + " must be int");
        }

        boolean oldCureCooldown = false;
        try {
            playerStateClass.getDeclaredField("cureCooldown");
            oldCureCooldown = true;
        } catch (NoSuchFieldException e) {
            // expected
        }
        assertFalse(oldCureCooldown, "Old 'cureCooldown' field should be removed");
    }

    @Test
    public void testStatesCleanup() throws Exception {
        Field statesField = DiseaseManager.class.getDeclaredField("STATES");
        statesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<UUID, Object> states = (java.util.Map<UUID, Object>) statesField.get(null);

        Class<?>[] declaredClasses = DiseaseManager.class.getDeclaredClasses();
        Class<?> playerStateClass = null;
        for (Class<?> c : declaredClasses) {
            if (c.getSimpleName().equals("PlayerState")) {
                playerStateClass = c;
                break;
            }
        }
        var ctor = playerStateClass.getDeclaredConstructor();
        ctor.setAccessible(true);

        Object stateEmpty = ctor.newInstance();
        Object stateWithDisease = ctor.newInstance();

        Field diseasesField = playerStateClass.getDeclaredField("diseases");
        diseasesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Set<Disease> diseasesSet = (java.util.Set<Disease>) diseasesField.get(stateWithDisease);
        diseasesSet.add(Disease.MUTENESS);

        UUID emptyId = UUID.randomUUID();
        UUID nonEmptyId = UUID.randomUUID();

        states.put(emptyId, stateEmpty);
        states.put(nonEmptyId, stateWithDisease);

        assertTrue(states.containsKey(emptyId), "Before cleanup: contains empty");
        assertTrue(states.containsKey(nonEmptyId), "Before cleanup: contains non-empty");

        // Execute cleanup line:
        states.values().removeIf(st -> {
            try {
                java.util.Set<?> d = (java.util.Set<?>) diseasesField.get(st);
                return d.isEmpty();
            } catch (Exception e) {
                return false;
            }
        });

        assertFalse(states.containsKey(emptyId), "After cleanup: empty state must be removed");
        assertTrue(states.containsKey(nonEmptyId), "After cleanup: non-empty state must be preserved");

        states.remove(nonEmptyId);
    }

    @Test
    public void testCureOneDiseaseMethodSignature() throws Exception {
        Method m = DiseaseManager.class.getMethod("cureOneDisease", net.minecraft.server.level.ServerPlayer.class);
        assertTrue(Modifier.isPublic(m.getModifiers()), "cureOneDisease must be public");
        assertTrue(Modifier.isStatic(m.getModifiers()), "cureOneDisease must be static");
        assertEquals(void.class, m.getReturnType(), "cureOneDisease returns void");

        boolean checkCureExists = false;
        for (Method method : DiseaseManager.class.getDeclaredMethods()) {
            if (method.getName().equals("checkCure")) {
                checkCureExists = true;
                break;
            }
        }
        assertFalse(checkCureExists, "checkCure must be removed from DiseaseManager");
    }

    @Test
    public void testEnchantedAppleCureMixinSafety() throws Exception {
        Class<?> mixinClass = EnchantedAppleCureMixin.class;

        // Verify there is no state field like diseases$wasEnchantedApple on LivingEntity
        for (Field f : mixinClass.getDeclaredFields()) {
            assertFalse(f.getName().contains("wasEnchantedApple"),
                    "Mixin should not use stateful wasEnchantedApple field to avoid early-return leaks");
        }

        // Verify method diseases$cureOnApple exists and targets finishUsingItem
        Method cureMethod = null;
        for (Method m : mixinClass.getDeclaredMethods()) {
            if (m.getName().equals("diseases$cureOnApple")) {
                cureMethod = m;
                break;
            }
        }
        assertNotNull(cureMethod, "diseases$cureOnApple method must exist");
        Inject injectAnn = cureMethod.getAnnotation(Inject.class);
        assertNotNull(injectAnn, "@Inject annotation must be present");
        assertEquals("completeUsingItem", injectAnn.method()[0]);
        At atAnn = injectAnn.at()[0];
        assertEquals("INVOKE", atAnn.value());
        assertTrue(atAnn.target().contains("finishUsingItem"), "Must target finishUsingItem invocation");
    }

    @Test
    public void testMixinsStructure() throws Exception {
        assertNotNull(Class.forName("com.diseasemod.mixin.EnchantedAppleCureMixin"),
                "EnchantedAppleCureMixin class must be loadable");

        assertNotNull(Class.forName("com.diseasemod.client.mixin.ChatComponentMixin"),
                "ChatComponentMixin class must be loadable");
        Method stutterTextMethod = ChatComponentMixin.class.getMethod("stutterText", String.class);
        assertTrue(Modifier.isStatic(stutterTextMethod.getModifiers()), "stutterText must be static");
        assertNotNull(stutterTextMethod.invoke(null, "test text"));

        assertNotNull(Class.forName("com.diseasemod.client.mixin.ChatListenerMixin"),
                "ChatListenerMixin class must be loadable");

        // Verify mixin JSON files
        String mainMixins = new String(ModLogicTest.class.getResourceAsStream("/diseases.mixins.json").readAllBytes());
        assertTrue(mainMixins.contains("EnchantedAppleCureMixin"), "diseases.mixins.json must contain EnchantedAppleCureMixin");

        String clientMixins = new String(ModLogicTest.class.getResourceAsStream("/diseases.client.mixins.json").readAllBytes());
        assertTrue(clientMixins.contains("ChatComponentMixin"), "diseases.client.mixins.json must contain ChatComponentMixin");
        assertTrue(clientMixins.contains("ChatListenerMixin"), "diseases.client.mixins.json must contain ChatListenerMixin");
    }
}
