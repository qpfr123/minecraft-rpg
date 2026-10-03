package io.github.qpfr123.rpg.combat;

/**
 * 피해 진입점. 바닐라 이벤트 어댑터와 플러그인 스킬 모두 이 요청을 만들어 {@link DamagePipeline}에 넘긴다.
 */
public sealed interface DamageRequest {
    /** RPG 직접 공격/투사체: ATK×계수 → 데미지 증가 → 치명 → DEF → 방어막 → HP → 흡수. */
    record Attack(AttackerStats attacker, double coefficient) implements DamageRequest {}

    /** 낙하·화재 등 환경 피해: 원피해/20 × 대상 최대 HP를 HP에 직접 적용. DEF·방어막·흡수 없음. */
    record Environment(double vanillaRawDamage) implements DamageRequest {}

    /** 공허·관리자 처치: 방어막을 우회해 즉사. */
    record TrueKill() implements DamageRequest {}
}
