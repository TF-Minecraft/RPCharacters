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
        sender.sendMessage("Usage: /focus restore <player> | /focus reload");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) return List.of();
        if (args.length == 1) {
            return List.of("restore", "reload").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("restore")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
        }
        return List.of();
    }
}
