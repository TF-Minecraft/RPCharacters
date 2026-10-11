package net.tfminecraft.rpcharacters.plots;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import net.tfminecraft.rpcharacters.permadeath.PermadeathBattleExemption;
import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;

/** The final phase: investigation, the hunt, its days, and the deaths that end it. */
public final class PlotHunt {
    static final String HUNTED = "§cYou feel hunted. An assassin is on your trail.";
    private static final Map<UUID, Hit> hits = new HashMap<>();

    private record Hit(UUID attacker, long at) {}

    private PlotHunt() {}

    private static PlotStore store() { return PlotService.store(); }

    private static long now() { return PlotService.now(); }

    // ---- Hunting --------------------------------------------------------------------------------

    /** True once an assassin's hunt has taken effect. */
    static boolean hunting(PlotMember member) {
        return member != null && member.huntingSince > 0 && now() >= member.huntingSince + PlotConfig.huntWarmup();
    }

    /** True while the player's active character is an assassin out on a hunt, warm-up included. */
    public static boolean hunting(Player player) {
        PlotCharacter character = PlotCharacters.active(player);
        Plot plot = character == null ? null : store().plotOf(character.characterId);
        PlotMember member = plot == null ? null : plot.member(character.characterId);
        return member != null && member.huntingSince > 0;
    }

    /** The target is warned when a hunt takes effect, and only once they know enough about the plot. */
    static boolean alerted(Plot plot) {
        return plot.discovered && plot.secrecy <= PlotConfig.huntAlertSecrecy();
    }

    /** Hunt days: the configured number, plus those bought with strength beyond the requirement. */
    static int totalDays(Plot plot) {
        return PlotConfig.huntDays() + PlotConfig.extraDays(plot.surplus());
    }

    /** An assassin starts the hunt, or calls it off once the minimum time has passed. */
    public static boolean hunt(Player player) {
        PlotCharacter me = PlotService.character(player);
        Plot plot = me == null ? null : store().plotOf(me.characterId);
        PlotMember member = plot == null ? null : plot.member(me.characterId);
        if (me == null) return false;
        if (member == null || member.role != PlotRole.ASSASSIN || plot.phase != PlotPhase.EXECUTION) {
            player.sendMessage("§cOnly an assassin can hunt, once the plot's assassins may strike.");
            return false;
        }
        if (member.huntingSince > 0) {
            long left = member.huntingSince + PlotConfig.huntMinimum() - now();
            if (left > 0) {
                player.sendMessage("§cYou are committed to the hunt for another " + PlotService.duration(left) + ".");
                return false;
            }
            endHunt(plot, member, "called off");
            player.sendMessage(PlotService.PREFIX + "You call off the hunt for now.");
            return true;
        }
        if (PermadeathBattleExemption.isInStartedBattle(player) || PlotFactions.inWarband(player)) {
            player.sendMessage("§cYou cannot hunt while in a warband or battle.");
            return false;
        }
        member.huntingSince = now();
        member.huntAnnounced = false;
        PlotCharacters.keepEvilSession(player, true);
        PlotLog.write(plot, "HUNT", PlotLog.who(me) + " sets out at " + PlotLog.where(player));
        player.sendMessage(PlotService.PREFIX + "You set out to hunt §e" + plot.target.name + "§7. In "
                + PlotService.duration(PlotConfig.huntWarmup()) + " the hunt is on: from then, their death or yours counts. You can't call it off for "
                + PlotService.duration(PlotConfig.huntMinimum()) + ", join a warband or switch character.");
        return true;
    }

    /** The target is never told a hunt ended, or reminded on login: either could point at who hunts them. */
    private static void endHunt(Plot plot, PlotMember member, String why) {
        member.huntingSince = 0;
        member.huntAnnounced = false;
        member.areaToldAt = 0;
        PlotLog.write(plot, "HUNT-END", PlotLog.who(member.character) + ": " + why);
    }

    /** One timer step of a plot in its hunt. True when something worth saving changed. */
    static boolean tick(Plot plot, long now) {
        hunts(plot, now);
        boolean changed = huntClock(plot, now);
        if (plot.huntDays >= totalDays(plot) && !plot.dayCounted || now >= plot.phaseStartedAt + PlotConfig.maxHunt()) {
            survived(plot);
            changed = true;
        }
        return changed;
    }

    /** Starts hunts whose warm-up has passed, ends those of absent assassins, and spreads word of the target. */
    private static void hunts(Plot plot, long now) {
        Player target = PlotCharacters.online(plot.target);
        boolean findable = target != null && findable(target);
        boolean sighted = findable && !plot.wasFindable && now - plot.sightedAt >= PlotConfig.sightingGap();
        plot.wasFindable = findable;
        if (sighted) {
            plot.sightedAt = now;
            PlotLog.write(plot, "SIGHTED", "the target is out in the open at " + PlotLog.where(target));
        }
        for (PlotMember member : plot.withRole(PlotRole.ASSASSIN)) {
            Player assassin = PlotCharacters.online(member.character);
            if (sighted && assassin != null) {
                assassin.sendMessage(PlotService.PREFIX + "Word comes that §e" + plot.target.name + "§7 is out in the open.");
                member.areaToldAt = 0;
            }
            if (member.huntingSince <= 0) continue;
            if (assassin == null) {
                endHunt(plot, member, "no longer played");
                continue;
            }
            PlotCharacters.keepEvilSession(assassin, false);
            if (!hunting(member)) continue;
            if (!member.huntAnnounced) {
                member.huntAnnounced = true;
                assassin.sendMessage(PlotService.PREFIX + "§cThe hunt is on.");
                if (target != null && alerted(plot)) {
                    target.sendMessage(PlotService.PREFIX + HUNTED);
                    PlotLog.write(plot, "ALERT", "the target felt the hunt of " + PlotLog.who(member.character));
                }
            }
            long interval = Math.round(PlotConfig.areaInterval() / sharpness(plot));
            if (PlotConfig.areaInterval() > 0 && (member.areaToldAt <= 0 || now - member.areaToldAt >= interval)) {
                member.areaToldAt = now;
                String area = area(plot, assassin);
                assassin.sendMessage(PlotService.PREFIX + area);
                PlotLog.write(plot, "AREA", "to " + PlotLog.who(member.character) + ": " + ChatColor.stripColor(area));
            }
        }
    }

    /** Spare strength makes the hunt sharper, up to the configured maximum. */
    static double sharpness(Plot plot) {
        return Math.min(PlotConfig.maxSurplus(), plot.surplus());
    }

    /** Out in the open: online in a hunting world and not deep below the surface above them. */
    static boolean findable(Player player) {
        var location = player.getLocation();
        var world = location.getWorld();
        if (world == null || !PlotConfig.worlds().isEmpty() && !PlotConfig.worlds().contains(world.getName())) return false;
        return location.getBlockY() >= world.getHighestBlockYAt(location.getBlockX(), location.getBlockZ()) - PlotConfig.maxDepth();
    }

    /** Counts a hunt day once the target has been findable long enough that day. True when a day was counted. */
    static boolean huntClock(Plot plot, long now) {
        while (now >= plot.huntDayStartedAt + PlotConfig.DAY) {
            plot.huntDayStartedAt += PlotConfig.DAY;
            plot.exposure = 0;
            plot.dayCounted = false;
        }
        long since = plot.exposureCheckedAt;
        plot.exposureCheckedAt = now;
        Player target = PlotCharacters.online(plot.target);
        if (plot.dayCounted || since <= 0 || target == null || !findable(target)) return false;
        // A stalled timer or a long pause never counts as time out in the open.
        plot.exposure += Math.min(now - since, 60_000);
        if (plot.exposure < PlotConfig.findable()) return false;
        plot.dayCounted = true;
        plot.huntDays++;
        PlotLog.write(plot, "HUNT-DAY", plot.huntDays + " of " + totalDays(plot));
        String message = "§e" + plot.target.name + "§7 has been out in the open today: hunt day §e" + plot.huntDays + "§7 of §e"
                + totalDays(plot) + "§7 counts.";
        PlotService.tell(plot.master, message);
        for (PlotMember assassin : plot.withRole(PlotRole.ASSASSIN)) PlotService.tell(assassin.character, message);
        return true;
    }

    /** Time ran out: the target lives, and nobody in the plot may ever plot against them again. */
    static void survived(Plot plot) {
        PlotService.end(plot, PlotPhase.FAILED, "The target survived", false);
        store().bans.put(banKey(plot.master, plot.target), plot.code);
        for (PlotMember member : plot.members) store().bans.put(banKey(member.character, plot.target), plot.code);
        PlotService.tellConspirators(plot, "Time has run out. §e" + plot.target.name
                + "§7 survived the plot, and none of you may ever plot against them again.");
        if (plot.discovered) PlotService.tell(plot.target, "You survived the plot against your life.");
        PlotService.save();
    }

    private static String banKey(PlotCharacter participant, PlotCharacter target) {
        return participant.characterId + "|" + target.characterId;
    }

    static boolean banned(PlotCharacter participant, PlotCharacter target) {
        return store().bans.containsKey(banKey(participant, target));
    }

    /** A target who knows they are hunted must face it as this character: no switching or new characters. */
    public static boolean locked(String characterId) {
        return store().against(characterId).stream().anyMatch(plot -> plot.phase == PlotPhase.EXECUTION && plot.discovered);
    }

    public static boolean locked(Player player) {
        PlotCharacter character = PlotCharacters.active(player);
        return character != null && locked(character.characterId);
    }

    /** The target's rough whereabouts, sharper with spare strength, if they can be found at all. */
    static String area(Plot plot, Player assassin) {
        Player target = PlotCharacters.online(plot.target);
        if (target == null || !findable(target)) return "Nobody has seen §e" + plot.target.name + "§7 out in the open lately.";
        var at = target.getLocation();
        int size = Math.max(1, (int) Math.round(PlotConfig.areaSize() / sharpness(plot)));
        long x = Math.floorDiv(at.getBlockX(), size) * (long) size + size / 2;
        long z = Math.floorDiv(at.getBlockZ(), size) * (long) size + size / 2;
        StringBuilder text = new StringBuilder("Word is that §e" + plot.target.name + "§7 was seen around §ex " + x + ", z " + z + "§7");
        if (at.getWorld().equals(assassin.getWorld())) {
            double dx = x - assassin.getLocation().getX(), dz = z - assassin.getLocation().getZ();
            long distance = Math.round(Math.hypot(dx, dz) / 50) * 50;
            text.append(distance < size ? ", close to you" : ", about " + distance + " blocks " + direction(dx, dz) + " of you");
        }
        String lands = PlotFactions.lands(at);
        if (lands != null) text.append(", in the lands of §e").append(lands).append("§7");
        return text.append('.').toString();
    }

    static String direction(double dx, double dz) {
        String[] names = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
        return names[(int) Math.floorMod(Math.round(Math.toDegrees(Math.atan2(-dx, dz)) / 45), 8)];
    }

    // ---- Investigation and encounters -----------------------------------------------------------

    /**
     * Each investigator rolls once against each conspirator they come near during the final preparations.
     * In every running plot, conspirators near the target are logged for staff.
     */
    public static void scan() {
        boolean changed = false;
        for (Plot plot : store().plots) {
            if (!plot.phase.active() || plot.target == null) continue;
            Player target = PlotCharacters.online(plot.target);
            if (target != null) for (PlotCharacter suspect : plot.suspects()) {
                Player suspected = PlotCharacters.online(suspect);
                if (suspected != null && PlotService.near(target, suspected, PlotConfig.encounterRadius()))
                    PlotLog.encounter(plot, suspect, suspected, target);
            }
            if (plot.phase == PlotPhase.INVESTIGATION) changed |= investigate(plot, target);
        }
        if (changed) PlotService.save();
    }

    private static boolean investigate(Plot plot, Player target) {
        Map<Player, PlotCharacter> investigators = new LinkedHashMap<>();
        if (target != null) investigators.put(target, plot.target);
        if (plot.discovered) for (PlotCharacter character : store().peek(plot.target.characterId).investigators) {
            Player player = PlotCharacters.online(character);
            if (player != null) investigators.put(player, character);
        }
        if (investigators.isEmpty()) return false;
        boolean changed = false;
        int modifier = PlotMath.investigationModifier(plot.discovered, PlotDefense.protection(plot).betrayal());
        for (PlotCharacter suspect : plot.suspects()) {
            Player suspected = plot.investigated.contains(suspect.characterId) ? null : PlotCharacters.online(suspect);
            if (suspected == null) continue;
            for (var investigator : investigators.entrySet()) {
                if (investigator.getKey().equals(suspected) || !PlotService.near(investigator.getKey(), suspected, PlotConfig.radius())) continue;
                plot.investigated.add(suspect.characterId);
                changed = true;
                PlotRole role = plot.role(suspect.characterId);
                int margin = PlotMath.investigate(PlotService.defense(investigator.getValue()), modifier,
                        PlotService.offense(suspect), role, PlotService.random.get());
                PlotLog.write(plot, "INVESTIGATE", PlotLog.who(investigator.getValue()) + " on " + PlotLog.who(suspect)
                        + " (" + role.label() + "), margin " + margin + " at " + PlotLog.where(investigator.getKey()));
                if (margin >= 0) investigator.getKey().sendMessage(StringFormatter.formatHex(ChatColor.translateAlternateColorCodes('&',
                        PlotConfig.hint(role, margin >= PlotConfig.strongMargin(), PlotCharacters.seenAs(suspected, suspect)))));
                break;
            }
        }
        return changed;
    }

    // ---- Deaths ---------------------------------------------------------------------------------

    /** Remembers the last player blow on each player: a knocked-out player may die from it later. */
    public static void damaged(Player victim, Player attacker) {
        hits.put(victim.getUniqueId(), new Hit(attacker.getUniqueId(), now()));
    }

    static boolean pruneHits(long now) {
        hits.values().removeIf(hit -> now - hit.at() > PlotConfig.hitWindow());
        return false;
    }

    /** Whoever killed the player, or failing that whoever struck them within the hit window. */
    private static List<Player> culprits(Player victim, Player killer) {
        Hit hit = hits.remove(victim.getUniqueId());
        List<Player> culprits = new ArrayList<>();
        if (killer != null) culprits.add(killer);
        Player striker = hit != null && now() - hit.at() <= PlotConfig.hitWindow() ? Bukkit.getPlayer(hit.attacker()) : null;
        if (striker != null && !striker.equals(killer)) culprits.add(striker);
        return culprits;
    }

    private static PlotMember assassin(Plot plot, Player player) {
        PlotCharacter character = PlotCharacters.active(player);
        PlotMember member = character == null ? null : plot.member(character.characterId);
        return member != null && member.role == PlotRole.ASSASSIN ? member : null;
    }

    /**
     * The target dying to a hunting assassin ends the plot and leaves clues at the scene; a hunting
     * assassin dying to anyone's hand is revealed. Both die for good. Other deaths are ordinary.
     */
    public static void died(Player victim, Player killer) {
        List<Player> culprits = culprits(victim, killer);
        PlotCharacter character = PlotCharacters.active(victim);
        if (character == null || culprits.isEmpty()) return;
        for (Plot plot : List.copyOf(store().plots)) {
            if (plot.phase != PlotPhase.EXECUTION) continue;
            if (plot.isTarget(character.characterId)) {
                Player assassin = culprits.stream().filter(culprit -> hunting(assassin(plot, culprit))).findFirst().orElse(null);
                if (assassin == null) continue;
                PlotService.end(plot, PlotPhase.SUCCEEDED, "The target was assassinated", false);
                Location scene = victim.getLocation();
                PlotCharacters.permakill(victim, character.characterId, assassin);
                int clues = PlotCharacters.leaveClues(scene, assassin, PlotConfig.killerClues());
                for (PlotMember member : plot.withRole(PlotRole.ASSASSIN)) {
                    Player accomplice = PlotCharacters.online(member.character);
                    if (accomplice != null && !accomplice.equals(assassin) && hunting(member)
                            && PlotService.near(accomplice, victim, PlotConfig.accompliceRadius()))
                        clues += PlotCharacters.leaveClues(scene, accomplice, PlotConfig.accompliceClues());
                }
                PlotLog.write(plot, "ASSASSINATED", PlotLog.who(character) + " by " + PlotLog.who(PlotCharacters.active(assassin))
                        + " at " + PlotLog.where(scene) + ", " + clues + " clues left");
                PlotService.tellConspirators(plot, "§e" + character.name + "§7 is dead. The plot has succeeded.");
                PlotService.save();
                return;
            }
            PlotMember member = assassin(plot, victim);
            if (!hunting(member)) continue;
            Player killedBy = culprits.getFirst();
            plot.members.remove(member);
            PlotService.save();
            PlotCharacters.permakill(victim, character.characterId, killedBy);
            PlotLog.write(plot, "ASSASSIN-FELL", PlotLog.who(character) + " killed by " + PlotLog.who(PlotCharacters.active(killedBy))
                    + " at " + PlotLog.where(victim));
            String reveal = "§cThe fallen assassin is revealed as §e" + character.name + "§c.";
            killedBy.sendMessage(PlotService.PREFIX + reveal);
            Player target = PlotCharacters.online(plot.target);
            if (target != null && !target.equals(killedBy)) target.sendMessage(PlotService.PREFIX + reveal);
            PlotService.tell(plot.master, "Your assassin §e" + character.name + "§7 has fallen.");
            if (plot.withRole(PlotRole.ASSASSIN).isEmpty()) {
                PlotService.end(plot, PlotPhase.FAILED, "Every assassin fell", true);
                PlotService.tellConspirators(plot, "The plot" + PlotService.against(plot) + " has failed: no assassin is left.");
            }
            PlotService.save();
            return;
        }
    }

    /** A character's permanent death, from any cause, ends or thins the plots and protections around it. */
    public static void characterDied(String characterId) {
        boolean changed = false;
        for (Plot plot : List.copyOf(store().plots)) {
            if (!plot.phase.active()) continue;
            changed |= plot.informed.removeIf(character -> character.is(characterId));
            if (plot.master.is(characterId)) {
                PlotService.end(plot, PlotPhase.DISSOLVED, "The plot master died", false);
                for (PlotMember member : plot.members)
                    PlotService.tell(member.character, "With the death of §e" + plot.master.name + "§7, the plot" + PlotService.against(plot) + " dissolves.");
            } else if (plot.isTarget(characterId)) {
                PlotService.end(plot, PlotPhase.DISSOLVED, "The target died", false);
                PlotService.tellConspirators(plot, "Fate has claimed §e" + plot.target.name + "§7 before the plot could. It dissolves.");
            } else if (plot.member(characterId) != null) {
                PlotMember member = plot.member(characterId);
                plot.members.remove(member);
                PlotLog.write(plot, "MEMBER-DIED", PlotLog.who(member.character));
                PlotService.tell(plot.master, "Your conspirator §e" + member.character.name + "§7 is dead.");
                if (plot.phase.ordinal() >= PlotPhase.INVESTIGATION.ordinal() && plot.withRole(PlotRole.ASSASSIN).isEmpty()) {
                    PlotService.end(plot, PlotPhase.FAILED, "Every assassin died", true);
                    PlotService.tellConspirators(plot, "The plot" + PlotService.against(plot) + " has failed: no assassin is left.");
                }
            } else continue;
            changed = true;
        }
        for (IntrigueSettings settings : store().characters.values()) {
            changed |= settings.investigators.removeIf(character -> character.is(characterId));
            if (settings.confidant != null && settings.confidant.is(characterId)) {
                settings.confidant = null;
                changed = true;
            }
        }
        changed |= store().characters.remove(characterId) != null;
        changed |= store().wards.remove(characterId) != null;
        for (List<PlotCharacter> wards : store().wards.values()) changed |= wards.removeIf(ward -> ward.is(characterId));
        if (changed) PlotService.save();
    }
}
