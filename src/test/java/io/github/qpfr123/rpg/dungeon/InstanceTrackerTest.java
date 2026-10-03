package io.github.qpfr123.rpg.dungeon;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.github.qpfr123.rpg.dungeon.InstanceTracker.EnterResult.*;
import static org.junit.jupiter.api.Assertions.*;

class InstanceTrackerTest {
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    @Test
    void capCountsCreatingInstancesAndBlocksDoubleEntry() {
        InstanceTracker t = new InstanceTracker(2, 60_000, 30_000);
        assertTrue(t.create("i1", "crypt", List.of(a), 4).isPresent());
        assertEquals(ALREADY_INSIDE, t.canCreate(List.of(a), 4));
        assertTrue(t.create("i2", "crypt", List.of(b), 4).isPresent()); // i1은 아직 CREATING이어도 상한에 포함
        assertEquals(CAP_REACHED, t.canCreate(List.of(c), 4));
        assertTrue(t.create("i3", "crypt", List.of(c), 4).isEmpty());
        assertEquals(TOO_MANY_PLAYERS, t.canCreate(List.of(c, UUID.randomUUID()), 1));
    }

    @Test
    void idleInstanceClosesOnlyAfterTimeout() {
        InstanceTracker t = new InstanceTracker(4, 60_000, 30_000);
        t.create("i1", "crypt", List.of(a), 4);
        t.activate("i1", 0);
        t.onPresence("i1", 1, 1_000);
        assertTrue(t.dueForClose(100_000).isEmpty());
        t.onPresence("i1", 0, 100_000);
        assertTrue(t.dueForClose(159_999).isEmpty());
        assertEquals(1, t.dueForClose(160_000).size());
        assertEquals(InstanceTracker.State.CLOSING, t.get("i1").orElseThrow().state());
        assertTrue(t.instanceOf(a).isEmpty(), "닫히는 인스턴스는 멤버 소속으로 치지 않음");
        assertEquals(1, t.openCount(), "월드 정리가 끝날 때까지 상한에 포함");
        t.remove("i1");
        assertEquals(0, t.openCount());
    }

    @Test
    void returningMemberResetsIdleTimer() {
        InstanceTracker t = new InstanceTracker(4, 60_000, 30_000);
        t.create("i1", "crypt", List.of(a), 4);
        t.activate("i1", 0);
        t.onPresence("i1", 0, 0);
        t.onPresence("i1", 1, 50_000);
        t.onPresence("i1", 0, 70_000);
        assertTrue(t.dueForClose(120_000).isEmpty());
        assertEquals(1, t.dueForClose(130_000).size());
    }

    @Test
    void clearedOnceThenClosesAfterExitDelay() {
        InstanceTracker t = new InstanceTracker(4, 60_000, 30_000);
        t.create("i1", "crypt", List.of(a, b), 4);
        t.activate("i1", 0);
        t.onPresence("i1", 2, 0);
        assertTrue(t.markCleared("i1", 10_000));
        assertFalse(t.markCleared("i1", 10_001));
        assertTrue(t.dueForClose(39_999).isEmpty());
        assertEquals(1, t.dueForClose(40_000).size());
    }

    @Test
    void membershipIsFixedAndLeavingRemovesOnlyThatPlayer() {
        InstanceTracker t = new InstanceTracker(4, 60_000, 30_000);
        t.create("i1", "crypt", List.of(a, b), 4);
        t.activate("i1", 0);
        t.onPresence("i1", 2, 0);
        t.removeMember("i1", a);
        assertTrue(t.instanceOf(a).isEmpty());
        assertTrue(t.instanceOf(b).isPresent());
        assertTrue(t.dueForClose(1).isEmpty());
        t.removeMember("i1", b);
        assertEquals(1, t.dueForClose(2).size(), "멤버가 모두 나가면 바로 닫음");
    }
}
