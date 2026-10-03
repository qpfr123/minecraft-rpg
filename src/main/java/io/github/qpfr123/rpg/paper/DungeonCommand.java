package io.github.qpfr123.rpg.paper;

import io.github.qpfr123.rpg.dungeon.DungeonDefinition;
import io.github.qpfr123.rpg.party.PartyService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /dungeon enter <id> | leave | list  ·  /party invite <플레이어> | accept | leave | list */
public final class DungeonCommand implements TabExecutor {
    private final DungeonService dungeons;
    private final PartyService parties;

    public DungeonCommand(DungeonService dungeons, PartyService parties) {
        this.dungeons = dungeons;
        this.parties = parties;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("플레이어만 사용할 수 있습니다.");
            return true;
        }
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        if (command.getName().equals("party")) {
            party(player, sub, args);
            return true;
        }
        switch (sub) {
            case "enter" -> {
                if (args.length < 2) player.sendMessage("/dungeon enter <던전ID>");
                else dungeons.enter(player, args[1]);
            }
            case "leave" -> dungeons.leave(player);
            default -> {
                for (DungeonDefinition d : dungeons.registry().all()) {
                    player.sendMessage(Component.text(d.id() + " — " + d.displayName() + " (최대 " + d.maxPlayers() + "명)", NamedTextColor.YELLOW));
                }
                player.sendMessage(Component.text("입장: /dungeon enter <던전ID> (파티라면 리더가 입장시키면 함께 들어갑니다)", NamedTextColor.GRAY));
            }
        }
        return true;
    }

    private void party(Player player, String sub, String[] args) {
        UUID me = player.getUniqueId();
        switch (sub) {
            case "invite" -> {
                Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : null;
                if (target == null) {
                    player.sendMessage("/party invite <접속 중 플레이어>");
                    return;
                }
                PartyService.Result r = parties.invite(me, target.getUniqueId());
                if (r == PartyService.Result.OK) {
                    player.sendMessage(Component.text(target.getName() + " 님을 초대했습니다.", NamedTextColor.GREEN));
                    target.sendMessage(Component.text(player.getName() + " 님이 파티에 초대했습니다. /party accept", NamedTextColor.GOLD));
                } else {
                    player.sendMessage(Component.text(message(r), NamedTextColor.RED));
                }
            }
            case "accept" -> {
                PartyService.Result r = parties.accept(me);
                if (r == PartyService.Result.OK) {
                    for (UUID m : parties.partyOf(me).orElseThrow().members()) {
                        Player p = Bukkit.getPlayer(m);
                        if (p != null) p.sendMessage(Component.text(player.getName() + " 님이 파티에 합류했습니다.", NamedTextColor.GREEN));
                    }
                } else {
                    player.sendMessage(Component.text(message(r), NamedTextColor.RED));
                }
            }
            case "leave" -> {
                PartyService.Result r = parties.leave(me);
                player.sendMessage(Component.text(r == PartyService.Result.OK ? "파티에서 나왔습니다." : message(r),
                        r == PartyService.Result.OK ? NamedTextColor.GRAY : NamedTextColor.RED));
            }
            default -> parties.partyOf(me).ifPresentOrElse(p -> {
                List<String> names = new ArrayList<>();
                for (UUID m : p.members()) {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(m);
                    names.add((m.equals(p.leader()) ? "★" : "") + op.getName());
                }
                player.sendMessage(Component.text("파티: " + String.join(", ", names), NamedTextColor.YELLOW));
            }, () -> player.sendMessage(Component.text("파티가 없습니다. /party invite <플레이어>", NamedTextColor.GRAY)));
        }
    }

    private static String message(PartyService.Result r) {
        return switch (r) {
            case ALREADY_IN_PARTY -> "이미 파티에 속해 있습니다.";
            case NOT_LEADER -> "파티 리더만 초대할 수 있습니다.";
            case FULL -> "파티가 가득 찼습니다(최대 " + PartyService.MAX_SIZE + "명).";
            case NO_INVITE -> "받은 초대가 없습니다.";
            case NOT_IN_PARTY -> "파티에 속해 있지 않습니다.";
            case SELF -> "자기 자신은 초대할 수 없습니다.";
            case OK -> "";
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equals("party")) {
            if (args.length == 1) return RpgCommand.filter(List.of("invite", "accept", "leave", "list"), args[0]);
            return args.length == 2 && args[0].equalsIgnoreCase("invite") ? null : List.of();
        }
        if (args.length == 1) return RpgCommand.filter(List.of("enter", "leave", "list"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("enter")) {
            List<String> ids = new ArrayList<>();
            dungeons.registry().all().forEach(d -> ids.add(d.id()));
            return RpgCommand.filter(ids, args[1]);
        }
        return List.of();
    }
}
