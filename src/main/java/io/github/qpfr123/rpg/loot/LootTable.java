package io.github.qpfr123.rpg.loot;

import java.util.List;

/**
 * 몹 처치 보상표. 항목마다 독립 판정한다. p = min(1, 기본 확률 × (1 + 드롭 보너스)).
 * itemId는 장비 ID 또는 소모품 ID("shield_tonic").
 */
public record LootTable(long exp, List<Entry> entries) {
    public record Entry(String itemId, double baseChance) {}

    public LootTable {
        entries = List.copyOf(entries);
    }
}
