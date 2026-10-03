package io.github.qpfr123.rpg.loot;

import io.github.qpfr123.rpg.stat.StatBonuses;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 슬라이스 1 장비 4종(시험값). */
public final class GearRegistry {
    private final Map<String, GearDefinition> gear = new LinkedHashMap<>();

    public static GearRegistry slice1() {
        GearRegistry r = new GearRegistry();
        r.add(new GearDefinition("ghoul_blade", "구울 사냥꾼의 검", "IRON_SWORD", GearSlot.MAIN_HAND,
                StatBonuses.builder().flatAtk(6).build(), "ATK +6"));
        r.add(new GearDefinition("bone_bow", "해골 궁수의 활", "BOW", GearSlot.MAIN_HAND,
                StatBonuses.builder().flatAtk(5).critChance(0.05).build(), "ATK +5, 치명타 확률 +5%"));
        r.add(new GearDefinition("ghoul_hide_vest", "구울 가죽 조끼", "LEATHER_CHESTPLATE", GearSlot.CHEST,
                StatBonuses.builder().flatHp(40).def(0.05).build(), "HP +40, DEF +5%"));
        r.add(new GearDefinition("grave_knight_helm", "묘지 기사의 투구", "IRON_HELMET", GearSlot.HEAD,
                StatBonuses.builder().flatHp(80).def(0.08).lifesteal(0.03).build(), "HP +80, DEF +8%, 흡수 +3%"));
        r.add(new GearDefinition("shield_tonic", "방어막 강장제", "HONEY_BOTTLE", GearSlot.CONSUMABLE,
                StatBonuses.NONE, "우클릭: 최대 HP 10% 방어막 (상한 최대 HP 50%)"));
        return r;
    }

    private void add(GearDefinition g) {
        gear.put(g.id(), g);
    }

    public Optional<GearDefinition> get(String id) {
        return Optional.ofNullable(gear.get(id));
    }

    public Iterable<GearDefinition> all() {
        return gear.values();
    }
}
