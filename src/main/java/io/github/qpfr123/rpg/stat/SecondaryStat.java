package io.github.qpfr123.rpg.stat;

import java.util.Locale;
import java.util.Optional;

/** 플레이어가 수동 배분하는 2차 스탯 7종. */
public enum SecondaryStat {
    STRENGTH("근력"),
    AGILITY("민첩"),
    RESISTANCE("저항"),
    VITALITY("건강"),
    FOCUS("집중"),
    LUCK("행운"),
    SPIRIT("정신");

    private final String displayName;

    SecondaryStat(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static Optional<SecondaryStat> parse(String input) {
        for (SecondaryStat stat : values()) {
            if (stat.name().equalsIgnoreCase(input) || stat.displayName.equals(input)) {
                return Optional.of(stat);
            }
        }
        try {
            return Optional.of(valueOf(input.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
