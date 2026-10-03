package io.github.qpfr123.rpg.paper;

import io.github.qpfr123.rpg.combat.DamagePipeline;
import io.github.qpfr123.rpg.config.Balance;
import io.github.qpfr123.rpg.profile.ExperienceCurve;
import io.github.qpfr123.rpg.profile.PlayerProfile;
import io.github.qpfr123.rpg.stat.StatSnapshot;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 플레이어별 사이드바: 레벨·EXP·포인트·HP/MP·주요 전투 수치. */
public final class SidebarService {
    private final Map<UUID, Scoreboard> boards = new HashMap<>();

    public void update(Player player, PlayerProfile p, StatSnapshot s) {
        Scoreboard board = boards.computeIfAbsent(player.getUniqueId(), id -> {
            Scoreboard b = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective o = b.registerNewObjective("minecraftrpg", Criteria.DUMMY, Component.text("Minecraft RPG", NamedTextColor.GOLD));
            o.setDisplaySlot(DisplaySlot.SIDEBAR);
            o.numberFormat(NumberFormat.blank());
            return b;
        });
        if (player.getScoreboard() != board) player.setScoreboard(board);
        Objective o = board.getObjective("minecraftrpg");
        String exp = p.level() >= Balance.MAX_LEVEL ? "MAX" : p.exp() + " / " + ExperienceCurve.required(p.level());
        List<Component> lines = List.of(
                Component.text("Lv " + p.level(), NamedTextColor.GREEN),
                Component.text("EXP " + exp, NamedTextColor.GREEN),
                Component.text("포인트 " + p.unspentPoints(), p.unspentPoints() > 0 ? NamedTextColor.GOLD : NamedTextColor.GRAY),
                Component.text("HP " + DamagePipeline.displayHp(p.hp()) + " / " + s.maxHp(), NamedTextColor.RED),
                Component.text("MP " + (int) Math.floor(p.mp()) + " / " + s.maxMp(), NamedTextColor.BLUE),
                Component.text(String.format(Locale.ROOT, "ATK %.1f  DEF %.0f%%", s.atk(), s.def() * 100), NamedTextColor.WHITE),
                Component.text(String.format(Locale.ROOT, "치명 %.0f%% / +%.0f%%", s.critChance() * 100, s.critDamage() * 100), NamedTextColor.WHITE));
        for (int i = 0; i < lines.size(); i++) {
            Score score = o.getScore("line" + i);
            score.setScore(lines.size() - i);
            score.customName(lines.get(i));
        }
    }

    public void forget(UUID id) {
        boards.remove(id);
    }
}
