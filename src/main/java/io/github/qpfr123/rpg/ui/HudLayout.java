package io.github.qpfr123.rpg.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 액션바 HUD 배치(순수 계산). 액션바는 줄 전체 폭으로 가운데 정렬되므로 줄의 총 진행폭을 핫바 폭(182)에 맞춘다.
 * <pre>
 * x 0     79 80   101 102     181   (줄 시작 = 핫바 왼쪽 끝)
 *   [ HP 바 ] [레벨 원] [ MP 바 ]
 * </pre>
 * 바·글자의 높이는 폰트의 ascent로 내려 바닐라 하트·배고픔 줄 자리에 그린다.
 */
public final class HudLayout {
    public static final int LINE_WIDTH = 182;
    static final int HP_X = 0;
    static final int ORB_X = LINE_WIDTH / 2 - Glyphs.ORB_WIDTH / 2;
    static final int MP_X = LINE_WIDTH - Glyphs.BAR_WIDTH;

    /** 한 조각: 폰트(null = 기본 글꼴), 글자, 색(0xRRGGBB, -1 = 기본), 그림자 여부. */
    public record Run(String font, String text, int color, boolean shadow) {}

    public record State(double hp, int maxHp, double mp, int maxMp, double shield, int level, int unspentPoints,
                        String notice, int noticeColor) {}

    private final List<Run> runs = new ArrayList<>();
    private int cursor;

    public static List<Run> layout(State s) {
        HudLayout l = new HudLayout();
        l.glyph(HP_X, Glyphs.bar(Glyphs.HP_BASE, s.hp(), s.maxHp()), Glyphs.BAR_WIDTH);
        l.glyph(ORB_X, (char) Glyphs.ORB, Glyphs.ORB_WIDTH);
        l.glyph(MP_X, Glyphs.bar(Glyphs.MP_BASE, s.mp(), s.maxMp()), Glyphs.BAR_WIDTH);
        String hp = (int) Math.ceil(Math.max(0, s.hp())) + "/" + s.maxHp();
        if (s.shield() > 0) hp += "+" + (int) Math.ceil(s.shield());
        l.centered(Glyphs.FONT_HUD_TEXT, hp, HP_X + Glyphs.BAR_WIDTH / 2, 0xFFFFFF);
        l.centered(Glyphs.FONT_HUD_TEXT, (int) Math.floor(Math.max(0, s.mp())) + "/" + s.maxMp(), MP_X + Glyphs.BAR_WIDTH / 2, 0xFFFFFF);
        l.centered(Glyphs.FONT_HUD_TEXT, String.valueOf(s.level()), LINE_WIDTH / 2, 0xF0D080);
        if (s.unspentPoints() > 0) l.centered(Glyphs.FONT_HUD_POINTS, "+" + s.unspentPoints(), LINE_WIDTH / 2, 0xFFD24A);
        if (s.notice() != null && !s.notice().isEmpty()) {
            int w = Glyphs.estimateWidth(s.notice());
            l.moveTo(LINE_WIDTH / 2 - w / 2);
            l.runs.add(new Run(null, s.notice(), s.noticeColor(), true));
            l.cursor += w;
        }
        l.moveTo(LINE_WIDTH);
        return List.copyOf(l.runs);
    }

    /** 줄의 총 진행폭(검증용). 기본 글꼴 조각은 추정치를 쓴다. */
    public static int advance(List<Run> runs) {
        int w = 0;
        for (Run r : runs) w += advance(r);
        return w;
    }

    static int advance(Run r) {
        if (Glyphs.FONT_SPACE.equals(r.font())) {
            int w = 0;
            for (char c : r.text().toCharArray()) {
                int i = c >= Glyphs.SPACE_POS ? c - Glyphs.SPACE_POS : c - Glyphs.SPACE_NEG;
                w += c >= Glyphs.SPACE_POS ? (1 << i) : -(1 << i);
            }
            return w;
        }
        if (Glyphs.FONT_HUD.equals(r.font())) {
            int w = 0;
            for (char c : r.text().toCharArray()) w += (c == Glyphs.ORB ? Glyphs.ORB_WIDTH : Glyphs.BAR_WIDTH) + 1;
            return w;
        }
        if (r.font() == null) return Glyphs.estimateWidth(r.text());
        return Glyphs.asciiWidth(r.text());
    }

    private void glyph(int x, char c, int width) {
        moveTo(x);
        runs.add(new Run(Glyphs.FONT_HUD, String.valueOf(c), 0xFFFFFF, false));
        cursor += width + 1;
    }

    /** 글자 가운데를 center에 둔다(마지막 글자 뒤 1픽셀 간격은 시각 폭에서 뺀다). */
    private void centered(String font, String text, int center, int color) {
        int w = Glyphs.asciiWidth(text);
        moveTo(center - (w - 1) / 2);
        runs.add(new Run(font, text, color, true));
        cursor += w;
    }

    private void moveTo(int x) {
        if (x != cursor) runs.add(new Run(Glyphs.FONT_SPACE, Glyphs.space(x - cursor), -1, false));
        cursor = x;
    }
}
