package io.github.qpfr123.rpg.paper;

import io.github.qpfr123.rpg.combat.CombatStateTracker;
import io.github.qpfr123.rpg.combat.DamagePipeline;
import io.github.qpfr123.rpg.combat.RegenCalculator;
import io.github.qpfr123.rpg.profile.ExperienceCurve;
import io.github.qpfr123.rpg.profile.PlayerProfile;
import io.github.qpfr123.rpg.config.Balance;
import io.github.qpfr123.rpg.stat.StatSnapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;

/** 1초마다: 최대치 변화 반영(무료 회복 없음), HP/MP 재생, 이동속도, 체력 표시, 액션바, 보스바. */
public final class HudTask implements Runnable {
    private final ProfileService profiles;
    private final MobService mobs;
    private final HealthDisplay display;
    private final CombatStateTracker combat;
    private final Keys keys;

    public HudTask(ProfileService profiles, MobService mobs, HealthDisplay display, CombatStateTracker combat, Keys keys) {
        this.profiles = profiles;
        this.mobs = mobs;
        this.display = display;
        this.combat = combat;
        this.keys = keys;
    }

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerProfile p = profiles.get(player.getUniqueId()).orElse(null);
            if (p == null || player.isDead()) continue;
            StatSnapshot s = profiles.stats(player);
            p.clampTo(s.maxHp(), s.maxMp(), s.shieldCap());
            boolean inCombat = combat.inCombat(player.getUniqueId(), now);
            p.setHp(RegenCalculator.regen(p.hp(), s.maxHp(), s.hpRegen(), 1, inCombat));
            p.setMp(RegenCalculator.regen(p.mp(), s.maxMp(), s.mpRegen(), 1, inCombat));
            applyMoveSpeed(player, s.moveSpeedMultiplier());
            display.sync(player, p, s.maxHp());
            player.sendActionBar(actionBar(p, s, inCombat));
        }
        mobs.tickBossBars();
    }

    private void applyMoveSpeed(Player player, double multiplier) {
        AttributeInstance attr = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attr == null) return;
        double amount = multiplier - 1;
        AttributeModifier current = attr.getModifier(keys.moveSpeed);
        if (current != null && Math.abs(current.getAmount() - amount) < 1e-9) return;
        if (current != null) attr.removeModifier(keys.moveSpeed);
        if (amount != 0) attr.addTransientModifier(new AttributeModifier(keys.moveSpeed, amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
    }

    private static Component actionBar(PlayerProfile p, StatSnapshot s, boolean inCombat) {
        Component bar = Component.text("HP " + DamagePipeline.displayHp(p.hp()) + "/" + s.maxHp(), NamedTextColor.RED);
        if (p.shield() > 0) bar = bar.append(Component.text(" +" + DamagePipeline.displayHp(p.shield()), NamedTextColor.AQUA));
        bar = bar.append(Component.text("  MP " + (int) Math.floor(p.mp()) + "/" + s.maxMp(), NamedTextColor.BLUE));
        String exp = p.level() >= Balance.MAX_LEVEL ? "MAX" : p.exp() + "/" + ExperienceCurve.required(p.level());
        bar = bar.append(Component.text("  Lv " + p.level() + " (" + exp + ")", NamedTextColor.GREEN));
        if (p.unspentPoints() > 0) bar = bar.append(Component.text("  포인트 " + p.unspentPoints(), NamedTextColor.GOLD));
        if (inCombat) bar = bar.append(Component.text("  ⚔", NamedTextColor.DARK_RED));
        return bar;
    }
}
