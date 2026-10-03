package io.github.qpfr123.rpg;

import io.github.qpfr123.rpg.combat.CombatStateTracker;
import io.github.qpfr123.rpg.combat.DamagePipeline;
import io.github.qpfr123.rpg.loot.GearRegistry;
import io.github.qpfr123.rpg.loot.LootRoller;
import io.github.qpfr123.rpg.mob.MobRegistry;
import io.github.qpfr123.rpg.paper.BackupService;
import io.github.qpfr123.rpg.paper.CombatListener;
import io.github.qpfr123.rpg.paper.GearItems;
import io.github.qpfr123.rpg.paper.HealthDisplay;
import io.github.qpfr123.rpg.paper.HudTask;
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
        profiles = new ProfileService(db, gear, getLogger());
        MobService mobs = new MobService(keys, MobRegistry.slice1());
        HealthDisplay display = new HealthDisplay(main);
        CombatStateTracker combat = new CombatStateTracker();
        RewardService rewards = new RewardService(db, profiles, gearRegistry, gear, main,
                new LootRoller(() -> ThreadLocalRandom.current().nextDouble()), getLogger());
        DamagePipeline pipeline = new DamagePipeline(() -> ThreadLocalRandom.current().nextDouble());
        backups = new BackupService(db, getDataFolder().toPath().resolve("backups"), getLogger());

        CombatListener combatListener = new CombatListener(profiles, mobs, rewards, display, combat, pipeline, main);
        getServer().getPluginManager().registerEvents(combatListener, this);
        getServer().getPluginManager().registerEvents(
                new PlayerListener(profiles, rewards, mobs, gear, display, combat, combatListener, main), this);

        RpgCommand rpg = new RpgCommand(profiles, rewards);
        Objects.requireNonNull(getCommand("rpg")).setExecutor(rpg);
        Objects.requireNonNull(getCommand("rpg")).setTabCompleter(rpg);
        RpgAdminCommand admin = new RpgAdminCommand(profiles, mobs, rewards, backups, db, main);
        Objects.requireNonNull(getCommand("rpgadmin")).setExecutor(admin);
        Objects.requireNonNull(getCommand("rpgadmin")).setTabCompleter(admin);

        Bukkit.getScheduler().runTaskTimer(this, new HudTask(profiles, mobs, display, combat, keys), SECOND, SECOND);
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
        if (profiles != null) profiles.saveAll();
        if (db != null) db.shutdown(); // 대기 중인 쓰기를 모두 끝낸 뒤 닫는다
        getLogger().info("MinecraftRPG disabled");
    }
}
