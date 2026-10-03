package io.github.qpfr123.rpg.mob;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MobEngagementTest {
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    void normalMobGoesToFirstHitterOnlyOnce() {
        MobEngagement m = new MobEngagement(false, 60);
        m.recordHit(a, 5, 0);
        m.recordHit(b, 50, 100);
        assertEquals(List.of(a), m.confirmKill());
        assertEquals(List.of(), m.confirmKill()); // 두 번째 처치 확정은 무시
    }

    @Test
    void ownershipExpiresWhenOwnerStopsHitting() {
        MobEngagement m = new MobEngagement(false, 60);
        m.recordHit(a, 5, 0);
        m.recordHit(b, 5, 29_999);
        assertEquals(a, m.owner());
        m.recordHit(b, 5, 30_000);
        assertEquals(b, m.owner());
    }

    @Test
    void ownerKeepsClaimWhileActive() {
        MobEngagement m = new MobEngagement(false, 60);
        m.recordHit(a, 5, 0);
        m.recordHit(a, 5, 25_000);
        m.recordHit(b, 5, 40_000);
        assertEquals(a, m.owner());
    }

    @Test
    void bossRewardsEveryContributorAboveThreshold() {
        MobEngagement m = new MobEngagement(true, 2000); // 5% = 100
        UUID c = UUID.randomUUID();
        m.recordHit(a, 100, 0);
        m.recordHit(b, 99.9, 0);
        m.recordHit(c, 60, 0);
        m.recordHit(c, 60, 1);
        List<UUID> r = m.confirmKill();
        assertTrue(r.contains(a));
        assertFalse(r.contains(b));
        assertTrue(r.contains(c));
        assertEquals(2, r.size());
        assertTrue(m.confirmKill().isEmpty());
    }
}
