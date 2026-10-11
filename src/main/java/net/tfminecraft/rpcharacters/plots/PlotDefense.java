package net.tfminecraft.rpcharacters.plots;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.entity.Player;

/**
 * Who stands between a character and the plots against them. A faction Spymaster who lists them comes
 * first, then their confidant, then they watch out for themselves. A Spymaster or confidant hears of
 * leaks first and decides whether to warn; an inactive one leaves the character to defend themselves.
 */
public final class PlotDefense {
    /** The defender actually rolling for a character right now. */
    record Guard(Defender kind, PlotCharacter character, int defense) {}
    record Protection(double value, Betrayal betrayal) {}

    private PlotDefense() {}

    private static PlotStore store() { return PlotService.store(); }

    /** The defender for this character; an absent protector leaves them on their own. */
    static Guard guard(PlotCharacter target) {
        long now = PlotService.now();
        var spymaster = PlotFactions.spymaster(target.playerName);
        if (spymaster != null && !spymaster.character().is(target.characterId)
                && store().wardsOf(spymaster.character().characterId).stream().anyMatch(ward -> ward.is(target.characterId))
                && PlotCharacters.active(spymaster.character().playerId, now))
            return new Guard(Defender.SPYMASTER, spymaster.character(),
                    PlotMath.defense(spymaster.intrigue(), store().peek(spymaster.character().characterId).approach()));
        PlotCharacter confidant = store().peek(target.characterId).confidant;
        if (confidant != null && !PlotCharacters.dead(confidant) && PlotCharacters.active(confidant.playerId, now))
            return new Guard(Defender.CONFIDANT, confidant, PlotService.defense(confidant));
        return new Guard(Defender.SELF, target, PlotService.defense(target));
    }

    static Protection protection(Plot plot) { return protection(plot.target, plot); }

    /** The character's protection; without a plot nobody can betray them. */
    static Protection protection(PlotCharacter target, Plot plot) {
        Guard guard = guard(target);
        boolean betrayed = plot != null && plot.involves(guard.character().characterId);
        return switch (guard.kind()) {
            case SPYMASTER -> betrayed ? new Protection(0, Betrayal.SPYMASTER) : new Protection(PlotMath.withSpymaster(guard.defense()), Betrayal.NONE);
            case CONFIDANT -> betrayed
                    ? new Protection(PlotMath.betrayedByConfidant(PlotService.defense(target), PlotService.offense(guard.character())), Betrayal.CONFIDANT)
                    : new Protection(PlotMath.withConfidant(PlotService.defense(target), guard.defense()), Betrayal.NONE);
            default -> new Protection(PlotMath.alone(guard.defense()), Betrayal.NONE);
        };
    }

    static PlotMath.Snapshot snapshot(Plot plot) {
        List<PlotMath.Contributor> contributors = new ArrayList<>();
        contributors.add(new PlotMath.Contributor(PlotRole.MASTER, null, PlotService.offense(plot.master)));
        for (PlotMember member : plot.members)
            contributors.add(new PlotMath.Contributor(member.role, member.task, PlotService.offense(member.character)));
        Protection protection = protection(plot);
        return new PlotMath.Snapshot(contributors, protection.value(), protection.betrayal());
    }

    /** A waiting plot keeps losing secrecy, and the defender keeps rolling against it. */
    static void watchDay(Plot plot) {
        var snapshot = snapshot(plot);
        boolean leak = PlotMath.watch(plot, snapshot, PlotService.random.get());
        PlotLog.write(plot, "WAIT-DAY", "secrecy " + Math.round(plot.secrecy) + "%, protection " + Math.round(snapshot.protection())
                + (leak ? ", leak" : ""));
        afterRoll(plot, snapshot, leak);
    }

    /**
     * What a won defence roll, or secrecy reaching zero, gives away. It reaches the Spymaster or confidant
     * if one is on watch, and the target directly when alone or once warned. A betrayed target may also
     * sense that their protector is lying.
     */
    static void afterRoll(Plot plot, PlotMath.Snapshot snapshot, boolean leak) {
        suspect(plot, snapshot.betrayal());
        boolean exposing = plot.secrecy <= 0 && !plot.exposed && snapshot.betrayal() != Betrayal.SPYMASTER;
        if (!leak && !exposing) return;
        plot.exposed |= exposing;
        List<String> names = PlotService.conspiratorNames(plot);
        Guard guard = guard(plot.target);
        if (guard.kind() != Defender.SELF) {
            plot.guardKnows = true;
            List<String> lines = PlotMath.reveal(plot, plot.guardKnowledge, names);
            if (lines.isEmpty()) lines = List.of(plot.guardKnowledge.get("threat"));
            for (String line : lines) PlotService.tell(guard.character(), "§c" + forGuard(plot, line));
            PlotLog.write(plot, "LEAK", "to " + guard.kind() + " " + PlotLog.who(guard.character()) + ": " + String.join(" ", lines));
        }
        if (guard.kind() == Defender.SELF || plot.discovered) {
            plot.discovered = true;
            List<String> lines = PlotMath.reveal(plot, plot.knowledge, names);
            if (lines.isEmpty()) lines = List.of(plot.knowledge.get("threat"));
            for (String line : lines) PlotService.tell(plot.target, "§c" + line);
            PlotLog.write(plot, "LEAK", "to the target: " + String.join(" ", lines));
        }
        if (leak) PlotService.tell(plot.master, "Whispers reach you: someone watching over §e" + plot.target.name
                + "§7 suspects that they are in danger.");
        if (exposing) PlotService.tellConspirators(plot, "§cThe plot is exposed: the names of its conspirators are out.");
    }

    /** A leak line as the Spymaster or confidant reads it: about their ward, not themselves. */
    static String forGuard(Plot plot, String line) {
        return "§e" + plot.target.name + "§c: " + line.replace("your life", "their life").replace("against you", "against them");
    }

    /** Once per plot, a betrayed character may notice that their protector's reports don't add up. */
    private static void suspect(Plot plot, Betrayal betrayal) {
        if (betrayal == Betrayal.NONE || plot.suspicionRaised) return;
        Guard guard = guard(plot.target);
        int margin = PlotMath.suspicion(PlotService.defense(plot.target), PlotService.offense(guard.character()), PlotService.random.get());
        PlotLog.write(plot, "SUSPICION", PlotLog.who(plot.target) + " against " + PlotLog.who(guard.character()) + ", margin " + margin);
        if (margin < 0) return;
        plot.suspicionRaised = true;
        PlotService.tell(plot.target, "§cSomething about §e" + guard.character().name + "§c's reports doesn't add up.");
    }

    /** The character's Spymaster or confidant tells them what they know of the plots against them. */
    public static boolean warnWard(Player player, PlotCharacter ward) {
        PlotCharacter me = PlotService.character(player);
        if (me == null) return false;
        Guard guard = guard(ward);
        List<Plot> known = store().against(ward.characterId).stream().filter(plot -> plot.guardKnows).toList();
        if (guard.kind() == Defender.SELF || !guard.character().is(me.characterId) || known.isEmpty()) {
            player.sendMessage("§cYou know of no plot against them to warn them of.");
            return false;
        }
        for (Plot plot : known) {
            plot.discovered = true;
            plot.knowledge.putAll(plot.guardKnowledge);
            PlotLog.write(plot, "WARN", PlotLog.who(me) + " (" + guard.kind() + ") warned " + PlotLog.who(ward));
            PlotService.tell(ward, "§e" + me.name + "§7 warns you:");
            for (String line : plot.guardKnowledge.values()) PlotService.tell(ward, "§c" + line);
        }
        PlotService.save();
        player.sendMessage(PlotService.PREFIX + "You warn §e" + ward.name + "§7 of what you know.");
        return true;
    }

    /** Someone who knows of a plot, in person, warns its target. */
    public static boolean warnTarget(Player player, Player other) {
        PlotCharacter me = PlotService.character(player);
        if (me == null) return false;
        PlotCharacter target = PlotService.other(player, other, "warn them");
        if (target == null) return false;
        List<Plot> known = store().against(target.characterId).stream().filter(plot -> plot.informed(me.characterId) != null).toList();
        if (known.isEmpty()) {
            player.sendMessage("§cYou know of no plot against them.");
            return false;
        }
        String seen = PlotCharacters.seenAs(player, me);
        for (Plot plot : known) {
            plot.discovered = true;
            plot.knowledge.put("threat", "Someone is plotting against your life.");
            plot.knowledge.put("warned-" + me.characterId, seen + " warned you that someone plots against your life.");
            PlotLog.write(plot, "INFORMANT", PlotLog.who(me) + " warned " + PlotLog.who(target) + " at " + PlotLog.where(player));
            PlotService.tell(plot.master, "Whispers reach you: someone has warned §e" + target.name + "§7.");
        }
        PlotService.save();
        other.sendMessage(PlotService.PREFIX + "§e" + seen + "§c warns you that someone plots against your life.");
        player.sendMessage(PlotService.PREFIX + "You warn §e" + target.name + "§7.");
        return true;
    }

    /** True when the character knows of at least one plot against them. */
    public static boolean threatened(String characterId) {
        return store().against(characterId).stream().anyMatch(plot -> plot.discovered);
    }

    public static boolean toggleInvestigator(Player player, Player other) {
        PlotCharacter me = PlotService.character(player);
        if (me == null) return false;
        if (!threatened(me.characterId)) {
            player.sendMessage("§cYou know of no plot against your life.");
            return false;
        }
        IntrigueSettings settings = store().settings(me.characterId);
        PlotCharacter known = other == null ? null : PlotCharacters.active(other);
        if (known != null && settings.investigators.removeIf(character -> character.is(known.characterId))) {
            PlotService.save();
            player.sendMessage(PlotService.PREFIX + "§e" + known.name + "§7 no longer investigates for you.");
            return true;
        }
        if (settings.investigators.size() >= PlotConfig.maxInvestigators()) {
            player.sendMessage("§cYou can have at most " + PlotConfig.maxInvestigators() + " investigators.");
            return false;
        }
        PlotCharacter investigator = PlotService.other(player, other, "ask for their help");
        if (investigator == null) return false;
        settings.investigators.add(investigator);
        PlotService.save();
        player.sendMessage(PlotService.PREFIX + "§e" + investigator.name + "§7 now investigates the plot against you.");
        other.sendMessage(PlotService.PREFIX + "§e" + me.name + "§7 has asked you to look into a plot against their life. "
                + "Stay close to the people around them.");
        return true;
    }

    // ---- Confidants -----------------------------------------------------------------------------

    private static final java.util.Map<String, String> requests = new java.util.HashMap<>();

    /** Asks another character, in person, to be this character's confidant. */
    public static boolean askConfidant(Player player, Player other) {
        PlotCharacter me = PlotService.character(player);
        if (me == null) return false;
        IntrigueSettings settings = store().settings(me.characterId);
        long wait = settings.confidantChangedAt + PlotConfig.confidantCooldown() - PlotService.now();
        if (settings.confidantChangedAt > 0 && wait > 0) {
            player.sendMessage("§cYou can choose a confidant again in " + PlotService.duration(wait) + ".");
            return false;
        }
        PlotCharacter confidant = PlotService.other(player, other, "ask them");
        if (confidant == null) return false;
        if (store().confidantFor(confidant.characterId) != null) {
            player.sendMessage("§cThey already watch over someone.");
            return false;
        }
        requests.put(confidant.characterId, me.characterId);
        player.sendMessage(PlotService.PREFIX + "You ask §e" + confidant.name + "§7 to watch over you.");
        other.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                .deserialize(PlotService.PREFIX + "§e" + PlotCharacters.seenAs(player, me) + "§7 asks you to be their confidant: to watch for plots against them. ")
                .append(net.kyori.adventure.text.Component.text("[Accept]", net.kyori.adventure.text.format.NamedTextColor.GREEN)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/plot confidant accept"))));
        return true;
    }

    public static boolean acceptConfidant(Player player) {
        PlotCharacter me = PlotService.character(player);
        if (me == null) return false;
        String wardId = requests.remove(me.characterId);
        Player ward = wardId == null ? null : playing(wardId);
        if (ward == null || store().confidantFor(me.characterId) != null) {
            player.sendMessage("§cNobody is waiting for your answer.");
            return false;
        }
        IntrigueSettings settings = store().settings(wardId);
        settings.confidant = me;
        settings.confidantChangedAt = PlotService.now();
        PlotService.intrigue(me);
        PlotService.save();
        player.sendMessage(PlotService.PREFIX + "You now watch over §e" + PlotCharacters.active(ward).name + "§7. You will hear of plots against them first.");
        ward.sendMessage(PlotService.PREFIX + "§e" + me.name + "§7 is now your confidant.");
        return true;
    }

    /** Either side ends the bond: the protected character, or the confidant. */
    public static boolean endConfidant(Player player) {
        PlotCharacter me = PlotService.character(player);
        if (me == null) return false;
        String wardId = store().confidantFor(me.characterId);
        IntrigueSettings own = store().peek(me.characterId);
        if (own.confidant != null) {
            PlotService.tell(own.confidant, "§e" + me.name + "§7 no longer needs you as their confidant.");
            own.confidant = null;
            own.confidantChangedAt = PlotService.now();
        } else if (wardId != null) {
            IntrigueSettings ward = store().settings(wardId);
            PlotCharacter left = ward.confidant;
            ward.confidant = null;
            ward.confidantChangedAt = PlotService.now();
            Player online = playing(wardId);
            PlotService.tell(new PlotCharacter(wardId, online == null ? null : online.getUniqueId(), null, ""),
                    "§e" + left.name + "§7 no longer watches over you.");
        } else {
            player.sendMessage("§cYou have no confidant and watch over nobody.");
            return false;
        }
        PlotService.save();
        player.sendMessage(PlotService.PREFIX + "The bond is ended.");
        return true;
    }

    /** The online player currently playing this character, or null. */
    static Player playing(String characterId) {
        for (Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
            PlotCharacter active = PlotCharacters.active(online);
            if (active != null && active.is(characterId)) return online;
        }
        return null;
    }

    // ---- Spymaster wards ------------------------------------------------------------------------

    /** The Spymaster stops protecting a ward, who may be offline. */
    public static boolean removeWard(Player player, String characterId) {
        PlotCharacter me = PlotService.character(player);
        if (me == null) return false;
        PlotCharacter removed = null;
        for (PlotCharacter ward : store().wardsOf(me.characterId)) if (ward.is(characterId)) removed = ward;
        if (removed == null) {
            player.sendMessage("§cThey are not under your protection.");
            return false;
        }
        store().wardsOf(me.characterId).remove(removed);
        PlotService.save();
        PlotService.tell(removed, "Your Spymaster no longer watches over you.");
        player.sendMessage(PlotService.PREFIX + "§e" + removed.name + "§7 is no longer under your protection.");
        return true;
    }

    /** The faction Spymaster adds or removes a faction member's active character from their protection. */
    public static boolean toggleWard(Player player, Player other) {
        PlotCharacter me = PlotService.character(player);
        if (me == null) return false;
        var spymaster = PlotFactions.spymaster(player.getName());
        if (spymaster == null || !spymaster.character().is(me.characterId)) {
            player.sendMessage("§cOnly your faction's Spymaster protects faction members.");
            return false;
        }
        PlotCharacter ward = other == null ? null : PlotCharacters.active(other);
        if (ward == null || ward.playerId.equals(me.playerId) || !PlotFactions.sameFaction(player.getName(), other.getName())) {
            player.sendMessage("§cChoose another living member of your faction.");
            return false;
        }
        List<PlotCharacter> wards = store().wardsOf(me.characterId);
        if (wards.removeIf(existing -> existing.is(ward.characterId))) {
            PlotService.save();
            player.sendMessage(PlotService.PREFIX + "§e" + ward.name + "§7 is no longer under your protection.");
            other.sendMessage(PlotService.PREFIX + "Your Spymaster no longer watches over you.");
            return true;
        }
        PlotService.intrigue(me);
        wards.add(ward);
        PlotService.save();
        player.sendMessage(PlotService.PREFIX + "§e" + ward.name + "§7 is now under your protection. You will hear of plots against them first.");
        other.sendMessage(PlotService.PREFIX + "Your Spymaster, §e" + me.name + "§7, now watches over you.");
        return true;
    }
}
