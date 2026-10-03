package io.github.qpfr123.rpg.loot;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

/** 아이템 증폭은 슬라이스 1 범위 밖이라 적용하지 않는다(증폭 스탯 0). */
public final class LootRoller {
    private final DoubleSupplier random;

    public LootRoller(DoubleSupplier random) {
        this.random = random;
    }

    public static double effectiveChance(double base, double dropBonus) {
        return Math.min(1.0, Math.max(0, base) * (1 + Math.max(0, dropBonus)));
    }

    public List<String> roll(LootTable table, double dropBonus) {
        List<String> out = new ArrayList<>();
        for (LootTable.Entry e : table.entries()) {
            if (random.getAsDouble() < effectiveChance(e.baseChance(), dropBonus)) out.add(e.itemId());
        }
        return out;
    }
}
