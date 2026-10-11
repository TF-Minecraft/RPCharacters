package net.tfminecraft.rpcharacters.plots;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.random.RandomGenerator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import net.tfminecraft.rpcharacters.RPCharacters;

/** Plots from request to outcome. Every rule works on characters; accounts carry messages and limits. */
public final class PlotService {
    static final String PREFIX = "§8[§5Plot§8] §7";
    private static final String CODE_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static LongSupplier clock = System::currentTimeMillis;
    static Supplier<RandomGenerator> random = ThreadLocalRandom::current;
    private static PlotStore store = new PlotStore();

    private PlotService() {}

    public static void load(Path file) throws IOException { store = PlotStore.load(file); }

    public static PlotStore store() { return store; }

    static long now() { return clock.getAsLong(); }

    static void save() {
        try {
            store.save();
        } catch (IOException error) {
            RPCharacters.plugin.getLogger().log(Level.SEVERE, "Could not save plots", error);
        }
    }

    // ---- Intrigue -------------------------------------------------------------------------------

    /** Intrigue rolls on first use while the character is played; until then it counts as the formula's base. */
    public static int intrigue(PlotCharacter character) {
        Integer known = Intrigue.known(character.characterId);
        if (known != null) return known;
        Player player = PlotCharacters.online(character);
        if (player != null) {
            try {
                return Intrigue.of(player, character.characterId);
            } catch (IOException | RuntimeException error) {
                RPCharacters.plugin.getLogger().log(Level.WARNING, "Could not roll Intrigue for " + character.name, error);
            }
        }
        return 50;
    }

    public static int offense(PlotCharacter character) {
        return PlotMath.offense(intrigue(character), store.peek(character.characterId).approach());
    }

    public static int defense(PlotCharacter character) {
        return PlotMath.defense(intrigue(character), store.peek(character.characterId).approach());
    }

    // ---- Messages -------------------------------------------------------------------------------

    /** Reaches the character now if it is played, otherwise the next time it is. */
    static void tell(PlotCharacter character, String message) {
        Player player = PlotCharacters.online(character);
        if (player != null) {
            player.sendMessage(PREFIX + message);
            return;
        }
        var queue = store.notices.computeIfAbsent(character.characterId, id -> new ArrayList<>());
        queue.add(PREFIX + message);
        while (queue.size() > PlotConfig.noticeLimit()) queue.removeFirst();
    }

    static void tellConspirators(Plot plot, String message) {
        tell(plot.master, message);
        for (PlotMember member : plot.members) tell(member.character, message);
    }

    /** Rolls Intrigue for a returning character and hands them what waited for them. */
    public static void deliver(Player player) {
        PlotCharacter character = PlotCharacters.active(player);
        if (character == null) return;
        intrigue(character);
        var queue = store.notices.remove(character.characterId);
        if (queue == null) return;
        queue.forEach(player::sendMessage);
        save();
    }

    static PlotCharacter character(Player player) {
        PlotCharacter character = PlotCharacters.active(player);
        if (character == null) player.sendMessage("§cYou need a living active character.");
        return character;
    }

    static boolean near(Player first, Player second, double radius) {
        return first.getWorld().equals(second.getWorld()) && first.getLocation().distanceSquared(second.getLocation()) <= radius * radius;
    }

    /** Another player's living character, met in person. */
    static PlotCharacter other(Player player, Player other, String purpose) {
        PlotCharacter character = PlotCharacters.active(other);
        if (other == null || character == null || other.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage("§cChoose another player's living active character.");
            return null;
        }
        if (!near(player, other, PlotConfig.inviteRadius())) {
            player.sendMessage("§cDo this in person: stand within " + (int) PlotConfig.inviteRadius() + " blocks of them to " + purpose + ".");
            return null;
        }
        return character;
    }

    public static boolean setApproach(Player player, PlotApproach approach) {
        PlotCharacter me = character(player);
        if (me == null) return false;
        IntrigueSettings settings = store.settings(me.characterId);
        if (settings.approach() == approach) {
            player.sendMessage("§7Your approach is already " + approach.label() + ".");
            return false;
        }
        long wait = settings.approachChangedAt + PlotConfig.approachCooldown() - now();
        if (settings.approachChangedAt > 0 && wait > 0) {
            player.sendMessage("§cYou can change your approach again in " + duration(wait) + ".");
            return false;
        }
        settings.approach = approach;
        settings.approachChangedAt = now();
        save();
        player.sendMessage(PREFIX + "Your approach is now §e" + approach.label() + "§7: +"
                + PlotConfig.offenseBonus(approach) + " Intrigue when plotting, +" + PlotConfig.defenseBonus(approach) + " when defending.");
        return true;
    }

    /** Whole minutes, as "1d 2h 3m". */
    static String duration(long millis) {
        long minutes = Math.max(1, (millis + 59_999) / 60_000);
        long days = minutes / 1440, hours = minutes % 1440 / 60, rest = minutes % 60;
        StringBuilder text = new StringBuilder();
        if (days > 0) text.append(days).append("d ");
        if (hours > 0) text.append(hours).append("h ");
        if (rest > 0 || text.isEmpty()) text.append(rest).append("m");
        return text.toString().strip();
    }

    // ---- Plot lifecycle -------------------------------------------------------------------------

    public static Plot request(Player player) {
        PlotCharacter me = character(player);
        if (me == null) return null;
        if (store.plotOfAccount(me.playerId) != null) {
            player.sendMessage("§cYou are already part of a plot, with this or another character.");
            return null;
        }
        intrigue(me);
        Plot plot = new Plot();
        plot.code = newCode();
        plot.master = me;
        plot.requestedAt = plot.phaseStartedAt = now();
        store.plots.add(plot);
        save();
        PlotLog.write(plot, "REQUEST", PlotLog.who(me) + " at " + PlotLog.where(player));
        player.sendMessage(PREFIX + "You begin to plan an assassination. Open a ticket naming your target and give staff this code: §e"
                + plot.code + "§7. It expires in " + duration(PlotConfig.requestExpiry()) + ".");
        return plot;
    }

    private static String newCode() {
        String code;
        do {
            StringBuilder builder = new StringBuilder("P-");
            for (int index = 0; index < 5; index++)
                builder.append(CODE_LETTERS.charAt(random.get().nextInt(CODE_LETTERS.length())));
            code = builder.toString();
        } while (store.plot(code) != null);
        return code;
    }

    /** Staff link the requested target. The target may be offline. */
    public static boolean approve(CommandSender staff, String code, String targetName) {
        Plot plot = store.plot(code);
        if (plot == null || plot.phase != PlotPhase.REQUESTED) {
            staff.sendMessage("§cNo plot request with that code is awaiting approval.");
            return false;
        }
        PlotCharacter target = resolve(targetName);
        if (target == null) {
            staff.sendMessage("§cThat player has no living active character.");
            return false;
        }
        if (target.playerId.equals(plot.master.playerId)) {
            staff.sendMessage("§cA player cannot plot against their own characters.");
            return false;
        }
        if (PlotHunt.banned(plot.master, target)) {
            staff.sendMessage("§c" + plot.master.name + " once failed to kill " + target.name + " and may never target them again.");
            return false;
        }
        Long until = store.cooldowns.get(cooldownKey(plot.master, target));
        if (until != null && until > now()) {
            staff.sendMessage("§c" + plot.master.playerName + " cannot target " + target.name + " again, with any character, for "
                    + duration(until - now()) + ".");
            return false;
        }
        plot.target = target;
        plot.phase = PlotPhase.RECRUITING;
        plot.phaseStartedAt = now();
        save();
        PlotLog.write(plot, "APPROVE", "by " + staff.getName() + ": " + PlotLog.who(plot.master) + " against " + PlotLog.who(target));
        staff.sendMessage("§aPlot " + plot.code + " approved: " + plot.master.name + " (" + plot.master.playerName
                + ") against " + target.name + " (" + target.playerName + ").");
        tell(plot.master, "Your plot against §e" + target.name + "§7 has been approved. Meet other characters and propose "
                + "that they join: stand near them and use the Plots menu or §e/plot invite <name>§7. Start the plot within "
                + duration(PlotConfig.recruiting()) + " or it dissolves.");
        save();
        return true;
    }

    static PlotCharacter resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return PlotCharacters.active(online);
        return PlotCharacters.offline(Bukkit.getOfflinePlayerIfCached(name));
    }

    /** Per account: switching to another character never shortens the wait. */
    static String cooldownKey(PlotCharacter master, PlotCharacter target) {
        return master.playerId + "|" + target.characterId;
    }

    public static boolean deny(CommandSender staff, String code) {
        Plot plot = store.plot(code);
        if (plot == null || plot.phase != PlotPhase.REQUESTED) {
            staff.sendMessage("§cNo plot request with that code is awaiting approval.");
            return false;
        }
        end(plot, PlotPhase.DISSOLVED, "Denied by staff", false);
        PlotLog.write(plot, "DENY", "by " + staff.getName());
        staff.sendMessage("§aPlot request " + plot.code + " denied.");
        tell(plot.master, "Your plot request §e" + plot.code + "§7 was not approved.");
        save();
        return true;
    }

    public static boolean cancel(CommandSender staff, String code) {
        Plot plot = store.plot(code);
        if (plot == null || !plot.phase.active()) {
            staff.sendMessage("§cNo running plot has that code.");
            return false;
        }
        end(plot, PlotPhase.DISSOLVED, "Ended by staff", false);
        PlotLog.write(plot, "CANCEL", "by " + staff.getName());
        tellConspirators(plot, "The plot" + against(plot) + " has come to nothing.");
        save();
        staff.sendMessage("§aPlot " + plot.code + " ended.");
        return true;
    }

    static String against(Plot plot) {
        return plot.target == null ? "" : " against §e" + plot.target.name + "§7";
    }

    static void end(Plot plot, PlotPhase phase, String outcome, boolean cooldown) {
        plot.phase = phase;
        plot.outcome = outcome;
        plot.invitations.clear();
        if (cooldown && plot.target != null)
            store.cooldowns.put(cooldownKey(plot.master, plot.target), now() + PlotConfig.retargetCooldown());
        PlotLog.write(plot, "END", phase + ": " + outcome);
        save();
    }

    /** The plot the player's character leads, or null with a message. */
    static Plot mastered(Player player) {
        PlotCharacter me = character(player);
        Plot plot = me == null ? null : store.plotOf(me.characterId);
        if (me != null && (plot == null || !plot.master.is(me.characterId))) {
            player.sendMessage("§cYou are not leading a plot.");
            return null;
        }
        return plot;
    }

    private static Plot organising(Player player) {
        Plot plot = mastered(player);
        if (plot != null && !plot.phase.organising()) {
            player.sendMessage("§cYou can only change the conspirators while the plot is being organised.");
            return null;
        }
        return plot;
    }

    /** The target's own player must never learn of the plot through another of their characters. */
    static boolean targetsAccount(Plot plot, PlotCharacter character) {
        return plot.isTarget(character.characterId) || character.playerId.equals(plot.target.playerId);
    }

    /** The proposal names the master as the guest sees them, never the target: only those who accept learn it. */
    public static boolean invite(Player player, Player other) {
        Plot plot = organising(player);
        if (plot == null) return false;
        PlotCharacter guest = other(player, other, "propose it");
        if (guest == null) return false;
        if (targetsAccount(plot, guest) || plot.involvesAccount(guest.playerId) || PlotHunt.banned(guest, plot.target)) {
            player.sendMessage("§cYou cannot invite them.");
            return false;
        }
        plot.invitations.values().removeIf(expiry -> expiry < now());
        if (plot.members.size() + plot.invitations.size() >= PlotConfig.maxMembers() && !plot.invitations.containsKey(guest.characterId)) {
            player.sendMessage("§cYour plot has no room for more conspirators.");
            return false;
        }
        plot.invitations.put(guest.characterId, now() + PlotConfig.inviteDuration());
        save();
        PlotLog.write(plot, "INVITE", PlotLog.who(guest) + " by " + PlotLog.who(plot.master) + ", "
                + Math.round(player.getLocation().distance(other.getLocation())) + " blocks apart at " + PlotLog.where(player));
        player.sendMessage(PREFIX + "You propose to §e" + guest.name + "§7 that they join your plot.");
        other.sendMessage(LegacyComponentSerializer.legacySection().deserialize(PREFIX + "§e" + PlotCharacters.seenAs(player, plot.master)
                        + "§7 proposes that you join a plot. You will learn against whom if you accept. ")
                .append(Component.text("[Accept]", NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/plot accept " + plot.code)))
                .append(Component.text(" "))
                .append(Component.text("[Decline]", NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/plot decline " + plot.code))));
        return true;
    }

    public static boolean accept(Player player, String code) {
        PlotCharacter me = character(player);
        Plot plot = me == null ? null : store.plot(code);
        Long expiry = plot == null ? null : plot.invitations.get(me.characterId);
        if (me == null) return false;
        if (expiry == null || expiry < now() || !plot.phase.organising()) {
            player.sendMessage("§cThat proposal is no longer open.");
            return false;
        }
        plot.invitations.remove(me.characterId);
        if (store.plotOfAccount(me.playerId) != null || plot.members.size() >= PlotConfig.maxMembers() || PlotHunt.banned(me, plot.target)) {
            save();
            player.sendMessage("§cYou cannot join this plot: you are already part of one, it has no room left, or you once failed to kill its target.");
            return false;
        }
        intrigue(me);
        plot.members.add(new PlotMember(me));
        plot.informed.removeIf(character -> character.is(me.characterId));
        save();
        PlotLog.write(plot, "JOIN", PlotLog.who(me) + " at " + PlotLog.where(player));
        player.sendMessage(PREFIX + "You have joined the plot against §e" + plot.target.name + "§7. §e"
                + plot.master.name + "§7 will give you your role.");
        tell(plot.master, "§e" + me.name + "§7 has joined your plot. Give them a role in the Plots menu.");
        save();
        return true;
    }

    /** Declining knows only that someone plots, not against whom, so it does not make them informed. */
    public static boolean decline(Player player, String code) {
        PlotCharacter me = character(player);
        Plot plot = me == null ? null : store.plot(code);
        if (me == null) return false;
        if (plot == null || plot.invitations.remove(me.characterId) == null) {
            player.sendMessage("§cThat proposal is no longer open.");
            return false;
        }
        save();
        PlotLog.write(plot, "DECLINE", PlotLog.who(me));
        player.sendMessage(PREFIX + "You turn the proposal down.");
        tell(plot.master, "§e" + me.name + "§7 has turned down your proposal.");
        save();
        return true;
    }

    public static boolean assign(Player player, String characterId, PlotRole role, CollaboratorTask task) {
        Plot plot = organising(player);
        if (plot == null) return false;
        PlotMember member = plot.member(characterId);
        if (member == null) {
            player.sendMessage("§cThat character is not in your plot.");
            return false;
        }
        if (role == PlotRole.ASSASSIN && member.role != role && plot.withRole(role).size() >= PlotConfig.maxAssassins()
                || role == PlotRole.COLLABORATOR && member.role != role && plot.withRole(role).size() >= PlotConfig.maxCollaborators()) {
            player.sendMessage("§cA plot has room for " + PlotConfig.maxAssassins() + " assassins and "
                    + PlotConfig.maxCollaborators() + " collaborators.");
            return false;
        }
        member.role = role;
        member.task = role == PlotRole.COLLABORATOR ? task == null ? CollaboratorTask.HIDE : task : null;
        save();
        PlotLog.write(plot, "ROLE", PlotLog.who(member.character) + ": " + member.roleLabel());
        player.sendMessage(PREFIX + "§e" + member.character.name + "§7 is now: " + member.roleLabel() + ".");
        tell(member.character, "§e" + plot.master.name + "§7 has made you: §e" + member.roleLabel() + "§7.");
        save();
        return true;
    }

    /** A member who leaves knows the target and the conspirators, so they stay informed. */
    static void leaves(Plot plot, PlotMember member, String event) {
        plot.members.remove(member);
        member.huntingSince = 0;
        if (plot.informed(member.character.characterId) == null) plot.informed.add(member.character);
        PlotLog.write(plot, event, PlotLog.who(member.character) + " (now informed)");
        save();
    }

    public static boolean dismiss(Player player, String characterId) {
        Plot plot = organising(player);
        if (plot == null) return false;
        PlotMember member = plot.member(characterId);
        if (member == null) {
            player.sendMessage("§cThat character is not in your plot.");
            return false;
        }
        leaves(plot, member, "DISMISS");
        player.sendMessage(PREFIX + "§e" + member.character.name + "§7 is no longer part of your plot, though they know of it.");
        tell(member.character, "You are no longer part of the plot" + against(plot) + ".");
        save();
        return true;
    }

    /** A conspirator marks a character who learned of the plot from them in roleplay. */
    public static boolean inform(Player player, Player other) {
        PlotCharacter me = character(player);
        Plot plot = me == null ? null : store.plotOf(me.characterId);
        if (me == null) return false;
        if (plot == null || plot.target == null) {
            player.sendMessage("§cYou are not part of a running plot.");
            return false;
        }
        PlotCharacter guest = other(player, other, "tell them");
        if (guest == null) return false;
        if (targetsAccount(plot, guest) || plot.role(guest.characterId) != null) {
            player.sendMessage("§cThey cannot be marked as knowing of the plot.");
            return false;
        }
        plot.informed.add(guest);
        save();
        PlotLog.write(plot, "INFORM", PlotLog.who(guest) + " by " + PlotLog.who(me) + " at " + PlotLog.where(player));
        player.sendMessage(PREFIX + "§e" + guest.name + "§7 now knows of the plot. Near the target, they may give it away.");
        other.sendMessage(PREFIX + "You now know of a plot against §e" + plot.target.name
                + "§7. Near them, your knowledge may show. You may also warn them from the Plots menu.");
        return true;
    }

    public static boolean begin(Player player) {
        Plot plot = mastered(player);
        if (plot == null) return false;
        if (plot.phase != PlotPhase.RECRUITING) {
            player.sendMessage("§cThe plot is not recruiting.");
            return false;
        }
        if (plot.withRole(PlotRole.ASSASSIN).isEmpty()) {
            player.sendMessage("§cRecruit at least one conspirator and make them an assassin first.");
            return false;
        }
        plot.phase = PlotPhase.BUILDING;
        plot.phaseStartedAt = now();
        plot.nextDayAt = now() + PlotConfig.DAY;
        var snapshot = PlotDefense.snapshot(plot);
        plot.required = PlotMath.required(snapshot.protection(), snapshot.betrayal());
        save();
        PlotLog.write(plot, "BEGIN", "required strength " + Math.round(plot.required) + ", protection "
                + Math.round(snapshot.protection()) + ", betrayal " + snapshot.betrayal());
        tellConspirators(plot, "The plot against §e" + plot.target.name + "§7 is under way. It needs about "
                + duration(Math.round(PlotConfig.buildDays() / PlotMath.speed(snapshot) * PlotConfig.DAY)) + " and enough strength.");
        save();
        return true;
    }

    public static boolean launch(Player player) {
        Plot plot = mastered(player);
        if (plot == null) return false;
        if (plot.phase != PlotPhase.READY) {
            player.sendMessage("§cThe plot is not ready to strike.");
            return false;
        }
        if (plot.withRole(PlotRole.ASSASSIN).isEmpty()) {
            player.sendMessage("§cThe plot has no assassin left.");
            return false;
        }
        plot.phase = PlotPhase.INVESTIGATION;
        plot.phaseStartedAt = now();
        save();
        PlotLog.write(plot, "LAUNCH", "strength " + Math.round(plot.strength) + " of " + Math.round(plot.required)
                + ", " + PlotHunt.totalDays(plot) + " hunt days");
        tellConspirators(plot, "Final preparations begin. In " + duration(PlotConfig.investigation())
                + " the assassins may hunt. Until then, §e" + plot.target.name + "§7 may notice something about anyone near them.");
        if (plot.discovered) tell(plot.target, "§cYou feel the danger closing in. Watch the people around you.");
        save();
        return true;
    }

    public static boolean leave(Player player) {
        PlotCharacter me = character(player);
        Plot plot = me == null ? null : store.plotOf(me.characterId);
        if (me == null) return false;
        if (plot == null || plot.master.is(me.characterId)) {
            player.sendMessage("§cYou are not a member of a plot. A plot master abandons it instead.");
            return false;
        }
        if (!plot.phase.organising() && plot.phase != PlotPhase.READY) {
            player.sendMessage("§cIt is too late to back out now.");
            return false;
        }
        leaves(plot, plot.member(me.characterId), "LEAVE");
        player.sendMessage(PREFIX + "You withdraw from the plot. You still know of it.");
        tell(plot.master, "§e" + me.name + "§7 has withdrawn from your plot.");
        save();
        return true;
    }

    public static boolean abandon(Player player) {
        Plot plot = mastered(player);
        if (plot == null) return false;
        end(plot, PlotPhase.DISSOLVED, "Abandoned by the plot master", true);
        player.sendMessage(PREFIX + "You abandon the plot.");
        for (PlotMember member : plot.members)
            tell(member.character, "§e" + plot.master.name + "§7 has abandoned the plot" + against(plot) + ".");
        save();
        return true;
    }

    // ---- Time -----------------------------------------------------------------------------------

    /** Advances every plot's timers; days missed while the server was down are caught up. */
    public static void tick() {
        long now = now();
        boolean changed = PlotHunt.pruneHits(now);
        for (Plot plot : List.copyOf(store.plots)) {
            if (!plot.phase.active()) continue;
            changed |= plot.invitations.values().removeIf(expiry -> expiry < now);
            switch (plot.phase) {
                case REQUESTED -> {
                    if (now >= plot.requestedAt + PlotConfig.requestExpiry()) {
                        end(plot, PlotPhase.DISSOLVED, "The request expired", false);
                        tell(plot.master, "Your plot request §e" + plot.code + "§7 expired without approval.");
                        changed = true;
                    }
                }
                case RECRUITING -> {
                    if (now >= plot.phaseStartedAt + PlotConfig.recruiting()) {
                        end(plot, PlotPhase.DISSOLVED, "Never got under way", true);
                        tellConspirators(plot, "The plot" + against(plot) + " never got under way and has dissolved.");
                        changed = true;
                    }
                }
                case BUILDING -> {
                    while (plot.phase == PlotPhase.BUILDING && now >= plot.nextDayAt) {
                        plot.nextDayAt += PlotConfig.DAY;
                        buildDay(plot);
                        changed = true;
                    }
                }
                case READY -> {
                    while (plot.phase == PlotPhase.READY && now >= plot.nextDayAt) {
                        plot.nextDayAt += PlotConfig.DAY;
                        PlotDefense.watchDay(plot);
                        changed = true;
                    }
                    if (now >= plot.phaseStartedAt + PlotConfig.ready()) {
                        end(plot, PlotPhase.FAILED, "Never launched", true);
                        tellConspirators(plot, "The plot" + against(plot) + " was never launched and has failed.");
                        changed = true;
                    }
                }
                case INVESTIGATION -> {
                    if (now >= plot.phaseStartedAt + PlotConfig.investigation()) {
                        plot.phase = PlotPhase.EXECUTION;
                        plot.phaseStartedAt += PlotConfig.investigation();
                        plot.huntDayStartedAt = plot.phaseStartedAt;
                        PlotLog.write(plot, "HUNT-PHASE", PlotHunt.totalDays(plot) + " hunt days");
                        String message = "§cThe time has come. The assassins may now hunt §e" + plot.target.name
                                + "§c with §e/plot hunt§c or the Plots menu. Only deaths while hunting count, theirs and yours. The hunt lasts "
                                + PlotHunt.totalDays(plot) + " days on which " + plot.target.name + " can be found out in the open.";
                        tell(plot.master, message);
                        for (PlotMember assassin : plot.withRole(PlotRole.ASSASSIN)) tell(assassin.character, message);
                        changed = true;
                    }
                }
                case EXECUTION -> changed |= PlotHunt.tick(plot, now);
                default -> { }
            }
        }
        if (changed) save();
    }

    /** Staff testing: one day passes for this plot only; in the hunt it counts as a hunt day. */
    public static boolean passDay(CommandSender staff, String code) {
        Plot plot = store.plot(code);
        if (plot == null || !plot.phase.active()) {
            staff.sendMessage("§cNo running plot has that code.");
            return false;
        }
        plot.requestedAt -= PlotConfig.DAY;
        plot.phaseStartedAt -= PlotConfig.DAY;
        plot.huntDayStartedAt -= PlotConfig.DAY;
        if (plot.phase == PlotPhase.BUILDING || plot.phase == PlotPhase.READY) plot.nextDayAt = Math.min(plot.nextDayAt, now());
        if (plot.phase == PlotPhase.EXECUTION) plot.huntDays++;
        plot.invitations.replaceAll((id, expiry) -> expiry - PlotConfig.DAY);
        PlotLog.write(plot, "STAFF-DAY", "by " + staff.getName());
        tick();
        staff.sendMessage("§aA day passes for plot " + plot.code + ". " + summary(plot));
        return true;
    }

    static void buildDay(Plot plot) {
        var snapshot = PlotDefense.snapshot(plot);
        boolean leak = PlotMath.day(plot, snapshot, random.get());
        PlotLog.write(plot, "DAY", summary(plot) + ", protection " + Math.round(snapshot.protection()) + (leak ? ", leak" : ""));
        PlotDefense.afterRoll(plot, snapshot, leak);
        if (plot.progress < 100) return;
        if (plot.strength >= plot.required) {
            plot.phase = PlotPhase.READY;
            plot.phaseStartedAt = plot.nextDayAt - PlotConfig.DAY;
            PlotLog.write(plot, "READY", summary(plot));
            tell(plot.master, "The plot" + against(plot) + " is ready. Begin the final phase within "
                    + duration(PlotConfig.ready()) + " from the Plots menu or with §e/plot launch§7. Until then it keeps losing secrecy.");
            for (PlotMember member : plot.members) tell(member.character, "The plot" + against(plot) + " is ready to strike.");
        } else {
            end(plot, PlotPhase.FAILED, "Not enough strength", true);
            tellConspirators(plot, "The plot" + against(plot) + " ran out of time without enough strength and has failed.");
        }
    }

    static List<String> conspiratorNames(Plot plot) {
        List<String> names = new ArrayList<>();
        names.add(plot.master.name + " (plot master)");
        for (PlotMember member : plot.members) names.add(member.character.name);
        return names;
    }

    public static String summary(Plot plot) {
        return plot.phase.label() + ", day " + plot.day + ", progress " + Math.round(plot.progress) + "%, strength "
                + Math.round(plot.strength) + "/" + Math.round(plot.required) + ", secrecy " + Math.round(plot.secrecy) + "%"
                + (plot.phase == PlotPhase.EXECUTION ? ", hunt days " + plot.huntDays + "/" + PlotHunt.totalDays(plot) : "");
    }
}
