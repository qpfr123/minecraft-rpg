package io.github.qpfr123.rpg.ui;

/**
 * 리소스팩 글리프 약속(scripts/gen-art.py와 같아야 한다. ResourcePackTest가 대조).
 * 사용자 영역 문자(U+E000~)에 그림을 배정한다.
 */
public final class Glyphs {
    private Glyphs() {}

    public static final String FONT_HUD = "rpg:hud";
    public static final String FONT_HUD_TEXT = "rpg:hud_text";
    public static final String FONT_HUD_POINTS = "rpg:hud_points";
    public static final String FONT_SPACE = "rpg:space";
    public static final String FONT_GUI = "rpg:gui";

    /** HP·MP 바: 채움 단계 0..BAR_STATES-1, 폭 BAR_WIDTH(글리프 진행폭은 +1). */
    public static final int BAR_STATES = 41;
    public static final int BAR_WIDTH = 80;
    public static final int HP_BASE = 0xE100;
    public static final int MP_BASE = 0xE140;
    public static final int ORB = 0xE180;
    public static final int ORB_WIDTH = 22;

    /** GUI 배경(176x222): 메인 메뉴, 스탯, 장비. */
    public static final char GUI_MENU = '';
    public static final char GUI_STATS = '';
    public static final char GUI_EQUIPMENT = '';
    public static final int GUI_WIDTH = 176;

    /** 공백: U+E800+i = -(2^i), U+E810+i = +(2^i), i = 0..9. */
    public static final int SPACE_NEG = 0xE800;
    public static final int SPACE_POS = 0xE810;
    public static final int SPACE_BITS = 10;

    /** 채움 비율(0..1)에 해당하는 바 글리프. 0이 아니면 최소 1단계, 가득이 아니면 최대 마지막-1. */
    public static char bar(int base, double current, double max) {
        int last = BAR_STATES - 1;
        int state;
        if (max <= 0 || current <= 0) state = 0;
        else if (current >= max) state = last;
        else state = Math.max(1, Math.min(last - 1, (int) Math.round(current / max * last)));
        return (char) (base + state);
    }

    /** n픽셀 이동하는 공백 문자열(rpg:space). */
    public static String space(int n) {
        StringBuilder sb = new StringBuilder();
        int base = n < 0 ? SPACE_NEG : SPACE_POS;
        int v = Math.abs(n);
        for (int i = SPACE_BITS - 1; i >= 0 && v > 0; i--) {
            int step = 1 << i;
            while (v >= step) {
                sb.append((char) (base + i));
                v -= step;
            }
        }
        return sb.toString();
    }

    /** 바닐라 ascii 글꼴의 글자 진행폭(픽셀, 글자 폭 + 1). 클라이언트 ascii.png에서 잰 값. HUD 숫자 배치에 쓴다. */
    public static int asciiAdvance(char c) {
        return switch (c) {
            case ' ' -> 4;
            case '!', '\'', ',', '.', ':', ';', 'i', '|' -> 2;
            case '`', 'l' -> 3;
            case '"', '(', ')', '*', 'I', '[', ']', 't', '{', '}' -> 4;
            case '<', '>', 'f', 'k' -> 5;
            case '@', '~' -> 7;
            default -> 6;
        };
    }

    public static int asciiWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) w += asciiAdvance(s.charAt(i));
        return w;
    }

    /** 기본 글꼴 문장 폭 추정(한글 9, 그 외 ascii 표). 짧은 알림 가운데 맞춤에만 쓴다. */
    public static int estimateWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            w += c >= 0xAC00 && c <= 0xD7A3 ? 9 : c < 0x80 ? asciiAdvance(c) : 7;
        }
        return w;
    }
}
