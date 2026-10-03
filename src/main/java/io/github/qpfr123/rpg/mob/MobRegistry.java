package io.github.qpfr123.rpg.mob;

import io.github.qpfr123.rpg.loot.LootTable;
import io.github.qpfr123.rpg.loot.LootTable.Entry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 몹 4종(시험값). crypt_warden은 던전 보스. 자연 스폰 좀비·스켈레톤은 RPG 몹으로 변환한다. */
public final class MobRegistry {
    private final Map<String, MobStatProfile> mobs = new LinkedHashMap<>();

    public static MobRegistry slice1() {
        MobRegistry r = new MobRegistry();
        r.add(new MobStatProfile("ghoul", "구울", "ZOMBIE", 60, 8, 0.05, false,
                new LootTable(30, List.of(new Entry("ghoul_blade", 0.08), new Entry("ghoul_hide_vest", 0.08),
                        new Entry("shield_tonic", 0.15)))));
        r.add(new MobStatProfile("bone_archer", "해골 궁수", "SKELETON", 45, 10, 0.0, false,
                new LootTable(35, List.of(new Entry("bone_bow", 0.10), new Entry("shield_tonic", 0.15)))));
        r.add(new MobStatProfile("grave_knight", "묘지 기사", "WITHER_SKELETON", 2000, 25, 0.20, true,
                new LootTable(600, List.of(new Entry("grave_knight_helm", 0.50), new Entry("ghoul_blade", 0.30),
                        new Entry("shield_tonic", 1.0)))));
        r.add(new MobStatProfile("crypt_warden", "납골당 수호자", "WITHER_SKELETON", 1200, 20, 0.15, true,
                new LootTable(300, List.of(new Entry("grave_knight_helm", 0.30), new Entry("shield_tonic", 1.0)))));
        return r;
    }

    private void add(MobStatProfile p) {
        mobs.put(p.id(), p);
    }

    public Optional<MobStatProfile> get(String id) {
        return Optional.ofNullable(mobs.get(id));
    }

    public Optional<MobStatProfile> naturalConversion(String entityType) {
        return switch (entityType) {
            case "ZOMBIE" -> get("ghoul");
            case "SKELETON" -> get("bone_archer");
            default -> Optional.empty();
        };
    }

    public Iterable<MobStatProfile> all() {
        return mobs.values();
    }
}
