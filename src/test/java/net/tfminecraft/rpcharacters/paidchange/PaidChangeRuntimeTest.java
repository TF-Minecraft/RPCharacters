package net.tfminecraft.rpcharacters.paidchange;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.creation.CharacterCreation;
import net.tfminecraft.rpcharacters.creation.Stage;
import net.tfminecraft.rpcharacters.creation.StageEditLock;
import net.tfminecraft.rpcharacters.creation.SummaryEditSupport;
import net.tfminecraft.rpcharacters.creation.stages.SummaryStage;
import net.tfminecraft.rpcharacters.loaders.StageLoader;
import net.tfminecraft.rpcharacters.managers.CreationManager;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet.Account;

class PaidChangeRuntimeTest {
    private RuntimeTestState state;
    private final RecordingWallet wallet = new RecordingWallet();
    private Player player;
    private RPCharacter character;
    private CharacterCreation creation;
    private Stage stage;
    private PaidChangeRule rule;

    @BeforeEach void setup() {
        MockBukkit.mock();
        state = new RuntimeTestState(PaidChangeService.class, StageLoader.class, CreationManager.class);
        StageLoader.oList.clear(); CreationManager.activeCreators.clear();
        player = mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        character = new RPCharacter(player); character.setMMOClass("WARRIOR");
        creation = CharacterCreation.forEdit(player, character);
        stage = stage("class_stage", 0);
        rule = new PaidChangeRule("class", "CLASS_STAGE", "class", List.of(d("10"), d("25")));
        PaidChangeService.setWallet(wallet); PaidChangeService.configure(List.of(rule), null);
        wallet.balances.put(Account.POUCH, d("100")); wallet.balances.put(Account.BANK, d("200"));
    }
    @AfterEach void teardown() { state.close(); MockBukkit.unmock(); }

    @Test void paidOpeningHoldsExactPaymentAndUnchangedSettlementRefundsOnlyOnce() {
        assertTrue(PaidChangeService.payToOpen(player, creation, stage));
        PendingPaidChange held = character.getPendingPaidChange(); assertEquals(d("10"), held.amount()); assertEquals(Account.POUCH, held.account()); assertEquals(player.getUniqueId(), held.payerId());
        assertEquals(d("90"), wallet.balances.get(Account.POUCH)); verify(player).sendMessage(contains("Paid 10 denars from your pouch")); verify(player).sendMessage(contains("Leave without changing"));
        PaidChangeService.settle(creation); assertNull(character.getPendingPaidChange()); assertEquals(d("100"), wallet.balances.get(Account.POUCH)); assertEquals(1, wallet.deposits);
        verify(player).sendMessage(contains("10 denars went back to your pouch")); PaidChangeService.settle(creation); assertEquals(1, wallet.deposits);
    }
    @Test void changedSettlementCountsItAndChargesTheNextPriceOnReopen() {
        assertTrue(PaidChangeService.payToOpen(player, creation, stage)); character.setMMOClass("MAGE");
        assertTrue(PaidChangeService.payToOpen(player, creation, stage)); assertEquals(1, character.getPaidChangeCount("class_stage")); assertEquals(d("25"), character.getPendingPaidChange().amount()); assertEquals(d("65"), wallet.balances.get(Account.POUCH));
        verify(player).sendMessage(contains("The next one costs 25 denars")); clearInvocations(player);
        character.setMMOClass("ROGUE"); PaidChangeService.configure(List.of(), List.of()); PaidChangeService.settle(creation); verify(player).sendMessage(contains("Your class change is paid for.")); assertEquals(2, character.getPaidChangeCount("class_stage"));
    }
    @Test void failedRefundLeavesOriginalHoldAndRejectsAnotherPaymentUntilRetryWorks() {
        assertTrue(PaidChangeService.payToOpen(player, creation, stage)); PendingPaidChange held = character.getPendingPaidChange(); wallet.refundsFail = true;
        assertFalse(PaidChangeService.payToOpen(player, creation, stage)); assertSame(held, character.getPendingPaidChange()); assertEquals(1, wallet.withdrawals); assertEquals(d("90"), wallet.balances.get(Account.POUCH));
        verify(player).sendMessage(contains("refund couldn't go through")); verify(player).sendMessage(contains("last refund hasn't gone through")); wallet.refundsFail = false;
        assertTrue(PaidChangeService.recover(character).contains("went back")); assertNull(character.getPendingPaidChange()); assertEquals(d("100"), wallet.balances.get(Account.POUCH));
    }
    @Test void unavailableEconomyMissingRuleAndInsufficientFundsNeverCreateAHold() {
        assertFalse(PaidChangeService.payToOpen(player, creation, stage("other", 0))); verify(player).sendMessage(contains("can no longer be edited"));
        wallet.available = false; assertFalse(PaidChangeService.payToOpen(player, creation, stage)); verify(player).sendMessage(contains("can't pay to change your class right now"));
        wallet.available = true; wallet.balances.put(Account.POUCH, d("1")); wallet.balances.put(Account.BANK, d("2.5"));
        assertFalse(PaidChangeService.payToOpen(player, creation, stage)); verify(player).sendMessage(contains("costs 10 denars. You have 1 denar in your pouch and 2.50 denars in the bank")); assertNull(character.getPendingPaidChange()); assertEquals(0, wallet.withdrawals);
    }
    @Test void failedPouchWithdrawalFallsBackToBankAndConfiguredOrderIsRespected() {
        wallet.rejectPouch = true; assertTrue(PaidChangeService.payToOpen(player, creation, stage)); assertEquals(Account.BANK, character.getPendingPaidChange().account()); assertEquals(d("100"), wallet.balances.get(Account.POUCH)); assertEquals(d("190"), wallet.balances.get(Account.BANK));
        PaidChangeService.settle(creation); wallet.rejectPouch = false; PaidChangeService.configure(List.of(rule), List.of(Account.BANK)); assertTrue(PaidChangeService.payToOpen(player, creation, stage)); assertEquals(Account.BANK, character.getPendingPaidChange().account());
    }
    @Test void freeChangesNeedNoEconomyAndDoNotSendRefundMessages() {
        PaidChangeRule free = new PaidChangeRule("free", "CLASS_STAGE", " ", List.of()); assertEquals("free", free.getId()); assertEquals("free", free.getLabel()); assertEquals(List.of(), free.getCosts()); assertEquals(d("0"), free.costAfter(99));
        PaidChangeService.configure(List.of(free), List.of()); wallet.available = false; assertTrue(PaidChangeService.payToOpen(player, creation, stage)); assertNull(character.getPendingPaidChange().account());
        PaidChangeService.settle(creation); assertNull(character.getPendingPaidChange()); assertEquals(0, wallet.deposits); assertEquals(0, wallet.withdrawals); verify(player, never()).sendMessage(anyString());
        assertNull(PaidChangeService.recover(null)); PaidChangeService.settle(null); PaidChangeService.settle(CharacterCreation.forEdit(null, null));
        character.setPendingPaidChange(PaidChangeService.charge(player.getUniqueId(), free, character).pending()); assertNull(PaidChangeService.recover(character));
    }
    @Test void offlineSessionSettlementClearsHoldWithoutTryingToSendToMissingPlayer() {
        character.setPendingPaidChange(PaidChangeService.charge(player.getUniqueId(), rule, character).pending()); PaidChangeService.settle(CharacterCreation.forEdit(null, character)); assertNull(character.getPendingPaidChange()); assertEquals(1, wallet.deposits);
    }
    @Test void summariesRespectMissingStagesUnavailableEconomyAndFreeWindowBounds() {
        assertNull(PaidChangeService.ruleFor(null)); assertNull(PaidChangeService.ruleFor(new Stage())); assertNull(PaidChangeService.summaryLore(stage, null, true));
        wallet.available = false; assertNull(PaidChangeService.summaryLore(stage, character, true)); assertEquals(1, PaidChangeService.summaryLore(stage, character, false).size());
        assertFalse(StageEditLock.canEdit((Stage) null, character)); assertFalse(StageEditLock.canEdit(stage, null)); assertEquals(0, StageEditLock.lockRemainingMs(null, character)); assertEquals(0, StageEditLock.lockRemainingMs(stage, null)); assertNull(StageEditLock.lockLore(null, character));
        stage.setLockTimeMs(-1); assertTrue(StageEditLock.canEdit(null, stage, character)); assertEquals(0, StageEditLock.lockRemainingMs(stage, character)); assertNull(StageEditLock.lockLore(stage, character));
        stage.setLockTimeMs(0); assertFalse(StageEditLock.canEdit(player, stage, character)); assertTrue(StageEditLock.lockLore(stage, character).contains("Locked")); when(player.hasPermission(StageEditLock.BYPASS_PERMISSION)).thenReturn(true); assertTrue(StageEditLock.canEdit(player, stage, character));
        character.setCreatedAtEpochSeconds((int) Instant.now().getEpochSecond()); stage.setLockTimeMs(60_000); assertTrue(StageEditLock.lockLore(stage, character).contains("Will lock in")); assertTrue(StageEditLock.lockRemainingMs(stage, character) > 50_000);
        assertTrue(String.join(" ", PaidChangeService.summaryLore(stage, character, false)).contains("Then:"));
    }
    @Test void traitOrderingIsNotAChangeButRealTraitAndRaceChoicesAre() {
        YamlConfiguration config = new YamlConfiguration(); config.set("name", "Trait");
        Trait first = new Trait("alpha", config), second = new Trait("beta", config);
        character.setTraits(new ArrayList<>(Arrays.asList(first, null, second))); character.setRace(new Race("human", config));
        character.setPendingPaidChange(PaidChangeService.charge(player.getUniqueId(), rule, character).pending()); character.setTraits(new ArrayList<>(List.of(second, first)));
        assertEquals(PaidChangeService.Outcome.REFUNDED, PaidChangeService.resolve(character));
        character.setPendingPaidChange(PaidChangeService.charge(player.getUniqueId(), rule, character).pending()); character.setTraits(new ArrayList<>(List.of(first))); assertEquals(PaidChangeService.Outcome.KEPT, PaidChangeService.resolve(character));
        character.setPendingPaidChange(PaidChangeService.charge(player.getUniqueId(), rule, character).pending()); character.setRace(new Race("elf", config)); assertEquals(PaidChangeService.Outcome.KEPT, PaidChangeService.resolve(character));
    }
    @Test void maximumPaidCountKeepsTheFinalPriceInsteadOfWrappingBackToFirst() {
        character.setPaidChangeCount("class_stage", Integer.MAX_VALUE); assertTrue(PaidChangeService.payToOpen(player, creation, stage)); character.setMMOClass("MAGE"); PaidChangeService.settle(creation);
        assertEquals(Integer.MAX_VALUE, character.getPaidChangeCount("class_stage")); assertEquals(d("25"), rule.costAfter(character.getPaidChangeCount("class_stage")));
        assertTrue(String.join(" ", PaidChangeService.summaryLore(stage, character, true)).contains("change after costs §e25 denars"));
    }
    @Test void configSkipsUnknownAccountsBadRulesAndInvalidCostsWhileKeepingValidRules() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR")); YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("accounts", List.of("invalid", " bank ")); yaml.set("rules.scalar", "not a section"); yaml.createSection("rules.missing"); yaml.set("rules.blank.stage", " "); yaml.set("rules.noCosts.stage", "unused");
        yaml.set("rules.valid.stage", " CLASS_STAGE "); yaml.set("rules.valid.label", " "); yaml.set("rules.valid.costs", Arrays.asList("bad", -1, 2.345, null));
        PaidChangeConfig.load(yaml); PaidChangeRule loaded = PaidChangeService.ruleFor(stage); assertEquals("valid", loaded.getLabel()); assertEquals("valid", loaded.getId()); assertEquals(List.of(d("2.35")), loaded.getCosts());
        assertTrue(PaidChangeService.payToOpen(player, creation, stage)); assertEquals(Account.BANK, character.getPendingPaidChange().account());
        PaidChangeConfig.load(null); assertNull(PaidChangeService.ruleFor(stage)); PaidChangeConfig.load(new YamlConfiguration()); assertNull(PaidChangeService.ruleFor(stage));
    }
    @Test void configRejectsYamlNonFinitePricesWithoutAbortingOtherRules() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration(); yaml.loadFromString("rules:\n  valid:\n    stage: class_stage\n    costs: [.nan, .inf, -.inf, 10]\n");
        assertDoesNotThrow(() -> PaidChangeConfig.load(yaml)); assertEquals(List.of(d("10")), PaidChangeService.ruleFor(stage).getCosts());
    }
    @Test void economyDisableSettlesAllSessionsFromASnapshotAndIgnoresOtherPlugins() {
        assertTrue(PaidChangeService.payToOpen(player, creation, stage)); Player other = mock(Player.class); when(other.getUniqueId()).thenReturn(UUID.randomUUID()); RPCharacter second = new RPCharacter(other); CharacterCreation next = CharacterCreation.forEdit(other, second); assertTrue(PaidChangeService.payToOpen(other, next, stage));
        CreationManager.activeCreators.put(player, creation); CreationManager.activeCreators.put(other, next); Plugin unrelated = mock(Plugin.class); when(unrelated.getName()).thenReturn("Other"); PaidChangeListener listener = new PaidChangeListener(); listener.onPluginDisable(new PluginDisableEvent(unrelated)); assertNotNull(character.getPendingPaidChange());
        wallet.afterDeposit = CreationManager.activeCreators::clear; Plugin economy = mock(Plugin.class); when(economy.getName()).thenReturn("DenarEconomy"); listener.onPluginDisable(new PluginDisableEvent(economy)); assertNull(character.getPendingPaidChange()); assertNull(second.getPendingPaidChange()); assertEquals(2, wallet.deposits); PaidChangeListener.settleAll();
    }
    @Test void summaryEntriesUseConfiguredOrderCaseInsensitiveKeysAndIndependentCopies() {
        assertNull(SummaryEditSupport.getSummaryStage()); assertEquals(List.of(), SummaryEditSupport.getEditEntryKeys()); assertNull(SummaryEditSupport.resolveStageId(null)); assertNull(SummaryEditSupport.resolveStageId(" ")); assertNull(SummaryEditSupport.resolveStageForEntry("unknown"));
        Stage wrong = stage("creation_summary_stage", -1); StageLoader.oList.add(wrong); assertNull(SummaryEditSupport.getSummaryStage()); StageLoader.oList.clear();
        YamlConfiguration config = new YamlConfiguration(); config.set("entries.Class", "class_stage"); config.set("entries.Clues", "clues"); config.set("entries.Missing", "missing"); SummaryStage summary = new SummaryStage(wrong, config); StageLoader.oList.add(summary); StageLoader.oList.add(stage);
        assertSame(summary, SummaryEditSupport.getSummaryStage()); assertEquals(List.of("Class", "Clues", "Missing"), SummaryEditSupport.getEditEntryKeys()); assertEquals("class_stage", SummaryEditSupport.resolveStageId("Class")); assertEquals("class_stage", SummaryEditSupport.resolveStageId("CLASS")); assertSame(stage, SummaryEditSupport.resolveStageForEntry("class")); assertNull(SummaryEditSupport.resolveStageForEntry("Clues")); assertNull(SummaryEditSupport.resolveStageForEntry("Missing")); assertNull(SummaryEditSupport.resolveStageId("no match"));
        SummaryEditSupport.getEditEntries().clear(); assertEquals(3, summary.getEntries().size());
    }
    private static Stage stage(String id, long lockMs) { Stage s = new Stage(); s.setId(id); s.setLockTimeMs(lockMs); return s; }
    private static BigDecimal d(String value) { return new BigDecimal(value).setScale(2); }
    private static final class RecordingWallet implements DenarWallet {
        final EnumMap<Account, BigDecimal> balances = new EnumMap<>(Account.class);
        boolean available = true, refundsFail, rejectPouch;
        int withdrawals, deposits;
        Runnable afterDeposit = () -> {};
        public boolean available() { return available; }
        public BigDecimal balance(UUID payer, Account account) { return balances.getOrDefault(account, d("0")); }
        public boolean withdraw(UUID payer, Account account, BigDecimal amount) { if ((account == Account.POUCH && rejectPouch) || balance(payer, account).compareTo(amount) < 0) return false; withdrawals++; balances.put(account, balance(payer, account).subtract(amount)); return true; }
        public boolean deposit(UUID payer, Account account, BigDecimal amount) { if (refundsFail) return false; deposits++; balances.put(account, balance(payer, account).add(amount)); afterDeposit.run(); return true; }
    }
}
