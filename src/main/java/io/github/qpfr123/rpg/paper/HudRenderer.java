package io.github.qpfr123.rpg.paper;

import io.github.qpfr123.rpg.profile.PlayerProfile;
import io.github.qpfr123.rpg.stat.StatSnapshot;
import io.github.qpfr123.rpg.ui.HudLayout;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 하단 HUD(HP·MP 바, 레벨, 미사용 포인트, 짧은 알림)를 액션바로 그린다. 4틱마다 다시 보내 화면에 계속 남게 한다.
 * 액션바는 하나뿐이라 다른 기능의 짧은 알림도 여기({@link #notice})로 보내 HUD와 함께 그린다.
 */
public final class HudRenderer implements Runnable {
    private static final long NOTICE_MILLIS = 2000;

    private record Notice(String text, int color, long until) {}

    private final ProfileService profiles;
    private final Map<UUID, Notice> notices = new HashMap<>();

    public HudRenderer(ProfileService profiles) {
        this.profiles = profiles;
    }

    /** 2초 동안 HUD 위에 보이는 짧은 알림(메인 스레드). */
    public void notice(Player player, String text, int color) {
        notices.put(player.getUniqueId(), new Notice(text, color, System.currentTimeMillis() + NOTICE_MILLIS));
        render(player);
    }

    public void forget(UUID player) {
        notices.remove(player);
    }

    @Override
    public void run() {
        for (Player p : Bukkit.getOnlinePlayers()) render(p);
    }

    public void render(Player player) {
        PlayerProfile p = profiles.get(player.getUniqueId()).orElse(null);
        if (p == null || player.isDead()) return;
        StatSnapshot s = profiles.stats(player);
        Notice n = notices.get(player.getUniqueId());
        if (n != null && n.until() < System.currentTimeMillis()) {
            notices.remove(player.getUniqueId());
            n = null;
        }
        HudLayout.State state = new HudLayout.State(p.hp(), s.maxHp(), p.mp(), s.maxMp(), p.shield(), p.level(), p.unspentPoints(),
                n == null ? null : n.text(), n == null ? 0xFFFFFF : n.color());
        player.sendActionBar(toComponent(HudLayout.layout(state)));
    }

    static Component toComponent(List<HudLayout.Run> runs) {
        TextComponent.Builder line = Component.text();
        for (HudLayout.Run r : runs) {
            TextComponent.Builder part = Component.text().content(r.text());
            if (r.font() != null) part.font(Key.key(r.font()));
            if (r.color() >= 0) part.color(TextColor.color(r.color()));
            if (!r.shadow()) part.shadowColor(ShadowColor.none());
            line.append(part.build());
        }
        return line.build();
    }
}
