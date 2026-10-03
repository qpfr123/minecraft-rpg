package io.github.qpfr123.rpg.paper.menu;

import io.github.qpfr123.rpg.ui.IconModels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** 메뉴 아이콘 아이템: 리소스팩의 rpg:&lt;이름&gt; 아이템 모델을 쓴다(목록은 ResourcePackTest가 대조). */
public final class MenuIcons {
    private MenuIcons() {}


    public static ItemStack icon(String model, int amount, Component name, List<Component> lore) {
        if (!IconModels.ALL.contains(model)) throw new IllegalArgumentException("unknown icon model " + model);
        ItemStack item = new ItemStack(Material.PAPER, Math.max(1, Math.min(99, amount)));
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey("rpg", model));
        meta.setMaxStackSize(99);
        meta.displayName(plain(name));
        List<Component> lines = new ArrayList<>();
        for (Component c : lore) lines.add(plain(c));
        meta.lore(lines);
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack icon(String model, Component name, Component... lore) {
        return icon(model, 1, name, List.of(lore));
    }

    static Component plain(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }

    static Component line(String text, NamedTextColor color) {
        return Component.text(text, color);
    }
}
