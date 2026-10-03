package io.github.qpfr123.rpg.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudLayoutTest {
    private static HudLayout.State state(String notice) {
        return new HudLayout.State(512.4, 1020, 33.9, 250, 0, 50, 3, notice, 0xFFFFFF);
    }

    @Test
    void lineAlwaysSpansHotbarWidthSoBarsStayPut() {
        assertEquals(182, HudLayout.advance(HudLayout.layout(state(null))));
        assertEquals(182, HudLayout.advance(HudLayout.layout(state("치명타! 123"))));
        assertEquals(182, HudLayout.advance(HudLayout.layout(new HudLayout.State(0, 1, 0, 0, 5, 1, 0, "", 0))));
    }

    @Test
    void barsAreDrawnAtFixedPositions() {
        List<HudLayout.Run> runs = HudLayout.layout(state(null));
        int x = 0;
        int[] glyphX = new int[3];
        int g = 0;
        for (HudLayout.Run r : runs) {
            if (Glyphs.FONT_HUD.equals(r.font())) glyphX[g++] = x;
            x += HudLayout.advance(r);
        }
        assertEquals(0, glyphX[0]);
        assertEquals(80, glyphX[1]);   // 레벨 원: 가운데(91) - 11
        assertEquals(102, glyphX[2]);  // MP 바: 182 - 80
        assertEquals(3, g);
    }

    @Test
    void barStateMatchesFillRatio() {
        assertEquals((char) Glyphs.HP_BASE, Glyphs.bar(Glyphs.HP_BASE, 0, 100));
        assertEquals((char) (Glyphs.HP_BASE + 40), Glyphs.bar(Glyphs.HP_BASE, 100, 100));
        assertEquals((char) (Glyphs.HP_BASE + 1), Glyphs.bar(Glyphs.HP_BASE, 0.1, 100), "살아 있으면 최소 한 칸");
        assertEquals((char) (Glyphs.HP_BASE + 39), Glyphs.bar(Glyphs.HP_BASE, 99.9, 100), "가득이 아니면 꽉 채우지 않음");
        assertEquals((char) (Glyphs.HP_BASE + 20), Glyphs.bar(Glyphs.HP_BASE, 50, 100));
    }

    @Test
    void spaceComposesExactAdvance() {
        for (int n : new int[] {-300, -1, 0, 1, 7, 182, 1023}) {
            assertEquals(n, HudLayout.advance(new HudLayout.Run(Glyphs.FONT_SPACE, Glyphs.space(n), -1, false)));
        }
        assertTrue(Glyphs.space(0).isEmpty());
    }
}
