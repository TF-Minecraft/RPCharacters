package net.tfminecraft.rpcharacters.plots;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.CommandSender;

/** Staff tools: inspect, read the log of, correct and end plots. Every change is logged. */
final class PlotStaff {
    static final List<String> COMMANDS = List.of("approve", "deny", "cancel", "day", "list", "info", "log", "set", "kick", "unban", "reload");
    private static final int PAGE = 10;

    private PlotStaff() {}

    static boolean handle(CommandSender sender, String sub, String[] args) {
        if (!sender.hasPermission(PlotConfig.staffPermission())) {
            sender.sendMessage("§cYou do not have permission to manage plots.");
            return true;
        }
        switch (sub) {
            case "list" -> list(sender);
            case "reload" -> Plots.reload(sender);
            case "approve" -> {
                if (args.length != 3) return usage(sender, "approve <code> <target player>");
                PlotService.approve(sender, args[1], args[2]);
            }
            case "log" -> {
                if (args.length < 2 || args.length > 3) return usage(sender, "log <code> [page]");
                log(sender, args[1], args.length == 3 ? args[2] : "1");
            }
            case "set" -> {
                if (args.length != 4) return usage(sender, "set <code> <progress|strength|secrecy|huntdays|phase> <value>");
                set(sender, args[1], args[2].toLowerCase(Locale.ROOT), args[3]);
            }
            case "kick" -> {
                if (args.length < 3) return usage(sender, "kick <code> <member>");
                kick(sender, args[1], String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)));
            }
            default -> {
                if (args.length != 2) return usage(sender, sub + " <code>");
                switch (sub) {
                    case "deny" -> PlotService.deny(sender, args[1]);
                    case "cancel" -> PlotService.cancel(sender, args[1]);
                    case "day" -> PlotService.passDay(sender, args[1]);
                    case "unban" -> unban(sender, args[1]);
                    default -> info(sender, args[1]);
                }
            }
        }
        return true;
    }

    private static boolean usage(CommandSender sender, String text) {
        sender.sendMessage("§7/plot " + text);
        return true;
    }

    private static void list(CommandSender sender) {
        var plots = PlotService.store().plots.stream().filter(plot -> plot.phase.active()).toList();
        if (plots.isEmpty()) sender.sendMessage("§7No plots are running.");
        for (Plot plot : plots) sender.sendMessage("§e" + plot.code + "§7 " + PlotLog.who(plot.master) + " -> "
                + (plot.target == null ? "?" : PlotLog.who(plot.target)) + ": " + plot.phase.label());
    }

    static void info(CommandSender sender, String code) {
        Plot plot = PlotService.store().plot(code);
        if (plot == null) {
            sender.sendMessage("§cNo plot has that code.");
            return;
        }
        sender.sendMessage("§e" + plot.code + "§7: " + PlotService.summary(plot) + (plot.outcome == null ? "" : " (" + plot.outcome + ")"));
        sender.sendMessage("§7Master: " + PlotLog.who(plot.master) + ", target: " + (plot.target == null ? "none yet" : PlotLog.who(plot.target)));
        for (PlotMember member : plot.members) sender.sendMessage("§7- " + PlotLog.who(member.character) + ": " + member.roleLabel()
                + (member.huntingSince > 0 ? ", hunting" : ""));
        for (PlotCharacter informed : plot.informed) sender.sendMessage("§8- " + PlotLog.who(informed) + ": informed");
        if (plot.target == null) return;
        var protection = PlotDefense.protection(plot);
        var guard = PlotDefense.guard(plot.target);
        sender.sendMessage("§7Protection " + Math.round(protection.value()) + " by " + guard.kind() + " " + PlotLog.who(guard.character())
                + ", betrayal " + protection.betrayal() + ", known to protector: " + plot.guardKnows + ", known to target: "
                + plot.discovered + (plot.exposed ? " (exposed)" : "") + ", conspirators investigated: " + plot.investigated.size());
    }

    private static void log(CommandSender sender, String code, String pageText) {
        List<String> lines;
        int page;
        try {
            page = Math.max(1, Integer.parseInt(pageText));
            lines = PlotLog.read(code);
        } catch (NumberFormatException | IOException error) {
            sender.sendMessage("§cCould not read that log page.");
            return;
        }
        if (lines.isEmpty()) {
            sender.sendMessage("§cNo log for that code.");
            return;
        }
        int pages = (lines.size() + PAGE - 1) / PAGE;
        page = Math.min(page, pages);
        // Newest first: page 1 shows the latest lines.
        int end = lines.size() - (page - 1) * PAGE;
        sender.sendMessage("§e" + code.toUpperCase(Locale.ROOT) + "§7 log, page " + page + " of " + pages + ":");
        for (String line : lines.subList(Math.max(0, end - PAGE), end)) sender.sendMessage("§7" + line);
    }

    private static Plot running(CommandSender sender, String code) {
        Plot plot = PlotService.store().plot(code);
        if (plot == null || !plot.phase.active()) sender.sendMessage("§cNo running plot has that code.");
        return plot == null || !plot.phase.active() ? null : plot;
    }

    private static void set(CommandSender sender, String code, String field, String value) {
        Plot plot = running(sender, code);
        if (plot == null) return;
        try {
            switch (field) {
                case "progress" -> plot.progress = Math.max(0, Math.min(100, Double.parseDouble(value)));
                case "strength" -> plot.strength = Math.max(0, Double.parseDouble(value));
                case "secrecy" -> plot.secrecy = Math.max(0, Math.min(100, Double.parseDouble(value)));
                case "huntdays" -> plot.huntDays = Math.max(0, Integer.parseInt(value));
                case "phase" -> {
                    PlotPhase phase = PlotPhase.valueOf(value.toUpperCase(Locale.ROOT));
                    if (!phase.active() || phase == PlotPhase.REQUESTED) throw new IllegalArgumentException(value);
                    plot.phase = phase;
                    plot.phaseStartedAt = plot.huntDayStartedAt = PlotService.now();
                    plot.nextDayAt = PlotService.now() + PlotConfig.DAY;
                }
                default -> {
                    sender.sendMessage("§cUnknown field. Use progress, strength, secrecy, huntdays or phase.");
                    return;
                }
            }
        } catch (IllegalArgumentException invalid) {
            sender.sendMessage("§cInvalid value for " + field + ".");
            return;
        }
        PlotService.save();
        PlotLog.write(plot, "STAFF-SET", sender.getName() + " set " + field + " to " + value);
        sender.sendMessage("§aPlot " + plot.code + ": " + PlotService.summary(plot));
    }

    private static void kick(CommandSender sender, String code, String name) {
        Plot plot = running(sender, code);
        if (plot == null) return;
        for (PlotMember member : plot.members)
            if (member.character.name.equalsIgnoreCase(name) || member.character.playerName.equalsIgnoreCase(name)) {
                PlotService.leaves(plot, member, "STAFF-KICK by " + sender.getName() + ":");
                PlotService.tell(member.character, "Staff have removed you from the plot" + PlotService.against(plot) + ".");
                PlotService.save();
                sender.sendMessage("§a" + member.character.name + " removed from plot " + plot.code + ".");
                return;
            }
        sender.sendMessage("§cNobody by that name is in that plot.");
    }

    /** Lifts the bans and the cooldown a plot left behind. */
    private static void unban(CommandSender sender, String code) {
        Plot plot = PlotService.store().plot(code);
        if (plot == null) {
            sender.sendMessage("§cNo plot has that code.");
            return;
        }
        int bans = 0;
        var iterator = PlotService.store().bans.entrySet().iterator();
        while (iterator.hasNext()) if (iterator.next().getValue().equalsIgnoreCase(plot.code)) {
            iterator.remove();
            bans++;
        }
        boolean cooldown = plot.target != null && PlotService.store().cooldowns.remove(PlotService.cooldownKey(plot.master, plot.target)) != null;
        PlotService.save();
        PlotLog.write(plot, "STAFF-UNBAN", sender.getName() + " lifted " + bans + " bans" + (cooldown ? " and the cooldown" : ""));
        sender.sendMessage("§aLifted " + bans + " bans" + (cooldown ? " and the retarget cooldown" : "") + " from plot " + plot.code + ".");
    }
}
