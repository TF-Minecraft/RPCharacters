package net.tfminecraft.rpcharacters.plots;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

@SuppressWarnings("deprecation")
class PlotMenuTest {
    @TempDir Path directory;
    PlotWorld world;
    PlayerMock master, assassin, helper, target;
    CommandSender staff = mock(CommandSender.class);

    @BeforeEach
    void setup() throws Exception {
        world = new PlotWorld(directory);
        master = world.player("Master", "c-master", 50);
        assassin = world.player("Assassin", "c-assassin", 60);
        helper = world.player("Helper", "c-helper", 80);
        target = world.player("Target", "c-target", 40);
        when(staff.getName()).thenReturn("Staffer");
    }

    @AfterEach
    void close() { world.close(); }

    PlotMenu.Holder holder(Player player) { return (PlotMenu.Holder) player.getOpenInventory().getTopInventory().getHolder(); }

    ItemStack item(Player player, int slot) { return player.getOpenInventory().getTopInventory().getItem(slot); }

    String name(Player player, int slot) { return item(player, slot).getItemMeta().getDisplayName(); }

    List<String> lore(Player player, int slot) { return item(player, slot).getItemMeta().getLore(); }

    void click(Player player, int slot, ClickType type) { holder(player).actions.get(slot).accept(type); }

    void click(Player player, int slot) { click(player, slot, ClickType.LEFT); }

    int slotNamed(Player player, String name) {
        for (int slot = 0; slot < 45; slot++) if (item(player, slot) != null && name(player, slot).equals(name)) return slot;
        return -1;
    }

    Plot building() {
        Plot plot = PlotService.request(master);
        PlotService.approve(staff, plot.code, "Target");
        PlotService.invite(master, assassin);
        PlotService.accept(assassin, plot.code);
        PlotService.assign(master, "c-assassin", PlotRole.ASSASSIN, null);
        return plot;
    }

    @Test
    void theMainMenuShowsIntrigueAndStartsAPlot() {
        PlotMenu.open(world.player("Nobody", null, null));
        PlotMenu.open(master);
        assertEquals("§5Intrigue: §e50/100", name(master, 4));
        assertTrue(lore(master, 4).contains("§7When plotting: §e58"));
        assertEquals("§5Approach: §eNeutral", name(master, 2));
        assertEquals("§5Watched over by: §eYourself", name(master, 6));
        assertTrue(lore(master, 6).contains("§7Protection now: §e29"));
        assertEquals("§5Begin a plot", name(master, 22));
        click(master, 2);
        assertEquals("§5Approach: §eDefensive", name(master, 2));
        click(master, 22);
        assertEquals("§5Assassination", name(master, 13));
        click(master, 22);
        click(master, 22);
        click(master, 13);
        assertNotNull(world.store().plotOf("c-master"));
        PlotMenu.open(master);
        assertEquals("§5Plot request §eP-ABCDE", name(master, 22));
        click(master, 31);
        assertNotNull(world.store().plotOf("c-master"));
        click(master, 31, ClickType.SHIFT_LEFT);
        assertNull(world.store().plotOf("c-master"));
        click(master, 49);
    }

    @Test
    void theConfidantMenuAsksSomeoneNearbyAndEndsTheBond() {
        PlotMenu.confidant(world.player("Nobody", null, null));
        PlotMenu.open(target);
        click(target, 6);
        assertEquals("§5Ask someone near you", name(target, 11));
        assertNull(holder(target).actions.get(15));
        click(target, 11);
        int helperSlot = slotNamed(target, "§eChar Helper");
        click(target, helperSlot);
        assertTrue(world.heard(helper, "asks you to be their confidant"));
        PlotDefense.acceptConfidant(helper);
        PlotMenu.open(target);
        assertEquals("§5Watched over by: §eYour confidant, Char Helper", name(target, 6));
        PlotMenu.confidant(target);
        assertTrue(lore(target, 15).contains("§7Your confidant: §eChar Helper"));
        click(target, 15);
        assertNotNull(world.store().peek("c-target").confidant);
        PlotMenu.confidant(target);
        click(target, 15, ClickType.SHIFT_LEFT);
        assertNull(world.store().peek("c-target").confidant);
        PlotMenu.confidant(target);
        click(target, 22);
        world.store().settings("c-target").confidant = world.character(helper);
        PlotMenu.confidant(helper);
        assertTrue(lore(helper, 15).contains("§7Stop watching over your charge."));
        PlotMenu.open(helper);
        assertEquals("§5You watch over §eChar Target", name(helper, 8));
        Plot plot = building();
        plot.guardKnows = true;
        plot.guardKnowledge.put("threat", "Someone is plotting against your life.");
        PlotMenu.open(helper);
        assertTrue(lore(helper, 8).getFirst().contains("(1 plot heard of)"));
        click(helper, 8);
        assertTrue(plot.discovered);
        world.logOff(target);
        world.store().plots.remove(plot);
        PlotMenu.open(helper);
        assertEquals("§5You watch over §eyour charge", name(helper, 8));
    }

    @Test
    void theSpymasterManagesTheirWards() {
        PlotMenu.wards(world.player("Nobody", null, null));
        var spymaster = new PlotFactions.Spymaster(world.character(helper), 70);
        world.spymasters.put("Helper", spymaster);
        world.spymasters.put("Target", spymaster);
        world.factions.when(() -> PlotFactions.sameFaction("Helper", "Target")).thenReturn(true);
        PlotMenu.open(helper);
        assertEquals("§5Under your protection", name(helper, 8));
        assertTrue(lore(helper, 8).contains("§7Nobody yet."));
        click(helper, 8);
        click(helper, 48);
        click(helper, slotNamed(helper, "§eChar Target"));
        assertEquals(1, world.store().wardsOf("c-helper").size());
        PlotMenu.open(helper);
        assertTrue(lore(helper, 8).contains("§7Char Target"));
        PlotMenu.wards(helper);
        assertEquals("§eChar Target", name(helper, 0));
        Plot plot = building();
        plot.guardKnows = true;
        plot.guardKnowledge.put("threat", "Someone is plotting against your life.");
        click(helper, 0);
        assertTrue(plot.discovered);
        click(helper, 0, ClickType.SHIFT_LEFT);
        assertTrue(world.store().wardsOf("c-helper").isEmpty());
        for (int index = 0; index < 46; index++) world.store().wardsOf("c-helper").add(new PlotCharacter("w" + index, null, "W", "W" + index));
        PlotMenu.wards(helper);
        assertNotNull(item(helper, 44));
        click(helper, 49);
    }

    @Test
    void theMasterRecruitsGivesRolesAndRunsThePlot() {
        Plot plot = PlotService.request(master);
        PlotService.approve(staff, plot.code, "Target");
        PlotMenu.open(master);
        assertTrue(lore(master, 22).stream().anyMatch(line -> line.contains("Start it within")));
        click(master, 29);
        click(master, slotNamed(master, "§eChar Assassin"));
        assertTrue(plot.invitations.containsKey("c-assassin"));
        PlotMenu.open(assassin);
        assertEquals("§5Proposal from §eChar Master", name(assassin, 45));
        click(assassin, 45);
        assertNotNull(plot.member("c-assassin"));
        PlotService.invite(master, helper);
        PlotMenu.open(helper);
        click(helper, 45, ClickType.RIGHT);
        assertTrue(plot.invitations.isEmpty());

        PlotMenu.open(master);
        click(master, 20);
        assertEquals("§eChar Assassin", name(master, 10));
        click(master, 10);
        click(master, 10);
        assertEquals(PlotRole.ASSASSIN, plot.member("c-assassin").role);
        click(master, 10);
        click(master, 12);
        assertEquals(CollaboratorTask.PROGRESS, plot.member("c-assassin").task);
        click(master, 10);
        click(master, 14);
        assertEquals(PlotRole.UNASSIGNED, plot.member("c-assassin").role);
        click(master, 10);
        click(master, 22);
        click(master, 10);
        click(master, 16);
        assertNotNull(plot.member("c-assassin"));
        click(master, 10);
        click(master, 10);
        click(master, 22);
        click(master, 24);
        assertEquals(PlotPhase.BUILDING, plot.phase);
        assertTrue(lore(master, 22).stream().anyMatch(line -> line.startsWith("§7Secrecy: ")));
        click(master, 33);
        PlotMenu.nearby(master, PlotMenu.Purpose.INFORM);
        assertSame(master.getOpenInventory().getTopInventory(), holder(master).getInventory());
        click(master, slotNamed(master, "§eChar Assassin"));
        assertTrue(world.heard(master, "cannot be marked"));
        PlotMenu.open(master);
        click(master, 49);
        PlotMenu.open(master);
        click(master, 20);
        click(master, 10);
        click(master, 16, ClickType.SHIFT_LEFT);
        assertNull(plot.member("c-assassin"));
        PlotMenu.members(helper);
    }

    @Test
    void conspiratorsSeeTheirPlotTheHuntAndTheTargetSeesThreats() {
        Plot plot = building();
        PlotService.begin(master);
        plot.informed.add(world.character(helper));
        PlotMenu.open(assassin);
        assertTrue(lore(assassin, 20).contains("§8Char Helper - Informed"));
        assertNull(holder(assassin).actions.get(20));
        click(assassin, 24);
        assertNotNull(plot.member("c-assassin"));
        click(assassin, 24, ClickType.SHIFT_LEFT);
        assertNull(plot.member("c-assassin"));
        PlotService.invite(master, assassin);
        PlotService.accept(assassin, plot.code);
        PlotService.assign(master, "c-assassin", PlotRole.ASSASSIN, null);

        plot.phase = PlotPhase.READY;
        plot.phaseStartedAt = world.clock.get();
        PlotMenu.open(master);
        assertEquals("§cBegin the final phase", name(master, 24));
        assertTrue(lore(master, 22).stream().anyMatch(line -> line.startsWith("§7Begin the final phase within")));
        click(master, 24);
        assertEquals(PlotPhase.INVESTIGATION, plot.phase);
        assertTrue(lore(master, 22).stream().anyMatch(line -> line.contains("Assassins may strike in")));
        plot.phase = PlotPhase.EXECUTION;
        PlotMenu.open(master);
        assertTrue(lore(master, 22).contains("§7Hunt days: §e0§7 of §e4§7 (today not yet)"));
        assertTrue(lore(master, 22).contains("§7Assassins hunting: §e0"));
        plot.dayCounted = true;
        PlotMenu.open(master);
        assertTrue(lore(master, 22).contains("§7Hunt days: §e0§7 of §e4§7 (today counted)"));
        assertFalse(holder(master).actions.containsKey(24));
        PlotMenu.open(assassin);
        assertTrue(name(assassin, 24).startsWith("§aHunt"));
        click(assassin, 24);
        assertTrue(name(assassin, 24).startsWith("§cHunting"));
        assertEquals("§7The hunt takes effect shortly.", lore(assassin, 24).getFirst());
        world.pass(PlotConfig.huntWarmup());
        PlotMenu.open(assassin);
        assertEquals("§7The hunt is on: their death or yours counts.", lore(assassin, 24).getFirst());

        PlotMenu.open(target);
        assertNull(item(target, 40));
        plot.discovered = true;
        plot.knowledge.put("threat", "Someone is plotting against your life.");
        world.store().settings("c-target").investigators.add(world.character(helper));
        PlotMenu.open(target);
        assertEquals(List.of("§7Someone is plotting against your life."), lore(target, 40));
        assertEquals("§7Char Helper", lore(target, 42).getFirst());
        click(target, 42);
        click(target, slotNamed(target, "§eChar Master"));

        PlotMenu.open(helper);
        assertEquals("§5Plots you know of", name(helper, 38));
        click(helper, 38);
        click(helper, slotNamed(helper, "§eChar Target"));
        assertTrue(world.heard(target, "warns you"));

        PlotMenu.open(master);
        click(master, 31);
        PlotMenu.open(master);
        click(master, 31, ClickType.SHIFT_LEFT);
        assertEquals(PlotPhase.DISSOLVED, plot.phase);
    }

    @Test
    void onlyFourProposalsShowAndNobodyNearbyIsSaid() {
        for (int index = 0; index < 5; index++) {
            Plot plot = new Plot();
            plot.code = "P-" + index;
            plot.master = new PlotCharacter("m" + index, null, "M", "M" + index);
            plot.target = new PlotCharacter("t", null, "T", "T");
            plot.phase = index == 4 ? PlotPhase.READY : PlotPhase.RECRUITING;
            plot.invitations.put("c-helper", world.clock.get() + 1000);
            world.store().plots.add(plot);
        }
        Plot other = new Plot();
        other.code = "P-X";
        other.master = new PlotCharacter("mx", null, "M", "MX");
        other.target = new PlotCharacter("t", null, "T", "T");
        other.phase = PlotPhase.RECRUITING;
        other.invitations.put("c-helper", world.clock.get() + 1000);
        world.store().plots.add(other);
        PlotMenu.open(helper);
        assertNotNull(item(helper, 48));
        assertEquals("§cClose", name(helper, 49));
        world.elsewhere(master);
        world.elsewhere(assassin);
        world.elsewhere(target);
        PlotMenu.nearby(helper, PlotMenu.Purpose.INVITE);
        assertEquals("§7Nobody is near you", name(helper, 22));
        click(helper, 49);
        assertEquals("§a|||||§8||||| §750%", PlotMenu.bar(50));
        assertEquals("§a§8|||||||||| §7-5%", PlotMenu.bar(-5));
    }

    @Test
    void characterInfoItemsShowIntrigueTheButtonAndTheLock() {
        RPCharacter played = mock(RPCharacter.class);
        when(played.getId()).thenReturn("c-master");
        when(played.getName()).thenReturn("Char Master");
        when(played.isActive()).thenReturn(true);
        assertEquals("§5Intrigue: §e50/100", PlotMenu.intrigueFor(master, played).getItemMeta().getDisplayName());
        RPCharacter resting = mock(RPCharacter.class);
        when(resting.getId()).thenReturn("c-old");
        when(resting.getName()).thenReturn("Old");
        ItemStack unknown = PlotMenu.intrigueFor(master, resting);
        assertEquals("§5Intrigue: §e?", unknown.getItemMeta().getDisplayName());
        assertTrue(unknown.getItemMeta().getLore().getFirst().contains("rolled the next time"));
        assertEquals(Material.IRON_SWORD, PlotMenu.button().getType());
        assertEquals(Material.RED_CONCRETE, PlotMenu.lockedItem().getType());
        PlotMenu.sendLocked(master);
        assertTrue(world.last(master).contains("Face it as this character"));
    }
}
