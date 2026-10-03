package io.github.qpfr123.rpg.paper;

import io.github.qpfr123.rpg.dungeon.DungeonDefinition;
import io.github.qpfr123.rpg.dungeon.DungeonDefinition.MobSpawn;
import io.github.qpfr123.rpg.dungeon.DungeonDefinition.Point;
import io.github.qpfr123.rpg.dungeon.DungeonValidator;
import io.github.qpfr123.rpg.loot.LootTable;
import io.github.qpfr123.rpg.mob.MobRegistry;
import io.github.qpfr123.rpg.mob.MobStatProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import io.github.qpfr123.rpg.storage.Database.EditorSession;
import io.github.qpfr123.rpg.storage.DbExecutor;
import java.io.File;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * 게임 안 던전 맵 편집기.
 * <pre>
 * /rpgadmin dungeonedit create &lt;id&gt; &lt;이름&gt;   새 던전(빈 발판)을 만들고 들어간다
 * /rpgadmin dungeonedit edit &lt;id&gt;              기존 던전 맵을 고친다
 *   (편집 월드 안에서) setentrance | addspawn &lt;몹&gt; | setboss &lt;몹&gt; | removespawn | spawns
 *                      name &lt;이름&gt; | maxplayers &lt;n&gt; | save | cancel
 * </pre>
 * 편집은 템플릿의 작업 사본(rpg_edit_&lt;id&gt;)에서 한다. 편집 중에도 플레이어는 기존 템플릿으로 입장할 수 있고,
 * 저장할 때만 검증을 통과하면 사본이 템플릿을 교체한다. 취소하거나 서버가 꺼지면 사본은 버린다.
 * 위치 표시는 저장되지 않는 TextDisplay라 인스턴스에 복사되지 않는다.
 */
public final class DungeonEditor implements Listener {
    public static final String EDIT_PREFIX = "rpg_edit_";
    private static final String OLD_PREFIX = "rpg_old_";
    private static final LootTable DEFAULT_REWARD = new LootTable(200, List.of(new LootTable.Entry("shield_tonic", 1.0)));

    private final DungeonStore store;
    private final DungeonService dungeons;
    private final MobRegistry mobs;
    private final DbExecutor db;
    private final MainThread main;
    private final Logger log;
    private final Map<String, Session> sessions = new LinkedHashMap<>();

    /** 검증용 강제 종료 지점(저장 단계 사이에 서버가 꺼지는 경우). */
    public enum SaveCrash { NONE, AFTER_STAGE, AFTER_TEMPLATE_MOVED, AFTER_SWAP, AFTER_COMMIT }

    private SaveCrash saveCrash = SaveCrash.NONE;
    /** 강제 종료 훅은 E2E 서버(-Dminecraftrpg.testHooks=true)에서만 켠다. 운영 서버에서는 명령이 거절된다. */
    public static final String TEST_HOOKS_PROPERTY = "minecraftrpg.testHooks";
    private static final boolean TEST_HOOKS = Boolean.getBoolean(TEST_HOOKS_PROPERTY);

    private static final class Session {
        DungeonDefinition draft;
        final boolean isNew;
        final World world;
        /** 편집 월드에 들어온 플레이어의 복귀 정보(DB editor_sessions의 메모리 사본). */
        final Map<UUID, EditorSession> origins = new HashMap<>();
        final List<UUID> markers = new ArrayList<>();

        Session(DungeonDefinition draft, boolean isNew, World world) {
            this.draft = draft;
            this.isNew = isNew;
            this.world = world;
        }
    }

    public DungeonEditor(DungeonStore store, DungeonService dungeons, MobRegistry mobs, DbExecutor db, MainThread main, Logger log) {
        this.db = db;
        this.main = main;
        this.store = store;
        this.dungeons = dungeons;
        this.mobs = mobs;
        this.log = log;
    }

    public static String editWorldName(String id) {
        return EDIT_PREFIX + id;
    }

    /** 서버 시작 시(템플릿 준비 전, 정의 로드 후): 지난 실행에서 남은 편집 사본·이전 템플릿을 지운다. */
    public void cleanupLeftovers() {
        try (Stream<Path> s = Files.list(Bukkit.getWorldContainer().toPath())) {
            for (Path p : s.toList()) {
                String n = p.getFileName().toString();
                if (Bukkit.getWorld(n) != null) continue;
                if (n.startsWith(EDIT_PREFIX) || n.startsWith(OLD_PREFIX)) {
                    DungeonTemplates.deleteRecursively(p);
                    log.info("removed leftover dungeon edit folder " + n);
                }
            }
        } catch (IOException e) {
            log.log(Level.SEVERE, "cannot clean leftover edit folders", e);
        }
    }

    // ---------------- 저장 저널 ----------------
    //
    // 저장은 정의(YAML)와 맵(템플릿 폴더) 두 곳을 바꾼다. 둘이 어긋나지 않도록 저널을 쓴다.
    //   1. 저널 <id>.save-journal 작성(이전 템플릿을 옮겨 둘 폴더 이름)
    //   2. 새 정의를 <id>.yml.new로 작성
    //   3. 템플릿 → rpg_old_*, 편집 사본 → 템플릿
    //   4. <id>.yml.new → <id>.yml 원자적 교체  ← 확정 지점
    //   5. 이전 템플릿·저널 삭제
    // 시작 시 저널이 남아 있으면: .yml.new가 있으면(확정 전) 이전 템플릿과 이전 YAML로 되돌리고,
    // 없으면(확정 후) 정리만 마저 한다.

    private static File journalFile(File dir, String id) {
        return new File(dir, id + ".save-journal");
    }

    /** 정의를 불러오기 전에 호출한다. */
    public static void recoverInterruptedSaves(File dir, Logger log) {
        File[] journals = dir.listFiles((d, n) -> n.endsWith(".save-journal"));
        if (journals == null) return;
        for (File j : journals) {
            String id = j.getName().substring(0, j.getName().length() - ".save-journal".length());
            try {
                String oldName = Files.readString(j.toPath()).trim();
                Path old = oldName.isEmpty() ? null : DungeonTemplates.worldFolder(oldName);
                Path template = DungeonTemplates.worldFolder("rpg_tpl_" + id);
                File staged = DungeonStore.stagedFile(dir, id);
                if (staged.exists()) {
                    // 확정 전: 맵과 정의를 모두 이전 상태로
                    if (old == null) {
                        DungeonTemplates.deleteRecursively(template); // 새 던전이었다: 사본이 들어왔으면 치운다
                    } else if (Files.exists(old)) {
                        DungeonTemplates.deleteRecursively(template);
                        Files.move(old, template);
                    } // old가 없으면 템플릿은 아직 옮기기 전(원래 것)이다
                    Files.delete(staged.toPath());
                    log.severe("dungeon save of " + id + " was interrupted before commit: rolled back map and definition");
                } else {
                    if (old != null) DungeonTemplates.deleteRecursively(old);
                    log.warning("dungeon save of " + id + " was interrupted after commit: finished cleanup");
                }
                Files.delete(j.toPath());
            } catch (IOException e) {
                log.log(Level.SEVERE, "cannot recover interrupted save of dungeon " + id, e);
            }
        }
    }

    private void haltIf(SaveCrash point, String id) {
        if (saveCrash != point) return;
        log.severe("[crash-test] halting dungeon save of " + id + " at " + point);
        Runtime.getRuntime().halt(137);
    }

    public boolean isEditing(String dungeonId) {
        return sessions.containsKey(dungeonId);
    }

    public void handle(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("플레이어만 사용할 수 있습니다.");
            return;
        }
        String op = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        String[] rest = args.length > 2 ? Arrays.copyOfRange(args, 2, args.length) : new String[0];
        switch (op) {
            case "create" -> create(p, rest);
            case "edit" -> edit(p, rest);
            case "list" -> list(p);
            case "crash" -> { // 검증용: 다음 저장의 해당 단계에서 강제 종료. JVM 옵션으로 켠 테스트 서버에서만 동작
                if (!TEST_HOOKS) {
                    p.sendMessage(Component.text("테스트 훅이 꺼져 있습니다(-D" + TEST_HOOKS_PROPERTY + "=true 로 실행한 서버에서만 사용).", NamedTextColor.RED));
                    return;
                }
                String point = rest.length == 0 ? "" : rest[0].toUpperCase(Locale.ROOT).replace('-', '_');
                try {
                    saveCrash = SaveCrash.valueOf(point);
                    p.sendMessage(Component.text("[테스트] 다음 던전 저장에서 강제 종료: " + saveCrash, NamedTextColor.RED));
                } catch (IllegalArgumentException e) {
                    p.sendMessage("/rpgadmin dungeonedit crash <none|after-stage|after-template-moved|after-swap|after-commit>");
                }
            }
            default -> {
                Session s = sessionOf(p).orElse(null);
                if (s == null) {
                    usage(p);
                    return;
                }
                inSession(p, s, op, rest);
            }
        }
    }

    public List<String> tabComplete(String[] args) {
        if (args.length == 2) {
            return RpgCommand.filter(List.of("create", "edit", "list", "setentrance", "addspawn", "setboss", "removespawn",
                    "spawns", "name", "maxplayers", "save", "cancel"), args[1]);
        }
        if (args.length == 3) {
            String op = args[1].toLowerCase(Locale.ROOT);
            List<String> ids = new ArrayList<>();
            if (op.equals("edit")) dungeons.registry().all().forEach(d -> ids.add(d.id()));
            if (op.equals("addspawn") || op.equals("setboss")) {
                boolean boss = op.equals("setboss");
                for (MobStatProfile m : mobs.all()) if (m.boss() == boss) ids.add(m.id());
            }
            return RpgCommand.filter(ids, args[2]);
        }
        return List.of();
    }

    private static void usage(Player p) {
        p.sendMessage(Component.text("/rpgadmin dungeonedit create <id> <이름> | edit <id> | list", NamedTextColor.YELLOW));
        p.sendMessage(Component.text("편집 월드 안에서: setentrance | addspawn <몹> | setboss <몹> | removespawn | spawns | name <이름> | maxplayers <n> | save | cancel", NamedTextColor.YELLOW));
    }

    private Optional<Session> sessionOf(Player p) {
        String w = p.getWorld().getName();
        if (!w.startsWith(EDIT_PREFIX)) return Optional.empty();
        return Optional.ofNullable(sessions.get(w.substring(EDIT_PREFIX.length())));
    }

    // ---------------- 시작 ----------------

    private void create(Player p, String[] rest) {
        if (rest.length < 2) {
            p.sendMessage(Component.text("/rpgadmin dungeonedit create <id> <이름>", NamedTextColor.RED));
            return;
        }
        String id = rest[0].toLowerCase(Locale.ROOT);
        String name = String.join(" ", Arrays.copyOfRange(rest, 1, rest.length));
        if (!DungeonValidator.ID.matcher(id).matches()) {
            p.sendMessage(Component.text("ID는 영소문자·숫자·_ 2~24자여야 합니다.", NamedTextColor.RED));
            return;
        }
        if (dungeons.registry().get(id).isPresent() || sessions.containsKey(id)) {
            p.sendMessage(Component.text("이미 있는 던전입니다. 고치려면 /rpgadmin dungeonedit edit " + id, NamedTextColor.RED));
            return;
        }
        if (!canStartEditing(p)) return;
        DungeonDefinition draft = new DungeonDefinition(id, name, null, 1, 4, null, List.of(), null, DEFAULT_REWARD);
        World w;
        try {
            DungeonTemplates.deleteRecursively(DungeonTemplates.worldFolder(editWorldName(id)));
            w = DungeonTemplates.create(editWorldName(id));
        } catch (IOException e) {
            log.log(Level.SEVERE, "cannot create edit world for " + id, e);
            p.sendMessage(Component.text("편집 월드를 만들지 못했습니다.", NamedTextColor.RED));
            return;
        }
        w.setAutoSave(false);
        DungeonTemplates.buildBlankPlatform(w);
        Session s = new Session(draft, true, w);
        sessions.put(id, s);
        log.info(p.getName() + " started creating dungeon " + id);
        join(p, s, "새 던전 '" + name + "' 편집을 시작했습니다. 블록으로 맵을 짓고 setentrance·addspawn·setboss로 위치를 찍은 뒤 save 하세요.");
    }

    private void edit(Player p, String[] rest) {
        if (rest.length < 1) {
            p.sendMessage(Component.text("/rpgadmin dungeonedit edit <id>", NamedTextColor.RED));
            return;
        }
        String id = rest[0].toLowerCase(Locale.ROOT);
        Session existing = sessions.get(id);
        if (existing != null) {
            if (!canStartEditing(p)) return;
            join(p, existing, "진행 중인 편집에 합류했습니다.");
            return;
        }
        DungeonDefinition def = dungeons.registry().get(id).orElse(null);
        if (def == null) {
            p.sendMessage(Component.text("알 수 없는 던전: " + id, NamedTextColor.RED));
            return;
        }
        if (!canStartEditing(p)) return;
        Path template = DungeonTemplates.worldFolder(def.templateWorldName());
        Path copy = DungeonTemplates.worldFolder(editWorldName(id));
        World w;
        try {
            if (!Files.isDirectory(template)) throw new IOException("template missing: " + template);
            DungeonTemplates.deleteRecursively(copy);
            DungeonTemplates.copyTemplate(template, copy); // 템플릿은 언로드 상태라 그대로 복사해도 안전
            w = DungeonTemplates.create(editWorldName(id));
        } catch (IOException e) {
            log.log(Level.SEVERE, "cannot open edit copy for " + id, e);
            p.sendMessage(Component.text("편집 사본을 열지 못했습니다.", NamedTextColor.RED));
            return;
        }
        w.setAutoSave(false);
        Session s = new Session(def, false, w);
        sessions.put(id, s);
        log.info(p.getName() + " started editing dungeon " + id);
        join(p, s, "'" + def.displayName() + "' 편집을 시작했습니다. 저장 전까지 플레이어는 기존 맵으로 입장합니다.");
    }

    private boolean canStartEditing(Player p) {
        String w = p.getWorld().getName();
        if (w.startsWith(DungeonService.INSTANCE_PREFIX) || w.startsWith(EDIT_PREFIX)) {
            p.sendMessage(Component.text("던전이나 다른 편집 월드 밖에서 시작하세요.", NamedTextColor.RED));
            return false;
        }
        return true;
    }

    /**
     * 편집 월드로 들어간다. 원래 위치·게임 모드를 먼저 DB에 기록하고(편집 중 로그아웃·강제 종료 대비),
     * 기록이 끝난 뒤에 이동·크리에이티브 전환을 한다.
     */
    private void join(Player p, Session s, String message) {
        Location l = p.getLocation();
        EditorSession row = new EditorSession(p.getUniqueId(), s.draft.id(), l.getWorld().getName(), l.getX(), l.getY(), l.getZ(),
                l.getYaw(), l.getPitch(), p.getGameMode().name());
        String id = s.draft.id();
        db.submit("editor session", d -> {
            d.saveEditorSession(row);
            return null;
        }).whenComplete((v, error) -> main.nextTick(() -> {
            boolean alive = sessions.get(id) == s;
            if (error != null || !alive || !p.isOnline() || !canStartEditing(p)) {
                if (error != null) log.log(Level.SEVERE, "cannot record editor session for " + p.getName(), error);
                if (p.isOnline()) p.sendMessage(Component.text("편집 월드에 들어가지 못했습니다.", NamedTextColor.RED));
                if (error == null) deleteRow(p.getUniqueId());
                if (alive && s.origins.isEmpty() && s.world.getPlayers().isEmpty()) cancel(s, "편집을 시작하지 못했습니다.");
                return;
            }
            s.origins.put(p.getUniqueId(), row);
            Point e = s.draft.entrance();
            Location to = e != null ? new Location(s.world, e.x(), e.y(), e.z(), e.yaw(), 0) : new Location(s.world, 0.5, 64, 0.5);
            p.teleport(to);
            p.setGameMode(GameMode.CREATIVE);
            refreshMarkers(s);
            p.sendMessage(Component.text(message, NamedTextColor.GOLD));
        }));
    }

    private void deleteRow(UUID player) {
        db.submit("delete editor session", d -> {
            d.deleteEditorSession(player);
            return null;
        });
    }

    /** 복귀 순서: 이동 → 게임 모드 복원 → 플레이어 데이터 저장 → 기록 삭제(dungeon 귀환과 같은 원칙). */
    private void restore(Player p, EditorSession row) {
        World w = Bukkit.getWorld(row.returnWorld());
        Location to = w == null || w.getName().startsWith(EDIT_PREFIX) || w.getName().startsWith(DungeonService.INSTANCE_PREFIX)
                ? Bukkit.getWorlds().getFirst().getSpawnLocation()
                : new Location(w, row.x(), row.y(), row.z(), row.yaw(), row.pitch());
        if (!p.teleport(to)) {
            log.warning("cannot return editor " + p.getName() + ", keeping editor session");
            return;
        }
        try {
            p.setGameMode(GameMode.valueOf(row.gameMode()));
        } catch (IllegalArgumentException e) {
            p.setGameMode(GameMode.SURVIVAL);
        }
        p.saveData();
        deleteRow(p.getUniqueId());
    }

    /** 접속 시: 편집 기록이 남아 있으면 진행 중인 편집으로 돌아온 경우를 빼고 원래 위치·모드로 되돌린다. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        main.then(db.submit("load editor session", d -> d.loadEditorSession(p.getUniqueId())), row -> {
            if (row.isEmpty() || !p.isOnline()) return;
            Session s = sessions.get(row.get().dungeonId());
            if (s != null && p.getWorld().equals(s.world)) {
                s.origins.put(p.getUniqueId(), row.get());
                p.sendMessage(Component.text("진행 중인 던전 편집으로 돌아왔습니다.", NamedTextColor.GOLD));
                return;
            }
            log.info("restoring " + p.getName() + " from dungeon edit " + row.get().dungeonId());
            restore(p, row.get());
            p.sendMessage(Component.text("던전 편집이 끝나 원래 위치로 돌아왔습니다.", NamedTextColor.GRAY));
        });
    }

    private void list(Player p) {
        if (sessions.isEmpty()) {
            p.sendMessage(Component.text("편집 중인 던전이 없습니다.", NamedTextColor.GRAY));
            return;
        }
        for (Map.Entry<String, Session> e : sessions.entrySet()) {
            p.sendMessage(Component.text("편집 중: " + e.getKey() + " (" + e.getValue().world.getPlayers().size() + "명 안에 있음)", NamedTextColor.GRAY));
        }
    }

    // ---------------- 편집 ----------------

    private void inSession(Player p, Session s, String op, String[] rest) {
        Location l = p.getLocation();
        switch (op) {
            case "setentrance" -> {
                s.draft = s.draft.withEntrance(new Point(l.getX(), l.getY(), l.getZ(), l.getYaw()));
                p.sendMessage(Component.text("입구 위치를 정했습니다: " + fmt(l), NamedTextColor.GREEN));
            }
            case "addspawn" -> {
                MobStatProfile m = mob(p, rest, false);
                if (m == null) return;
                s.draft = s.draft.plusSpawn(new MobSpawn(m.id(), l.getX(), l.getY(), l.getZ()));
                p.sendMessage(Component.text(m.displayName() + " 스폰 추가 (" + s.draft.spawns().size() + "개): " + fmt(l), NamedTextColor.GREEN));
            }
            case "setboss" -> {
                MobStatProfile m = mob(p, rest, true);
                if (m == null) return;
                s.draft = s.draft.withBoss(new MobSpawn(m.id(), l.getX(), l.getY(), l.getZ()));
                p.sendMessage(Component.text("보스 " + m.displayName() + " 위치를 정했습니다: " + fmt(l), NamedTextColor.GREEN));
            }
            case "removespawn" -> {
                DungeonDefinition next = s.draft.minusNearestSpawn(l.getX(), l.getY(), l.getZ(), 3.0);
                if (next == null) {
                    p.sendMessage(Component.text("3칸 안에 스폰 지점이 없습니다.", NamedTextColor.RED));
                    return;
                }
                s.draft = next;
                p.sendMessage(Component.text("가장 가까운 스폰을 지웠습니다 (" + s.draft.spawns().size() + "개 남음).", NamedTextColor.GREEN));
            }
            case "spawns" -> {
                DungeonDefinition d = s.draft;
                p.sendMessage(Component.text(d.id() + " '" + d.displayName() + "' 최대 " + d.maxPlayers() + "명", NamedTextColor.GOLD));
                p.sendMessage(Component.text("입구: " + (d.entrance() == null ? "없음" : fmt(d.entrance().x(), d.entrance().y(), d.entrance().z())), NamedTextColor.GRAY));
                for (MobSpawn m : d.spawns()) p.sendMessage(Component.text(" - " + m.mobId() + " " + fmt(m.x(), m.y(), m.z()), NamedTextColor.GRAY));
                p.sendMessage(Component.text("보스: " + (d.boss() == null ? "없음" : d.boss().mobId() + " " + fmt(d.boss().x(), d.boss().y(), d.boss().z())), NamedTextColor.GRAY));
                return;
            }
            case "name" -> {
                if (rest.length == 0) {
                    p.sendMessage(Component.text("/rpgadmin dungeonedit name <이름>", NamedTextColor.RED));
                    return;
                }
                s.draft = s.draft.withDisplayName(String.join(" ", rest));
                p.sendMessage(Component.text("이름: " + s.draft.displayName(), NamedTextColor.GREEN));
            }
            case "maxplayers" -> {
                int n;
                try {
                    n = Integer.parseInt(rest.length == 0 ? "" : rest[0]);
                } catch (NumberFormatException e) {
                    n = -1;
                }
                if (n < 1 || n > 4) {
                    p.sendMessage(Component.text("최대 인원은 1~4명입니다.", NamedTextColor.RED));
                    return;
                }
                s.draft = s.draft.withMaxPlayers(n);
                p.sendMessage(Component.text("최대 인원: " + n, NamedTextColor.GREEN));
            }
            case "save" -> {
                save(p, s);
                return;
            }
            case "cancel" -> {
                cancel(s, "편집을 취소했습니다.");
                return;
            }
            default -> {
                usage(p);
                return;
            }
        }
        refreshMarkers(s);
    }

    private MobStatProfile mob(Player p, String[] rest, boolean boss) {
        MobStatProfile m = rest.length == 0 ? null : mobs.get(rest[0].toLowerCase(Locale.ROOT)).orElse(null);
        if (m == null || m.boss() != boss) {
            List<String> ids = new ArrayList<>();
            for (MobStatProfile x : mobs.all()) if (x.boss() == boss) ids.add(x.id());
            p.sendMessage(Component.text((boss ? "보스 몹: " : "일반 몹: ") + String.join(", ", ids), NamedTextColor.RED));
            return null;
        }
        return m;
    }

    // ---------------- 저장·취소 ----------------

    private void save(Player p, Session s) {
        DungeonDefinition def = s.draft.generator() != null ? s.draft.asHandBuilt() : s.draft;
        List<String> errors = DungeonValidator.validate(def, mobs);
        if (!errors.isEmpty()) {
            p.sendMessage(Component.text("저장할 수 없습니다:", NamedTextColor.RED));
            for (String e : errors) p.sendMessage(Component.text(" - " + e, NamedTextColor.RED));
            return;
        }
        if (dungeons.hasCreatingInstance(def.id())) {
            // 인스턴스 준비 중에는 다른 스레드가 템플릿 폴더를 복사하고 있다
            p.sendMessage(Component.text("이 던전의 인스턴스를 준비하는 중입니다. 잠시 후 다시 save 하세요.", NamedTextColor.RED));
            return;
        }
        String id = def.id();
        removeMarkers(s);
        List<Player> editors = new ArrayList<>(s.world.getPlayers());
        for (Player e : editors) e.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation()); // 월드를 내리려면 비워야 한다
        s.world.save();
        if (!Bukkit.unloadWorld(s.world, true)) {
            p.sendMessage(Component.text("편집 월드를 내리지 못해 저장하지 않았습니다.", NamedTextColor.RED));
            for (Player e : editors) {
                EditorSession row = s.origins.get(e.getUniqueId());
                if (row != null) restore(e, row);
            }
            cancel(s, "편집 월드를 내리지 못해 편집을 버렸습니다.");
            return;
        }
        sessions.remove(id);
        for (Player e : editors) {
            EditorSession row = s.origins.get(e.getUniqueId());
            if (row != null) restore(e, row);
        }
        Path copy = DungeonTemplates.worldFolder(editWorldName(id));
        Path template = DungeonTemplates.worldFolder(def.templateWorldName());
        String oldName = Files.exists(template) ? OLD_PREFIX + id + "_" + System.currentTimeMillis() : "";
        try {
            Files.deleteIfExists(copy.resolve("uid.dat"));
            Files.deleteIfExists(copy.resolve("session.lock"));
            DungeonTemplates.writeMarker(copy, "edited");
            Files.writeString(journalFile(store.dir(), id).toPath(), oldName);
            store.writeStaged(def);
            haltIf(SaveCrash.AFTER_STAGE, id);
            if (!oldName.isEmpty()) Files.move(template, DungeonTemplates.worldFolder(oldName));
            haltIf(SaveCrash.AFTER_TEMPLATE_MOVED, id);
            Files.move(copy, template);
            haltIf(SaveCrash.AFTER_SWAP, id);
            store.commitStaged(id); // 확정
            haltIf(SaveCrash.AFTER_COMMIT, id);
            if (!oldName.isEmpty()) DungeonTemplates.deleteRecursively(DungeonTemplates.worldFolder(oldName));
            Files.delete(journalFile(store.dir(), id).toPath());
        } catch (IOException | RuntimeException e) {
            log.log(Level.SEVERE, "cannot save dungeon " + id + ", rolling back", e);
            recoverInterruptedSaves(store.dir(), log);
            try {
                DungeonTemplates.deleteRecursively(copy);
            } catch (IOException ignored) {
                // 다음 시작 때 정리된다
            }
            p.sendMessage(Component.text("저장 중 오류가 나 이전 상태로 되돌렸습니다. 서버 로그를 확인하세요.", NamedTextColor.RED));
            return;
        }
        dungeons.registry().put(def);
        log.info(p.getName() + " saved dungeon " + id + " (" + def.spawns().size() + " spawns, boss " + def.boss().mobId() + ")");
        p.sendMessage(Component.text("던전 '" + def.displayName() + "'을 저장했습니다. /dungeon enter " + id, NamedTextColor.GOLD));
    }

    private void cancel(Session s, String message) {
        String id = s.draft.id();
        removeMarkers(s);
        for (Player p : s.world.getPlayers()) p.sendMessage(Component.text(message, NamedTextColor.GRAY));
        returnEditors(s);
        Bukkit.unloadWorld(s.world, false);
        sessions.remove(id);
        try {
            DungeonTemplates.deleteRecursively(DungeonTemplates.worldFolder(editWorldName(id)));
        } catch (IOException e) {
            log.log(Level.WARNING, "cannot delete edit copy " + id, e);
        }
        log.info("dungeon edit " + id + " discarded");
    }

    /** 플러그인 종료: 저장하지 않은 편집은 버린다. */
    public void shutdown() {
        for (Session s : new ArrayList<>(sessions.values())) {
            log.warning("discarding unsaved dungeon edit " + s.draft.id() + " on shutdown");
            cancel(s, "서버 종료로 저장하지 않은 편집을 버렸습니다.");
        }
    }

    /** 편집 월드 안의 플레이어를 되돌린다. 오프라인인 편집자는 기록이 남아 다음 접속 때 되돌린다. */
    private void returnEditors(Session s) {
        for (Player p : new ArrayList<>(s.world.getPlayers())) {
            EditorSession row = s.origins.get(p.getUniqueId());
            if (row != null) restore(p, row);
            else p.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation());
        }
    }

    // ---------------- 위치 표시 ----------------

    private void refreshMarkers(Session s) {
        removeMarkers(s);
        DungeonDefinition d = s.draft;
        if (d.entrance() != null) marker(s, d.entrance().x(), d.entrance().y(), d.entrance().z(), "입구", NamedTextColor.GREEN);
        for (MobSpawn m : d.spawns()) marker(s, m.x(), m.y(), m.z(), name(m.mobId()), NamedTextColor.RED);
        if (d.boss() != null) marker(s, d.boss().x(), d.boss().y(), d.boss().z(), "보스: " + name(d.boss().mobId()), NamedTextColor.DARK_PURPLE);
    }

    private String name(String mobId) {
        return mobs.get(mobId).map(MobStatProfile::displayName).orElse(mobId);
    }

    private void marker(Session s, double x, double y, double z, String text, NamedTextColor color) {
        TextDisplay t = s.world.spawn(new Location(s.world, x, y + 1.2, z), TextDisplay.class, d -> {
            d.setPersistent(false);
            d.text(Component.text(text, color));
            d.setBillboard(Display.Billboard.CENTER);
            d.setSeeThrough(true);
            d.addScoreboardTag("rpg_edit_marker");
        });
        s.markers.add(t.getUniqueId());
    }

    private void removeMarkers(Session s) {
        for (UUID id : s.markers) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        s.markers.clear();
        for (Entity e : s.world.getEntitiesByClass(TextDisplay.class)) {
            if (e.getScoreboardTags().contains("rpg_edit_marker")) e.remove();
        }
    }

    private static String fmt(Location l) {
        return fmt(l.getX(), l.getY(), l.getZ());
    }

    private static String fmt(double x, double y, double z) {
        return String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", x, y, z);
    }
}
