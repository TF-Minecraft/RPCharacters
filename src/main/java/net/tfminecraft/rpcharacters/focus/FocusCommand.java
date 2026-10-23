package net.tfminecraft.rpcharacters.focus;

import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.RPCharacters;

public final class FocusCommand implements CommandExecutor, TabCompleter {
    public static final String PERMISSION = "rpchar.focus.admin";
    private static final List<String> VALUES = List.of("get", "set", "add", "remove", "reset");
    private static final String USAGE = "Usage: /focus <get|restore|reset> <player> | /focus <set|add|remove> <player> <points> | /focus reload";
    private final RPCharacters plugin;

    public FocusCommand(RPCharacters plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage("You do not have permission to use this command.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            boolean loaded = plugin.reloadFocusConfig();
            sender.sendMessage(loaded ? "Focus configuration reloaded."
                    : "Focus configuration did not reload. Check console for the configuration error.");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("restore")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("Player not found: " + args[1]);
                return true;
            }
            FocusService focus = RPCharacters.getFocusService();
            if (focus == null || !focus.restore(target)) {
                sender.sendMessage("Could not restore focus for " + target.getName()
                        + ". Check their active character and the focus service in console.");
                return true;
            }
            sender.sendMessage("Restored focus for " + target.getName() + " ("
                    + focus.getPoints(target) + "/" + focus.getMax() + ").");
            return true;
        }
        if (args.length >= 2 && VALUES.contains(args[0].toLowerCase(Locale.ROOT))) {
            String action = args[0].toLowerCase(Locale.ROOT);
            boolean numeric = List.of("set", "add", "remove").contains(action);
            if (args.length != (numeric ? 3 : 2)) {
                sender.sendMessage(USAGE);
                return true;
            }
            int amount = 0;
            if (numeric) {
                try {
                    amount = Integer.parseInt(args[2]);
                    if (action.equals("remove") && amount < 0) throw new NumberFormatException();
                } catch (NumberFormatException ex) {
                    sender.sendMessage("Use whole points within the integer range; remove requires a nonnegative amount.");
                    return true;
                }
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("Player not found: " + args[1]);
                return true;
            }
            FocusService focus = RPCharacters.getFocusService();
            if (focus == null || focus.getLoadedPoints(target) < 0) {
                sender.sendMessage("Focus unavailable for " + target.getName() + ". Check their active character.");
                return true;
            }
            if (!action.equals("get")) {
                if (action.equals("reset")) amount = focus.getMax();
                if (action.equals("remove")) amount = -amount;
                if (!focus.editPoints(target, amount, action.equals("add") || action.equals("remove"))) {
                    sender.sendMessage("Could not save focus for " + target.getName() + ". Check console.");
                    return true;
                }
            }
            sender.sendMessage("Focus for " + target.getName() + ": " + focus.getLoadedPoints(target) + "/" + focus.getMax() + ".");
            return true;
        }
        sender.sendMessage(USAGE);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) return List.of();
        if (args.length == 1) {
            return List.of("restore", "reload", "get", "set", "add", "remove", "reset").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("restore") || VALUES.contains(args[0].toLowerCase(Locale.ROOT)))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
        }
        return List.of();
    }
}
