package io.github.qpfr123.rpg.paper;

import io.github.qpfr123.rpg.dungeon.DungeonDefinition;
import io.github.qpfr123.rpg.dungeon.DungeonRegistry;
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
    static final String MARKER = "rpg_layout_version.txt";

    private final Logger log;

    public DungeonTemplates(Logger log) {
        this.log = log;
    }

    public static Path worldFolder(String worldName) {
        return Bukkit.getWorldContainer().toPath().resolve(worldName);
    }

    /**
     * 템플릿 준비. 메인 스레드(플러그인 활성화 중)에서 호출.
     * <ul>
     *   <li>코드 생성 던전: 템플릿이 없거나, 생성기가 만든 이전 버전이면 다시 만든다.</li>
     *   <li>게임 안에서 편집한 템플릿("edited")은 절대 덮어쓰지 않는다.</li>
     *   <li>직접 제작 던전인데 템플릿이 없으면 빈 발판만 있는 템플릿을 만든다.</li>
     * </ul>
     */
    public void ensure(DungeonDefinition def) throws IOException {
        Path folder = worldFolder(def.templateWorldName());
        String marker = readMarker(folder);
        if (Bukkit.getWorld(def.templateWorldName()) != null) return; // 이미 로드돼 있으면 건드리지 않는다
        boolean exists = Files.isDirectory(folder.resolve("region")) || Files.exists(folder.resolve("level.dat"));
        if (exists && "edited".equals(marker)) return;
        if (exists && def.generator() == null) return;
        if (exists && def.generator() != null && ("generated:" + def.layoutVersion()).equals(marker)) return;
        deleteRecursively(folder);
        log.info("building dungeon template " + def.templateWorldName()
                + (def.generator() != null ? " (" + def.generator() + " v" + def.layoutVersion() + ")" : " (blank)"));
        World w = create(def.templateWorldName());
        if (DungeonRegistry.CRYPT_GENERATOR.equals(def.generator())) CryptBuilder.build(w);
        else buildBlankPlatform(w);
        if (def.entrance() != null) {
            w.setSpawnLocation(new Location(w, def.entrance().x(), def.entrance().y(), def.entrance().z(), def.entrance().yaw(), 0));
        }
        w.save();
        if (!Bukkit.unloadWorld(w, true)) throw new IOException("cannot unload template world");
        writeMarker(folder, def.generator() != null ? "generated:" + def.layoutVersion() : "edited");
    }

    public static World create(String name) throws IOException {
        World w = new WorldCreator(name).generator(new VoidGenerator()).generateStructures(false).createWorld();
        if (w == null) throw new IOException("cannot create world " + name);
        applyRules(w);
        return w;
    }

    /** 새 던전용: 원점 주변 9x9 돌 발판. */
    static void buildBlankPlatform(World w) {
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) w.getBlockAt(x, 63, z).setType(Material.STONE_BRICKS, false);
    }

    public static String readMarker(Path folder) throws IOException {
        Path m = folder.resolve(MARKER);
        if (!Files.exists(m)) return null;
        String v = Files.readString(m).trim();
        return v.matches("\\d+") ? "generated:" + v : v; // 예전 형식(숫자만)
    }

    public static void writeMarker(Path folder, String value) throws IOException {
        Files.writeString(folder.resolve(MARKER), value);
    }

    public static void applyRules(World w) {
        w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        w.setTime(18000);
        w.setStorm(false);
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
