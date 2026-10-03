package io.github.qpfr123.rpg.loot;

import io.github.qpfr123.rpg.stat.StatBonuses;

/** 고정 옵션 장비. material은 Bukkit Material 이름(순수 로직에서 Paper 의존을 피하려고 문자열). */
public record GearDefinition(String id, String displayName, String material, GearSlot slot, StatBonuses bonuses, String loreLine) {}
