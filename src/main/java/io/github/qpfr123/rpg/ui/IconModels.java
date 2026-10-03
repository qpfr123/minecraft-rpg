package io.github.qpfr123.rpg.ui;

import java.util.List;

/** 메뉴 아이콘 아이템 모델 rpg:&lt;이름&gt; 목록(scripts/gen-art.py가 만드는 것과 같아야 한다). */
public final class IconModels {
    private IconModels() {}

    public static final List<String> ALL = List.of("blank", "stat_strength", "stat_agility", "stat_resistance", "stat_vitality",
            "stat_focus", "stat_luck", "stat_spirit", "plus", "back", "close", "summary");
}
