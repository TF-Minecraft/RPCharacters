package net.tfminecraft.rpcharacters.estate;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** {@code /estate}: players collect inheritances; staff with {@code rpchar.estate.admin} run the estate tools. */
final class EstateCommand implements TabExecutor {
    static final String PERMISSION = "rpchar.estate.admin";
    private static final List<String> STAFF = List.of("list", "show", "hold", "release", "decide", "assign", "collect",
            "retry", "waive", "revert");
    private static final List<String> FILTERS = List.of("open", "held", "waiting", "frozen", "retry", "postponed", "empty");

    private final EstateStaff staff;
    private final EstateCollections collections;
    private final Function<Player, Optional<UUID>> currentCharacter;

    EstateCommand(EstateStaff staff, EstateCollections collections, Function<Player, Optional<UUID>> currentCharacter) {
        this.staff = staff;
        this.collections = collections;
        this.currentCharacter = currentCharacter;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);
        if (sub.equals("collect") && args.length == 1) {
            if (!(sender instanceof Player player)) return reply(sender, "Only a player can collect an inheritance.");
            Optional<UUID> character = currentCharacter.apply(player);
            if (character.isEmpty()) return reply(sender, "Play the character that inherited, then collect.");
            return reply(sender, collections.collect(player, character.get()));
        }
        if (!sender.hasPermission(PERMISSION)) return reply(sender, "Use /estate collect to collect an inheritance.");
        if (!STAFF.contains(sub)) return reply(sender, "Usage: /estate " + String.join("|", STAFF));
        if (sub.equals("list")) {
            staff.list(args.length > 1 ? args[1] : null).forEach(sender::sendMessage);
            return true;
        }
        if (args.length < 2) return reply(sender, "Name the estate: a death ID, character ID or dead character's name.");
        // The longest run of words naming an estate wins, so two-word names work before further arguments.
        int end = args.length;
        Optional<EstateRecord> found = Optional.empty();
        while (end > 1 && (found = staff.find(String.join(" ", Arrays.copyOfRange(args, 1, end)))).isEmpty()) end--;
        if (found.isEmpty()) return reply(sender, "No estate matches " + args[1] + ".");
        EstateRecord record = found.get();
        String by = sender.getName();
        String[] rest = Arrays.copyOfRange(args, end, args.length);
        return switch (sub) {
            case "show" -> {
                staff.show(record).forEach(sender::sendMessage);
                yield true;
            }
            case "hold" -> reply(sender, staff.hold(record, by));
            case "release" -> reply(sender, staff.release(record, by));
            case "decide" -> reply(sender, rest.length == 1 && rest[0].equals("--no-guild")
                    ? staff.decideWithoutGuild(record, by) : "Usage: /estate decide <deathId> --no-guild");
            case "assign" -> reply(sender, assign(record, rest, by));
            case "collect" -> reply(sender, sender instanceof Player player
                    ? collections.collectAsStaff(player, record, staff.stranded(record)) : "Only a player can collect.");
            case "retry" -> reply(sender, rest.length == 1 ? staff.retry(record, rest[0], by)
                    : "Usage: /estate retry <deathId> <participant>");
            case "waive" -> reply(sender, rest.length > 1
                    ? staff.waive(record, rest[0], String.join(" ", Arrays.copyOfRange(rest, 1, rest.length)), by)
                    : "Usage: /estate waive <deathId> <participant> <note>");
            default -> reply(sender, staff.revert(record, by));
        };
    }

    private String assign(EstateRecord record, String[] rest, String by) {
        if (rest.length > 2 && rest[0].equalsIgnoreCase("money") && rest[1].equalsIgnoreCase("guild")) {
            return staff.assignMoneyToGuild(record, String.join(" ", Arrays.copyOfRange(rest, 2, rest.length)), by);
        }
        if (rest.length < 2) return "Usage: /estate assign <deathId> <money|property|guildlocks|all> <character>";
        return staff.assign(record, rest[0], String.join(" ", Arrays.copyOfRange(rest, 1, rest.length)), by);
    }

    private static boolean reply(CommandSender sender, String message) {
        sender.sendMessage(message);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) return args.length == 1 ? List.of("collect") : List.of();
        if (args.length == 1) return STAFF.stream().filter(s -> s.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("list")) return FILTERS;
        if (args.length == 3 && args[0].equalsIgnoreCase("assign")) return List.of("money", "property", "guildlocks", "all");
        if (args.length == 3 && args[0].equalsIgnoreCase("decide")) return List.of("--no-guild");
        return List.of();
    }
}
