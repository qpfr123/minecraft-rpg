package io.github.qpfr123.rpg.paper;

import io.github.qpfr123.rpg.profile.PlayerProfile;
import io.github.qpfr123.rpg.stat.PointAllocationPolicy;
import io.github.qpfr123.rpg.stat.SecondaryStat;
import io.github.qpfr123.rpg.stat.StatSnapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /rpg stats | alloc <스탯> <양> | claim */
public final class RpgCommand implements TabExecutor {
    private final ProfileService profiles;
    private final RewardService rewards;

    public RpgCommand(ProfileService profiles, RewardService rewards) {
        this.profiles = profiles;
        this.rewards = rewards;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("플레이어만 사용할 수 있습니다.");
            return true;
        }
        PlayerProfile profile = profiles.get(player.getUniqueId()).orElse(null);
        if (profile == null) {
            player.sendMessage(Component.text("RPG 데이터를 불러오는 중입니다.", NamedTextColor.GRAY));
            return true;
        }
        String sub = args.length == 0 ? "stats" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "stats" -> showStats(player, profile);
            case "alloc" -> allocate(player, profile, args);
            case "claim" -> rewards.claimAll(player);
            default -> player.sendMessage(Component.text("/rpg stats | /rpg alloc <스탯> <양> | /rpg claim", NamedTextColor.GRAY));
        }
        return true;
    }

    private void showStats(Player player, PlayerProfile p) {
        StatSnapshot s = profiles.stats(player);
        int cap = PointAllocationPolicy.singleStatCap(p.level());
        player.sendMessage(Component.text("— Lv " + p.level() + " | 미사용 포인트 " + p.unspentPoints()
                + " | 스탯당 상한 " + cap + " —", NamedTextColor.GOLD));
        StringBuilder alloc = new StringBuilder();
        for (SecondaryStat st : SecondaryStat.values()) {
            alloc.append(st.displayName()).append(' ').append(p.allocation().get(st)).append("  ");
        }
        player.sendMessage(Component.text(alloc.toString().trim(), NamedTextColor.YELLOW));
        player.sendMessage(Component.text(String.format(Locale.ROOT,
                "최대HP %d  최대MP %d  ATK %.1f  DEF %.1f%%  치명 %.1f%%/+%.0f%%  공속 %.2f  재생 %.1f/%.1f  드롭 +%.1f%%",
                s.maxHp(), s.maxMp(), s.atk(), s.def() * 100, s.critChance() * 100, s.critDamage() * 100,
                s.attackSpeed(), s.hpRegen(), s.mpRegen(), s.dropBonus() * 100), NamedTextColor.WHITE));
        player.sendMessage(Component.text("배분: /rpg alloc <근력|민첩|저항|건강|집중|행운|정신> <양>", NamedTextColor.GRAY));
    }

    private void allocate(Player player, PlayerProfile p, String[] args) {
        if (args.length < 3) {
            player.sendMessage(Component.text("/rpg alloc <스탯> <양>", NamedTextColor.GRAY));
            return;
        }
        SecondaryStat stat = SecondaryStat.parse(args[1]).orElse(null);
        int amount;
        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            amount = -1;
        }
        if (stat == null) {
            player.sendMessage(Component.text("알 수 없는 스탯: " + args[1], NamedTextColor.RED));
            return;
        }
        // 메인 스레드에서 검증과 반영을 한 번에 하므로 연속 입력에도 상한을 우회할 수 없다.
        PointAllocationPolicy.Result r = p.allocate(stat, amount);
        switch (r) {
            case OK -> {
                profiles.save(p);
                player.sendMessage(Component.text(stat.displayName() + " +" + amount + " (현재 " + p.allocation().get(stat)
                        + ", 남은 포인트 " + p.unspentPoints() + ")", NamedTextColor.GREEN));
            }
            case INVALID_AMOUNT -> player.sendMessage(Component.text("양은 1 이상의 정수여야 합니다.", NamedTextColor.RED));
            case NOT_ENOUGH_POINTS -> player.sendMessage(Component.text("포인트가 부족합니다. 남은 포인트 " + p.unspentPoints(), NamedTextColor.RED));
            case CAP_EXCEEDED -> player.sendMessage(Component.text("한 스탯에는 지급 포인트의 50%("
                    + PointAllocationPolicy.singleStatCap(p.level()) + ")까지만 투자할 수 있습니다.", NamedTextColor.RED));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) return filter(List.of("stats", "alloc", "claim"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("alloc")) {
            List<String> names = new ArrayList<>();
            for (SecondaryStat s : SecondaryStat.values()) names.add(s.displayName());
            return filter(names, args[1]);
        }
        return List.of();
    }

    static List<String> filter(List<String> options, String prefix) {
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
    }
}
