package net.tfminecraft.rpcharacters.plots;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** /plot: the Plots menu, the same actions by command, and staff tools. */
public final class PlotCommand implements CommandExecutor, TabCompleter {
    private static final List<String> PLAYER = List.of("request", "accept", "decline", "invite", "inform", "role", "dismiss",
            "start", "launch", "hunt", "leave", "abandon", "approach", "confidant", "ward", "warn", "investigator", "status");

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (PlotStaff.COMMANDS.contains(sub)) return PlotStaff.handle(sender, sub, args);
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use plots. Staff: /plot " + String.join("|", PlotStaff.COMMANDS));
            return true;
        }
        String rest = String.join(" ", Arrays.copyOfRange(args, Math.min(1, args.length), args.length));
        switch (sub) {
            case "" -> PlotMenu.open(player);
            case "request" -> PlotService.request(player);
            case "accept", "decline" -> {
                if (args.length != 2) return usage(player, sub + " <code>");
                if (sub.equals("accept")) PlotService.accept(player, args[1]);
                else PlotService.decline(player, args[1]);
            }
            case "invite", "inform", "investigator", "ward", "warn" -> {
                if (rest.isBlank()) return usage(player, sub + " <player or character>");
                Player other = find(player, rest);
                if (other == null) return true;
                switch (sub) {
                    case "invite" -> PlotService.invite(player, other);
                    case "inform" -> PlotService.inform(player, other);
                    case "investigator" -> PlotDefense.toggleInvestigator(player, other);
                    case "ward" -> PlotDefense.toggleWard(player, other);
                    default -> warn(player, other);
                }
            }
            case "confidant" -> confidant(player, rest);
            case "role" -> role(player, args);
            case "dismiss" -> {
                PlotMember member = member(player, rest);
                if (member != null) PlotService.dismiss(player, member.character.characterId);
            }
            case "start" -> PlotService.begin(player);
            case "launch" -> PlotService.launch(player);
            case "hunt" -> PlotHunt.hunt(player);
            case "leave" -> PlotService.leave(player);
            case "abandon" -> {
                if (!rest.equalsIgnoreCase("confirm")) return usage(player, "abandon confirm");
                PlotService.abandon(player);
            }
            case "approach" -> {
                PlotApproach approach = PlotApproach.parse(rest);
                if (approach == null) return usage(player, "approach <offensive|neutral|defensive>");
                PlotService.setApproach(player, approach);
            }
            case "status" -> status(player);
            default -> usage(player, String.join("|", PLAYER));
        }
        return true;
    }

    private static boolean usage(CommandSender sender, String text) {
        sender.sendMessage("§7/plot " + text);
        return true;
    }

    /** An online player by exact account name, or the only one whose character goes by that name. */
    static Player find(Player sender, String input) {
        Player exact = Bukkit.getPlayerExact(input);
        if (exact != null) return exact;
        List<Player> matches = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            PlotCharacter character = PlotCharacters.active(online);
            if (character != null && (character.name.equalsIgnoreCase(input)
                    || ChatColor.stripColor(PlotCharacters.seenAs(online, character)).equalsIgnoreCase(input))) matches.add(online);
        }
        if (matches.size() == 1) return matches.getFirst();
        sender.sendMessage(matches.isEmpty() ? "§cNo online player goes by that name." : "§cSeveral characters share that name. Use their Minecraft name.");
        return null;
    }

    /** A Spymaster or confidant warns their charge; anyone else who knows of a plot warns its target in person. */
    private static void warn(Player player, Player other) {
        PlotCharacter ward = PlotCharacters.active(other);
        PlotCharacter me = PlotCharacters.active(player);
        var guard = ward == null ? null : PlotDefense.guard(ward);
        if (me != null && guard != null && guard.kind() != Defender.SELF && guard.character().is(me.characterId)) PlotDefense.warnWard(player, ward);
        else PlotDefense.warnTarget(player, other);
    }

    private static void confidant(Player player, String rest) {
        if (rest.equalsIgnoreCase("accept")) PlotDefense.acceptConfidant(player);
        else if (rest.equalsIgnoreCase("end")) PlotDefense.endConfidant(player);
        else if (rest.isBlank()) usage(player, "confidant <player or character>|accept|end");
        else {
            Player other = find(player, rest);
            if (other != null) PlotDefense.askConfidant(player, other);
        }
    }

    /** /plot role <name...> <assassin|hide|progress|strength|none>; the name may have spaces. */
    private static void role(Player player, String[] args) {
        if (args.length < 3) {
            usage(player, "role <member> <assassin|hide|progress|strength|none>");
            return;
        }
        String choice = args[args.length - 1].toLowerCase(Locale.ROOT);
        PlotRole role = choice.equals("assassin") ? PlotRole.ASSASSIN : choice.equals("none") ? PlotRole.UNASSIGNED
                : CollaboratorTask.parse(choice) != null ? PlotRole.COLLABORATOR : null;
        if (role == null) {
            usage(player, "role <member> <assassin|hide|progress|strength|none>");
            return;
        }
        PlotMember member = member(player, String.join(" ", Arrays.copyOfRange(args, 1, args.length - 1)));
        if (member != null) PlotService.assign(player, member.character.characterId, role, CollaboratorTask.parse(choice));
    }

    /** A member of the player's own plot, by character or Minecraft name. */
    private static PlotMember member(Player player, String name) {
        Plot plot = PlotService.mastered(player);
        if (plot == null) return null;
        for (PlotMember member : plot.members)
            if (member.character.name.equalsIgnoreCase(name) || member.character.playerName.equalsIgnoreCase(name)) return member;
        player.sendMessage("§cNobody by that name is in your plot.");
        return null;
    }

    private static void status(Player player) {
        PlotCharacter me = PlotService.character(player);
        if (me == null) return;
        var guard = PlotDefense.guard(me);
        player.sendMessage(PlotService.PREFIX + "Intrigue §e" + PlotService.intrigue(me) + "§7, plotting §e"
                + PlotService.offense(me) + "§7, defending §e" + PlotService.defense(me) + "§7. Watched over by §e"
                + (guard.kind() == Defender.SELF ? "yourself" : guard.character().name) + "§7.");
        Plot plot = PlotService.store().plotOf(me.characterId);
        if (plot != null) player.sendMessage(PlotService.PREFIX + (plot.target == null ? "Request " + plot.code + " awaits approval."
                : "Plot against §e" + plot.target.name + "§7: " + PlotService.summary(plot)));
        for (Plot against : PlotService.store().against(me.characterId))
            for (String line : against.knowledge.values()) player.sendMessage(PlotService.PREFIX + "§c" + line);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        List<String> options = new ArrayList<>(PLAYER);
        if (sender.hasPermission(PlotConfig.staffPermission())) options.addAll(PlotStaff.COMMANDS);
        return options.stream().filter(option -> option.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
    }
}
