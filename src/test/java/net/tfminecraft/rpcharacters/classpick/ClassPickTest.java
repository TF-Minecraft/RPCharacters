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

class ClassPickTest {
    ServerMock server; PlayerMock player; RuntimeTestState state; MMOCore previousMmo; ClassManager classes;
    PlayerData account; RPCharacter character; PlayerManager playerManager; FakeWallet wallet;
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
        server = MockBukkit.mock(); state = new RuntimeTestState(RPCharacters.class, StageLoader.class, PaidChangeService.class); previousMmo = MMOCore.plugin;
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
        wallet = new FakeWallet(); ClassPickService.setWallet(wallet); ClassPickService.configure(Settings.DEFAULTS);
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
        return new Settings(enabled, infinite, new BigDecimal("200.00"), List.of(Account.POUCH, Account.BANK), Set.of("class", "c"));
    }

    @Test void configReadsEverySettingAndFallsBackOnMissingOrInvalidValues() {
        ClassPickConfig.load(null); assertSame(Settings.DEFAULTS, ClassPickService.settings());
        var yaml = new YamlConfiguration();
        yaml.set("a.enabled", false); yaml.set("a.infinite-points", true); yaml.set("a.change-cost", 150.5);
        yaml.set("a.accounts", List.of("Bank", "vault")); yaml.set("a.commands", List.of("/Class", " ", "Pick"));
        ClassPickConfig.load(yaml.getConfigurationSection("a")); var read = ClassPickService.settings();
        assertFalse(read.enabled()); assertFalse(ClassPickService.isEnabled()); assertTrue(read.infinitePoints()); assertEquals(new BigDecimal("150.50"), read.changeCost());
        assertEquals(List.of(Account.BANK), read.accounts()); assertEquals(Set.of("class", "pick"), read.commands());
        yaml.set("b.change-cost", "lots"); yaml.set("b.accounts", List.of("vault")); yaml.set("b.commands", "class");
        ClassPickConfig.load(yaml.getConfigurationSection("b")); read = ClassPickService.settings();
        assertTrue(read.enabled()); assertFalse(read.infinitePoints()); assertEquals(Settings.DEFAULTS.changeCost(), read.changeCost());
        assertEquals(Settings.DEFAULTS.accounts(), read.accounts()); assertEquals(Settings.DEFAULTS.commands(), read.commands());
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
        wallet.balances.put(Account.POUCH, new BigDecimal("150")); var paid = ClassPickService.choose(player, "mage");
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
        level.set(2); character.setSubclassPicked(true); wallet.balances.put(Account.POUCH, new BigDecimal("100")); wallet.balances.put(Account.BANK, new BigDecimal("500"));
        var result = ClassPickService.choose(player, "mage");
        assertEquals(Status.CHOSEN, result.status()); assertEquals(Account.BANK, result.paidFrom()); assertEquals(new BigDecimal("300.00"), wallet.balances.get(Account.BANK).setScale(2));
        assertEquals(1, character.getPaidClassPicks()); assertTrue(ChatColor.stripColor(ClassPickService.message(result)).contains("Paid 200 denars from your bank."));
        wallet.balances.put(Account.POUCH, new BigDecimal("1000")); wallet.failWithdraw = true; assertEquals(Status.INSUFFICIENT_FUNDS, ClassPickService.choose(player, "warrior").status());
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
        classService.when(() -> ClassService.applyClass(player, "mage")).thenReturn(false); wallet.balances.put(Account.POUCH, new BigDecimal("250"));
        var result = ClassPickService.choose(player, "mage");
        assertEquals(Status.NOT_APPLIED, result.status()); assertEquals("WARRIOR", character.getMMOClass()); assertEquals(0, new BigDecimal("250").compareTo(wallet.balances.get(Account.POUCH)));
        assertTrue(ChatColor.stripColor(ClassPickService.message(result)).contains("You were not charged."));
        wallet.failDeposit = true; ClassPickService.choose(player, "mage");
        assertTrue(logs.stream().anyMatch(record -> record.getMessage().contains("Refund it by hand")));
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

    @Test void pickerShowsBaseClassColumnsWithSubclassesBelowAndPrices() {
        var top = openPicker(); assertEquals(ClassPickGui.SIZE, top.getSize()); assertSame(top, top.getHolder().getInventory());
        assertEquals(Material.IRON_SWORD, top.getItem(10).getType()); assertTrue(lore(top.getItem(10)).contains("Your current class"));
        assertEquals(Material.PAPER, top.getItem(11).getType()); assertTrue(lore(top.getItem(11)).contains("Cost: 200 denars"));
        assertEquals(Material.PAPER, top.getItem(19).getType()); assertTrue(lore(top.getItem(19)).contains("Subclass of Warrior")); assertTrue(lore(top.getItem(19)).contains("Unlocks at level 2"));
        assertEquals(Material.PAPER, top.getItem(28).getType()); assertEquals(Material.GRAY_STAINED_GLASS_PANE, top.getItem(20).getType());
        assertTrue(lore(top.getItem(ClassPickGui.INFO_SLOT)).contains("Class: Warrior")); assertEquals(Material.BARRIER, top.getItem(ClassPickGui.CLOSE_SLOT).getType());
        when(account.getActiveCharacter()).thenReturn(new RPCharacter(player)); level.set(2); top = openPicker();
        assertTrue(lore(top.getItem(ClassPickGui.INFO_SLOT)).contains("Class: None")); assertTrue(lore(top.getItem(19)).contains("Cost: Free"));
    }

    @Test void pickerShowsAtMostSevenColumnsAndThreeSubclassesEach() {
        for (int i = 0; i < 9; i++) { playerClass("base" + i, "Base " + i, 10 + i, null); displayed.add("base" + i); }
        var subs = new ArrayList<Subclass>(); for (int i = 0; i < 5; i++) subs.add(new Subclass(playerClass("sub" + i, "Sub " + i, 40 + i, null), 1));
        when(warrior.getSubclasses()).thenReturn(subs); var top = openPicker();
        var holder = (ClassPickGui.Holder) top.getHolder();
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, top.getItem(46).getType()); assertNotNull(top.getItem(37)); assertTrue(lore(top.getItem(37)).contains("Subclass of Warrior"));
        assertNull(holder.getPending());
    }

    @Test void pickerClicksMarkThenConfirmAndCloseOnSuccess() {
        level.set(2); var top = openPicker(); var holder = (ClassPickGui.Holder) top.getHolder();
        ClassPickGui.click(player, holder, 0); assertNull(holder.getPending());
        ClassPickGui.click(player, holder, 19); assertEquals("berserker", holder.getPending()); assertTrue(lore(top.getItem(19)).contains("Click again to confirm"));
        assertTrue(top.getItem(19).getItemMeta().getEnchantmentGlintOverride());
        ClassPickGui.click(player, holder, 19); assertEquals("BERSERKER", character.getMMOClass()); assertNull(holder.getPending());
        assertTrue(messages().contains("You are now a Berserker."));
        top = openPicker(); holder = (ClassPickGui.Holder) top.getHolder();
        ClassPickGui.click(player, holder, ClassPickGui.CLOSE_SLOT);
    }

    @Test void pickerClicksOnLockedOrFailingClassesExplainAndRefresh() {
        var top = openPicker(); var holder = (ClassPickGui.Holder) top.getHolder();
        ClassPickGui.click(player, holder, 10); assertTrue(messages().contains("You are already a Warrior."));
        ClassPickGui.click(player, holder, 19); assertTrue(messages().contains("unlocks at level 2"));
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
        for (String command : List.of("/class", "/C list", "/mmocore:class")) {
            player.closeInventory(); var event = new PlayerCommandPreprocessEvent(player, command); listener.onCommand(event);
            assertTrue(event.isCancelled(), command); assertInstanceOf(ClassPickGui.Holder.class, player.getOpenInventory().getTopInventory().getHolder());
        }
        for (String command : List.of("/classes", "/", "/mmocore:skills")) {
            var event = new PlayerCommandPreprocessEvent(player, command); listener.onCommand(event); assertFalse(event.isCancelled(), command);
        }
        ClassPickService.configure(settings(false, false)); var disabled = new PlayerCommandPreprocessEvent(player, "/class");
        listener.onCommand(disabled); assertFalse(disabled.isCancelled());
    }

    static final class FakeWallet implements DenarWallet {
        final Map<Account, BigDecimal> balances = new EnumMap<>(Account.class);
        boolean available = true, failWithdraw, failDeposit;
        @Override public boolean available() { return available; }
        @Override public BigDecimal balance(UUID playerId, Account account) { return balances.getOrDefault(account, BigDecimal.ZERO); }
        @Override public boolean withdraw(UUID playerId, Account account, BigDecimal amount) {
            if (failWithdraw) return false;
            balances.put(account, balance(playerId, account).subtract(amount)); return true;
        }
        @Override public boolean deposit(UUID playerId, Account account, BigDecimal amount) {
            if (failDeposit) return false;
            balances.put(account, balance(playerId, account).add(amount)); return true;
        }
    }
}
