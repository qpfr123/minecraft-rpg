package io.github.qpfr123.rpg.combat;

public record DamageResult(
        double preMitigation,
        boolean critical,
        double shieldDamage,
        double hpDamage,
        double attackerHeal,
        double newHp,
        double newShield) {

    public boolean lethal() {
        return newHp <= 0;
    }
}
