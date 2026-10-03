package io.github.qpfr123.rpg.paper.menu;

import io.github.qpfr123.rpg.ui.Glyphs;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * 상자 화면(6줄)을 쓰는 RPG 메뉴 한 장. 제목에 배경 그림 글리프를 넣어 상자 그림을 덮는다.
 * 모든 클릭은 {@link MenuService}가 막고, 등록된 칸의 동작만 실행한다.
 */
public final class RpgMenu implements InventoryHolder {
    public static final int ROWS = 6;
    static final TextColor TITLE_COLOR = TextColor.color(0xE8C08C);

    public interface Action {
        void click(Player player, ClickType type);
    }

    public final String kind;
    private final Inventory inventory;
    private final Map<Integer, Action> actions = new HashMap<>();

    RpgMenu(String kind, char background, String title) {
        this.kind = kind;
        this.inventory = Bukkit.createInventory(this, ROWS * 9, title(background, title));
    }

    /** [-8 공백][배경 176px][-169 공백으로 제목 자리 복귀][제목]. 배경 왼쪽 끝 = 상자 왼쪽 끝. */
    static Component title(char background, String text) {
        return Component.text()
                .append(Component.text(Glyphs.space(-8)).font(Key.key(Glyphs.FONT_SPACE)))
                .append(Component.text(String.valueOf(background)).font(Key.key(Glyphs.FONT_GUI))
                        .color(TextColor.color(0xFFFFFF)).shadowColor(ShadowColor.none()))
                .append(Component.text(Glyphs.space(-(Glyphs.GUI_WIDTH + 1 - 8))).font(Key.key(Glyphs.FONT_SPACE)))
                .append(Component.text(text).color(TITLE_COLOR))
                .build();
    }

    public static int slot(int col, int row) {
        return row * 9 + col;
    }

    void set(int slot, ItemStack item, Action action) {
        inventory.setItem(slot, item);
        if (action != null) actions.put(slot, action);
        else actions.remove(slot);
    }

    Action actionAt(int slot) {
        return actions.get(slot);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
