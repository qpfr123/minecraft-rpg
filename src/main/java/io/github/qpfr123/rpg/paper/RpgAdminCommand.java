package io.github.qpfr123.rpg.paper;

import io.github.qpfr123.rpg.loot.RewardGrant;
import io.github.qpfr123.rpg.mob.MobStatProfile;
import io.github.qpfr123.rpg.profile.PlayerProfile;
import io.github.qpfr123.rpg.stat.SecondaryStat;
import io.github.qpfr123.rpg.storage.DbExecutor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * /rpgadmin profile <플레이어> | ledger <플레이어> | spawn <몹ID> | backup | level <플레이어> <레벨> | crashtest claim
 */
public final class RpgAdminCommand implements TabExecutor {
    private final ProfileService profiles;
    private final MobService mobs;
    private final RewardService rewards;
    private final BackupService backups;
    private final DbExecutor db;
    private final MainThread main;
    private final io.github.qpfr123.rpg.loot.GearRegistry gearRegistry;
    private final GearItems gearItems;

    public RpgAdminCommand(ProfileService profiles, MobService mobs, RewardService rewards, BackupService backups,
                           DbExecutor db, MainThread main, io.github.qpfr123.rpg.loot.GearRegistry gearRegistry, GearItems gearItems) {
        this.gearRegistry = gearRegistry;
        this.gearItems = gearItems;
        this.profiles = profiles;
        this.mobs = mobs;
        this.rewards = rewards;
        this.backups = backups;
        this.db = db;
        this.main = main;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "profile" -> withTarget(sender, args, id -> profile(sender, id));
            case "ledger" -> withTarget(sender, args, id -> ledger(sender, id));
            case "spawn" -> spawn(sender, args);
            case "backup" -> main.then(backups.backup("manual"), path -> sender.sendMessage("백업 완료: " + path));
            case "level" -> level(sender, args);
            case "crashtest" -> {
                RewardService.CrashPoint point = args.length < 2 ? null : switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "claim" -> RewardService.CrashPoint.AFTER_SAVE;
                    case "claim-nosave" -> RewardService.CrashPoint.BEFORE_SAVE;
                    default -> null;
                };
                if (point == null) {
                    sender.sendMessage("/rpgadmin crashtest <claim|claim-nosave>");
                } else {
                    rewards.armCrashTest(point);
                    sender.sendMessage(Component.text("[테스트] 다음 보상 수령에서 서버를 강제 종료합니다: " + point, NamedTextColor.RED));
                }
            }
            case "givegear" -> giveGear(sender, args);
            default -> sender.sendMessage("/rpgadmin profile|ledger <플레이어> · spawn <몹ID> [플레이어] · backup · level <플레이어> <레벨> · givegear <플레이어> <장비ID> · crashtest <claim|claim-nosave>");
        }
        return true;
    }

    private interface IdAction { void run(UUID id); }

    private void withTarget(CommandSender sender, String[] args, IdAction action) {
        if (args.length < 2) {
            sender.sendMessage("플레이어 이름이 필요합니다.");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]); // 메인 스레드에서 원격 조회하지 않음
        if (target == null) {
            sender.sendMessage("이 서버에 접속한 적 없는 플레이어입니다: " + args[1]);
            return;
        }
        action.run(target.getUniqueId());
    }

    private void profile(CommandSender sender, UUID id) {
        PlayerProfile online = profiles.get(id).orElse(null);
        if (online != null) {
            sender.sendMessage(describe(online, "접속 중(메모리)"));
            return;
        }
        main.then(db.submit("admin profile", d -> d.loadProfile(id)), p -> sender.sendMessage(
                p.map(x -> describe(x, "DB")).orElse(Component.text("프로필 없음: " + id))));
    }

    private static Component describe(PlayerProfile p, String source) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(source).append("] ").append(p.id()).append(" Lv ").append(p.level()).append(" EXP ").append(p.exp())
                .append(" HP ").append(String.format(Locale.ROOT, "%.1f", p.hp()))
                .append(" MP ").append(String.format(Locale.ROOT, "%.1f", p.mp()))
                .append(" 방어막 ").append(String.format(Locale.ROOT, "%.1f", p.shield()))
                .append(" v").append(p.version()).append(" | ");
        for (SecondaryStat s : SecondaryStat.values()) sb.append(s.displayName()).append(p.allocation().get(s)).append(' ');
        sb.append("| 미사용 ").append(p.unspentPoints());
        return Component.text(sb.toString(), NamedTextColor.YELLOW);
    }

    private void ledger(CommandSender sender, UUID id) {
        main.then(db.submit("admin ledger", d -> d.recentRewards(id, 15)), rows -> {
            if (rows.isEmpty()) {
                sender.sendMessage("보상 기록 없음: " + id);
                return;
            }
            for (RewardGrant g : rows) {
                sender.sendMessage(Component.text(g.status() + " " + g.eventId() + " EXP " + g.exp() + " " + g.gearIds(),
                        g.status() == RewardGrant.Status.CLAIMED ? NamedTextColor.GRAY : NamedTextColor.YELLOW));
            }
        });
    }

    /** 테스트용 장비 지급. 보상 원장을 거치지 않으므로 관리자 전용이며 로그를 남긴다. */
    private void giveGear(CommandSender sender, String[] args) {
        Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[1]) : null;
        var def = args.length >= 3 ? gearRegistry.get(args[2]).orElse(null) : null;
        if (target == null || def == null) {
            sender.sendMessage("/rpgadmin givegear <접속 중 플레이어> <장비ID>");
            return;
        }
        String instance = "admin:" + UUID.randomUUID();
        target.getInventory().addItem(gearItems.create(def, instance));
        Bukkit.getLogger().warning("[MinecraftRPG] admin " + sender.getName() + " gave " + def.id() + " (" + instance + ") to " + target.getName());
        sender.sendMessage("지급: " + def.displayName() + " → " + target.getName());
    }

    private void spawn(CommandSender sender, String[] args) {
        Player player = args.length >= 3 ? Bukkit.getPlayerExact(args[2]) : sender instanceof Player p ? p : null;
        if (player == null || args.length < 2) {
            sender.sendMessage("/rpgadmin spawn <몹ID> [근처 플레이어]");
            return;
        }
        MobStatProfile p = mobs.registry().get(args[1]).orElse(null);
        if (p == null) {
            sender.sendMessage("알 수 없는 몹: " + args[1]);
            return;
        }
        var dir = player.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new org.bukkit.util.Vector(1, 0, 0);
        var mob = mobs.spawn(p, player.getLocation().add(dir.normalize().multiply(3)));
        sender.sendMessage("소환: " + p.displayName() + " " + mob.getUniqueId());
    }

    private void level(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("/rpgadmin level <플레이어> <레벨>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        PlayerProfile p = target == null ? null : profiles.get(target.getUniqueId()).orElse(null);
        if (p == null) {
            sender.sendMessage("접속 중인 플레이어만 가능합니다.");
            return;
        }
        int lv;
        try {
            lv = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            lv = -1;
        }
        if (p.raiseLevelForAdmin(lv)) {
            profiles.save(p);
            sender.sendMessage(target.getName() + " → Lv " + lv);
        } else {
            sender.sendMessage("레벨은 현재보다 높고 최대 레벨 이하여야 합니다.");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) return RpgCommand.filter(List.of("profile", "ledger", "spawn", "backup", "level", "crashtest", "givegear"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) {
            List<String> ids = new ArrayList<>();
            mobs.registry().all().forEach(m -> ids.add(m.id()));
            return RpgCommand.filter(ids, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("crashtest")) return List.of("claim", "claim-nosave");
        if (args.length == 2) return null; // 플레이어 이름
        return List.of();
    }
}
