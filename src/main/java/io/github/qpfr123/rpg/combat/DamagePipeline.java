package io.github.qpfr123.rpg.combat;

import java.util.function.DoubleSupplier;

/**
 * 모든 RPG HP 감소가 지나는 단일 계산기. 부작용이 없고, 호출자가 결과를 상태에 반영한다.
 */
public final class DamagePipeline {
    private final DoubleSupplier random;

    /** @param random [0,1) 난수원. 테스트에서는 고정값을 넣는다. */
    public DamagePipeline(DoubleSupplier random) {
        this.random = random;
    }

    public DamageResult apply(DamageRequest request, DefenderState target) {
        return switch (request) {
            case DamageRequest.Attack a -> attack(a, target);
            case DamageRequest.Environment e -> direct(Math.max(0, e.vanillaRawDamage()) / 20.0 * target.maxHp(), target, false);
            case DamageRequest.TrueKill k -> new DamageResult(target.hp(), false, 0, target.hp(), 0, 0, target.shield());
        };
    }

    private DamageResult attack(DamageRequest.Attack a, DefenderState t) {
        AttackerStats s = a.attacker();
        double dmg = Math.max(0, s.atk()) * Math.max(0, a.coefficient());
        dmg *= 1 + Math.max(0, s.damageIncrease());
        boolean crit = s.critChance() > 0 && random.getAsDouble() < s.critChance();
        if (crit) dmg *= 1 + s.critDamage();
        double pre = dmg;
        dmg *= 1 - clampDef(t.def());

        double shieldDamage = Math.min(Math.max(0, t.shield()), dmg);
        double remaining = dmg - shieldDamage;
        double hpDamage = Math.min(Math.max(0, t.hp()), remaining); // 초과 피해 제외
        double heal = hpDamage * Math.max(0, s.lifesteal());
        return new DamageResult(pre, crit, shieldDamage, hpDamage, heal, t.hp() - hpDamage, t.shield() - shieldDamage);
    }

    private static DamageResult direct(double amount, DefenderState t, boolean crit) {
        double hpDamage = Math.min(Math.max(0, t.hp()), amount);
        return new DamageResult(amount, crit, 0, hpDamage, 0, t.hp() - hpDamage, t.shield());
    }

    private static double clampDef(double def) {
        return Math.max(0, Math.min(io.github.qpfr123.rpg.config.Balance.DEF_CAP, def));
    }

    /** 표시용 HP: 살아 있으면 최소 1. */
    public static int displayHp(double hp) {
        return hp <= 0 ? 0 : (int) Math.ceil(hp);
    }
}
