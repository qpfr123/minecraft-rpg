package io.github.qpfr123.rpg.config;

/**
 * 슬라이스 1 밸런스 값. design.md의 "초기 구현 계수"와 slice-1.md의 시험값을 한곳에 모은다.
 * 값을 바꿀 때는 이 파일과 문서를 함께 고친다.
 */
public final class Balance {
    private Balance() {}

    // 성장
    public static final int MAX_LEVEL = 10;              // 슬라이스 1 상한(설계 가정 최대 레벨은 60)
    public static final int INITIAL_POINTS = 10;
    public static final int POINTS_PER_LEVEL = 3;
    public static final double SINGLE_STAT_CAP_RATIO = 0.5;

    // 기본 1차 스탯
    public static final double BASE_HP = 100;
    public static final double BASE_MP = 100;
    public static final double BASE_REGEN = 4;
    public static final double BASE_ATK = 10;
    public static final double BASE_CRIT_CHANCE = 0.10;
    public static final double BASE_CRIT_DAMAGE = 0.50;
    public static final double BASE_ATTACK_SPEED = 1.0;

    // 2차 스탯 점당 계수
    public static final double STR_ATK_PCT = 0.006;
    public static final double AGI_ATTACK_SPEED = 0.006;
    public static final double AGI_MOVE_SPEED = 0.004;
    public static final double RES_DEF = 0.002;
    public static final double RES_STATUS = 0.002;
    public static final double VIT_HP_PCT = 0.006;
    public static final double VIT_REGEN = 0.1;
    public static final double FOC_CRIT_CHANCE = 0.006;
    public static final double FOC_CRIT_DAMAGE = 0.01;
    public static final double LUK_DROP = 0.004;
    public static final double SPI_MP_PCT = 0.006;
    public static final double SPI_REGEN = 0.1;

    // 상한
    public static final double DEF_CAP = 0.50;
    public static final double STATUS_RESIST_CAP = 0.70;
    public static final double CRIT_CHANCE_CAP = 1.0;
    public static final double CRIT_DAMAGE_CAP = 4.0;
    public static final double ATTACK_SPEED_CAP = 3.0;
    public static final double COOLDOWN_REDUCTION_CAP_SECONDS = 10;
    public static final double LIFESTEAL_CAP = 0.20;
    public static final double DROP_BONUS_CAP = 2.0;
    public static final double EXP_BONUS_CAP = 5.0;
    public static final double SHIELD_CAP_RATIO = 0.5;

    // 전투
    public static final long COMBAT_TIMEOUT_MILLIS = 5_000;  // 시험값
    public static final double COMBAT_REGEN_RATIO = 0.2;
    public static final long BASIC_ATTACK_INTERVAL_MILLIS = 600; // 시험값
    public static final double SKILL_COOLDOWN_FLOOR_SECONDS = 5;
    /** 태그 없는 바닐라 몹의 원피해를 RPG 공격력으로 바꾸는 배율(시험값, 기본 HP 100 기준 ×5). */
    public static final double VANILLA_MOB_DAMAGE_SCALE = 5;

    // 보상
    public static final double BOSS_CONTRIBUTION_RATIO = 0.05; // 시험값
    public static final long MOB_CLAIM_EXPIRE_MILLIS = 30_000;  // 시험값: 소유자가 이 시간 동안 때리지 않으면 소유 해제
    public static final double SHIELD_TONIC_RATIO = 0.10;
}
