package io.github.qpfr123.rpg;

import io.github.qpfr123.rpg.combat.CombatStateTracker;
import io.github.qpfr123.rpg.combat.DamagePipeline;
import io.github.qpfr123.rpg.loot.GearRegistry;
import io.github.qpfr123.rpg.loot.LootRoller;
import io.github.qpfr123.rpg.mob.MobRegistry;
import io.github.qpfr123.rpg.dungeon.DungeonDefinition;
import io.github.qpfr123.rpg.dungeon.InstanceTracker;
import io.github.qpfr123.rpg.party.PartyService;
import io.github.qpfr123.rpg.paper.BackupService;
import io.github.qpfr123.rpg.paper.DungeonCommand;
import io.github.qpfr123.rpg.paper.DungeonEditor;
import io.github.qpfr123.rpg.paper.DungeonStore;
import io.github.qpfr123.rpg.paper.DungeonService;
import io.github.qpfr123.rpg.paper.DungeonTemplates;
import io.github.qpfr123.rpg.paper.ClaimLockListener;
import io.github.qpfr123.rpg.paper.SidebarService;
import io.github.qpfr123.rpg.paper.VanillaGuardListener;
import io.github.qpfr123.rpg.paper.CombatListener;
import io.github.qpfr123.rpg.paper.GearItems;
import io.github.qpfr123.rpg.paper.HealthDisplay;
import io.github.qpfr123.rpg.paper.HudTask;
import io.github.qpfr123.rpg.paper.HudRenderer;
import io.github.qpfr123.rpg.paper.Keys;
import io.github.qpfr123.rpg.paper.MainThread;
import io.github.qpfr123.rpg.paper.MobService;
import io.github.qpfr123.rpg.paper.PlayerListener;
import io.github.qpfr123.rpg.paper.ProfileService;
import io.github.qpfr123.rpg.paper.RewardService;
import io.github.qpfr123.rpg.paper.RpgAdminCommand;
import io.github.qpfr123.rpg.paper.RpgCommand;
import io.github.qpfr123.rpg.storage.Database;
import io.github.qpfr123.rpg.storage.DbExecutor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

public final class RpgPlugin extends JavaPlugin {
    private static final long SECOND = 20L;

    private DbExecutor db;
    private ProfileService profiles;
    private BackupService backups;
    private DungeonService dungeons;
    private io.github.qpfr123.rpg.paper.ResourcePackService packs;
    private DungeonEditor editor;

    @Override
    public void onEnable() {
        try {
            db = new DbExecutor(new Database(getDataFolder().toPath().resolve("rpg.db")), getLogger());
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "cannot open database, disabling", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        Keys keys = new Keys(this);
        MainThread main = new MainThread(this);
        GearRegistry gearRegistry = GearRegistry.slice1();
        GearItems gear = new GearItems(keys, gearRegistry);
        profiles = new ProfileService(db, gear, main, getLogger());
        MobService mobs = new MobService(keys, MobRegistry.slice1());
        HealthDisplay display = new HealthDisplay(main);
        CombatStateTracker combat = new CombatStateTracker();
        RewardService rewards = new RewardService(this, db, profiles, gearRegistry, gear, main,
                new LootRoller(() -> ThreadLocalRandom.current().nextDouble()), getLogger());
        DamagePipeline pipeline = new DamagePipeline(() -> ThreadLocalRandom.current().nextDouble());
        backups = new BackupService(db, getDataFolder().toPath().resolve("backups"), getLogger());

        saveDefaultConfig();
        packs = new io.github.qpfr123.rpg.paper.ResourcePackService(getConfig().getConfigurationSection("resource-pack"), getLogger());
        packs.start(getResource("resourcepack.zip"));
        getServer().getPluginManager().registerEvents(packs, this);
        HudRenderer hud = new HudRenderer(profiles);
        CombatListener combatListener = new CombatListener(profiles, mobs, rewards, display, combat, pipeline, main, hud);
        getServer().getPluginManager().registerEvents(combatListener, this);
        SidebarService sidebar = new SidebarService();
        getServer().getPluginManager().registerEvents(
                new PlayerListener(profiles, rewards, mobs, gear, display, combat, combatListener, main, sidebar, hud), this);
        getServer().getPluginManager().registerEvents(new ClaimLockListener(gear, hud), this);
        getServer().getPluginManager().registerEvents(new io.github.qpfr123.rpg.paper.VanillaHudListener(), this);
        getServer().getPluginManager().registerEvents(new VanillaGuardListener(gear, mobs), this);

        RpgCommand rpg = new RpgCommand(profiles, rewards);
        io.github.qpfr123.rpg.paper.menu.MenuService menus = new io.github.qpfr123.rpg.paper.menu.MenuService(profiles, rewards, rpg, db, main);
        rpg.setMenus(menus);
        getServer().getPluginManager().registerEvents(menus, this);
        Objects.requireNonNull(getCommand("rpg")).setExecutor(rpg);
        Objects.requireNonNull(getCommand("rpg")).setTabCompleter(rpg);
        RpgAdminCommand admin = new RpgAdminCommand(profiles, mobs, rewards, backups, db, main, gearRegistry, gear);
        Objects.requireNonNull(getCommand("rpgadmin")).setExecutor(admin);
        Objects.requireNonNull(getCommand("rpgadmin")).setTabCompleter(admin);

        // 던전(슬라이스 2)
        PartyService parties = new PartyService();
        DungeonStore dungeonStore = new DungeonStore(getDataFolder(), getLogger());
        DungeonEditor.recoverInterruptedSaves(dungeonStore.dir(), getLogger()); // 정의를 읽기 전에 중단된 저장을 정리
        dungeons = new DungeonService(this, dungeonStore.loadAll(), new InstanceTracker(4, 120_000, 30_000), db, mobs,
                rewards, parties, main, getLogger());
        dungeons.cleanupLeftovers();
        editor = new DungeonEditor(dungeonStore, dungeons, mobs.registry(), db, main, getLogger());
        editor.cleanupLeftovers();
        DungeonTemplates templates = new DungeonTemplates(getLogger());
        for (DungeonDefinition d : dungeons.registry().all()) {
            try {
                templates.ensure(d);
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "cannot prepare dungeon template " + d.id(), e);
            }
        }
        getServer().getPluginManager().registerEvents(dungeons, this);
        getServer().getPluginManager().registerEvents(editor, this);
        combatListener.addKillListener(dungeons::onMobKilled);
        admin.setDungeons(dungeons);
        admin.setEditor(editor);
        DungeonCommand dungeonCommand = new DungeonCommand(dungeons, parties);
        for (String name : new String[] {"dungeon", "party"}) {
            Objects.requireNonNull(getCommand(name)).setExecutor(dungeonCommand);
            Objects.requireNonNull(getCommand(name)).setTabCompleter(dungeonCommand);
        }
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
                parties.leave(e.getPlayer().getUniqueId()); // 파티는 메모리 전용: 접속을 끊으면 빠진다
                dungeons.forgetOnQuit(e.getPlayer().getUniqueId());
                hud.forget(e.getPlayer().getUniqueId());
            }
        }, this);
        Bukkit.getScheduler().runTaskTimer(this, dungeons::tick, SECOND, SECOND);

        Bukkit.getScheduler().runTaskTimer(this, new HudTask(profiles, mobs, display, combat, keys, sidebar), SECOND, SECOND);
        Bukkit.getScheduler().runTaskTimer(this, hud, SECOND, 4L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            profiles.saveDirty();
            profiles.purgeRecent(5 * 60_000L);
        }, 30 * SECOND, 30 * SECOND);
        Bukkit.getScheduler().runTaskTimer(this, () -> backups.backup("auto"), 6 * 3600 * SECOND, 6 * 3600 * SECOND);
        backups.backup("startup");

        // /reload 등으로 이미 접속 중인 플레이어가 있으면 입장을 다시 요구한다(프로필 로드는 사전 로그인에서만).
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.kick(net.kyori.adventure.text.Component.text("MinecraftRPG가 다시 로드됐습니다. 재접속하세요."));
        }
        getLogger().info("MinecraftRPG " + getPluginMeta().getVersion() + " enabled");
    }

    @Override
    public void onDisable() {
        if (editor != null) editor.shutdown(); // 저장하지 않은 편집은 버린다
        if (dungeons != null) dungeons.shutdown(); // 안에 있는 플레이어를 먼저 돌려보낸 뒤 저장
        if (profiles != null) profiles.saveAll();
        if (packs != null) packs.stop();
        if (db != null) db.shutdown(); // 대기 중인 쓰기를 모두 끝낸 뒤 닫는다
        getLogger().info("MinecraftRPG disabled");
    }
}
