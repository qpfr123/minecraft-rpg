package io.github.qpfr123.rpg.combat;

/** 피해를 받는 쪽의 현재 상태. HP·방어막은 소수로 보관하고 표시할 때만 올림한다. */
public record DefenderState(double hp, double shield, int maxHp, double def) {}
