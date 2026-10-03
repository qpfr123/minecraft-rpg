package io.github.qpfr123.rpg.paper;

import io.github.qpfr123.rpg.combat.CombatStateTracker;
import io.github.qpfr123.rpg.combat.RegenCalculator;
import io.github.qpfr123.rpg.profile.PlayerProfile;
import io.github.qpfr123.rpg.stat.StatSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;

/** 1초마다: 최대치 변화 반영(무료 회복 없음), HP/MP 재생, 이동속도, 체력 표시, 사이드바, 보스바. 하단 HUD는 {@link HudRenderer}. */
public final class HudTask implements Runnable {
    private final ProfileService profiles;
    private final MobService mobs;
    private final HealthDisplay display;
    private final CombatStateTracker combat;
    private final Keys keys;
    private final SidebarService sidebar;

    public HudTask(ProfileService profiles, MobService mobs, HealthDisplay display, CombatStateTracker combat, Keys keys,
                   SidebarService sidebar) {
        this.sidebar = sidebar;
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
            if (player.getLevel() != 0 || player.getExp() != 0) { // 숨긴 바닐라 경험치바(명령어 등으로 바뀐 경우)
                player.setLevel(0);
                player.setExp(0);
            }
            display.sync(player, p, s.maxHp());
            sidebar.update(player, p, s);
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
}
