package net.tfminecraft.rpcharacters.plots;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

/** The Plots menu and its sub-menus. Every slot with an item may carry its own click action. */
@SuppressWarnings("deprecation")
public final class PlotMenu {
    private PlotMenu() {}

    enum Purpose { INVITE, INFORM, CONFIDANT, INVESTIGATOR, WARD, WARN }

    static final class Holder implements InventoryHolder {
        final Map<Integer, Consumer<ClickType>> actions = new HashMap<>();
        private Inventory inventory;

        @Override
        public Inventory getInventory() { return inventory; }

        void set(int slot, ItemStack item, Consumer<ClickType> action) {
            inventory.setItem(slot, item);
            if (action != null) actions.put(slot, action);
        }
    }

    private static Holder create(int rows, String title) {
        Holder holder = new Holder();
        holder.inventory = Bukkit.createInventory(holder, rows * 9, title);
        return holder;
    }

    static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    static ItemStack item(Material material, String name, String... lore) { return item(material, name, Arrays.asList(lore)); }

    static String bar(double value) {
        int filled = (int) Math.max(0, Math.min(10, Math.round(value / 10)));
        return "§a" + "|".repeat(filled) + "§8" + "|".repeat(10 - filled) + " §7" + Math.round(value) + "%";
    }

    private static String left(long until) { return PlotService.duration(Math.max(0, until - PlotService.now())); }

    private static String guardName(PlotDefense.Guard guard) {
        return guard.kind() == Defender.SELF ? Defender.SELF.label() : guard.kind().label() + ", " + guard.character().name;
    }

    /** Intrigue, approach and defender of a character the viewer owns. */
    public static ItemStack intrigueItem(PlotCharacter character, Integer intrigue) {
        PlotApproach approach = PlotService.store().peek(character.characterId).approach();
        List<String> lore = new ArrayList<>();
        if (intrigue == null) lore.add("§7Not yet known. It is rolled the next time you play this character.");
        else {
            lore.add("§7When plotting: §e" + PlotMath.offense(intrigue, approach));
            lore.add("§7When defending: §e" + PlotMath.defense(intrigue, approach));
        }
        lore.add("§7Approach: §e" + approach.label());
        lore.add("§8Rolled once for life. A Spymaster serves with it too.");
        return item(Material.SPYGLASS, "§5Intrigue: §e" + (intrigue == null ? "?" : intrigue + "/100"), lore);
    }

    /** The Intrigue item for one of the player's characters: rolled now if it is the one being played. */
    public static ItemStack intrigueFor(Player player, net.tfminecraft.rpcharacters.objects.RPCharacter character) {
        PlotCharacter plotCharacter = new PlotCharacter(character.getId(), player.getUniqueId(), player.getName(), character.getName());
        return intrigueItem(plotCharacter, character.isActive() ? Integer.valueOf(PlotService.intrigue(plotCharacter)) : Intrigue.known(character.getId()));
    }

    /** Shown instead of the switch button to a hunted character who knows it. */
    public static ItemStack lockedItem() {
        return item(Material.RED_CONCRETE, "§cNot now", "§7Someone is hunting your life.", "§7Face it as this character.");
    }

    public static void sendLocked(Player player) {
        player.sendMessage(PlotService.PREFIX + "§cSomeone is hunting your life. Face it as this character.");
    }

    /** The Character Info button that opens this menu. */
    public static ItemStack button() {
        return item(Material.IRON_SWORD, "§5Plots", "§7Your approach, who watches over you,",
                "§7threats against you and your own plots.", "§7Click to open.");
    }

    public static void open(Player player) {
        PlotCharacter me = PlotService.character(player);
        if (me == null) return;
        PlotStore store = PlotService.store();
        IntrigueSettings settings = store.peek(me.characterId);
        Holder holder = create(6, "§5Intrigue & Plots");
        holder.set(4, intrigueItem(me, PlotService.intrigue(me)), null);
        PlotApproach next = settings.approach().next();
        holder.set(2, item(Material.COMPASS, "§5Approach: §e" + settings.approach().label(),
                "§7Offensive favours your plots, Defensive your", "§7safety, Neutral both a little.",
                "§7Click to change to §e" + next.label() + "§7."), click -> {
            PlotService.setApproach(player, next);
            open(player);
        });
        var guard = PlotDefense.guard(me);
        holder.set(6, item(Material.SHIELD, "§5Watched over by: §e" + guardName(guard),
                "§7Who rolls against plots aimed at you,", "§7and hears of them first.",
                "§7Protection now: §e" + Math.round(PlotDefense.protection(me, null).value()), "§7Click for your confidant."),
                click -> confidant(player));
        protecting(holder, player, me);
        Plot plot = store.plotOf(me.characterId);
        if (plot == null) holder.set(22, item(Material.IRON_SWORD, "§5Begin a plot",
                "§7Plan a plot against another character.", "§7Staff approve each plot through a ticket."), click -> types(player));
        else plot(holder, player, me, plot);
        threats(holder, player, me);
        known(holder, player, me);
        int slot = 45;
        for (Plot invited : store.plots) {
            if (slot > 48 || !invited.phase.organising() || !invited.invitations.containsKey(me.characterId)) continue;
            holder.set(slot++, item(Material.PAPER, "§5Proposal from §e" + invited.master.name,
                    "§7A plot. You learn against whom if you join.", "§aLeft-click to join. §cRight-click to decline."), click -> {
                if (click.isRightClick()) PlotService.decline(player, invited.code);
                else PlotService.accept(player, invited.code);
                open(player);
            });
        }
        holder.set(49, item(Material.BARRIER, "§cClose"), click -> player.closeInventory());
        player.openInventory(holder.inventory);
    }

    /** For a Spymaster their wards, for a confidant the one they watch over: threats heard and a warning to give. */
    private static void protecting(Holder holder, Player player, PlotCharacter me) {
        var spymaster = PlotFactions.spymaster(player.getName());
        if (spymaster != null && spymaster.character().is(me.characterId)) {
            List<String> lore = new ArrayList<>();
            for (PlotCharacter ward : PlotService.store().wardsOf(me.characterId)) lore.add("§7" + ward.name + threatNote(ward));
            if (lore.isEmpty()) lore.add("§7Nobody yet.");
            lore.add("§7Click to manage.");
            holder.set(8, item(Material.ENDER_EYE, "§5Under your protection", lore), click -> wards(player));
            return;
        }
        String wardId = PlotService.store().confidantFor(me.characterId);
        if (wardId == null) return;
        PlotCharacter ward = wardOf(wardId);
        holder.set(8, item(Material.ENDER_EYE, "§5You watch over §e" + ward.name, "§7" + ward.name + threatNote(ward),
                "§7Click to warn them of what you know."), click -> {
            PlotDefense.warnWard(player, ward);
            open(player);
        });
    }

    private static PlotCharacter wardOf(String characterId) {
        for (Plot plot : PlotService.store().plots) if (plot.isTarget(characterId)) return plot.target;
        Player online = PlotDefense.playing(characterId);
        return online == null ? new PlotCharacter(characterId, null, null, "your charge") : PlotCharacters.active(online);
    }

    private static String threatNote(PlotCharacter ward) {
        long known = PlotService.store().against(ward.characterId).stream().filter(plot -> plot.guardKnows).count();
        return known == 0 ? "" : " §c(" + known + " plot" + (known == 1 ? "" : "s") + " heard of)";
    }

    private static void plot(Holder holder, Player player, PlotCharacter me, Plot plot) {
        boolean master = plot.master.is(me.characterId);
        if (plot.phase == PlotPhase.REQUESTED) {
            holder.set(22, item(Material.WRITABLE_BOOK, "§5Plot request §e" + plot.code,
                    "§7Open a ticket naming your target", "§7and give staff this code."), null);
            holder.set(31, item(Material.TNT, "§cWithdraw the request", "§7Shift-click to confirm."), click -> {
                if (click.isShiftClick()) PlotService.abandon(player);
                open(player);
            });
            return;
        }
        List<String> lore = new ArrayList<>(List.of("§7Target: §e" + plot.target.name, "§7Stage: §e" + plot.phase.label(),
                "§7Your role: §e" + (master ? PlotRole.MASTER.label() : plot.member(me.characterId).roleLabel())));
        if (plot.phase == PlotPhase.RECRUITING) lore.add("§7Start it within §e" + left(plot.phaseStartedAt + PlotConfig.recruiting()));
        if (plot.phase.ordinal() >= PlotPhase.BUILDING.ordinal()) {
            lore.add("§7Plot: " + bar(plot.progress));
            lore.add("§7Strength: §e" + Math.round(plot.strength) + "§7 of §e" + Math.round(plot.required) + "§7 needed");
            lore.add("§7Secrecy: " + bar(plot.secrecy));
        }
        if (plot.phase == PlotPhase.READY) lore.add("§7Begin the final phase within §e" + left(plot.phaseStartedAt + PlotConfig.ready()));
        if (plot.phase == PlotPhase.INVESTIGATION) lore.add("§7Assassins may strike in §e" + left(plot.phaseStartedAt + PlotConfig.investigation()));
        if (plot.phase == PlotPhase.EXECUTION) {
            lore.add("§7Hunt days: §e" + plot.huntDays + "§7 of §e" + PlotHunt.totalDays(plot)
                    + (plot.dayCounted ? "§7 (today counted)" : "§7 (today not yet)"));
            lore.add("§8A day counts once the target has been out in the open");
            lore.add("§8for " + PlotService.duration(PlotConfig.findable()) + " that day.");
            lore.add("§7The plot ends at the latest in §e" + left(plot.phaseStartedAt + PlotConfig.maxHunt()));
            lore.add("§7Assassins hunting: §e" + plot.withRole(PlotRole.ASSASSIN).stream().filter(PlotHunt::hunting).count());
        }
        holder.set(22, item(Material.IRON_SWORD, "§5Assassination plot", lore), null);
        List<String> roster = new ArrayList<>(List.of("§7" + plot.master.name + " §8- " + PlotRole.MASTER.label()));
        for (PlotMember member : plot.members) roster.add("§7" + member.character.name + " §8- " + member.roleLabel());
        for (PlotCharacter informed : plot.informed) roster.add("§8" + informed.name + " - " + PlotRole.INFORMED.label());
        boolean organising = master && plot.phase.organising();
        if (organising) roster.add("§7Click to give roles.");
        holder.set(20, item(Material.PLAYER_HEAD, "§5Conspirators: §e" + plot.conspirators(), roster), organising ? click -> members(player) : null);
        if (organising) holder.set(29, item(Material.WRITABLE_BOOK, "§5Propose the plot to someone",
                "§7Choose a character near you."), click -> nearby(player, Purpose.INVITE));
        holder.set(33, item(Material.BOOK, "§5Mark someone as told", "§7For a character who learned of the plot",
                "§7in roleplay. Near the target, they", "§7may give it away."), click -> nearby(player, Purpose.INFORM));
        if (master && plot.phase == PlotPhase.RECRUITING) holder.set(24, item(Material.LIME_DYE, "§aStart the plot",
                "§7Needs at least one assassin."), click -> {
            PlotService.begin(player);
            open(player);
        });
        if (master && plot.phase == PlotPhase.READY) holder.set(24, item(Material.REDSTONE, "§cBegin the final phase",
                "§7The target may notice the conspirators", "§7for " + PlotService.duration(PlotConfig.investigation())
                        + ", then the assassins may hunt."), click -> {
            PlotService.launch(player);
            open(player);
        });
        if (!master && (plot.phase.organising() || plot.phase == PlotPhase.READY))
            holder.set(24, item(Material.OAK_DOOR, "§cWithdraw from the plot", "§7You will still know of it.", "§7Shift-click to confirm."), click -> {
                if (click.isShiftClick()) PlotService.leave(player);
                open(player);
            });
        PlotMember own = plot.member(me.characterId);
        if (own != null && own.role == PlotRole.ASSASSIN && plot.phase == PlotPhase.EXECUTION)
            holder.set(24, own.huntingSince > 0
                    ? item(Material.CROSSBOW, "§cHunting §e" + plot.target.name, PlotHunt.hunting(own)
                            ? "§7The hunt is on: their death or yours counts." : "§7The hunt takes effect shortly.",
                            "§7Click to call it off once you may.")
                    : item(Material.CROSSBOW, "§aHunt §e" + plot.target.name, "§7Only deaths while hunting count, theirs and yours.",
                            "§7Takes effect after " + PlotService.duration(PlotConfig.huntWarmup()) + "; you can't call it off for "
                                    + PlotService.duration(PlotConfig.huntMinimum()) + "."), click -> {
                PlotHunt.hunt(player);
                open(player);
            });
        if (master) holder.set(31, item(Material.TNT, "§cAbandon the plot", "§7Shift-click to confirm."), click -> {
            if (click.isShiftClick()) PlotService.abandon(player);
            open(player);
        });
    }

    private static void threats(Holder holder, Player player, PlotCharacter me) {
        List<String> lore = new ArrayList<>();
        for (Plot plot : PlotService.store().against(me.characterId)) lore.addAll(plot.knowledge.values());
        if (lore.isEmpty()) return;
        holder.set(40, item(Material.WITHER_ROSE, "§cThreats against you", lore.stream().map(line -> "§7" + line).toList()), null);
        List<String> investigators = new ArrayList<>();
        for (PlotCharacter character : PlotService.store().peek(me.characterId).investigators) investigators.add("§7" + character.name);
        investigators.add("§7They roll, like you, near anyone involved");
        investigators.add("§7once the plot's final preparations begin.");
        investigators.add("§7Click to choose (up to " + PlotConfig.maxInvestigators() + ").");
        holder.set(42, item(Material.ENDER_EYE, "§5Investigators", investigators), click -> nearby(player, Purpose.INVESTIGATOR));
    }

    /** Plots this character knows of without taking part: they may warn the target in person. */
    private static void known(Holder holder, Player player, PlotCharacter me) {
        List<String> lore = new ArrayList<>();
        for (Plot plot : PlotService.store().plots)
            if (plot.phase.active() && plot.informed(me.characterId) != null) lore.add("§7A plot against §e" + plot.target.name);
        if (lore.isEmpty()) return;
        lore.add("§7Click to warn the target, in person.");
        holder.set(38, item(Material.PAPER, "§5Plots you know of", lore), click -> nearby(player, Purpose.WARN));
    }

    static void types(Player player) {
        Holder holder = create(3, "§5Begin a plot");
        holder.set(13, item(Material.IRON_SWORD, "§5Assassination", "§7Up to " + PlotConfig.maxAssassins() + " assassins, "
                        + PlotConfig.maxCollaborators() + " collaborators and you.",
                "§7If it succeeds, the target dies for good;", "§7so does any assassin who falls while hunting.",
                "§7Click for a code to give staff in a ticket."), click -> {
            PlotService.request(player);
            player.closeInventory();
        });
        holder.set(22, item(Material.ARROW, "§7Back"), click -> open(player));
        player.openInventory(holder.inventory);
    }

    /** Asking for a confidant, or ending the bond. */
    static void confidant(Player player) {
        PlotCharacter me = PlotService.character(player);
        if (me == null) return;
        Holder holder = create(3, "§5Your confidant");
        PlotCharacter confidant = PlotService.store().peek(me.characterId).confidant;
        holder.set(11, item(Material.PLAYER_HEAD, "§5Ask someone near you",
                "§7A confidant watches over only you:", "§7protection " + Math.round(PlotConfig.confidantShare() * 100)
                        + "% of your Intrigue and theirs,", "§7averaged. They hear of plots first.",
                "§cShould they join a plot against you,", "§ctheirs counts against you."), click -> nearby(player, Purpose.CONFIDANT));
        if (confidant != null || PlotService.store().confidantFor(me.characterId) != null)
            holder.set(15, item(Material.BARRIER, "§cEnd the bond", confidant == null ? "§7Stop watching over your charge."
                    : "§7Your confidant: §e" + confidant.name, "§7Shift-click to confirm."), click -> {
                if (click.isShiftClick()) PlotDefense.endConfidant(player);
                open(player);
            });
        holder.set(13, item(Material.SPYGLASS, "§5Your Spymaster", "§7Your faction's Spymaster chooses whom",
                "§7they protect, at their full office Intrigue."), null);
        holder.set(22, item(Material.ARROW, "§7Back"), click -> open(player));
        player.openInventory(holder.inventory);
    }

    /** The Spymaster's list of wards: warn one of what is known, remove one, or add a member nearby. */
    static void wards(Player player) {
        PlotCharacter me = PlotService.character(player);
        if (me == null) return;
        Holder holder = create(6, "§5Under your protection");
        int slot = 0;
        for (PlotCharacter ward : List.copyOf(PlotService.store().wardsOf(me.characterId))) {
            if (slot >= 45) break;
            holder.set(slot++, item(Material.PLAYER_HEAD, "§e" + ward.name + threatNote(ward), "§7Left-click to warn them of what you know.",
                    "§7Shift-click to stop protecting them."), click -> {
                if (click.isShiftClick()) PlotDefense.removeWard(player, ward.characterId);
                else PlotDefense.warnWard(player, ward);
                wards(player);
            });
        }
        holder.set(48, item(Material.LIME_DYE, "§aAdd a faction member near you"), click -> nearby(player, Purpose.WARD));
        holder.set(49, item(Material.ARROW, "§7Back"), click -> open(player));
        player.openInventory(holder.inventory);
    }

    static void nearby(Player player, Purpose purpose) {
        Holder holder = create(6, "§5Choose someone near you");
        int slot = 0;
        for (Player other : player.getWorld().getPlayers()) {
            PlotCharacter character = other.equals(player) || !PlotService.near(player, other, PlotConfig.inviteRadius())
                    ? null : PlotCharacters.active(other);
            if (character == null || slot >= 45) continue;
            ItemStack head = item(Material.PLAYER_HEAD, "§e" + PlotCharacters.seenAs(other, character), "§7Click to choose.");
            if (head.getItemMeta() instanceof SkullMeta skull) {
                skull.setOwningPlayer(other);
                head.setItemMeta(skull);
            }
            holder.set(slot++, head, click -> {
                switch (purpose) {
                    case INVITE -> PlotService.invite(player, other);
                    case INFORM -> PlotService.inform(player, other);
                    case CONFIDANT -> PlotDefense.askConfidant(player, other);
                    case INVESTIGATOR -> PlotDefense.toggleInvestigator(player, other);
                    case WARD -> PlotDefense.toggleWard(player, other);
                    case WARN -> PlotDefense.warnTarget(player, other);
                }
                open(player);
            });
        }
        if (slot == 0) holder.set(22, item(Material.GRAY_DYE, "§7Nobody is near you",
                "§7Stand within " + (int) PlotConfig.inviteRadius() + " blocks of them."), null);
        holder.set(49, item(Material.ARROW, "§7Back"), click -> open(player));
        player.openInventory(holder.inventory);
    }

    static void members(Player player) {
        Plot plot = PlotService.mastered(player);
        if (plot == null) return;
        Holder holder = create(3, "§5Conspirators");
        int slot = 10;
        for (PlotMember member : plot.members) holder.set(slot++, item(Material.PLAYER_HEAD, "§e" + member.character.name,
                "§7Role: §e" + member.roleLabel(), "§7Click to change."), click -> role(player, member));
        holder.set(22, item(Material.ARROW, "§7Back"), click -> open(player));
        player.openInventory(holder.inventory);
    }

    static void role(Player player, PlotMember member) {
        Holder holder = create(3, "§5Role: " + member.character.name);
        holder.set(10, item(Material.IRON_SWORD, "§5Assassin", "§7Hunts in the final phase.",
                "§cDies for good if killed while hunting."), click -> assign(player, member, PlotRole.ASSASSIN, null));
        int slot = 11;
        for (CollaboratorTask task : CollaboratorTask.values()) holder.set(slot++, item(Material.WRITABLE_BOOK,
                "§5Collaborator: " + task.label(), "§7" + task.effect()), click -> assign(player, member, PlotRole.COLLABORATOR, task));
        holder.set(14, item(Material.GRAY_DYE, "§7No role"), click -> assign(player, member, PlotRole.UNASSIGNED, null));
        holder.set(16, item(Material.BARRIER, "§cDismiss from the plot", "§7They will still know of it.", "§7Shift-click to confirm."), click -> {
            if (click.isShiftClick()) PlotService.dismiss(player, member.character.characterId);
            members(player);
        });
        holder.set(22, item(Material.ARROW, "§7Back"), click -> members(player));
        player.openInventory(holder.inventory);
    }

    private static void assign(Player player, PlotMember member, PlotRole role, CollaboratorTask task) {
        PlotService.assign(player, member.character.characterId, role, task);
        members(player);
    }
}
