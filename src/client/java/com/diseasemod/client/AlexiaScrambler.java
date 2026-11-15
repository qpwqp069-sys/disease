package com.diseasemod.client;

/**
 * Алексия: пока она активна, каждая буква, которую рисует игра, заменяется на другую.
 * Замена одинакова для одной и той же буквы в течение ~1.5 секунды, потом "перемешивается" заново.
 * Цифры, пробелы и знаки не трогаются. Меню и интерфейсы тоже подвержены алексии (хардкорная задумка).
 */
public final class AlexiaScrambler {
    private static final String LETTERS = "абвгдеёжзийклмнопрстуфхцчшщъыьэюяabcdefghijklmnopqrstuvwxyz";

    /** Есть ли у игрока алексия/дислексия (ставится из DiseasesClient). */
    public static volatile boolean active = false;
    /** Временно отключает замену (чтобы наш собственный список болезней в HUD оставался читаемым). */
    public static volatile boolean exempt = false;
    /** true = дислексия: путается только часть букв (около трети), false = алексия: путаются все. */
    public static volatile boolean partial = false;

    private AlexiaScrambler() {}

    public static int scramble(int codepoint) {
        if (!active || exempt || !Character.isLetter(codepoint)) {
            return codepoint;
        }
        long bucket = System.currentTimeMillis() / 1500L;
        long h = (codepoint * 2654435761L) ^ (bucket * 40503L);
        h ^= (h >>> 13);
        h *= 0x9E3779B97F4A7C15L;
        h ^= (h >>> 29);
        if (partial && Math.floorMod(h >>> 7, 100L) >= 35) {
            return codepoint;
        }
        int out = LETTERS.charAt((int) Math.floorMod(h, (long) LETTERS.length()));
        return Character.isUpperCase(codepoint) ? Character.toUpperCase(out) : out;
    }
}
