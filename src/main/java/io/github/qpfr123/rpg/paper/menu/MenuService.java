package io.github.qpfr123.rpg.paper.menu;

import io.github.qpfr123.rpg.paper.MainThread;
import io.github.qpfr123.rpg.paper.ProfileService;
import io.github.qpfr123.rpg.paper.RewardService;
import io.github.qpfr123.rpg.paper.RpgCommand;
import io.github.qpfr123.rpg.profile.PlayerProfile;
import io.github.qpfr123.rpg.stat.PointAllocationPolicy;
import io.github.qpfr123.rpg.stat.SecondaryStat;
import io.github.qpfr123.rpg.stat.StatSnapshot;
import io.github.qpfr123.rpg.storage.DbExecutor;
import io.github.qpfr123.rpg.ui.Glyphs;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * /rpg 기능을 메뉴로: 메인(장비·스탯·보상) → 스탯(클릭 배분) / 장비(착용 장비 보기). 열기: /rpg, Shift+F.
 * 메뉴 안에서는 아이템을 옮길 수 없다(모든 클릭·드래그 취소 후 등록된 버튼만 실행).
 */
public final class MenuService implements Listener {
    /** 스탯 아이콘·+버튼 위치(열, 줄): scripts/gen-art.py STAT_SLOTS와 같다. +버튼은 오른쪽 칸. */
    static final int[][] STAT_SLOTS = {{1, 1}, {1, 2}, {1, 3}, {1, 4}, {5, 1}, {5, 2}, {5, 3}};
    static final int[] SUMMARY_SLOT = {5, 4};
    static final int MENU_BUTTON_ROW = 1;
    static final int SHIFT_AMOUNT = 10;
    private static final String[] STAT_MODELS = {"stat_strength", "stat_agility", "stat_resistance", "stat_vitality", "stat_focus",
            "stat_luck", "stat_spirit"};
    private static final String[] STAT_EFFECTS = {"공격력 증가", "공격 속도·이동 속도 증가", "방어율·상태 이상 저항 증가",
            "최대 HP·HP 재생 증가", "치명타 확률·피해 증가", "드롭률 증가", "최대 MP·MP 재생 증가"};

    private final ProfileService profiles;
    private final RewardService rewards;
    private final RpgCommand commands;
    private final DbExecutor db;
    private final MainThread main;

    public MenuService(ProfileService profiles, RewardService rewards, RpgCommand commands, DbExecutor db, MainThread main) {
        this.profiles = profiles;
        this.rewards = rewards;
        this.commands = commands;
        this.db = db;
        this.main = main;
    }

    // ---------------- 메인 ----------------

    public void openMain(Player player) {
        if (profiles.get(player.getUniqueId()).isEmpty()) {
            player.sendMessage(Component.text("RPG 데이터를 불러오는 중입니다.", NamedTextColor.GRAY));
            return;
        }
        RpgMenu m = new RpgMenu("main", Glyphs.GUI_MENU, "메인 메뉴");
        button(m, 0, MenuIcons.icon("blank", Component.text("장비", NamedTextColor.GOLD),
                MenuIcons.line("착용 중인 RPG 장비를 봅니다.", NamedTextColor.GRAY)), (p, t) -> openEquipment(p));
        button(m, 3, statsButton(player), (p, t) -> openStats(p));
        button(m, 6, rewardButton(-1), (p, t) -> {
            p.closeInventory();
            rewards.claimAll(p);
        });
        m.set(RpgMenu.slot(8, 0), close(), (p, t) -> p.closeInventory());
        player.openInventory(m.getInventory());
        // 대기 중인 보상 수는 DB에서 읽어 와 버튼에 표시한다(메인 스레드는 기다리지 않는다).
        main.then(db.submit("open rewards count", d -> d.openRewards(player.getUniqueId()).size()), n -> {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof RpgMenu open && open == m) {
                ItemStack item = rewardButton(n);
                for (int r = 0; r < 3; r++) for (int c = 6; c < 9; c++) m.getInventory().setItem(RpgMenu.slot(c, MENU_BUTTON_ROW + r), item);
            }
        });
    }

    private ItemStack statsButton(Player player) {
        PlayerProfile p = profiles.get(player.getUniqueId()).orElseThrow();
        return MenuIcons.icon("blank", Component.text("스탯", NamedTextColor.AQUA),
                MenuIcons.line("Lv " + p.level() + " · 남은 포인트 " + p.unspentPoints(), NamedTextColor.YELLOW),
                MenuIcons.line("스탯을 확인하고 포인트를 배분합니다.", NamedTextColor.GRAY));
    }

    private static ItemStack rewardButton(int pending) {
        String status = pending < 0 ? "대기 중인 보상 확인 중..." : pending == 0 ? "받을 보상이 없습니다." : "받을 보상 " + pending + "건";
        return MenuIcons.icon("blank", Component.text("보상 수령", NamedTextColor.GOLD),
                MenuIcons.line(status, pending > 0 ? NamedTextColor.YELLOW : NamedTextColor.GRAY),
                MenuIcons.line("클릭하면 쌓인 보상(EXP·아이템)을 받습니다.", NamedTextColor.GRAY));
    }

    /** 큰 버튼 한 개 = 3x3칸(열 col..col+2, 줄 MENU_BUTTON_ROW..+2). */
    private static void button(RpgMenu m, int col, ItemStack item, RpgMenu.Action action) {
        for (int r = 0; r < 3; r++) for (int c = col; c < col + 3; c++) m.set(RpgMenu.slot(c, MENU_BUTTON_ROW + r), item, action);
    }

    private static ItemStack close() {
        return MenuIcons.icon("close", Component.text("닫기", NamedTextColor.RED));
    }

    private static ItemStack back() {
        return MenuIcons.icon("back", Component.text("메인 메뉴로", NamedTextColor.GRAY));
    }

    // ---------------- 스탯 ----------------

    public void openStats(Player player) {
        PlayerProfile p = profiles.get(player.getUniqueId()).orElse(null);
        if (p == null) return;
        StatSnapshot s = profiles.stats(player);
        int cap = PointAllocationPolicy.singleStatCap(p.level());
        RpgMenu m = new RpgMenu("stats", Glyphs.GUI_STATS, "스탯 · 남은 포인트 " + p.unspentPoints());
        SecondaryStat[] stats = SecondaryStat.values();
        for (int i = 0; i < stats.length; i++) {
            SecondaryStat st = stats[i];
            int value = p.allocation().get(st);
            int col = STAT_SLOTS[i][0], row = STAT_SLOTS[i][1];
            m.set(RpgMenu.slot(col, row), MenuIcons.icon(STAT_MODELS[i], value, Component.text(st.displayName() + " " + value, NamedTextColor.GOLD),
                    List.of(MenuIcons.line(STAT_EFFECTS[i], NamedTextColor.GRAY),
                            MenuIcons.line("투자 " + value + " / 상한 " + cap, NamedTextColor.YELLOW))), null);
            m.set(RpgMenu.slot(col + 1, row), MenuIcons.icon("plus", Component.text(st.displayName() + " 올리기", NamedTextColor.GREEN),
                    MenuIcons.line("클릭: +1", NamedTextColor.GRAY),
                    MenuIcons.line("쉬프트 클릭: +" + SHIFT_AMOUNT, NamedTextColor.GRAY),
                    MenuIcons.line("남은 포인트 " + p.unspentPoints(), NamedTextColor.YELLOW)), (pl, type) -> allocate(pl, st, type));
        }
        List<Component> lore = new ArrayList<>();
        for (String line : RpgCommand.summary(s)) lore.add(MenuIcons.line(line, NamedTextColor.WHITE));
        m.set(RpgMenu.slot(SUMMARY_SLOT[0], SUMMARY_SLOT[1]), MenuIcons.icon("summary", 1,
                Component.text("능력치 요약 (Lv " + p.level() + ")", NamedTextColor.AQUA), lore), null);
        m.set(RpgMenu.slot(0, 0), back(), (pl, t) -> openMain(pl));
        m.set(RpgMenu.slot(8, 0), close(), (pl, t) -> pl.closeInventory());
        player.openInventory(m.getInventory());
    }

    private void allocate(Player player, SecondaryStat stat, ClickType type) {
        PlayerProfile p = profiles.get(player.getUniqueId()).orElse(null);
        if (p == null) return;
        int amount = 1;
        if (type.isShiftClick()) {
            int room = PointAllocationPolicy.singleStatCap(p.level()) - p.allocation().get(stat);
            amount = Math.max(1, Math.min(SHIFT_AMOUNT, Math.min(p.unspentPoints(), room)));
        }
        if (commands.allocate(player, p, stat, amount)) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
        }
        openStats(player); // 제목(남은 포인트)과 수치를 새로 그린다
    }

    // ---------------- 장비 ----------------

    public void openEquipment(Player player) {
        RpgMenu m = new RpgMenu("equipment", Glyphs.GUI_EQUIPMENT, "장비");
        EntityEquipment eq = player.getEquipment();
        ItemStack[] armor = {eq.getHelmet(), eq.getChestplate(), eq.getLeggings(), eq.getBoots()};
        String[] armorNames = {"투구", "갑옷", "각반", "신발"};
        for (int i = 0; i < 4; i++) m.set(RpgMenu.slot(1, 1 + i), shown(armor[i], armorNames[i]), null);
        m.set(RpgMenu.slot(7, 2), shown(eq.getItemInMainHand(), "주 무기"), null);
        m.set(RpgMenu.slot(7, 3), shown(eq.getItemInOffHand(), "보조 손"), null);
        m.set(RpgMenu.slot(0, 0), back(), (pl, t) -> openMain(pl));
        m.set(RpgMenu.slot(8, 0), close(), (pl, t) -> pl.closeInventory());
        player.openInventory(m.getInventory());
    }

    private static ItemStack shown(ItemStack item, String slotName) {
        if (item == null || item.getType() == Material.AIR) {
            return MenuIcons.icon("blank", Component.text(slotName + ": 비어 있음", NamedTextColor.DARK_GRAY));
        }
        return item.clone();
    }

    // ---------------- 입력 ----------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof RpgMenu menu)) return;
        event.setCancelled(true); // 메뉴가 열려 있는 동안은 어떤 아이템도 움직이지 않는다
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != menu.getInventory()) return;
        RpgMenu.Action action = menu.actionAt(event.getSlot());
        if (action != null) main.nextTick(() -> {
            if (player.isOnline()) action.click(player, event.getClick());
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof RpgMenu) event.setCancelled(true);
    }

    /** Shift+F(손 바꾸기)로 메뉴 열기. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (!event.getPlayer().isSneaking()) return;
        event.setCancelled(true);
        openMain(event.getPlayer());
    }
}
