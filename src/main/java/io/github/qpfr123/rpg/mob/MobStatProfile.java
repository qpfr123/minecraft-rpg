package io.github.qpfr123.rpg.mob;

import io.github.qpfr123.rpg.loot.LootTable;

/** RPG 몹 정의. entityType은 Bukkit EntityType 이름. */
public record MobStatProfile(String id, String displayName, String entityType, int maxHp, double atk, double def,
                             boolean boss, LootTable loot) {}
