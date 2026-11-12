package com.diseasemod;

public enum Disease {
    BLINDNESS("blindness", "Слепота", "Ты почти ничего не видишь."),
    MUTENESS("muteness", "Немота", "Ты не можешь писать в чат."),
    NO_LEFT_HAND("no_left_hand", "Без левой руки", "Во вторую (левую) руку ничего не взять."),
    ASTHMA("asthma", "Астма", "Если долго бежать - приступ: темнеет в глазах и нечем дышать."),
    DIABETES("diabetes", "Диабет", "Голод наступает намного быстрее."),
    FEVER("fever", "Лихорадка", "Периодически бьёт озноб: тебя трясёт."),
    OCD("ocd", "ОКР", "Навязчивые действия: не выполнишь вовремя - умрёшь."),
    NIGHT_BLINDNESS("night_blindness", "Куриная слепота", "В темноте ты почти ничего не видишь."),
    INSOMNIA("insomnia", "Бессонница", "Ты не можешь уснуть в кровати."),
    ACROPHOBIA("acrophobia", "Акрофобия", "Рядом с обрывом накатывает паника, темнеет в глазах."),
    TREMOR("tremor", "Тремор", "Руки трясутся, прицел дрожит."),
    CEREBRAL_PALSY("cerebral_palsy", "ДЦП", "Подёргивания, шаткая походка, непроизвольные движения."),
    LIMP("limp", "Хромота", "Ты еле ходишь, скорость заметно упала."),
    SCHIZOPHRENIA("schizophrenia", "Шизофрения", "Ты слышишь звуки, которых нет на самом деле."),
    EPILEPSY("epilepsy", "Эпилепсия", "Время от времени случаются приступы: тебя трясёт, ты не можешь двигаться."),
    NARCOLEPSY("narcolepsy", "Нарколепсия", "Ты внезапно засыпаешь посреди дня."),
    VERTIGO("vertigo", "Головокружение", "Мир плывёт перед глазами."),
    DEAFNESS("deafness", "Глухота", "Ты почти ничего не слышишь."),
    HEMOPHILIA("hemophilia", "Гемофилия", "Время от времени открывается кровотечение."),
    ANEMIA("anemia", "Анемия", "Мало крови: меньше здоровья, периодически темнеет в глазах."),
    ALLERGY("allergy", "Аллергия на цветы", "Рядом с цветами слезятся глаза."),
    AQUAPHOBIA("aquaphobia", "Аквафобия", "В воде и под дождём накатывает паника."),
    CLAUSTROPHOBIA("claustrophobia", "Клаустрофобия", "В тесных местах накатывает паника, темнеет в глазах."),
    HICCUPS("hiccups", "Икота", "Ты время от времени икаешь, камера дёргается."),
    STUTTER("stutter", "Заикание", "Твои сообщения в чате заикаются."),
    ALEXIA("alexia", "Алексия", "Ты не можешь читать: вместо букв случайные буквы."),
    DYSLEXIA("dyslexia", "Дислексия", "Часть букв в тексте путается."),
    OSTEOPOROSIS("osteoporosis", "Остеопороз", "Кости хрупкие: урон от падения вдвое больше."),
    PARANOIA("paranoia", "Паранойя", "Тебе мерещится, что рядом кто-то есть: ложные сообщения и звуки."),
    TUNNEL_VISION("tunnel_vision", "Туннельное зрение", "Края поля зрения затемнены: видишь только узкий центр."),
    DWARFISM("dwarfism", "Карликовость", "Твой рост уменьшен на четверть."),
    GIGANTISM("gigantism", "Гигантизм", "Ты выше на треть: в двери высотой 2 блока уже не пролезть.");

    public final String id;
    public final String title;
    public final String description;

    Disease(String id, String title, String description) {
        this.id = id;
        this.title = title;
        this.description = description;
    }

    public static Disease byId(String id) {
        for (Disease d : values()) {
            if (d.id.equals(id)) {
                return d;
            }
        }
        return null;
    }
}
