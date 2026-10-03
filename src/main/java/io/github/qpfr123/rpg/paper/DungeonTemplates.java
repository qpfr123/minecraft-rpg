package io.github.qpfr123.rpg.paper;

import io.github.qpfr123.rpg.dungeon.DungeonDefinition;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * 던전 템플릿 월드 준비. 템플릿은 서버 시작 시 한 번 만들어 저장·언로드하고, 인스턴스는 언로드된(정지된) 템플릿
 * 폴더를 복사해 만든다. 실행 중인 월드 폴더는 복사하지 않는다.
 */
public final class DungeonTemplates {
    private static final String MARKER = "rpg_layout_version.txt";

    private final Logger log;

    public DungeonTemplates(Logger log) {
        this.log = log;
    }

    public static Path worldFolder(String worldName) {
        return Bukkit.getWorldContainer().toPath().resolve(worldName);
    }

    /** 템플릿이 없거나 배치 버전이 다르면 다시 만든다. 메인 스레드(플러그인 활성화 중)에서 호출. */
    public void ensure(DungeonDefinition def) throws IOException {
        Path folder = worldFolder(def.templateWorldName());
        Path marker = folder.resolve(MARKER);
        if (Files.exists(marker) && Files.readString(marker).trim().equals(String.valueOf(def.layoutVersion()))
                && Bukkit.getWorld(def.templateWorldName()) == null) {
            return;
        }
        World loaded = Bukkit.getWorld(def.templateWorldName());
        if (loaded != null) Bukkit.unloadWorld(loaded, false);
        deleteRecursively(folder);
        log.info("building dungeon template " + def.templateWorldName() + " (layout v" + def.layoutVersion() + ")");
        World w = new WorldCreator(def.templateWorldName()).generator(new VoidGenerator()).generateStructures(false).createWorld();
        if (w == null) throw new IOException("cannot create template world");
        applyRules(w);
        if ("crypt".equals(def.id())) buildCrypt(w);
        w.setSpawnLocation(new Location(w, def.entrance().x(), def.entrance().y(), def.entrance().z(), def.entrance().yaw(), 0));
        w.save();
        if (!Bukkit.unloadWorld(w, true)) throw new IOException("cannot unload template world");
        Files.writeString(marker, String.valueOf(def.layoutVersion()));
    }

    public static void applyRules(World w) {
        w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        w.setTime(18000);
        w.setStorm(false);
    }

    /** 지하 납골당: 폭 13, 길이 65의 닫힌 회랑. 바닥 y=63, 천장 y=70. */
    private static void buildCrypt(World w) {
        for (int x = -7; x <= 7; x++) {
            for (int z = -3; z <= 63; z++) {
                boolean wall = x == -7 || x == 7 || z == -3 || z == 63;
                w.getBlockAt(x, 63, z).setType(Material.STONE_BRICKS, false);
                w.getBlockAt(x, 70, z).setType((x % 6 == 0 && z % 6 == 0) ? Material.GLOWSTONE : Material.DEEPSLATE_BRICKS, false);
                for (int y = 64; y <= 69; y++) {
                    w.getBlockAt(x, y, z).setType(wall ? Material.DEEPSLATE_BRICKS : Material.AIR, false);
                }
            }
        }
        // 구역 경계 기둥(통로는 가운데)
        for (int z : new int[] {12, 26, 40, 52}) {
            for (int x : new int[] {-6, -5, 5, 6}) {
                for (int y = 64; y <= 69; y++) w.getBlockAt(x, y, z).setType(Material.POLISHED_BLACKSTONE_BRICKS, false);
            }
        }
        for (int x = -2; x <= 2; x++) w.getBlockAt(x, 63, 58).setType(Material.CHISELED_STONE_BRICKS, false); // 보스 단상
    }

    public static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path q : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(q);
        }
    }

    /** 템플릿 폴더 → 인스턴스 폴더 복사(비동기 스레드에서 호출 가능). session.lock·uid.dat는 제외. */
    public static void copyTemplate(Path from, Path to) throws IOException {
        if (Files.exists(to)) throw new IOException("instance folder already exists: " + to);
        try (Stream<Path> s = Files.walk(from)) {
            for (Path src : s.toList()) {
                String name = src.getFileName().toString();
                if (name.equals("session.lock") || name.equals("uid.dat") || name.equals(MARKER)) continue;
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) Files.createDirectories(dst);
                else Files.copy(src, dst);
            }
        }
    }
}
