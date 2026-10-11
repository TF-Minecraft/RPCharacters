package net.tfminecraft.rpcharacters.plots;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Location;

/** What plots need from SimpleFactions. Without it there are no Spymasters, faction lands or faction formula. */
final class PlotFactions {
    /** A faction's current Spymaster and the Intrigue their office gives them. */
    record Spymaster(PlotCharacter character, int intrigue) {}

    private PlotFactions() {}

    static boolean enabled() {
        return Bukkit.getServer() != null && Bukkit.getPluginManager() != null
                && Bukkit.getPluginManager().isPluginEnabled("SimpleFactions");
    }

    /** The Spymaster of the faction this account belongs to, or null when there is none. */
    static Spymaster spymaster(String playerName) {
        return enabled() ? Factions.spymaster(playerName) : null;
    }

    static boolean sameFaction(String first, String second) {
        return enabled() && Factions.sameFaction(first, second);
    }

    /** Names of every member and the leader of this account's faction. */
    static List<String> members(String playerName) {
        return enabled() ? Factions.members(playerName) : List.of();
    }

    /** True when the player belongs to a warband: a hunt and a battle never mix. */
    static boolean inWarband(org.bukkit.entity.Player player) {
        return enabled() && Factions.inWarband(player);
    }

    /** The faction holding the province at this location, when provinces are on. */
    static String lands(Location at) {
        return enabled() ? Factions.lands(at) : null;
    }

    /** The Spymaster formula with SimpleFactions' configured weights, or null without it. */
    static Integer intrigueRoll(Map<String, Integer> attributes, RandomGenerator random) {
        return enabled() ? Factions.roll(attributes, random) : null;
    }

    private static final class Factions {
        static Spymaster spymaster(String playerName) {
            var faction = net.tfminecraft.simplefactions.managers.FactionManager.getByMember(playerName);
            if (faction == null || !net.tfminecraft.simplefactions.espionage.EspionageService.hasSpymaster(faction)) return null;
            var holder = faction.getEspionage().getSpymaster();
            if (holder.characterId == null || holder.playerId == null) return null;
            var character = new PlotCharacter(holder.characterId, holder.playerId, holder.playerName,
                    nameOf(holder.playerId, holder.characterId, holder.playerName));
            return new Spymaster(character, net.tfminecraft.simplefactions.espionage.EspionageService.effectiveAptitude(faction, holder));
        }

        private static String nameOf(UUID account, String characterId, String fallback) {
            var data = net.tfminecraft.rpcharacters.managers.PlayerManager.get(account);
            var character = data == null ? null : data.getCharacterById(characterId);
            return character == null ? fallback : character.getName();
        }

        static boolean sameFaction(String first, String second) {
            var faction = net.tfminecraft.simplefactions.managers.FactionManager.getByMember(first);
            return faction != null && faction == net.tfminecraft.simplefactions.managers.FactionManager.getByMember(second);
        }

        static List<String> members(String playerName) {
            var faction = net.tfminecraft.simplefactions.managers.FactionManager.getByMember(playerName);
            if (faction == null) return List.of();
            var names = new java.util.ArrayList<String>(faction.getMembers());
            if (faction.getLeader() != null && names.stream().noneMatch(faction.getLeader()::equalsIgnoreCase)) names.add(faction.getLeader());
            return names;
        }

        static boolean inWarband(org.bukkit.entity.Player player) {
            return net.tfminecraft.simplefactions.war.battle.warband.WarbandManager.getByMemberId(player.getUniqueId()) != null;
        }

        static String lands(Location at) {
            var plugin = net.tfminecraft.simplefactions.SimpleFactions.getInstance();
            if (plugin == null || !net.tfminecraft.simplefactions.Cache.provincesEnabled || at.getWorld() == null
                    || !at.getWorld().getName().equals(net.tfminecraft.simplefactions.Cache.worldName)) return null;
            var grid = plugin.getProvinceGrid();
            var provinces = plugin.getProvinceManager();
            if (grid == null || provinces == null) return null;
            int id = grid.getAt(at.getBlockX(), at.getBlockZ());
            var owner = id > 0 && provinces.contains(id) ? provinces.get(id).getOwner() : null;
            return owner == null ? null : owner.getName();
        }

        static Integer roll(Map<String, Integer> attributes, RandomGenerator random) {
            return net.tfminecraft.simplefactions.espionage.EspionageMath.aptitude(attributes, random);
        }
    }
}
