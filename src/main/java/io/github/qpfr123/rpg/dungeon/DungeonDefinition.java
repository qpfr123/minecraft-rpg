package io.github.qpfr123.rpg.dungeon;

import io.github.qpfr123.rpg.loot.LootTable;

import java.util.ArrayList;
import java.util.List;

/**
 * 던전 정의. 좌표는 템플릿 월드 기준이다.
 * <ul>
 *   <li>generator가 있으면 코드 생성기가 템플릿을 만든다. layoutVersion이 바뀌면 다시 만든다(편집된 템플릿은 건드리지 않음).</li>
 *   <li>generator가 null이면 관리자가 게임 안에서 직접 지은 맵이다.</li>
 *   <li>boss는 편집 중에는 비어 있을 수 있지만, 입장하려면 있어야 한다({@link DungeonValidator}).</li>
 * </ul>
 */
public record DungeonDefinition(
        String id,
        String displayName,
        String generator,
        int layoutVersion,
        int maxPlayers,
        Point entrance,
        List<MobSpawn> spawns,
        MobSpawn boss,
        LootTable clearReward) {

    public record Point(double x, double y, double z, float yaw) {}

    public record MobSpawn(String mobId, double x, double y, double z) {
        public double distanceSquared(double px, double py, double pz) {
            double dx = x - px, dy = y - py, dz = z - pz;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    public DungeonDefinition {
        spawns = List.copyOf(spawns);
    }

    public String templateWorldName() {
        return "rpg_tpl_" + id;
    }

    public DungeonDefinition withEntrance(Point p) {
        return new DungeonDefinition(id, displayName, generator, layoutVersion, maxPlayers, p, spawns, boss, clearReward);
    }

    public DungeonDefinition withSpawns(List<MobSpawn> s) {
        return new DungeonDefinition(id, displayName, generator, layoutVersion, maxPlayers, entrance, s, boss, clearReward);
    }

    public DungeonDefinition plusSpawn(MobSpawn s) {
        List<MobSpawn> list = new ArrayList<>(spawns);
        list.add(s);
        return withSpawns(list);
    }

    public DungeonDefinition withBoss(MobSpawn b) {
        return new DungeonDefinition(id, displayName, generator, layoutVersion, maxPlayers, entrance, spawns, b, clearReward);
    }

    public DungeonDefinition withDisplayName(String name) {
        return new DungeonDefinition(id, name, generator, layoutVersion, maxPlayers, entrance, spawns, boss, clearReward);
    }

    public DungeonDefinition withMaxPlayers(int n) {
        return new DungeonDefinition(id, displayName, generator, layoutVersion, n, entrance, spawns, boss, clearReward);
    }

    /** 게임 안에서 직접 고친 맵은 코드 생성기와 분리한다(다시 생성되지 않게). */
    public DungeonDefinition asHandBuilt() {
        return new DungeonDefinition(id, displayName, null, layoutVersion, maxPlayers, entrance, spawns, boss, clearReward);
    }

    /** 위치에서 가장 가까운 일반 스폰을 반경 안에서 지운다. @return 지웠으면 새 정의, 아니면 null */
    public DungeonDefinition minusNearestSpawn(double x, double y, double z, double radius) {
        int best = -1;
        double bestD = radius * radius;
        for (int i = 0; i < spawns.size(); i++) {
            double d = spawns.get(i).distanceSquared(x, y, z);
            if (d <= bestD) {
                bestD = d;
                best = i;
            }
        }
        if (best < 0) return null;
        List<MobSpawn> list = new ArrayList<>(spawns);
        list.remove(best);
        return withSpawns(list);
    }
}
