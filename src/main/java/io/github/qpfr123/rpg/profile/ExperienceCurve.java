package io.github.qpfr123.rpg.profile;

import io.github.qpfr123.rpg.config.Balance;

/** 레벨 1~10 시험 곡선: 필요 EXP = round(100 × LV^1.5). 최대 레벨 이후 EXP는 쌓지 않는다. */
public final class ExperienceCurve {
    private ExperienceCurve() {}

    public static long required(int level) {
        return Math.round(100 * Math.pow(level, 1.5));
    }

    public record Progress(int level, long exp, int levelsGained) {}

    public static Progress add(int level, long exp, long gained) {
        if (gained < 0) throw new IllegalArgumentException("negative exp");
        int lv = level;
        long cur = exp + gained;
        int ups = 0;
        while (lv < Balance.MAX_LEVEL && cur >= required(lv)) {
            cur -= required(lv);
            lv++;
            ups++;
        }
        if (lv >= Balance.MAX_LEVEL) cur = 0;
        return new Progress(lv, cur, ups);
    }

    public static long applyBonus(long base, double expBonus) {
        return (long) Math.floor(base * (1 + Math.max(0, expBonus)));
    }
}
