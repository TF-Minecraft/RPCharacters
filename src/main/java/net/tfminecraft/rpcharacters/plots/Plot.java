package net.tfminecraft.rpcharacters.plots;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One plot from request to outcome. Bars run from 0 to 100; strength has no upper bound. */
public final class Plot {
    public String code;
    public String type = "assassination";
    public PlotPhase phase = PlotPhase.REQUESTED;
    public PlotCharacter master;
    public PlotCharacter target;
    public List<PlotMember> members = new ArrayList<>();
    /** Characters who know of the plot without being part of it. */
    public List<PlotCharacter> informed = new ArrayList<>();
    public Map<String, Long> invitations = new LinkedHashMap<>();
    public Set<String> investigated = new LinkedHashSet<>();
    public long requestedAt, phaseStartedAt, nextDayAt;
    public int day;
    public double progress, strength, required, secrecy = 100;
    /** discovered: the target knows; guardKnows: their Spymaster or confidant does, and may not have said. */
    public boolean discovered, exposed, guardKnows, suspicionRaised;
    /** What the target has learned, keyed so a newer leak replaces an older line of the same kind. */
    public Map<String, String> knowledge = new LinkedHashMap<>();
    /** What the target's Spymaster or confidant has learned and may pass on. */
    public Map<String, String> guardKnowledge = new LinkedHashMap<>();
    /** Hunt days counted so far, the start of the current one, and whether it has been counted. */
    public int huntDays;
    public long huntDayStartedAt;
    public boolean dayCounted;
    public String outcome;
    /** Time the target has been findable today; a restart starts the day's count again. */
    public transient long exposure, exposureCheckedAt, sightedAt;
    public transient boolean wasFindable;

    public PlotMember member(String characterId) {
        for (PlotMember member : members) if (member.character.is(characterId)) return member;
        return null;
    }

    /** Master and recruited members. */
    public boolean involves(String characterId) { return master.is(characterId) || member(characterId) != null; }

    /** Any character of this account leads or has joined the plot. */
    public boolean involvesAccount(java.util.UUID account) {
        if (account == null) return false;
        if (account.equals(master.playerId)) return true;
        for (PlotMember member : members) if (account.equals(member.character.playerId)) return true;
        return false;
    }

    public PlotRole role(String characterId) {
        if (master.is(characterId)) return PlotRole.MASTER;
        PlotMember member = member(characterId);
        if (member != null) return member.role;
        for (PlotCharacter character : informed) if (character.is(characterId)) return PlotRole.INFORMED;
        return null;
    }

    public PlotCharacter informed(String characterId) {
        for (PlotCharacter character : informed) if (character.is(characterId)) return character;
        return null;
    }

    public List<PlotMember> withRole(PlotRole role) {
        return members.stream().filter(member -> member.role == role).toList();
    }

    public int conspirators() { return members.size() + 1; }

    public boolean isTarget(String characterId) { return target != null && target.is(characterId); }

    /** Everyone the target's side can sense: master, members and informed characters. */
    public List<PlotCharacter> suspects() {
        List<PlotCharacter> result = new ArrayList<>();
        result.add(master);
        members.forEach(member -> result.add(member.character));
        result.addAll(informed);
        return result;
    }

    /** Strength beyond what was needed, as a ratio of at least 1. */
    public double surplus() {
        return required <= 0 ? 2 : Math.max(1, strength / required);
    }
}
