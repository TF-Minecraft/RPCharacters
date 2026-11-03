package net.tfminecraft.rpcharacters.classpick;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.Indyuce.mmocore.api.player.profess.Subclass;
import net.Indyuce.mmocore.manager.ClassManager;
import net.tfminecraft.rpcharacters.Cache;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.classpick.ClassPickService.Option;
import net.tfminecraft.rpcharacters.classpick.ClassPickService.Result;
import net.tfminecraft.rpcharacters.classpick.ClassPickService.Settings;
import net.tfminecraft.rpcharacters.classpick.ClassPickService.Status;
import net.tfminecraft.rpcharacters.creation.CharacterCreation;
import net.tfminecraft.rpcharacters.creation.Stage;
import net.tfminecraft.rpcharacters.creation.StageEditLock;
import net.tfminecraft.rpcharacters.creation.stages.SelectionStage;
import net.tfminecraft.rpcharacters.loaders.StageLoader;
import net.tfminecraft.rpcharacters.paidchange.PaidChangeRule;
import net.tfminecraft.rpcharacters.paidchange.PaidChangeService;
import net.tfminecraft.rpcharacters.lifecycle.CharacterClassChangeEvent;
import net.tfminecraft.rpcharacters.managers.CreationManager;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.mmocore.ClassService;
import net.tfminecraft.rpcharacters.mmocore.MmoCoreClassGuiHelper;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet.Account;
import net.tfminecraft.rpcharacters.paidchange.FakeLedger;
import net.tfminecraft.rpcharacters.paidchange.Payment;
import net.tfminecraft.rpcharacters.paidchange.Payments;
import net.tfminecraft.rpcharacters.paidchange.PendingPaidChange;
import net.tfminecraft.rpcharacters.paidchange.PaymentsFixture;
import net.tfminecraft.rpcharacters.database.CharacterClassPickFields;
import net.tfminecraft.tlibs.character.CharacterRef;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

class ClassPickTest {
    ServerMock server; PlayerMock player; RuntimeTestState state; MMOCore previousMmo; ClassManager classes;
    PlayerData account; RPCharacter character; PlayerManager playerManager; FakeLedger wallet; final List<String> saves = new ArrayList<>();
    PlayerClass warrior, mage, berserker, spellblade, hidden;
    final AtomicInteger level = new AtomicInteger(1);
    final Map<String, PlayerClass> classMap = new LinkedHashMap<>();
    final Set<String> displayed = new HashSet<>(Set.of("warrior", "mage"));
    final List<AutoCloseable> boundaries = new ArrayList<>();
    final List<LogRecord> logs = new ArrayList<>();
    final Handler handler = new Handler() {
        @Override public void publish(LogRecord record) { logs.add(record); }
        @Override public void flush() {}
        @Override public void close() {}
    };
    MockedStatic<PlayerManager> players; MockedStatic<ClassService> classService;

    <T> MockedStatic<T> boundary(Class<T> type) { var result = mockStatic(type); boundaries.add(result); return result; }

    @BeforeEach void setup() throws Exception {
        server = MockBukkit.mock(); state = new RuntimeTestState(RPCharacters.class, StageLoader.class, PaidChangeService.class, Payments.class); previousMmo = MMOCore.plugin;
        Cache.attributes = new ArrayList<>(); Cache.professions = new ArrayList<>();
        player = server.addPlayer("Picker");
        MMOCore.plugin = mock(MMOCore.class); classes = mock(ClassManager.class);
        Field field = MMOCore.class.getField("classManager"); field.setAccessible(true); field.set(MMOCore.plugin, classes);
        when(classes.getAll()).thenAnswer(call -> classMap.values());
        when(classes.get(anyString())).thenAnswer(call -> classMap.get(call.<String>getArgument(0).toLowerCase(Locale.ROOT)));
        berserker = playerClass("berserker", "&cBerserker", 3, null); spellblade = playerClass("spellblade", "Spellblade", 4, new ItemStack(Material.AIR));
        warrior = playerClass("warrior", "Warrior", 1, new ItemStack(Material.IRON_SWORD)); mage = playerClass("mage", "Mage", 2, null); hidden = playerClass("test", "Test", 0, null);
        when(warrior.getSubclasses()).thenReturn(List.of(new Subclass(berserker, 2), new Subclass(spellblade, 2)));
        var gui = mockStatic(MmoCoreClassGuiHelper.class, CALLS_REAL_METHODS); boundaries.add(gui);
        gui.when(() -> MmoCoreClassGuiHelper.isClassDisplayed(any())).thenAnswer(call -> displayed.contains(call.<PlayerClass>getArgument(0).getId()));
        gui.when(() -> MmoCoreClassGuiHelper.buildClassLore(any())).thenReturn(List.of("Class lore"));
        character = new RPCharacter(player); character.setMMOClass("warrior");
        account = mock(PlayerData.class); when(account.hasActiveCharacter()).thenReturn(true); when(account.getActiveCharacter()).thenReturn(character); when(account.getUniqueId()).thenReturn(player.getUniqueId());
        players = boundary(PlayerManager.class); players.when(() -> PlayerManager.get(player)).thenReturn(account);
        playerManager = mock(PlayerManager.class); boundary(RPCharacters.class).when(RPCharacters::getPlayerManager).thenReturn(playerManager);
        classService = boundary(ClassService.class); classService.when(() -> ClassService.applyClass(eq(player), anyString())).thenReturn(true);
        var mmo = mock(net.Indyuce.mmocore.api.player.PlayerData.class); when(mmo.getLevel()).thenAnswer(call -> level.get());
        boundary(net.Indyuce.mmocore.api.player.PlayerData.class).when(() -> net.Indyuce.mmocore.api.player.PlayerData.get(player)).thenReturn(mmo);
        wallet = new FakeLedger(); ClassPickService.setWallet(wallet); ClassPickService.configure(Settings.DEFAULTS);
        PaymentsFixture.saves((owner, saved) -> saves.add(owner + "/" + saved.getId()));
        Logger.getLogger("RPCharacters").addHandler(handler);
    }

    @AfterEach void cleanup() throws Exception {
        Logger.getLogger("RPCharacters").removeHandler(handler); CreationManager.activeCreators.clear();
        ClassPickService.configure(Settings.DEFAULTS); ClassPickService.setWallet(mock(DenarWallet.class));
        for (int i = boundaries.size() - 1; i >= 0; i--) boundaries.get(i).close();
        MMOCore.plugin = previousMmo; state.close(); MockBukkit.unmock();
    }

    PlayerClass playerClass(String id, String name, int order, ItemStack icon) {
        var result = mock(PlayerClass.class); when(result.getId()).thenReturn(id); when(result.getName()).thenReturn(name);
        when(result.getDisplayOrder()).thenReturn(order); when(result.getSubclasses()).thenReturn(List.of()); when(result.getIcon()).thenReturn(icon);
        classMap.put(id, result); return result;
    }

    String messages() {
        List<String> out = new ArrayList<>(); String message;
        while ((message = player.nextMessage()) != null) out.add(ChatColor.stripColor(message));
        return String.join("\n", out);
    }

    static String lore(ItemStack item) { return ChatColor.stripColor(String.join("\n", item.getItemMeta().getLore())); }

    Settings settings(boolean enabled, boolean infinite) {
        return new Settings(enabled, infinite, new BigDecimal("200.00"), List.of(Account.POUCH, Account.BANK), Set.of("class", "c"), "rpchar.class.reset");
    }

    @Test void configReadsEverySettingAndFallsBackOnMissingOrInvalidValues() {
        ClassPickConfig.load(null); assertSame(Settings.DEFAULTS, ClassPickService.settings());
        var yaml = new YamlConfiguration();
        yaml.set("a.enabled", false); yaml.set("a.infinite-points", true); yaml.set("a.change-cost", 150.5);
        yaml.set("a.accounts", List.of("Bank", "vault")); yaml.set("a.commands", List.of("/Class", " ", "Pick")); yaml.set("a.reset-permission", " staff.reset ");
        ClassPickConfig.load(yaml.getConfigurationSection("a")); var read = ClassPickService.settings();
        assertFalse(read.enabled()); assertFalse(ClassPickService.isEnabled()); assertTrue(read.infinitePoints()); assertEquals(new BigDecimal("150.50"), read.changeCost());
        assertEquals(List.of(Account.BANK), read.accounts()); assertEquals(Set.of("class", "pick"), read.commands()); assertEquals("staff.reset", read.resetPermission());
        yaml.set("b.change-cost", "lots"); yaml.set("b.accounts", List.of("vault")); yaml.set("b.commands", "class");
        ClassPickConfig.load(yaml.getConfigurationSection("b")); read = ClassPickService.settings();
        assertTrue(read.enabled()); assertFalse(read.infinitePoints()); assertEquals(Settings.DEFAULTS.changeCost(), read.changeCost());
        assertEquals(Settings.DEFAULTS.accounts(), read.accounts()); assertEquals(Settings.DEFAULTS.commands(), read.commands());
        assertEquals("rpchar.class.reset", read.resetPermission()); yaml.set("e.reset-permission", " "); ClassPickConfig.load(yaml.getConfigurationSection("e"));
        assertEquals("rpchar.class.reset", ClassPickService.settings().resetPermission());
        yaml.set("c.change-cost", -5); yaml.set("c.enabled", true); ClassPickConfig.load(yaml.getConfigurationSection("c"));
        assertEquals(Settings.DEFAULTS.changeCost(), ClassPickService.settings().changeCost());
        yaml.set("d.infinite-points", true); ClassPickConfig.load(yaml.getConfigurationSection("d"));
        assertEquals(Settings.DEFAULTS.changeCost(), ClassPickService.settings().changeCost()); assertTrue(ClassPickService.settings().infinitePoints());
        assertTrue(logs.stream().anyMatch(record -> record.getMessage().contains("change-cost: ignored 'lots'")));
        assertTrue(logs.stream().anyMatch(record -> record.getMessage().contains("unknown account 'vault'")));
    }

    @Test void optionsListDisplayedBaseClassesInOrderWithTheirSubclasses() {
        assertEquals(List.of(warrior, mage), ClassPickService.baseClasses());
        assertEquals(new Option(warrior, null, 0), ClassPickService.option("WARRIOR"));
        var sub = ClassPickService.option("berserker"); assertSame(berserker, sub.playerClass()); assertSame(warrior, sub.base()); assertEquals(2, sub.requiredLevel()); assertTrue(sub.isSubclass());
        assertNull(ClassPickService.option("test")); assertNull(ClassPickService.option("missing")); assertNull(ClassPickService.option(null));
    }

    @Test void firstClassAndFirstSubclassOfTheCurrentClassAreFreeAndTheRestCostTheChangePrice() {
        var berserkerPick = ClassPickService.option("berserker"); var magePick = ClassPickService.option("mage");
        assertEquals(BigDecimal.ZERO, ClassPickService.price(character, berserkerPick));
        assertEquals(new BigDecimal("200.00"), ClassPickService.price(character, magePick));
        character.setMMOClass("mage"); assertEquals(new BigDecimal("200.00"), ClassPickService.price(character, berserkerPick), "Only the current class's subclasses are free");
        character.setMMOClass("berserker"); var spellbladePick = ClassPickService.option("spellblade");
        assertEquals(BigDecimal.ZERO, ClassPickService.price(character, spellbladePick), "A reset subclass holder's sibling is the free subclass pick");
        assertEquals(new BigDecimal("200.00"), ClassPickService.price(character, magePick));
        character.setMMOClass("warrior"); character.setSubclassPicked(true); assertEquals(new BigDecimal("200.00"), ClassPickService.price(character, berserkerPick));
        assertEquals(BigDecimal.ZERO, ClassPickService.price(new RPCharacter(player), magePick), "A character without a class picks its first one free");
        character.setMMOClass("mage"); ClassPickService.configure(settings(true, true)); assertEquals(BigDecimal.ZERO, ClassPickService.price(character, magePick));
        assertEquals("Free", ClassPickService.priceText(BigDecimal.ZERO)); assertEquals("200 denars", ClassPickService.priceText(new BigDecimal("200.00")));
    }

    @Test void classChangesFollowTheClassStageLockWindowAndPaidChangeRule() {
        var stage = mock(SelectionStage.class); when(stage.getTarget()).thenReturn("class"); when(stage.getId()).thenReturn("class_selection_stage");
        StageLoader.oList = new ArrayList<>(List.of(mock(Stage.class), stage));
        PaidChangeService.configure(List.of(new PaidChangeRule("class", "class_selection_stage", "class",
                List.of(new BigDecimal("100"), new BigDecimal("1000"), new BigDecimal("3000")))), List.of());
        var locks = boundary(StageEditLock.class);
        locks.when(() -> StageEditLock.canEdit(player, stage, character)).thenReturn(true); locks.when(() -> StageEditLock.lockRemainingMs(stage, character)).thenReturn(3_600_000L);
        var magePick = ClassPickService.option("mage"); var lore = (java.util.function.Supplier<String>) () -> String.join(" / ", ClassPickService.pricingLore(character, "Head").stream().map(ChatColor::stripColor).toList());
        assertEquals(BigDecimal.ZERO, ClassPickService.price(character, magePick), "Class changes are free while the class stage is unlocked");
        assertTrue(lore.get().matches("Head / First subclass: Free / Class changes: Free for 1h.* / Then: 100 denars"), lore.get());
        locks.when(() -> StageEditLock.lockRemainingMs(stage, character)).thenReturn(0L); assertEquals("Head / First subclass: Free / Class changes: Free", lore.get());
        locks.when(() -> StageEditLock.canEdit(player, stage, character)).thenReturn(false);
        assertEquals(new BigDecimal("100.00"), ClassPickService.price(character, magePick)); assertEquals("Head / First subclass: Free / Class changes: 100 denars / Then: 1,000 denars", lore.get());
        wallet.set(Account.POUCH, 15_000); var paid = ClassPickService.choose(player, "mage");
        assertEquals(Status.CHOSEN, paid.status()); assertEquals(new BigDecimal("100.00"), paid.cost()); assertEquals(1, character.getPaidChangeCount("class_selection_stage"));
        assertEquals(new BigDecimal("1000.00"), ClassPickService.price(character, ClassPickService.option("warrior")), "Each paid change raises the next price");
    }

    @Test void freeSubclassPickAppliesSavesAndAnnouncesTheChange() {
        level.set(2); var result = ClassPickService.choose(player, "berserker");
        assertEquals(Status.CHOSEN, result.status()); assertNull(result.paidFrom()); assertEquals("BERSERKER", character.getMMOClass());
        assertTrue(character.hasPickedSubclass()); assertEquals(0, character.getPaidClassPicks()); classService.verify(() -> ClassService.applyClass(player, "berserker"));
        verify(playerManager).savePlayer(player); server.getPluginManager().assertEventFired(CharacterClassChangeEvent.class);
        assertEquals("You are now a Berserker.", ChatColor.stripColor(ClassPickService.message(result)));
    }

    @Test void paidPicksTakeTheWholeCostFromTheFirstAccountThatCoversIt() {
        level.set(2); character.setSubclassPicked(true); wallet.set(Account.POUCH, 10_000); wallet.set(Account.BANK, 50_000);
        var result = ClassPickService.choose(player, "mage");
        assertEquals(Status.CHOSEN, result.status()); assertEquals(Account.BANK, result.paidFrom()); assertEquals(30_000, wallet.get(Account.BANK)); assertNull(character.getPendingClassPick());
        assertEquals(1, character.getPaidClassPicks()); assertTrue(ChatColor.stripColor(ClassPickService.message(result)).contains("Paid 200 denars from your bank."));
        wallet.set(Account.POUCH, 100_000); wallet.set(Account.BANK, 0); wallet.rejectPouch = true; assertEquals(Status.INSUFFICIENT_FUNDS, ClassPickService.choose(player, "warrior").status());
        assertNull(character.getPendingClassPick());
    }

    @Test void picksAreRefusedWithoutChargingWhenTheyCannotGoAhead() {
        ClassPickService.configure(settings(false, false)); assertEquals(Status.DISABLED, ClassPickService.choose(player, "mage").status()); ClassPickService.configure(Settings.DEFAULTS);
        players.when(() -> PlayerManager.get(player)).thenReturn(null); assertEquals(Status.NO_CHARACTER, ClassPickService.choose(player, "mage").status());
        players.when(() -> PlayerManager.get(player)).thenReturn(account); when(account.hasActiveCharacter()).thenReturn(false); assertEquals(Status.NO_CHARACTER, ClassPickService.blocker(player));
        when(account.hasActiveCharacter()).thenReturn(true); CreationManager.activeCreators.put(player, mock(CharacterCreation.class)); assertEquals(Status.BUSY, ClassPickService.choose(player, "mage").status());
        CreationManager.activeCreators.clear(); assertNull(ClassPickService.blocker(player));
        assertEquals(Status.UNKNOWN_CLASS, ClassPickService.choose(player, "test").status());
        assertEquals(Status.CURRENT_CLASS, ClassPickService.choose(player, "Warrior").status());
        assertEquals(Status.LEVEL_TOO_LOW, ClassPickService.choose(player, "berserker").status());
        wallet.available = false; assertEquals(Status.UNAVAILABLE, ClassPickService.choose(player, "mage").status());
        wallet.available = true; var broke = ClassPickService.choose(player, "mage"); assertEquals(Status.INSUFFICIENT_FUNDS, broke.status()); assertEquals(new BigDecimal("200.00"), broke.cost());
        assertEquals("WARRIOR", character.getMMOClass()); verify(playerManager, never()).savePlayer(any()); classService.verify(() -> ClassService.applyClass(any(), anyString()), never());
    }

    @Test void aPickMmoCoreRejectsKeepsTheOldClassAndRefundsThePayment() {
        classService.when(() -> ClassService.applyClass(player, "mage")).thenReturn(false); wallet.set(Account.POUCH, 25_000);
        var result = ClassPickService.choose(player, "mage");
        assertEquals(Status.NOT_APPLIED, result.status()); assertEquals("WARRIOR", character.getMMOClass()); assertEquals(25_000, wallet.get(Account.POUCH)); assertNull(character.getPendingClassPick());
        assertTrue(ChatColor.stripColor(ClassPickService.message(result)).contains("You were not charged."));
        wallet.depositsDown = true; var held = ClassPickService.choose(player, "mage");
        assertTrue(logs.stream().anyMatch(record -> record.getMessage().contains("retried when the owner next joins")));
        assertEquals(Account.POUCH, held.paidFrom()); assertFalse(ChatColor.stripColor(ClassPickService.message(held)).contains("charged"));
        assertTrue(messages().contains("refund couldn't go through yet")); wallet.depositsDown = false;
        assertTrue(ClassPickService.recover(player.getUniqueId(), character).contains("went back to your pouch")); assertEquals(25_000, wallet.get(Account.POUCH));
        ClassPickService.configure(settings(true, true)); var free = ClassPickService.choose(player, "mage");
        assertEquals(Status.NOT_APPLIED, free.status()); assertFalse(ChatColor.stripColor(ClassPickService.message(free)).contains("charged"));
        var classless = new RPCharacter(player); when(account.getActiveCharacter()).thenReturn(classless);
        classService.when(() -> ClassService.applyClass(player, "mage")).thenThrow(new IllegalStateException("MMOCore broke"));
        assertEquals(Status.NOT_APPLIED, ClassPickService.choose(player, "mage").status()); assertFalse(classless.hasMMOClass(), "A character without a class stays without one");
        assertTrue(logs.stream().anyMatch(record -> record.getMessage().contains("could not be applied") && record.getThrown() instanceof IllegalStateException));
    }

    @Test void everyOutcomeHasAPlayerMessage() {
        level.set(1);
        for (Status status : Status.values()) {
            assertFalse(ClassPickService.message(new Result(status, berserker, new BigDecimal("200.00"), Account.POUCH)).isBlank());
            assertFalse(ClassPickService.message(new Result(status, null, BigDecimal.ZERO, null)).isBlank());
        }
        assertTrue(ChatColor.stripColor(ClassPickService.message(new Result(Status.LEVEL_TOO_LOW, berserker, BigDecimal.ZERO, null))).contains("unlocks at level 2"));
        assertTrue(ChatColor.stripColor(ClassPickService.message(new Result(Status.LEVEL_TOO_LOW, hidden, BigDecimal.ZERO, null))).contains("level 0"));
    }

    @Test void pricingLoreDescribesFreeAndPaidPicks() {
        assertEquals(List.of("Head", "First subclass: Free", "Other picks: 200 denars"), ClassPickService.pricingLore(character, "Head").stream().map(ChatColor::stripColor).toList());
        character.setSubclassPicked(true); assertEquals(List.of("Head", "Other picks: 200 denars"), ClassPickService.pricingLore(character, "Head").stream().map(ChatColor::stripColor).toList());
        ClassPickService.configure(settings(true, true)); assertEquals(List.of("Head", "Class picks: Free"), ClassPickService.pricingLore(character, "Head").stream().map(ChatColor::stripColor).toList());
    }

    Inventory openPicker() {
        ClassPickGui.open(player); var top = player.getOpenInventory().getTopInventory();
        assertInstanceOf(ClassPickGui.Holder.class, top.getHolder()); return top;
    }

    Inventory openSubclasses() {
        ClassPickGui.openSubclasses(player); return player.getOpenInventory().getTopInventory();
    }

    @Test void separateWindowsShowOnlyBasesOrTheCurrentClassSubclassFamily() {
        displayed.addAll(Set.of("berserker", "spellblade"));
        assertEquals(List.of(warrior, mage), ClassPickService.baseClasses());
        assertEquals(List.of("warrior", "mage"), MmoCoreClassGuiHelper.buildClassOptions(54, Map.of("warrior", 10, "mage", 11)).getOptions().stream().map(item -> item.getId()).toList());
        var top = openPicker(); assertEquals(ClassPickGui.SIZE, top.getSize()); assertSame(top, top.getHolder().getInventory());
        assertEquals("Class Selection", ChatColor.stripColor(player.getOpenInventory().getTitle()));
        assertEquals(Material.IRON_SWORD, top.getItem(10).getType()); assertTrue(lore(top.getItem(10)).contains("Your current class"));
        assertEquals(Material.PAPER, top.getItem(11).getType()); assertTrue(lore(top.getItem(11)).contains("Cost: 200 denars"));
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, top.getItem(19).getType());
        assertTrue(lore(top.getItem(ClassPickGui.INFO_SLOT)).contains("Class: Warrior"));
        ClassPickGui.click(player, (ClassPickGui.Holder) top.getHolder(), ClassPickGui.NAV_SLOT);
        top = player.getOpenInventory().getTopInventory();
        assertEquals("Subclass Selection", ChatColor.stripColor(player.getOpenInventory().getTitle()));
        assertTrue(lore(top.getItem(10)).contains("Subclass of Warrior")); assertTrue(lore(top.getItem(10)).contains("Unlocks at level 2"));
        assertEquals(Material.PAPER, top.getItem(11).getType());
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, top.getItem(12).getType());
        ClassPickGui.click(player, (ClassPickGui.Holder) top.getHolder(), ClassPickGui.NAV_SLOT);
        assertEquals("Class Selection", ChatColor.stripColor(player.getOpenInventory().getTitle()));
        level.set(2); character.setMMOClass("berserker"); top = openSubclasses();
        assertTrue(lore(top.getItem(10)).contains("Your current class")); assertTrue(lore(top.getItem(11)).contains("Cost: Free"));
        character.setSubclassPicked(true); top = openSubclasses(); assertTrue(lore(top.getItem(11)).contains("Cost: 200 denars"));
        character.setMMOClass("mage"); top = openSubclasses();
        assertTrue(ChatColor.stripColor(top.getItem(22).getItemMeta().getDisplayName()).contains("No subclasses"));
        character.setMMOClass(null); assertTrue(ClassPickService.subclassOptions(character).isEmpty());
        top = openPicker(); assertTrue(lore(top.getItem(ClassPickGui.INFO_SLOT)).contains("Class: None"));
        assertTrue(lore(top.getItem(10)).contains("Cost: Free"));
    }

    @Test void baseClassOfTheCurrentSubclassIsMarkedAndWarnsThatPickingItRemovesTheSubclass() {
        level.set(2); character.setMMOClass("berserker"); character.setSubclassPicked(true);
        assertSame(berserker, ClassPickService.subclassOf(character, warrior)); assertNull(ClassPickService.subclassOf(character, mage));
        var top = openPicker(); var base = top.getItem(10);
        assertTrue(lore(base).contains("Your class, via Berserker")); assertTrue(lore(base).contains("Picking it removes your Berserker subclass."));
        assertFalse(lore(base).contains("Your current class")); assertTrue(base.getItemMeta().getEnchantmentGlintOverride());
        assertFalse(lore(top.getItem(11)).contains("Your class, via")); assertFalse(top.getItem(11).getItemMeta().hasEnchantmentGlintOverride());
        character.setMMOClass("warrior"); assertNull(ClassPickService.subclassOf(character, warrior));
        character.setMMOClass(null); assertNull(ClassPickService.subclassOf(character, warrior));
    }

    @Test void staleSubclassWindowCannotPickAnotherClassesSubclassOrCharge() {
        level.set(2); var top = openSubclasses(); var holder = (ClassPickGui.Holder) top.getHolder();
        ClassPickGui.click(player, holder, 10); character.setMMOClass("mage");
        ClassPickGui.click(player, holder, 10); assertEquals("MAGE", character.getMMOClass());
        assertTrue(messages().contains("can't be picked"));
        assertEquals(Status.UNKNOWN_CLASS, ClassPickService.choose(player, "spellblade").status());
        classService.verifyNoInteractions(); assertTrue(wallet.attempts.isEmpty());
    }

    @Test void hiddenCurrentBaseStillAllowsItsOwnSubclasses() {
        displayed.remove("warrior"); level.set(2);
        assertEquals(List.of(mage), ClassPickService.baseClasses());
        assertEquals(2, ClassPickService.subclassOptions(character).size());
        assertEquals(Status.CHOSEN, ClassPickService.choose(player, "berserker").status());
        assertEquals(2, ClassPickService.subclassOptions(character).size());
        ClassPickService.configure(settings(true, true));
        assertEquals(Status.CHOSEN, ClassPickService.choose(player, "spellblade").status());
        character.setMMOClass("missing"); assertTrue(ClassPickService.subclassOptions(character).isEmpty());
    }

    @Test void assignedHiddenClassesAreNeverOfferedAndBindTheCharacterWhilePlayed() {
        assertEquals(Status.UNKNOWN_CLASS, ClassPickService.choose(player, "test").status(), "Players never pick a hidden class");
        playerClass("blood", "Blood", -5, null);
        for (String id : List.of("test", "blood")) character.addUnlockedClass(id);
        assertEquals(List.of(warrior, mage), ClassPickService.baseClasses());
        assertNull(ClassPickService.option("test"));
        assertEquals(Status.UNKNOWN_CLASS, ClassPickService.choose(player, "test").status(), "Assigned or not, the picker can't choose it");
        var top = openPicker();
        for (int slot = 0; slot < top.getSize(); slot++) {
            var item = top.getItem(slot);
            assertFalse(item != null && item.hasItemMeta() && "Test".equals(ChatColor.stripColor(item.getItemMeta().getDisplayName())));
        }
        character.setMMOClass("TEST");
        assertEquals(Status.ASSIGNED_CLASS, ClassPickService.blocker(player));
        assertEquals(Status.ASSIGNED_CLASS, ClassPickService.choose(player, "mage").status());
        assertEquals("Your class is bound to your character and can't be changed.",
                ChatColor.stripColor(ClassPickService.message(new Result(Status.ASSIGNED_CLASS, null, BigDecimal.ZERO, null))));
        player.closeInventory(); messages(); ClassPickGui.open(player);
        assertTrue(messages().contains("can't be changed"));
        character.removeUnlockedClass("test");
        assertNull(ClassPickService.blocker(player), "A released class no longer binds");
        assertEquals("TEST", character.getMMOClass());
    }

    @Test void pickerUsesAdditionalRowsForMoreThanSevenClassesAndSubclasses() {
        for (int i = 0; i < 9; i++) { playerClass("base" + i, "Base " + i, 10 + i, null); displayed.add("base" + i); }
        var top = openPicker(); assertEquals(Material.PAPER, top.getItem(19).getType());
        var subs = new ArrayList<Subclass>(); for (int i = 0; i < 5; i++) subs.add(new Subclass(playerClass("sub" + i, "Sub " + i, 40 + i, null), 1));
        when(warrior.getSubclasses()).thenReturn(subs); top = openSubclasses();
        assertTrue(lore(top.getItem(14)).contains("Subclass of Warrior")); assertNull(((ClassPickGui.Holder) top.getHolder()).getPending());
    }

    @Test void pickerClicksMarkThenConfirmAndCloseOnSuccess() {
        level.set(2); var top = openSubclasses(); var holder = (ClassPickGui.Holder) top.getHolder();
        ClassPickGui.click(player, holder, 0); assertNull(holder.getPending());
        ClassPickGui.click(player, holder, 10); assertEquals("berserker", holder.getPending()); assertTrue(lore(top.getItem(10)).contains("Click again to confirm"));
        assertTrue(top.getItem(10).getItemMeta().getEnchantmentGlintOverride());
        ClassPickGui.click(player, holder, 10); assertEquals("BERSERKER", character.getMMOClass()); assertNull(holder.getPending());
        assertTrue(messages().contains("You are now a Berserker."));
        top = openPicker(); holder = (ClassPickGui.Holder) top.getHolder();
        ClassPickGui.click(player, holder, ClassPickGui.CLOSE_SLOT);
    }

    @Test void pickerClicksOnLockedOrFailingClassesExplainAndRefresh() {
        var top = openPicker(); var holder = (ClassPickGui.Holder) top.getHolder();
        ClassPickGui.click(player, holder, 10); assertTrue(messages().contains("You are already a Warrior."));
        var subclassTop = openSubclasses(); ClassPickGui.click(player, (ClassPickGui.Holder) subclassTop.getHolder(), 10); assertTrue(messages().contains("unlocks at level 2"));
        top = openPicker(); holder = (ClassPickGui.Holder) top.getHolder();
        ClassPickGui.click(player, holder, 11); ClassPickGui.click(player, holder, 11); assertTrue(messages().contains("costs 200 denars"));
        assertSame(top, player.getOpenInventory().getTopInventory());
        ClassPickGui.click(player, holder, 11); when(account.hasActiveCharacter()).thenReturn(false); ClassPickGui.click(player, holder, 11);
        assertTrue(messages().contains("You need an active character"));
    }

    @Test void openingIsRefusedWithAReasonWhenThePlayerCannotPick() {
        when(account.hasActiveCharacter()).thenReturn(false); ClassPickGui.open(player);
        var top = player.getOpenInventory().getTopInventory(); assertTrue(top == null || !(top.getHolder() instanceof ClassPickGui.Holder));
        assertTrue(messages().contains("You need an active character to pick a class."));
    }

    @Test void listenerRoutesTopInventoryClicksAndOpensThePickerForConfiguredCommands() {
        var listener = new ClassPickListener(); var top = openPicker(); var view = player.getOpenInventory();
        var bottomClick = new InventoryClickEvent(view, SlotType.CONTAINER, 60, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onClick(bottomClick); assertTrue(bottomClick.isCancelled());
        var topClick = new InventoryClickEvent(view, SlotType.CONTAINER, 11, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onClick(topClick); assertTrue(topClick.isCancelled()); assertEquals("mage", ((ClassPickGui.Holder) top.getHolder()).getPending());
        var drag = new InventoryDragEvent(view, null, new ItemStack(Material.STONE), false, Map.of(11, new ItemStack(Material.STONE)));
        listener.onDrag(drag); assertTrue(drag.isCancelled());
        player.closeInventory(); InventoryView plain = player.openInventory(server.createInventory(null, 9));
        var otherClick = new InventoryClickEvent(plain, SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onClick(otherClick); assertFalse(otherClick.isCancelled());
        var otherDrag = new InventoryDragEvent(plain, null, new ItemStack(Material.STONE), false, Map.of(0, new ItemStack(Material.STONE)));
        listener.onDrag(otherDrag); assertFalse(otherDrag.isCancelled());
        for (String command : List.of("/class", "/C list", "/mmocore:class", "/mmocore:c", "/rpcharacters:class")) {
            player.closeInventory(); var event = new PlayerCommandPreprocessEvent(player, command); listener.onCommand(event);
            assertTrue(event.isCancelled(), command); assertInstanceOf(ClassPickGui.Holder.class, player.getOpenInventory().getTopInventory().getHolder());
        }
        for (String command : List.of("/classes", "/", "/mmocore:skills")) {
            var event = new PlayerCommandPreprocessEvent(player, command); listener.onCommand(event); assertFalse(event.isCancelled(), command);
        }
        for (String raw : List.of("/subclass", "/mmocore:subclass", "/rpcharacters:subclass")) {
            var event = new PlayerCommandPreprocessEvent(player, raw); listener.onCommand(event);
            assertTrue(event.isCancelled()); assertEquals("Subclass Selection", ChatColor.stripColor(player.getOpenInventory().getTitle()));
        }
        var command = mock(org.bukkit.command.Command.class); when(command.getName()).thenReturn("subclass");
        assertTrue(listener.onCommand(player, command, "subclass", new String[0]));
        when(command.getName()).thenReturn("class"); assertTrue(listener.onCommand(player, command, "class", new String[0]));
        assertEquals("Class Selection", ChatColor.stripColor(player.getOpenInventory().getTitle()));
        assertTrue(listener.onCommand(server.getConsoleSender(), command, "class", new String[0]));
        ClassPickService.configure(settings(false, false)); var disabled = new PlayerCommandPreprocessEvent(player, "/class");
        listener.onCommand(disabled); assertFalse(disabled.isCancelled());
    }

    @Test void aPaidPickIsSavedBeforeItsDebitAndClearedOnceApplied() {
        wallet.set(Account.POUCH, 50_000); List<String> seen = new ArrayList<>();
        PaymentsFixture.saves((owner, saved) -> {
            PendingClassPick held = saved.getPendingClassPick();
            seen.add(held.targetClass() + " " + held.payment().debitOpId().startsWith("paid:" + saved.getId() + ":class-pick:")
                    + " posted=" + wallet.stored.containsKey(held.payment().debitOpId()));
            return true;
        });
        assertEquals(Status.CHOSEN, ClassPickService.choose(player, "mage").status());
        assertEquals(List.of("mage true posted=false"), seen); assertNull(character.getPendingClassPick());
        assertEquals(DenarWallet.CLASS_PICK, wallet.applied.get(0).reason());
        assertEquals(new CharacterRef(player.getUniqueId(), UUID.fromString(character.getId())), wallet.applied.get(0).character());
    }

    @Test void anUnsavablePickTakesNothing() {
        wallet.set(Account.POUCH, 50_000); PaymentsFixture.saves((owner, saved) -> false);
        assertEquals(Status.UNAVAILABLE, ClassPickService.choose(player, "mage").status());
        assertTrue(wallet.attempts.isEmpty()); assertNull(character.getPendingClassPick()); assertEquals("WARRIOR", character.getMMOClass());
    }

    @Test void aCrashBetweenDebitAndClassRefundsOnceOrKeepsAnAppliedPick() {
        wallet.set(Account.POUCH, 50_000); List<JSONObject> onDisk = new ArrayList<>();
        PaymentsFixture.saves((owner, saved) -> onDisk.add(picksJson(saved)));
        assertEquals(Status.CHOSEN, ClassPickService.choose(player, "mage").status());
        RPCharacter crashed = new RPCharacter(player); crashed.setId(character.getId()); crashed.setMMOClass("warrior");
        CharacterClassPickFields.load(crashed, onDisk.get(0));
        RPCharacter again = new RPCharacter(player); again.setId(character.getId()); again.setMMOClass("warrior");
        CharacterClassPickFields.load(again, onDisk.get(0));
        assertEquals("mage", crashed.getPendingClassPick().targetClass());

        assertTrue(ClassPickService.recover(player.getUniqueId(), crashed).contains("200 denars went back to your pouch"));
        assertNull(crashed.getPendingClassPick()); assertEquals(50_000, wallet.get(Account.POUCH));
        ClassPickService.recover(player.getUniqueId(), again);
        assertEquals(50_000, wallet.get(Account.POUCH)); assertEquals(1, wallet.count(false));

        RPCharacter stuck = new RPCharacter(player); stuck.setMMOClass("mage");
        CharacterClassPickFields.load(stuck, onDisk.get(0));
        assertNull(ClassPickService.recover(player.getUniqueId(), stuck));
        assertNull(stuck.getPendingClassPick()); assertEquals(1, stuck.getPaidClassPicks()); assertEquals(1, wallet.count(false));
        assertNull(ClassPickService.recover(player.getUniqueId(), null)); assertNull(ClassPickService.recover(player.getUniqueId(), stuck));
    }

    @Test void aRefusedOrUncertainDebitNeverRefundsAndBlocksPicksUntilKnown() {
        wallet.set(Account.POUCH, 50_000); List<JSONObject> onDisk = new ArrayList<>();
        PaymentsFixture.saves((owner, saved) -> onDisk.add(picksJson(saved)));
        wallet.loseReplies = true; wallet.statusDown = true;
        assertEquals(Status.UNAVAILABLE, ClassPickService.choose(player, "mage").status());
        assertEquals("WARRIOR", character.getMMOClass()); assertEquals(30_000, wallet.get(Account.POUCH));
        assertEquals(Status.UNAVAILABLE, ClassPickService.choose(player, "mage").status(), "an unconfirmed pick blocks the next");
        assertEquals(1, wallet.attempts.size());
        wallet.loseReplies = false; wallet.statusDown = false;
        assertEquals(Status.CHOSEN, ClassPickService.choose(player, "mage").status());
        assertTrue(messages().contains("200 denars went back to your pouch")); assertEquals(30_000, wallet.get(Account.POUCH));

        RPCharacter refused = new RPCharacter(player); refused.setMMOClass("warrior");
        CharacterClassPickFields.load(refused, onDisk.get(0));
        wallet.stored.put(refused.getPendingClassPick().payment().debitOpId(), DenarWallet.Posting.REFUSED);
        long deposits = wallet.count(false);
        assertNull(ClassPickService.recover(player.getUniqueId(), refused)); assertNull(refused.getPendingClassPick());
        assertEquals(deposits, wallet.count(false));
    }

    @Test void pendingPicksSurviveASaveAndUnreadableOnesAreDropped() {
        Payment payment = Payment.open(new CharacterRef(player.getUniqueId(), UUID.fromString(character.getId())), Account.BANK, 500, "class-pick");
        character.setPendingClassPick(new PendingClassPick("MAGE", payment, true));
        RPCharacter loaded = new RPCharacter(player); CharacterClassPickFields.load(loaded, picksJson(character));
        assertEquals(character.getPendingClassPick(), loaded.getPendingClassPick());
        RPCharacter broken = new RPCharacter(player);
        JSONObject json = new JSONObject(); json.put("class-picks", Map.of("pending", Map.of("target", "MAGE")));
        CharacterClassPickFields.load(broken, json); assertNull(broken.getPendingClassPick());
    }

    @Test void aFailedPaidPickIsRefundedBeforeAnyEditEvenIfTheClassLaterMatches() {
        wallet.set(Account.POUCH, 25_000); List<JSONObject> onDisk = new ArrayList<>();
        PaymentsFixture.saves((owner, saved) -> onDisk.add(picksJson(saved)));
        classService.when(() -> ClassService.applyClass(player, "mage")).thenReturn(false); wallet.depositsDown = true;
        var failed = ClassPickService.choose(player, "mage");
        assertEquals(Status.NOT_APPLIED, failed.status()); assertEquals(5_000, wallet.get(Account.POUCH));
        assertTrue(character.getPendingClassPick().refundRequired());
        assertEquals(true, ((Map<?, ?>) ((Map<?, ?>) onDisk.getLast().get("class-picks")).get("pending")).get("refund-required"));

        Stage classStage = new Stage(); classStage.setId("class_selection_stage"); StageLoader.oList.add(classStage);
        CharacterCreation edit = CharacterCreation.forEdit(player, character); messages();
        edit.jumpToStageForEdit("class_selection_stage");
        assertTrue(messages().contains("last refund hasn't gone through yet")); assertNotNull(character.getPendingClassPick());
        level.set(2); assertEquals(Status.UNAVAILABLE, ClassPickService.choose(player, "berserker").status());

        // The ledger is back and the character reached the same class some other way: still a refund.
        wallet.depositsDown = false; character.setMMOClass("mage");
        edit.jumpToStageForEdit("class_selection_stage");
        assertTrue(messages().contains("200 denars went back to your pouch"));
        assertNull(character.getPendingClassPick()); assertEquals(25_000, wallet.get(Account.POUCH)); assertEquals(0, character.getPaidClassPicks());
        assertEquals(1, wallet.count(false));
    }

    @Test void aHeldPaidStageChangeBlocksClassPicksUntilItSettles() {
        wallet.set(Account.POUCH, 50_000); boolean[] ready = {false}; PaymentsFixture.paidChangeWallet(wallet);
        CharacterRef heir = new CharacterRef(player.getUniqueId(), UUID.fromString(character.getId()));
        PaymentsFixture.heirs(() -> ready[0], owner -> Optional.of(heir));
        character.setPendingPaidChange(new PendingPaidChange("class_selection_stage", "class",
                Payment.legacy(player.getUniqueId(), Account.POUCH, 1_000, "refund-legacy:x:class_selection_stage"), "before"));
        assertEquals(Status.UNAVAILABLE, ClassPickService.choose(player, "mage").status());
        assertTrue(messages().contains("refund couldn't go through yet")); assertEquals("WARRIOR", character.getMMOClass());
        ready[0] = true;
        assertEquals(Status.CHOSEN, ClassPickService.choose(player, "mage").status());
        assertNull(character.getPendingPaidChange()); assertEquals(31_000, wallet.get(Account.POUCH));
    }

    @SuppressWarnings("unchecked")
    private static JSONObject picksJson(RPCharacter character) {
        HashMap<String, Object> saved = new HashMap<>(); CharacterClassPickFields.save(saved, character);
        try { return (JSONObject) new JSONParser().parse(new JSONObject(saved).toJSONString()); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
