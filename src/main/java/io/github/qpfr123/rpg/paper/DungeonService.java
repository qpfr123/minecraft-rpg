package io.github.qpfr123.rpg.paper;

import io.github.qpfr123.rpg.dungeon.DungeonDefinition;
import io.github.qpfr123.rpg.dungeon.DungeonRegistry;
import io.github.qpfr123.rpg.dungeon.DungeonValidator;
import io.github.qpfr123.rpg.dungeon.InstanceTracker;
import io.github.qpfr123.rpg.dungeon.InstanceTracker.Instance;
import io.github.qpfr123.rpg.mob.MobStatProfile;
import io.github.qpfr123.rpg.party.PartyService;
import io.github.qpfr123.rpg.storage.Database.DungeonSession;
import io.github.qpfr123.rpg.storage.DbExecutor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * 던전 인스턴스 수명주기.
 * <pre>
 * 입장: 상한·중복 검사 → 귀환 위치를 DB에 기록 → 템플릿 폴더 복사(비동기) → 월드 로드·몹 배치(메인) → 멤버 텔레포트
 * 진행: 몹·블록·AI·전리품은 인스턴스 월드 안에서만 존재. 보스 처치 시 클리어 보상(멤버별 한 번) 후 귀환 대기
 * 종료: 클리어 후 귀환 지연, 유휴(안에 접속 중인 멤버 없음) 시간 초과, 멤버 전원 퇴장 → 귀환·월드 언로드·폴더 삭제
 * 복구: 서버 시작 시 남은 인스턴스 폴더 삭제. 입장 기록이 남은 플레이어는 접속할 때 원래 위치로 돌려보낸다
 * </pre>
 */
public final class DungeonService implements Listener {
    public static final String INSTANCE_PREFIX = "rpg_inst_";

    private final Plugin plugin;
    private final DungeonRegistry registry;
    private final InstanceTracker tracker;
    private final DbExecutor db;
    private final MobService mobs;
    private final RewardService rewards;
    private final PartyService parties;
    private final MainThread main;
    private final Logger log;
    /** 검증용 강제 종료 지점(귀환 직후 서버가 꺼지는 경우). */
    public enum ReturnCrash { NONE, AFTER_TELEPORT, AFTER_SAVE }

    private volatile ReturnCrash returnCrash = ReturnCrash.NONE;
    /** 검증용: 템플릿 복사를 일부러 늦춘다(준비 중 재접속 재현). */
    private volatile long copyDelayMillis;

    public void armReturnCrash(ReturnCrash point) {
        returnCrash = point;
    }

    public void setCopyDelayMillis(long ms) {
        copyDelayMillis = Math.max(0, ms);
    }

    public long copyDelayMillis() {
        return copyDelayMillis;
    }

    public CompletableFuture<Optional<DungeonSession>> loadSession(UUID player) {
        return db.submit("load session", d -> d.loadDungeonSession(player));
    }

    /**
     * 인스턴스별로 입장 순간의 던전 정의를 고정한다. 진행 중에 편집기로 저장해도
     * 보스 판정·클리어 보상·리스폰 입구는 그 인스턴스를 만든 정의(=복사한 맵)를 따른다.
     */
    private final Map<String, DungeonDefinition> pinned = new HashMap<>();

    private Optional<DungeonDefinition> definitionOf(Instance inst) {
        return Optional.ofNullable(pinned.get(inst.id()));
    }

    private void forgetInstance(String instanceId) {
        tracker.remove(instanceId);
        pinned.remove(instanceId);
    }

    /** 접속 중인 플레이어의 귀환 위치(입장 기록의 메모리 사본). */
    private final Map<UUID, DungeonSession> sessions = new HashMap<>();

    public DungeonService(Plugin plugin, DungeonRegistry registry, InstanceTracker tracker, DbExecutor db, MobService mobs,
                          RewardService rewards, PartyService parties, MainThread main, Logger log) {
        this.plugin = plugin;
        this.registry = registry;
        this.tracker = tracker;
        this.db = db;
        this.mobs = mobs;
        this.rewards = rewards;
        this.parties = parties;
        this.main = main;
        this.log = log;
    }

    public InstanceTracker tracker() {
        return tracker;
    }

    public DungeonRegistry registry() {
        return registry;
    }

    /** 템플릿 폴더를 복사 중인(준비 중) 인스턴스가 있으면 템플릿을 교체하면 안 된다. */
    public boolean hasCreatingInstance(String dungeonId) {
        for (Instance i : tracker.all()) {
            if (i.dungeonId().equals(dungeonId) && i.state() == InstanceTracker.State.CREATING) return true;
        }
        return false;
    }

    public static String worldName(String instanceId) {
        return INSTANCE_PREFIX + instanceId;
    }

    /** 서버 시작 시: 지난 실행에서 남은 인스턴스 폴더를 지운다(인스턴스 월드는 자동 로드되지 않는다). */
    public void cleanupLeftovers() {
        try (Stream<Path> s = Files.list(Bukkit.getWorldContainer().toPath())) {
            for (Path p : s.filter(p -> p.getFileName().toString().startsWith(INSTANCE_PREFIX)).toList()) {
                if (Bukkit.getWorld(p.getFileName().toString()) != null) continue;
                DungeonTemplates.deleteRecursively(p);
                log.info("removed leftover dungeon instance folder " + p.getFileName());
            }
        } catch (IOException e) {
            log.log(Level.SEVERE, "cannot clean leftover instances", e);
        }
    }

    // ---------------- 입장 ----------------

    public void enter(Player leader, String dungeonId) {
        DungeonDefinition def = registry.get(dungeonId).orElse(null);
        if (def == null) {
            leader.sendMessage(Component.text("알 수 없는 던전: " + dungeonId, NamedTextColor.RED));
            return;
        }
        List<String> problems = DungeonValidator.validate(def, mobs.registry());
        if (!problems.isEmpty()) {
            log.warning("dungeon " + def.id() + " is not playable: " + problems);
            leader.sendMessage(Component.text("이 던전은 설정이 완성되지 않아 입장할 수 없습니다.", NamedTextColor.RED));
            return;
        }
        if (!Files.isDirectory(DungeonTemplates.worldFolder(def.templateWorldName()))) {
            leader.sendMessage(Component.text("이 던전의 맵이 준비되지 않았습니다.", NamedTextColor.RED));
            return;
        }
        List<Player> group = new ArrayList<>();
        Optional<PartyService.Party> party = parties.partyOf(leader.getUniqueId());
        if (party.isPresent()) {
            if (!party.get().leader().equals(leader.getUniqueId())) {
                leader.sendMessage(Component.text("파티 리더만 던전에 입장시킬 수 있습니다.", NamedTextColor.RED));
                return;
            }
            for (UUID m : party.get().members()) {
                Player p = Bukkit.getPlayer(m);
                if (p != null) group.add(p);
            }
        } else {
            group.add(leader);
        }
        for (Player p : group) {
            if (isInstanceWorld(p.getWorld())) {
                leader.sendMessage(Component.text(p.getName() + " 님이 이미 던전 안에 있습니다.", NamedTextColor.RED));
                return;
            }
        }
        List<UUID> ids = group.stream().map(Player::getUniqueId).toList();
        String instanceId = UUID.randomUUID().toString().substring(0, 8);
        switch (tracker.canCreate(ids, def.maxPlayers())) {
            case CAP_REACHED -> {
                leader.sendMessage(Component.text("열려 있는 던전이 가득 찼습니다(" + tracker.cap() + "개). 잠시 후 다시 시도하세요.", NamedTextColor.RED));
                return;
            }
            case ALREADY_INSIDE -> {
                leader.sendMessage(Component.text("이미 진행 중인 던전이 있는 멤버가 있습니다.", NamedTextColor.RED));
                return;
            }
            case TOO_MANY_PLAYERS -> {
                leader.sendMessage(Component.text("이 던전은 최대 " + def.maxPlayers() + "명입니다.", NamedTextColor.RED));
                return;
            }
            case OK -> { }
        }
        Instance inst = tracker.create(instanceId, def.id(), ids, def.maxPlayers()).orElseThrow();
        pinned.put(instanceId, def);
        List<DungeonSession> rows = new ArrayList<>();
        for (Player p : group) {
            Location l = p.getLocation();
            rows.add(new DungeonSession(p.getUniqueId(), instanceId, l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()));
            p.sendMessage(Component.text(def.displayName() + " 준비 중...", NamedTextColor.GRAY));
        }
        Path from = DungeonTemplates.worldFolder(def.templateWorldName());
        Path to = DungeonTemplates.worldFolder(worldName(instanceId));
        CompletableFuture<Void> prepared = db.submit("dungeon sessions " + instanceId, d -> {
            d.saveDungeonSessions(rows); // 텔레포트 전에 귀환 위치를 먼저 영속화
            return null;
        }).thenRunAsync(() -> {
            try {
                if (copyDelayMillis > 0) Thread.sleep(copyDelayMillis);
                DungeonTemplates.copyTemplate(from, to);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CompletionException(e);
            } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        prepared.whenComplete((v, error) -> main.nextTick(() -> {
            if (error != null) {
                log.log(Level.SEVERE, "dungeon instance preparation failed: " + instanceId, error);
                abortCreate(inst, rows);
                return;
            }
            World world;
            try {
                world = new WorldCreator(worldName(instanceId)).generator(new VoidGenerator()).generateStructures(false).createWorld();
            } catch (RuntimeException e) {
                log.log(Level.SEVERE, "cannot load instance world " + instanceId, e);
                world = null;
            }
            if (world == null) {
                abortCreate(inst, rows);
                return;
            }
            world.setAutoSave(false);
            DungeonTemplates.applyRules(world);
            spawnMobs(world, def);
            tracker.activate(instanceId, System.currentTimeMillis());
            Location entrance = entrance(world, def);
            for (DungeonSession row : rows) {
                Player p = Bukkit.getPlayer(row.player());
                // 준비 중 나갔으면 건너뛴다(입장 기록은 남아 다음 접속 때 귀환). 준비 중 재접속했어도 기록은 유지돼 있다.
                if (p == null || !inst.isMember(row.player()) || isInstanceWorld(p.getWorld())) continue;
                sessions.put(row.player(), row);
                if (!p.teleport(entrance)) {
                    log.warning("cannot teleport " + p.getName() + " into instance " + instanceId);
                    continue;
                }
                p.sendMessage(Component.text(def.displayName() + "에 입장했습니다. 나가려면 /dungeon leave", NamedTextColor.GOLD));
            }
            log.info("dungeon instance " + instanceId + " (" + def.id() + ") opened for " + rows.size() + " player(s)");
        }));
    }

    private void abortCreate(Instance inst, List<DungeonSession> rows) {
        forgetInstance(inst.id());
        for (DungeonSession row : rows) {
            db.submit("abort session", d -> {
                d.deleteDungeonSession(row.player(), inst.id());
                return null;
            });
            Player p = Bukkit.getPlayer(row.player());
            if (p != null) p.sendMessage(Component.text("던전을 준비하지 못했습니다. 잠시 후 다시 시도하세요.", NamedTextColor.RED));
        }
        try {
            DungeonTemplates.deleteRecursively(DungeonTemplates.worldFolder(worldName(inst.id())));
        } catch (IOException e) {
            log.log(Level.WARNING, "cannot delete aborted instance folder", e);
        }
    }

    private void spawnMobs(World world, DungeonDefinition def) {
        List<DungeonDefinition.MobSpawn> all = new ArrayList<>(def.spawns());
        if (def.boss() != null) all.add(def.boss());
        for (DungeonDefinition.MobSpawn s : all) {
            MobStatProfile profile = mobs.registry().get(s.mobId()).orElse(null);
            if (profile == null) {
                log.severe("unknown mob in dungeon " + def.id() + ": " + s.mobId());
                continue;
            }
            LivingEntity e = mobs.spawn(profile, new Location(world, s.x(), s.y(), s.z()));
            e.setRemoveWhenFarAway(false);
            e.setPersistent(true);
        }
    }

    private static Location entrance(World world, DungeonDefinition def) {
        return new Location(world, def.entrance().x(), def.entrance().y(), def.entrance().z(), def.entrance().yaw(), 0);
    }

    // ---------------- 퇴장·귀환 ----------------

    public void leave(Player player) {
        Optional<Instance> inst = instanceOfWorld(player.getWorld());
        if (inst.isEmpty()) {
            player.sendMessage(Component.text("던전 안에 있지 않습니다.", NamedTextColor.GRAY));
            return;
        }
        tracker.removeMember(inst.get().id(), player.getUniqueId());
        returnPlayer(player, inst.get().id());
    }

    /** 귀환 위치로 보내고 입장 기록을 지운다. 기록이 없으면 기본 월드 스폰. */
    private void returnPlayer(Player player, String instanceId) {
        DungeonSession s = sessions.remove(player.getUniqueId());
        if (s != null) {
            returnAndForget(player, s);
            return;
        }
        main.then(loadSession(player.getUniqueId()), row -> {
            if (!player.isOnline()) return;
            if (row.isPresent()) {
                returnAndForget(player, row.get());
            } else {
                player.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation());
            }
        });
    }

    /**
     * 귀환 순서: 텔레포트 → 플레이어 데이터 저장(귀환 위치 영속화) → 입장 기록 삭제.
     * 텔레포트가 실패하면 기록을 남긴다. 저장 전에 서버가 꺼져도 기록이 남아 다음 접속 때 다시 귀환시킨다.
     */
    private void returnAndForget(Player player, DungeonSession s) {
        if (!teleportBack(player, s)) {
            log.warning("return teleport failed for " + player.getName() + ", keeping dungeon session " + s.instanceId());
            return;
        }
        haltIf(ReturnCrash.AFTER_TELEPORT, player);
        player.saveData();
        haltIf(ReturnCrash.AFTER_SAVE, player);
        deleteSession(player.getUniqueId(), s.instanceId());
    }

    private void haltIf(ReturnCrash point, Player player) {
        if (returnCrash != point) return;
        log.severe("[crash-test] halting at dungeon return " + point + " for " + player.getName());
        Runtime.getRuntime().halt(137);
    }

    private boolean teleportBack(Player player, DungeonSession s) {
        World w = Bukkit.getWorld(s.returnWorld());
        Location to = w == null ? Bukkit.getWorlds().getFirst().getSpawnLocation()
                : new Location(w, s.x(), s.y(), s.z(), s.yaw(), s.pitch());
        boolean ok = player.teleport(to);
        if (ok) player.sendMessage(Component.text("던전에서 나왔습니다.", NamedTextColor.GRAY));
        return ok;
    }

    private void deleteSession(UUID player, String instanceId) {
        db.submit("delete session", d -> {
            d.deleteDungeonSession(player, instanceId);
            return null;
        });
    }

    // ---------------- 주기 점검·종료 ----------------

    /** 1초마다: 인스턴스 안 접속 멤버 수 갱신, 닫을 인스턴스 정리. */
    public void tick() {
        long now = System.currentTimeMillis();
        for (Instance i : tracker.all()) {
            if (i.state() != InstanceTracker.State.ACTIVE && i.state() != InstanceTracker.State.CLEARED) continue;
            World w = Bukkit.getWorld(worldName(i.id()));
            int inside = 0;
            if (w != null) {
                for (Player p : w.getPlayers()) if (i.isMember(p.getUniqueId())) inside++;
            }
            tracker.onPresence(i.id(), inside, now);
        }
        for (Instance i : tracker.dueForClose(now)) close(i);
    }

    private void close(Instance inst) {
        World w = Bukkit.getWorld(worldName(inst.id()));
        if (w != null) {
            for (Player p : new ArrayList<>(w.getPlayers())) returnPlayer(p, inst.id());
            for (Entity e : w.getEntities()) mobs.forget(e.getUniqueId());
            if (!Bukkit.unloadWorld(w, false)) {
                log.warning("instance world still busy, retrying close later: " + inst.id());
                main.nextTick(() -> Bukkit.getScheduler().runTaskLater(plugin, () -> close(inst), 20L));
                return;
            }
        }
        Path folder = DungeonTemplates.worldFolder(worldName(inst.id()));
        CompletableFuture.runAsync(() -> {
            try {
                DungeonTemplates.deleteRecursively(folder);
            } catch (IOException e) {
                log.log(Level.WARNING, "cannot delete instance folder " + folder, e);
            }
        }).whenComplete((v, e) -> main.nextTick(() -> forgetInstance(inst.id())));
        log.info("dungeon instance " + inst.id() + " closed");
    }

    /** 플러그인 종료: 안에 있는 플레이어를 돌려보내고 월드를 지운다(동기). */
    public void shutdown() {
        for (Instance inst : tracker.all()) {
            World w = Bukkit.getWorld(worldName(inst.id()));
            if (w != null) {
                for (Player p : new ArrayList<>(w.getPlayers())) {
                    DungeonSession s = sessions.remove(p.getUniqueId());
                    if (s != null) {
                        returnAndForget(p, s);
                    } else {
                        p.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation());
                    }
                }
                Bukkit.unloadWorld(w, false);
            }
            try {
                DungeonTemplates.deleteRecursively(DungeonTemplates.worldFolder(worldName(inst.id())));
            } catch (IOException e) {
                log.log(Level.WARNING, "cannot delete instance folder on shutdown", e);
            }
            forgetInstance(inst.id());
        }
    }

    // ---------------- 보스 처치 ----------------

    /** CombatListener의 처치 확정 알림. 인스턴스 보스면 멤버별 클리어 보상을 한 번 기록한다. */
    public void onMobKilled(LivingEntity mob, MobStatProfile profile) {
        Optional<Instance> inst = instanceOfWorld(mob.getWorld());
        if (inst.isEmpty()) return;
        DungeonDefinition def = definitionOf(inst.get()).orElse(null);
        if (def == null || def.boss() == null || !def.boss().mobId().equals(profile.id())) return;
        long now = System.currentTimeMillis();
        if (!tracker.markCleared(inst.get().id(), now)) return;
        String eventId = "dungeon:" + inst.get().id() + ":clear";
        long seconds = tracker.exitDelayMillis() / 1000;
        for (Player p : mob.getWorld().getPlayers()) {
            if (!inst.get().isMember(p.getUniqueId())) continue;
            rewards.grantLoot(eventId, p.getUniqueId(), def.clearReward(), false);
            p.sendMessage(Component.text(def.displayName() + " 클리어! " + seconds + "초 뒤 귀환합니다.", NamedTextColor.GOLD));
        }
        log.info("dungeon instance " + inst.get().id() + " cleared");
    }

    // ---------------- 접속·리스폰 ----------------

    /** 접속 시: 입장 기록이 있는데 인스턴스가 사라졌거나 멤버가 아니면 원래 위치로 돌려보낸다. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        main.then(db.submit("load session", d -> d.loadDungeonSession(player.getUniqueId())), row -> {
            if (!player.isOnline()) return;
            if (row.isEmpty()) {
                if (isInstanceWorld(player.getWorld())) player.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation());
                return;
            }
            DungeonSession s = row.get();
            Optional<Instance> inst = tracker.get(s.instanceId());
            if (inst.isPresent() && inst.get().state() == InstanceTracker.State.CREATING && inst.get().isMember(player.getUniqueId())) {
                // 준비 중 재접속: 기록을 유지한다. 준비가 끝나면 생성 콜백이 입장시킨다.
                player.sendMessage(Component.text("던전을 준비하고 있습니다...", NamedTextColor.GRAY));
                return;
            }
            boolean stillInside = inst.isPresent() && inst.get().state() != InstanceTracker.State.CLOSING
                    && inst.get().isMember(player.getUniqueId())
                    && player.getWorld().getName().equals(worldName(s.instanceId()));
            if (stillInside) {
                sessions.put(player.getUniqueId(), s);
                player.sendMessage(Component.text("진행 중인 던전으로 돌아왔습니다.", NamedTextColor.GOLD));
            } else {
                log.info("returning " + player.getName() + " from closed dungeon instance " + s.instanceId());
                returnAndForget(player, s);
            }
        });
    }

    /** 던전 안에서 죽으면 입구에서 다시 시작한다. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        tracker.instanceOf(player.getUniqueId()).ifPresent(inst -> {
            World w = Bukkit.getWorld(worldName(inst.id()));
            DungeonDefinition def = definitionOf(inst).orElse(null);
            if (w != null && def != null) event.setRespawnLocation(entrance(w, def));
        });
    }

    public void forgetOnQuit(UUID player) {
        sessions.remove(player);
    }

    private Optional<Instance> instanceOfWorld(World world) {
        String name = world.getName();
        if (!name.startsWith(INSTANCE_PREFIX)) return Optional.empty();
        return tracker.get(name.substring(INSTANCE_PREFIX.length()));
    }

    private static boolean isInstanceWorld(World world) {
        return world.getName().startsWith(INSTANCE_PREFIX);
    }
}
