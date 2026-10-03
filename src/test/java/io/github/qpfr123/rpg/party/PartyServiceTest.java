package io.github.qpfr123.rpg.party;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.qpfr123.rpg.party.PartyService.Result.*;
import static org.junit.jupiter.api.Assertions.*;

class PartyServiceTest {
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    @Test
    void inviteAcceptLeaveAndLeaderHandover() {
        PartyService s = new PartyService();
        assertEquals(OK, s.invite(a, b));
        assertEquals(NO_INVITE, s.accept(c));
        assertEquals(OK, s.accept(b));
        assertEquals(OK, s.invite(a, c));
        assertEquals(OK, s.accept(c));
        assertEquals(3, s.partyOf(b).orElseThrow().members().size());
        assertEquals(NOT_LEADER, s.invite(b, UUID.randomUUID()));
        assertEquals(OK, s.leave(a));
        assertEquals(b, s.partyOf(c).orElseThrow().leader());
        assertEquals(OK, s.leave(c));
        assertTrue(s.partyOf(b).isEmpty(), "혼자 남으면 해산");
    }

    @Test
    void cannotInviteSelfOrMemberOfAnotherParty() {
        PartyService s = new PartyService();
        assertEquals(SELF, s.invite(a, a));
        s.invite(a, b);
        s.accept(b);
        assertEquals(ALREADY_IN_PARTY, s.invite(c, b));
        assertEquals(NOT_IN_PARTY, s.leave(c));
    }

    @Test
    void partyIsCappedAtFour() {
        PartyService s = new PartyService();
        for (int i = 0; i < 3; i++) {
            UUID x = UUID.randomUUID();
            assertEquals(OK, s.invite(a, x));
            assertEquals(OK, s.accept(x));
        }
        assertEquals(FULL, s.invite(a, b));
    }
}
