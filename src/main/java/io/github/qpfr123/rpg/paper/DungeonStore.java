package io.github.qpfr123.rpg.paper;

import io.github.qpfr123.rpg.dungeon.DungeonDefinition;
import io.github.qpfr123.rpg.dungeon.DungeonDefinition.MobSpawn;
import io.github.qpfr123.rpg.dungeon.DungeonDefinition.Point;
import io.github.qpfr123.rpg.dungeon.DungeonRegistry;
import io.github.qpfr123.rpg.loot.LootTable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 던전 정의 파일(plugins/MinecraftRPG/dungeons/<id>.yml). 기본 던전 파일이 없으면 만들고,
 * 코드 생성 던전의 배치 버전이 올라가면 파일도 새 기본값으로 바꾼다(게임 안에서 고친 던전은 그대로 둔다).
 */
public final class DungeonStore {
    private final File dir;
    private final Logger log;

    public DungeonStore(File dataFolder, Logger log) {
        this.dir = new File(dataFolder, "dungeons");
        this.log = log;
    }

    public DungeonRegistry loadAll() {
        if (!dir.isDirectory() && !dir.mkdirs()) log.warning("cannot create " + dir);
        DungeonRegistry r = new DungeonRegistry();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files != null) {
            for (File f : files) {
                try {
                    r.put(read(f));
                } catch (RuntimeException e) {
                    log.log(Level.SEVERE, "invalid dungeon file " + f.getName() + " (skipped)", e);
                }
            }
        }
        DungeonDefinition builtIn = DungeonRegistry.defaultCrypt();
        DungeonDefinition current = r.get(builtIn.id()).orElse(null);
        if (current == null || (current.generator() != null && current.layoutVersion() < builtIn.layoutVersion())) {
            save(builtIn);
            r.put(builtIn);
            log.info("wrote default dungeon " + builtIn.id() + " (layout v" + builtIn.layoutVersion() + ")");
        }
        return r;
    }

    public File dir() {
        return dir;
    }

    public void save(DungeonDefinition d) {
        write(d, new File(dir, d.id() + ".yml"));
    }

    /** 편집기 저장 1단계: 새 정의를 &lt;id&gt;.yml.new로 써 둔다(아직 적용 전). */
    public void writeStaged(DungeonDefinition d) {
        write(d, stagedFile(dir, d.id()));
    }

    /** 편집기 저장의 확정 지점: &lt;id&gt;.yml.new → &lt;id&gt;.yml 원자적 교체. */
    public void commitStaged(String id) throws IOException {
        java.nio.file.Files.move(stagedFile(dir, id).toPath(), new File(dir, id + ".yml").toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public static File stagedFile(File dir, String id) {
        return new File(dir, id + ".yml.new");
    }

    private void write(DungeonDefinition d, File target) {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("던전 정의. 좌표는 템플릿 월드(rpg_tpl_" + d.id() + ") 기준.",
                "게임 안에서는 /rpgadmin dungeonedit 로 고칠 수 있다."));
        y.set("id", d.id());
        y.set("name", d.displayName());
        y.set("generator", d.generator());
        y.set("layout-version", d.layoutVersion());
        y.set("max-players", d.maxPlayers());
        if (d.entrance() != null) {
            Point e = d.entrance();
            y.set("entrance", map("x", e.x(), "y", e.y(), "z", e.z(), "yaw", (double) e.yaw()));
        }
        List<Map<String, Object>> spawns = new ArrayList<>();
        for (MobSpawn s : d.spawns()) spawns.add(map("mob", s.mobId(), "x", s.x(), "y", s.y(), "z", s.z()));
        y.set("spawns", spawns);
        if (d.boss() != null) y.set("boss", map("mob", d.boss().mobId(), "x", d.boss().x(), "y", d.boss().y(), "z", d.boss().z()));
        if (d.clearReward() != null) {
            y.set("clear-reward.exp", d.clearReward().exp());
            List<Map<String, Object>> items = new ArrayList<>();
            for (LootTable.Entry e : d.clearReward().entries()) items.add(map("id", e.itemId(), "chance", e.baseChance()));
            y.set("clear-reward.items", items);
        }
        try {
            if (!dir.isDirectory()) dir.mkdirs();
            y.save(target);
        } catch (IOException e) {
            throw new IllegalStateException("cannot save dungeon " + d.id(), e);
        }
    }

    private static DungeonDefinition read(File f) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        String id = req(y.getString("id"), "id");
        Point entrance = null;
        ConfigurationSection e = y.getConfigurationSection("entrance");
        if (e != null) entrance = new Point(e.getDouble("x"), e.getDouble("y"), e.getDouble("z"), (float) e.getDouble("yaw"));
        List<MobSpawn> spawns = new ArrayList<>();
        for (Map<?, ?> m : y.getMapList("spawns")) spawns.add(spawn(m));
        MobSpawn boss = null;
        ConfigurationSection b = y.getConfigurationSection("boss");
        if (b != null) boss = new MobSpawn(req(b.getString("mob"), "boss.mob"), b.getDouble("x"), b.getDouble("y"), b.getDouble("z"));
        List<LootTable.Entry> items = new ArrayList<>();
        for (Map<?, ?> m : y.getMapList("clear-reward.items")) {
            items.add(new LootTable.Entry(String.valueOf(m.get("id")), num(m.get("chance"))));
        }
        LootTable reward = new LootTable(y.getLong("clear-reward.exp", 200), items);
        return new DungeonDefinition(id, y.getString("name", id), y.getString("generator"), y.getInt("layout-version", 1),
                y.getInt("max-players", 4), entrance, spawns, boss, reward);
    }

    private static MobSpawn spawn(Map<?, ?> m) {
        return new MobSpawn(String.valueOf(m.get("mob")), num(m.get("x")), num(m.get("y")), num(m.get("z")));
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(o));
    }

    private static String req(String v, String key) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("missing " + key);
        return v;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
