package net.tfminecraft.rpcharacters.body;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.Answers;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import io.lumine.mythic.lib.api.item.NBTItem;
import net.Indyuce.mmoitems.MMOItems;
import net.kyori.adventure.text.Component;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

class BodyAdminCommandTest {
    @TempDir
    Path dir;

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock staff, receiver, template;
    private ConsoleCommandSenderMock console;
    private BodyStore store;
    private CharacterBodies bodies;
    private CharacterBodies running;
    private String blocked;
    private MMOItems previousItems;
    private final List<MockedStatic<?>> mocks = new ArrayList<>();
    private MockedConstruction<Database> databases;
    private final Map<UUID, PlayerData> saved = new HashMap<>();
    private final Map<Player, PlayerData> online = new HashMap<>();
    private final Map<String, OfflinePlayer> cached = new HashMap<>();
    private final UUID owner = UUID.fromString("ffd04cb6-58d1-44f6-b7ba-c8d695c54e8e");
    private final UUID aliveId = UUID.fromString("1e1e51e1-bc97-4a08-abff-2ae15a8438ab");
    private final UUID deadId = UUID.fromString("2e1e51e1-bc97-4a08-abff-2ae15a8438ab");
    private final UUID brokenId = UUID.fromString("3e1e51e1-bc97-4a08-abff-2ae15a8438ab");
    private final UUID emptyId = UUID.fromString("4e1e51e1-bc97-4a08-abff-2ae15a8438ab");
    private final UUID barrenId = UUID.fromString("5e1e51e1-bc97-4a08-abff-2ae15a8438ab");

    private <T> MockedStatic<T> boundary(Class<T> type) {
        MockedStatic<T> result = mockStatic(type);
        mocks.add(result);
        return result;
    }

    @BeforeEach
    void setup() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        console = server.getConsoleSender();
        staff = server.addPlayer("Staff");
        receiver = server.addPlayer("Receiver");
        template = server.addPlayer("Template");
        store = new BodyStore(dir.resolve("bodies"));
        bodies = mock(CharacterBodies.class);
        running = bodies;
        BodyEditor.Accessories accessories = new BodyEditor.Accessories() {
            @Override
            public boolean slot(int inventorySlot) {
                return inventorySlot >= 9 && inventorySlot <= 12;
            }

            @Override
            public boolean placeholder(byte[] item) {
                return BodyAdminCommand.mmoId(ItemStack.deserializeBytes(item)) == null;
            }
        };
        when(bodies.store()).thenReturn(store);
        when(bodies.editor()).thenAnswer(call -> new BodyEditor(store, (who, which) -> blocked, accessories));
        when(bodies.editBlocked(any(), any())).thenAnswer(call -> blocked);
        when(bodies.hasLiveBody(any())).thenReturn(true);

        RPCharacters.plugin = mock(RPCharacters.class);
        when(RPCharacters.plugin.getDataFolder()).thenReturn(dir.toFile());
        boundary(RPCharacters.class).when(RPCharacters::getCharacterBodies).thenAnswer(call -> running);
        boundary(PlayerManager.class).when(() -> PlayerManager.get(any(Player.class))).thenAnswer(call -> online.get(call.getArgument(0)));
        MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, Answers.CALLS_REAL_METHODS);
        mocks.add(bukkit);
        bukkit.when(() -> Bukkit.getOfflinePlayerIfCached(any(String.class))).thenAnswer(call -> cached.get(call.getArgument(0)));
        boundary(NBTItem.class).when(() -> NBTItem.get(any(ItemStack.class))).thenAnswer(call -> nbt(call.getArgument(0)));
        databases = mockConstruction(Database.class,
                (database, context) -> when(database.loadPlayerData(any())).thenAnswer(call -> saved.get(call.getArgument(0))));
        previousItems = MMOItems.plugin;
        MMOItems.plugin = mock(MMOItems.class);
        when(MMOItems.plugin.getItem("MAGE_STAFFS", "MAGE_CUSTOM_STAFF")).thenAnswer(call -> new ItemStack(Material.STICK));

        OfflinePlayer andros = mock(OfflinePlayer.class);
        when(andros.getUniqueId()).thenReturn(owner);
        when(andros.getName()).thenReturn("Andros");
        cached.put("Andros", andros);
        RPCharacter alive = character(aliveId, "Old Man Andros", Status.ALIVE);
        RPCharacter dead = character(deadId, "Young Andros", Status.DEAD);
        RPCharacter broken = character(brokenId, "Broken Andros", Status.ALIVE);
        RPCharacter empty = character(emptyId, "Nobody Yet", Status.ALIVE);
        RPCharacter barren = character(barrenId, "Barren Andros", Status.ALIVE);
        saved.put(owner, account(alive, dead, broken, empty, barren));

        template.getInventory().clear();
        template.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        template.getInventory().setItem(1, new ItemStack(Material.STICK));
        template.getInventory().setItem(9, new ItemStack(Material.DIAMOND_HOE));
        template.getInventory().setItem(39, new ItemStack(Material.IRON_HELMET));
        template.getEnderChest().setItem(5, new ItemStack(Material.EMERALD, 2));
        store.write(owner, aliveId, 0, "quit", BodySnapshot.capture(template, Set.of()).withLocation(new Location(template.getWorld(), 1, 64, 1)));
        store.write(owner, deadId, 0, "quit", BodySnapshot.capture(template, Set.of()).withLocation(new Location(template.getWorld(), 1, 64, 1)));
        template.getInventory().clear();
        template.getEnderChest().clear();
        store.write(owner, barrenId, 0, "quit", BodySnapshot.capture(template, Set.of()).withLocation(new Location(template.getWorld(), 1, 64, 1)));
        Path brokenFile = dir.resolve("bodies").resolve(owner.toString()).resolve(brokenId + ".body");
        Files.writeString(brokenFile, "not a body", StandardCharsets.UTF_8);
    }

    @AfterEach
    void cleanup() throws IOException {
        databases.close();
        for (int i = mocks.size() - 1; i >= 0; i--) mocks.get(i).close();
        MMOItems.plugin = previousItems;
        RPCharacters.plugin = null;
        MockBukkit.unmock();
        Files.deleteIfExists(Path.of("plugins/RPCharacters/data/playerdata").resolve(owner + ".json"));
    }

    private static NBTItem nbt(ItemStack item) {
        if (item.getType() == Material.BARRIER) throw new IllegalStateException("no NBT here");
        NBTItem nbt = mock(NBTItem.class);
        boolean staffItem = item.getType() == Material.STICK || item.getType() == Material.GOLDEN_HOE;
        when(nbt.hasType()).thenReturn(staffItem);
        when(nbt.getType()).thenReturn(item.getType() == Material.STICK ? "MAGE_STAFFS" : "ACCESSORY");
        when(nbt.getString("MMOITEMS_ITEM_ID")).thenReturn(item.getType() == Material.STICK ? "MAGE_CUSTOM_STAFF" : "RING");
        return nbt;
    }

    private static RPCharacter character(UUID id, String name, Status status) {
        RPCharacter character = mock(RPCharacter.class);
        when(character.getId()).thenReturn(id.toString());
        when(character.getName()).thenReturn(name);
        when(character.getStatus()).thenReturn(status);
        return character;
    }

    private static PlayerData account(RPCharacter... characters) {
        PlayerData account = mock(PlayerData.class);
        when(account.getCharacters()).thenReturn(new ArrayList<>(List.of(characters)));
        return account;
    }

    private void grant(PlayerMock player, String permission) {
        player.addAttachment(plugin, permission, true);
    }

    private String run(org.bukkit.command.CommandSender sender, String... args) {
        String[] full = new String[args.length + 2];
        full[0] = "admin";
        full[1] = "body";
        System.arraycopy(args, 0, full, 2, args.length);
        assertTrue(BodyAdminCommand.handle(sender, full, 2));
        List<String> out = new ArrayList<>();
        String message;
        if (sender instanceof PlayerMock player) {
            while ((message = player.nextMessage()) != null) out.add(ChatColor.stripColor(message));
        } else {
            while ((message = console.nextMessage()) != null) out.add(ChatColor.stripColor(message));
        }
        return String.join("\n", out);
    }

    private String console(String... args) {
        return run(console, args);
    }

    private BodySnapshot body(UUID character) throws IOException {
        return store.read(owner, character).orElseThrow().body();
    }

    private List<String> audit() throws IOException {
        Path log = dir.resolve("logs").resolve("body-edits.log");
        return Files.exists(log) ? Files.readAllLines(log) : List.of();
    }

    @Test
    void needsAPermissionRunningBodiesAndAKnownPlayer() {
        assertTrue(run(staff, "Andros").contains("permission"));
        assertFalse(BodyAdminCommand.canUse(staff));
        grant(staff, "rpchar.body.view");
        assertTrue(BodyAdminCommand.canUse(staff));
        running = null;
        assertTrue(run(staff, "Andros").contains("not running"));
        running = bodies;
        assertTrue(run(staff).contains("Usage"));
        assertTrue(run(staff, "Ghost").contains("No player called Ghost"));
        assertTrue(run(staff, "Receiver").contains("No player called Receiver"), "online player without account data");
        online.put(receiver, account());
        assertTrue(run(staff, "Receiver").contains("has no characters"));
    }

    @Test
    void listsTheCharactersWithTheirBodies() {
        String out = console("Andros");
        assertTrue(out.contains("Characters of Andros"), out);
        assertTrue(out.contains("Old Man Andros id 1e1e51e1, ALIVE, body version 1"), out);
        assertTrue(out.contains("Young Andros id 2e1e51e1, DEAD, body version 1"), out);
        assertTrue(out.contains("Broken Andros id 3e1e51e1, ALIVE, body unreadable"), out);
        assertTrue(out.contains("Nobody Yet id 4e1e51e1, ALIVE, no stored body"), out);
    }

    @Test
    void picksACharacterByIdPrefixOrName() {
        assertTrue(console("Andros", aliveId.toString(), "list").contains("Old Man Andros (Andros) body version 1"));
        assertTrue(console("Andros", "1E1E51E1").contains("body version 1"));
        assertTrue(console("Andros", "old_man_andros", "list").contains("body version 1"));
        assertTrue(console("Andros", "1e1e").contains("No single character of Andros matches 1e1e"));
        saved.put(owner, account(character(aliveId, "Twin", Status.ALIVE), character(deadId, "Twin", Status.ALIVE)));
        assertTrue(console("Andros", "Twin").contains("No single character"));
    }

    @Test
    void listShowsEverySlotAndWhetherItCanChange() {
        String out = console("Andros", "1e1e51e1");
        assertTrue(out.contains("saved ") && out.contains("(quit). Can be changed now."), out);
        assertTrue(out.contains("inv 0: 3x (diamond)"), out);
        assertTrue(out.contains("inv 1: 1x (MAGE_STAFFS:MAGE_CUSTOM_STAFF)"), out);
        assertTrue(out.contains("inv 9 (accessory): 1x (diamond_hoe)"), out);
        assertTrue(out.contains("inv 39 (helmet): 1x (iron_helmet)"), out);
        assertTrue(out.contains("ender 5: 2x (emerald)"), out);
        blocked = "The player is playing this character right now.";
        assertTrue(console("Andros", "1e1e51e1", "list").contains("Cannot be changed now: The player is playing this character right now."));
        assertTrue(console("Andros", "2e1e51e1", "list").contains("Cannot be changed now: the character is DEAD"));
        assertTrue(console("Andros", "5e1e51e1", "list").contains("The body holds no items."));
        assertTrue(console("Andros", "4e1e51e1", "list").contains("Nothing changed: This character has no stored body yet."));
    }

    @Test
    void changesNeedTheEditPermissionALivingCharacterAndAKnownAction() throws Exception {
        grant(staff, "rpchar.body.view");
        assertTrue(run(staff, "Andros", "1e1e51e1", "put", "inv", "auto", "1", "item", "dirt").contains("You may only list bodies."));
        grant(staff, "rpchar.body.edit");
        assertTrue(run(staff, "Andros", "2e1e51e1", "take", "inv", "0", "1", "diamond", "delete").contains("this one is DEAD"));
        assertTrue(run(staff, "Andros", "1e1e51e1", "frob").contains("Usage"));
        assertEquals(1, store.read(owner, aliveId).orElseThrow().rev());
    }

    @Test
    void putsItemsFromEverySource() throws Exception {
        String out = console("Andros", "1e1e51e1", "put", "inv", "auto", "1", "item", "gold_ingot", "2");
        assertTrue(out.contains("Put 2x (gold_ingot) into inv 2. Body version is now 2."), out);
        assertEquals(new ItemStack(Material.GOLD_INGOT, 2), ItemStack.deserializeBytes(body(aliveId).slot(BodySnapshot.Container.INVENTORY, 2)));
        assertTrue(audit().getFirst().contains("CONSOLE put inv 2 of Andros " + owner + " character " + aliveId + " version 1->2 2x (gold_ingot) item data "));

        assertTrue(console("Andros", "1e1e51e1", "put", "ender", "0", "2", "mi", "mage_staffs", "mage_custom_staff").contains("into ender 0. Body version is now 3."));
        String encoded = Base64.getEncoder().encodeToString(new ItemStack(Material.APPLE, 5).serializeAsBytes());
        assertTrue(console("Andros", "1e1e51e1", "put", "inv", "3", "3", "base64", encoded).contains("Put 5x (apple) into inv 3. Body version is now 4."));

        grant(staff, "rpchar.body.edit");
        staff.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_BLOCK));
        assertTrue(run(staff, "Andros", "1e1e51e1", "put", "inventory", "auto", "4", "held").contains("Put 1x (gold_block) into inv 4."));
        assertTrue(staff.getInventory().getItemInMainHand().getType().isAir());
        assertTrue(run(staff, "Andros", "1e1e51e1", "put", "inv", "auto", "5", "held").contains("Hold the item to put in."));
        assertEquals(5, store.read(owner, aliveId).orElseThrow().rev());
        assertEquals(4, audit().size());
    }

    @Test
    void refusesBadPutsWithoutWriting() throws Exception {
        String[][] bad = {
            {"put", "inv", "auto", "1"},
            {"put", "inv", "auto", "1", "held"},
            {"put", "inv", "auto", "1", "held", "extra"},
            {"put", "inv", "auto", "1", "mi", "MAGE_STAFFS"},
            {"put", "inv", "auto", "1", "mi", "NOPE", "NOPE"},
            {"put", "inv", "auto", "1", "item"},
            {"put", "inv", "auto", "1", "item", "not_a_thing"},
            {"put", "inv", "auto", "1", "item", "diamond", "0"},
            {"put", "inv", "auto", "1", "item", "diamond", "65"},
            {"put", "inv", "auto", "1", "base64"},
            {"put", "inv", "auto", "1", "base64", "%%%"},
            {"put", "inv", "auto", "1", "base64", Base64.getEncoder().encodeToString(new byte[] {1, 2, 3})},
            {"put", "inv", "auto", "1", "frob"},
            {"put", "chest", "auto", "1", "item", "diamond"},
            {"put", "inv", "x", "1", "item", "diamond"},
            {"put", "inv", "-1", "1", "item", "diamond"},
            {"put", "inv", "auto", "0", "item", "diamond"},
            {"put", "inv", "0", "1", "item", "diamond"},
            {"put", "inv", "10", "1", "item", "diamond"},
        };
        List<String> replies = new ArrayList<>();
        for (String[] args : bad) {
            String[] full = new String[args.length + 2];
            full[0] = "Andros";
            full[1] = "1e1e51e1";
            System.arraycopy(args, 0, full, 2, args.length);
            String reply = console(full);
            replies.add(reply);
            assertTrue(reply.contains("Usage") || reply.contains("Nothing changed"), String.join(" ", args) + " -> " + reply);
        }
        assertTrue(replies.get(1).contains("Only a player can put the held item."));
        assertTrue(replies.get(4).contains("MMOItems has no NOPE NOPE."));
        assertTrue(replies.get(6).contains("No item called not_a_thing."));
        assertTrue(replies.get(7).contains("The amount must be 1 to 64."));
        assertTrue(replies.get(13).contains("Say inv or ender, not chest."));
        assertTrue(replies.get(14).contains("The slot must be a whole number from 0 up."));
        assertTrue(replies.get(16).contains("The body is at version 1, not 0."));
        assertTrue(replies.get(17).contains("Slot 0 is not empty."));
        assertTrue(replies.get(18).contains("Slot 10 is an accessory slot."));
        assertEquals(1, store.read(owner, aliveId).orElseThrow().rev());
        assertEquals(List.of(), audit());
    }

    @Test
    void takesToAPlayingReceiverOrDeletes() throws Exception {
        assertTrue(console("Andros", "1e1e51e1", "take", "inv", "0", "1", "diamond").contains("Usage"));
        assertTrue(console("Andros", "1e1e51e1", "take", "inv", "0", "1", "diamond", "to", "Ghost").contains("No online player called Ghost."));
        assertTrue(console("Andros", "1e1e51e1", "take", "inv", "0", "1", "diamond", "delete", "now").contains("Usage"));
        assertTrue(console("Andros", "1e1e51e1", "take", "inv", "0", "1", "diamond", "frob").contains("Usage"));
        when(bodies.hasLiveBody(receiver)).thenReturn(false);
        assertTrue(console("Andros", "1e1e51e1", "take", "inv", "0", "1", "diamond", "to", "Receiver").contains("Receiver must be playing a character"));
        when(bodies.hasLiveBody(receiver)).thenReturn(true);
        for (int slot = 0; slot < 36; slot++) receiver.getInventory().setItem(slot, new ItemStack(Material.DIAMOND, 64));
        assertTrue(console("Andros", "1e1e51e1", "take", "inv", "0", "1", "diamond", "to", "Receiver").contains("Receiver has no room for that stack."));
        receiver.getInventory().setItem(5, new ItemStack(Material.DIAMOND, 61));
        String out = console("Andros", "1e1e51e1", "take", "inv", "0", "1", "minecraft:diamond", "to", "Receiver");
        assertTrue(out.contains("Took 3x (diamond) out of inv 0 (to Receiver). Body version is now 2."), out);
        assertEquals(64, receiver.getInventory().getItem(5).getAmount());
        assertEquals(0, body(aliveId).slot(BodySnapshot.Container.INVENTORY, 0).length);

        assertTrue(console("Andros", "1e1e51e1", "take", "inv", "1", "2", "diamond", "delete").contains("holds a different item"));
        assertTrue(console("Andros", "1e1e51e1", "take", "inv", "9", "2", "diamond_hoe", "delete").contains("empty accessory slot"));
        assertTrue(console("Andros", "1e1e51e1", "take", "inv", "1", "2", "mage_staffs:mage_custom_staff", "delete")
                .contains("Took 1x (MAGE_STAFFS:MAGE_CUSTOM_STAFF) out of inv 1 (deleted). Body version is now 3."));
        assertTrue(console("Andros", "1e1e51e1", "take", "ender", "5", "3", "nothing_known", "delete").contains("holds a different item"));
        assertEquals(2, audit().size());
        assertTrue(audit().get(1).endsWith(Base64.getEncoder().encodeToString(new ItemStack(Material.STICK).serializeAsBytes())));
    }

    @Test
    void findsAccountsByUuid() throws Exception {
        online.put(receiver, account(character(aliveId, "Receiver's", Status.ALIVE)));
        assertTrue(console(receiver.getUniqueId().toString()).contains("Characters of Receiver"));
        assertTrue(console(owner.toString()).contains("No player called " + owner));
        Path playerdata = Path.of("plugins/RPCharacters/data/playerdata");
        Files.createDirectories(playerdata);
        Files.writeString(playerdata.resolve(owner + ".json"), "{}");
        assertTrue(console(owner.toString()).contains("Characters of "));
        assertTrue(console("ffd04cb6-58d1-44f6-b7ba-c8d695c54e8X").contains("No player called"));
        saved.remove(owner);
        assertTrue(console(owner.toString()).contains("No player called"));
        assertTrue(console("Andros").contains("No player called Andros"));
    }

    @Test
    void anAuditLogThatCannotBeWrittenDoesNotUndoTheChange() throws Exception {
        Files.writeString(dir.resolve("logs"), "a file where the folder should be");
        assertTrue(console("Andros", "1e1e51e1", "put", "inv", "auto", "1", "item", "diamond").contains("Body version is now 2."));
        assertEquals(2, store.read(owner, aliveId).orElseThrow().rev());
    }

    @Test
    void savesThePlayerFileAfterAHandOff() {
        Player player = mock(Player.class);
        BodyAdminCommand.save(player);
        verify(player).saveData();
        doThrow(new IllegalStateException("disk")).when(player).saveData();
        BodyAdminCommand.save(player); // only logged: the body change is already written
    }

    @Test
    void describesItemsAndSlots() {
        assertEquals("unreadable item (2 bytes)", BodyAdminCommand.describe(new byte[] {1, 2}));
        ItemStack named = new ItemStack(Material.APPLE);
        ItemMeta meta = named.getItemMeta();
        meta.displayName(Component.text("Lucky Apple"));
        named.setItemMeta(meta);
        assertEquals("1x Lucky Apple (apple)", BodyAdminCommand.describe(named.serializeAsBytes()));
        assertEquals("1x (barrier)", BodyAdminCommand.describe(new ItemStack(Material.BARRIER).serializeAsBytes()));
        assertEquals("ender 3", BodyAdminCommand.slotName(BodySnapshot.Container.ENDER_CHEST, 3));
        assertEquals("inv 40 (offhand)", BodyAdminCommand.slotName(BodySnapshot.Container.INVENTORY, 40));
        assertEquals("inv 35", BodyAdminCommand.slotName(BodySnapshot.Container.INVENTORY, 35));
        assertTrue(BodyAdminCommand.matches("ACCESSORY:RING", new ItemStack(Material.GOLDEN_HOE).serializeAsBytes()));
        assertFalse(BodyAdminCommand.matches("golden_hoe", new ItemStack(Material.GOLDEN_HOE).serializeAsBytes()));
    }
}
