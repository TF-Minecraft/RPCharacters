package net.tfminecraft.rpcharacters.estate;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Locale;
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
        Resolved resolved = resolve(args, args.length, staff::find);
        if (resolved == null) return reply(sender, "No estate matches " + args[1] + ".");
        EstateRecord record = resolved.record();
        String by = sender.getName();
        String[] rest = Arrays.copyOfRange(args, resolved.end(), args.length);
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

    private record Resolved(EstateRecord record, int end) {}

    private static Resolved resolve(String[] args, int end, Function<String, Optional<EstateRecord>> lookup) {
        while (end > 1) {
            Optional<EstateRecord> found = lookup.apply(String.join(" ", Arrays.copyOfRange(args, 1, end)));
            if (found.isPresent()) return new Resolved(found.get(), end);
            end--;
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) return List.of();
        String prefix = args[args.length - 1];
        if (!sender.hasPermission(PERMISSION)) return sender instanceof Player && args.length == 1
                ? filter(List.of("collect"), prefix) : List.of();
        if (args.length == 1) return filter(STAFF.stream()
                .filter(sub -> !sub.equals("collect") || sender instanceof Player).toList(), prefix);
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("collect") && !(sender instanceof Player)) return List.of();
        if (sub.equals("list")) return args.length == 2 ? filter(FILTERS, prefix) : List.of();
        if (!STAFF.contains(sub)) return List.of();

        List<EstateRecord> records = staff.records();
        List<String> names = new ArrayList<>();
        if (args.length == 2) records.forEach(record -> names.add(record.deathId.toString()));
        String preceding = args.length == 2 ? "" : String.join(" ", Arrays.copyOfRange(args, 1, args.length - 1)) + " ";
        String typed = (preceding + prefix).toLowerCase(Locale.ROOT);
        for (EstateRecord record : records) {
            if (record.name.toLowerCase(Locale.ROOT).startsWith(typed)) names.add(record.name.substring(preceding.length()));
        }
        if (!names.isEmpty()) return filter(names, prefix);

        // The current token is still being replaced; only completed words can consume the estate key.
        Resolved resolved = resolve(args, args.length - 1, key -> records.stream()
                .filter(record -> record.deathId.toString().equalsIgnoreCase(key)
                        || record.testator.characterId().toString().equalsIgnoreCase(key)
                        || record.name.equalsIgnoreCase(key))
                .reduce((older, newer) -> newer));
        if (resolved == null) return List.of();
        EstateRecord record = resolved.record();
        int position = args.length - 1 - resolved.end();
        List<String> options = new ArrayList<>();
        if (position == 0) {
            if (sub.equals("decide") && record.state == net.tfminecraft.tlibs.character.estate.EstateState.OPEN) {
                options.add("--no-guild");
            } else if (sub.equals("assign") && record.settling()) {
                var decision = record.decision;
                if (assignable(decision.money())) options.add("money");
                if (assignable(decision.property())) options.add("property");
                if (assignable(decision.guildLocks())) options.add("guildlocks");
                if (!options.isEmpty()) options.add("all");
            } else if ((sub.equals("retry") || sub.equals("waive")) && record.settling()) {
                record.participants.forEach((id, report) -> {
                    if (report.outcome().equals("BLOCKED")
                            && (!sub.equals("waive") || report.revision() == record.decision.revision())) options.add(id);
                });
            }
        } else if (position == 1 && sub.equals("assign") && record.settling()
                && args[resolved.end()].equalsIgnoreCase("money") && record.decision.money().recipient().isFrozen()) {
            options.add("guild");
        }
        return filter(options, prefix);
    }

    private static boolean assignable(EstateRecord.Part part) {
        return part.recipient().isWaiting() || part.recipient().isFrozen();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).distinct().toList();
    }
}
