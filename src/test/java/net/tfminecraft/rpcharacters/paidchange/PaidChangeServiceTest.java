package net.tfminecraft.rpcharacters.paidchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.configuration.file.YamlConfiguration;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.creation.Stage;
import net.tfminecraft.rpcharacters.creation.StageEditLock;
import net.tfminecraft.rpcharacters.creation.StageRevisions;
import net.tfminecraft.rpcharacters.database.CharacterStageChangeFields;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet.Account;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet.Posting;
import net.tfminecraft.rpcharacters.paidchange.PaidChangeService.ChargeResult;
import net.tfminecraft.rpcharacters.paidchange.PaidChangeService.ChargeStatus;
import net.tfminecraft.tlibs.character.CharacterRef;

class PaidChangeServiceTest {

	private static final long DAY_MS = 86_400_000L;
	private static final UUID PLAYER = UUID.randomUUID();
	private static final String CLASS_STAGE = "class_selection_stage";
	private static final PaidChangeRule CLASS_RULE =
			new PaidChangeRule("class", "class_selection_stage", "class", List.of(d("100"), d("1000"), d("3000")));

	private final FakeLedger wallet = new FakeLedger();
	private final List<String> saves = new ArrayList<>();
	private RuntimeTestState state;

	@BeforeEach
	void setUp() {
		state = new RuntimeTestState(PaidChangeService.class, Payments.class);
		PaidChangeService.setWallet(wallet);
		PaidChangeService.configure(List.of(CLASS_RULE), List.of(Account.POUCH, Account.BANK));
		PaymentsFixture.saves((owner, character) -> saves.add(owner + "/" + character.getId()));
	}

	@AfterEach
	void tearDown() {
		PaidChangeService.configure(List.of(), List.of());
		state.close();
	}

	@Test
	void pricesRiseThenRepeatTheLastCost() {
		assertEquals(d("100"), CLASS_RULE.costAfter(0));
		assertEquals(d("1000"), CLASS_RULE.costAfter(1));
		assertEquals(d("3000"), CLASS_RULE.costAfter(2));
		assertEquals(d("3000"), CLASS_RULE.costAfter(7));
	}

	@Test
	void chargesThePouchFirstThenTheBank() {
		wallet.set(Account.POUCH, 15_000);
		wallet.set(Account.BANK, 500_000);
		RPCharacter character = characterWithClass("WARRIOR");

		ChargeResult first = PaidChangeService.charge(PLAYER, CLASS_RULE, character);
		assertEquals(ChargeStatus.PAID, first.status());
		assertEquals(Account.POUCH, first.pending().account());
		assertEquals(5_000, wallet.get(Account.POUCH));
		assertEquals(new CharacterRef(PLAYER, UUID.fromString(character.getId())), wallet.applied.get(0).character());
		assertEquals(DenarWallet.PAID_CHANGE, wallet.applied.get(0).reason());

		character.setPaidChangeCount(CLASS_STAGE, 1);
		ChargeResult second = PaidChangeService.charge(PLAYER, CLASS_RULE, character);
		assertEquals(Account.BANK, second.pending().account());
		assertEquals(100_000, second.pending().cents());
		assertEquals(400_000, wallet.get(Account.BANK));
	}

	@Test
	void refusesWhenNoAccountCoversTheCost() {
		wallet.set(Account.POUCH, 6_000);
		wallet.set(Account.BANK, 6_000);

		ChargeResult result = PaidChangeService.charge(PLAYER, CLASS_RULE, characterWithClass("WARRIOR"));
		assertEquals(ChargeStatus.INSUFFICIENT_FUNDS, result.status());
		assertEquals(d("100"), result.cost());
		assertEquals(6_000, wallet.get(Account.POUCH));
		assertEquals(6_000, wallet.get(Account.BANK));
		assertTrue(saves.isEmpty());
	}

	@Test
	void unavailableWithoutDenarEconomy() {
		wallet.available = false;
		wallet.set(Account.POUCH, 50_000);
		assertFalse(PaidChangeService.canPayToOpen(stage("class_selection_stage", 5 * DAY_MS)));
		assertEquals(ChargeStatus.UNAVAILABLE,
				PaidChangeService.charge(PLAYER, CLASS_RULE, characterWithClass("WARRIOR")).status());
	}

	@Test
	void backingOutWithoutAChangeRefunds() {
		wallet.set(Account.POUCH, 10_000);
		RPCharacter character = characterWithClass("WARRIOR");
		ChargeResult result = PaidChangeService.charge(PLAYER, CLASS_RULE, character);
		assertEquals(0, wallet.get(Account.POUCH));
		character.setPendingPaidChange(result.pending());

		assertEquals(PaidChangeService.Outcome.REFUNDED, PaidChangeService.resolve(character));
		assertEquals(10_000, wallet.get(Account.POUCH));
		assertEquals(result.pending().payment().refundOpId(), wallet.applied.get(1).opId());
		assertEquals(DenarWallet.REFUND, wallet.applied.get(1).reason());
		assertEquals(0, character.getPaidChangeCount(CLASS_STAGE));
		assertNull(character.getPendingPaidChange());
	}

	@Test
	void aFailedRefundStaysHeldAndIsRetried() {
		wallet.set(Account.POUCH, 10_000);
		RPCharacter character = characterWithClass("WARRIOR");
		character.setPendingPaidChange(PaidChangeService.charge(PLAYER, CLASS_RULE, character).pending());

		wallet.depositsDown = true;
		assertEquals(PaidChangeService.Outcome.REFUND_FAILED, PaidChangeService.resolve(character));
		assertEquals(0, wallet.get(Account.POUCH));
		assertTrue(character.getPendingPaidChange() != null);

		wallet.depositsDown = false;
		String message = PaidChangeService.recover(character);
		assertTrue(message.contains("100 denars went back to your pouch"), message);
		assertEquals(10_000, wallet.get(Account.POUCH));
		assertNull(character.getPendingPaidChange());
		assertNull(PaidChangeService.recover(character));
	}

	@Test
	void aSavedHoldSurvivesACrashAndSettlesOnRecovery() throws Exception {
		wallet.set(Account.BANK, 100_000);
		RPCharacter character = characterWithClass("WARRIOR");
		character.setPaidChangeCount(CLASS_STAGE, 1);
		character.setPendingPaidChange(PaidChangeService.charge(PLAYER, CLASS_RULE, character).pending());
		character.setMMOClass("mage");

		RPCharacter loaded = reload(character, "MAGE");
		PendingPaidChange held = loaded.getPendingPaidChange();
		assertEquals(character.getPendingPaidChange(), held);
		assertEquals(Account.BANK, held.account());
		assertEquals(100_000, held.cents());
		assertEquals(new CharacterRef(PLAYER, UUID.fromString(character.getId())), held.payment().payer());

		String message = PaidChangeService.recover(loaded);
		assertTrue(message.contains("class change is paid for. The next one costs 3,000 denars"), message);
		assertEquals(2, loaded.getPaidChangeCount(CLASS_STAGE));
		assertEquals(0, wallet.get(Account.BANK));
		assertNull(loaded.getPendingPaidChange());
	}

	@Test
	void keepingTheChangeKeepsThePaymentAndRaisesTheNextPrice() {
		wallet.set(Account.BANK, 10_000);
		RPCharacter character = characterWithClass("WARRIOR");
		character.setPendingPaidChange(PaidChangeService.charge(PLAYER, CLASS_RULE, character).pending());

		character.setMMOClass("mage");
		assertEquals(PaidChangeService.Outcome.KEPT, PaidChangeService.resolve(character));
		assertEquals(0, wallet.get(Account.BANK));
		assertEquals(1, character.getPaidChangeCount(CLASS_STAGE));
		assertEquals(d("1000"), CLASS_RULE.costAfter(character.getPaidChangeCount(CLASS_STAGE)));
	}

	@Test
	void loreShowsTheFreeWindowThenTheNextTwoPrices() {
		RPCharacter character = characterWithClass("WARRIOR");
		character.setCreatedAtEpochSeconds((int) Instant.now().getEpochSecond() - 3600);

		List<String> lore = PaidChangeService.summaryLore(stage("class_selection_stage", 5 * DAY_MS), character, false);
		String joined = String.join("\n", lore);
		assertTrue(joined.contains("Free to change for: "), joined);
		assertTrue(joined.contains("4d 23h"), joined);
		assertTrue(joined.contains("Then: §e100 denars"), joined);
		assertTrue(joined.contains("next: §e1,000 denars"), joined);
	}

	@Test
	void lockedLoreShowsThePriceNowAndTheOneAfter() {
		RPCharacter character = characterWithClass("WARRIOR");
		character.setCreatedAtEpochSeconds((int) (Instant.now().getEpochSecond() - 6 * 86_400L));
		Stage stage = stage("class_selection_stage", 5 * DAY_MS);

		String first = String.join("\n", PaidChangeService.summaryLore(stage, character, true));
		assertTrue(first.contains("Click to change for 100 denars"), first);
		assertTrue(first.contains("change after costs §e1,000 denars"), first);

		character.setPaidChangeCount(CLASS_STAGE, 1);
		String second = String.join("\n", PaidChangeService.summaryLore(stage, character, true));
		assertTrue(second.contains("Click to change for 1,000 denars"), second);
		assertTrue(second.contains("change after costs §e3,000 denars"), second);

		character.setPaidChangeCount(CLASS_STAGE, 2);
		String third = String.join("\n", PaidChangeService.summaryLore(stage, character, true));
		assertTrue(third.contains("Click to change for 3,000 denars"), third);
		assertTrue(third.contains("change after costs §e3,000 denars"), third);
	}

	@Test
	void stagesWithoutARuleKeepTheirDefaultLore() {
		assertNull(PaidChangeService.summaryLore(stage("race_selection_stage", DAY_MS), characterWithClass("WARRIOR"), true));
		assertFalse(PaidChangeService.canPayToOpen(stage("race_selection_stage", DAY_MS)));
		assertTrue(PaidChangeService.canPayToOpen(stage("class_selection_stage", 5 * DAY_MS)));
	}

	@Test
	void configReadsRulesAndAccounts() {
		YamlConfiguration yaml = new YamlConfiguration();
		yaml.set("paid-changes.accounts", List.of("bank"));
		yaml.set("paid-changes.rules.race.stage", "race_selection_stage");
		yaml.set("paid-changes.rules.race.costs", List.of(50, 75));
		PaidChangeConfig.load(yaml.getConfigurationSection("paid-changes"));

		assertFalse(PaidChangeService.canPayToOpen(stage("class_selection_stage", 5 * DAY_MS)));
		PaidChangeRule race = PaidChangeService.ruleFor(stage("race_selection_stage", DAY_MS));
		assertEquals("race", race.getLabel());
		assertEquals(d("75"), race.costAfter(3));

		wallet.set(Account.POUCH, 100_000);
		wallet.set(Account.BANK, 5_000);
		ChargeResult result = PaidChangeService.charge(PLAYER, race, characterWithClass("WARRIOR"));
		assertEquals(Account.BANK, result.pending().account());
	}

	@Test
	void raisingTheRevisionReopensTheWindowAndResetsPrices() {
		long now = Instant.now().getEpochSecond();
		RPCharacter character = characterWithClass("WARRIOR");
		character.setCreatedAtEpochSeconds((int) (now - 30 * 86_400L));
		character.setPaidChangeCount(CLASS_STAGE, 2);
		Stage stage = stage(CLASS_STAGE, 5 * DAY_MS);

		assertFalse(StageRevisions.refresh(character, List.of(stage), now));
		assertFalse(StageEditLock.canEdit(stage, character));

		stage.setRevision(1);
		assertTrue(StageRevisions.refresh(character, List.of(stage), now - 3600));
		assertTrue(StageEditLock.canEdit(stage, character));
		assertEquals(0, character.getPaidChangeCount(CLASS_STAGE));
		String lore = String.join("\n", PaidChangeService.summaryLore(stage, character, false));
		assertTrue(lore.contains("4d 23h") && lore.contains("Then: §e100 denars"), lore);

		// Same revision on the next load keeps the window where it started.
		assertFalse(StageRevisions.refresh(character, List.of(stage), now + 10 * 86_400L));
		assertEquals(now - 3600, character.getStageRevisionSince(CLASS_STAGE));
	}

	@Test
	void newCharactersStartOnTheCurrentRevision() {
		long now = Instant.now().getEpochSecond();
		Stage stage = stage(CLASS_STAGE, 5 * DAY_MS);
		stage.setRevision(3);
		RPCharacter character = characterWithClass("WARRIOR");
		character.setCreatedAtEpochSeconds((int) (now - 6 * 86_400L));

		StageRevisions.stampCurrent(character, List.of(stage));
		assertFalse(StageRevisions.refresh(character, List.of(stage), now));
		assertFalse(StageEditLock.canEdit(stage, character));
	}

	@Test
	void stageChangeStateSurvivesASaveAndLoad() throws Exception {
		RPCharacter character = new RPCharacter(null);
		character.setPaidChangeCount(CLASS_STAGE, 2);
		character.setStageRevision(CLASS_STAGE, 4, 1_790_000_000L);
		character.setStageRevision("race_selection_stage", 1, 0L);
		HashMap<String, Object> saved = new HashMap<>();
		CharacterStageChangeFields.save(saved, character);
		JSONObject reparsed = (JSONObject) new JSONParser().parse(new JSONObject(saved).toJSONString());

		RPCharacter loaded = new RPCharacter(null);
		CharacterStageChangeFields.load(loaded, reparsed);
		assertEquals(2, loaded.getPaidChangeCount(CLASS_STAGE));
		assertEquals(4, loaded.getStageRevision(CLASS_STAGE));
		assertEquals(1_790_000_000L, loaded.getStageRevisionSince(CLASS_STAGE));
		assertEquals(1, loaded.getStageRevision("race_selection_stage"));
		assertEquals(0L, loaded.getStageRevisionSince("race_selection_stage"));

		HashMap<String, Object> clean = new HashMap<>();
		CharacterStageChangeFields.save(clean, new RPCharacter(null));
		assertTrue(clean.isEmpty());
	}

	@Test
	void denarsFormatWithSeparators() {
		assertEquals("100 denars", PaidChangeService.formatDenars(d("100")));
		assertEquals("3,000 denars", PaidChangeService.formatDenars(d("3000")));
		assertEquals("1 denar", PaidChangeService.formatDenars(d("1")));
		assertEquals("2.50 denars", PaidChangeService.formatDenars(d("2.5")));
	}

	private static BigDecimal d(String value) {
		return new BigDecimal(value).setScale(2);
	}

	@Test
	void fractionalPricesStayExact() {
		PaidChangeRule cheap = new PaidChangeRule("tip", CLASS_STAGE, "class", List.of(d("0.1")));
		wallet.set(Account.POUCH, 30);
		RPCharacter character = characterWithClass("WARRIOR");
		for (int i = 0; i < 3; i++) {
			character.setPendingPaidChange(null);
			assertEquals(ChargeStatus.PAID, PaidChangeService.charge(PLAYER, cheap, character).status());
		}
		assertEquals(0, wallet.get(Account.POUCH));
		assertEquals(10, wallet.applied.get(2).cents());
	}

	private static RPCharacter characterWithClass(String classId) {
		RPCharacter character = new RPCharacter(null);
		character.setMMOClass(classId);
		return character;
	}

	private static Stage stage(String id, long lockMs) {
		Stage stage = new Stage();
		stage.setId(id);
		stage.setLockTimeMs(lockMs);
		return stage;
	}
	@Test
	void theIntentIsSavedBeforeTheDebitIsPosted() {
		wallet.set(Account.POUCH, 10_000);
		RPCharacter character = characterWithClass("WARRIOR");
		List<String> seenAtSave = new ArrayList<>();
		PaymentsFixture.saves((owner, saved) -> {
			Payment payment = saved.getPendingPaidChange().payment();
			seenAtSave.add(payment.debitOpId() + " posted=" + wallet.stored.containsKey(payment.debitOpId()));
			return true;
		});

		PendingPaidChange pending = PaidChangeService.charge(PLAYER, CLASS_RULE, character).pending();
		Payment payment = pending.payment();
		String prefix = "paid:" + character.getId() + ":" + CLASS_STAGE + ":";
		assertTrue(payment.debitOpId().startsWith(prefix), payment.debitOpId());
		assertEquals("refund:" + payment.debitOpId().substring("paid:".length()), payment.refundOpId());
		assertEquals(List.of(payment.debitOpId() + " posted=false"), seenAtSave);
		assertEquals(Posting.APPLIED, wallet.stored.get(payment.debitOpId()));
		assertSame(pending, character.getPendingPaidChange());
	}

	@Test
	void anUnsavableIntentTakesNothing() {
		wallet.set(Account.POUCH, 10_000);
		RPCharacter character = characterWithClass("WARRIOR");
		PendingPaidChange earlier = new PendingPaidChange(CLASS_STAGE, "class", null, "x");
		character.setPendingPaidChange(earlier);
		PaymentsFixture.saves((owner, saved) -> false);

		assertEquals(ChargeStatus.UNAVAILABLE, PaidChangeService.charge(PLAYER, CLASS_RULE, character).status());
		assertTrue(wallet.attempts.isEmpty());
		assertSame(earlier, character.getPendingPaidChange());
	}

	@Test
	void aCrashAfterTheDebitRefundsOnceOnRecovery() throws Exception {
		wallet.set(Account.POUCH, 10_000);
		RPCharacter character = characterWithClass("WARRIOR");
		PaidChangeService.charge(PLAYER, CLASS_RULE, character);
		RPCharacter afterCrash = reload(character, "WARRIOR");
		RPCharacter sameFileAgain = reload(character, "WARRIOR");

		String message = PaidChangeService.recover(afterCrash);
		assertTrue(message.contains("100 denars went back to your pouch"), message);
		assertEquals(10_000, wallet.get(Account.POUCH));
		assertNull(afterCrash.getPendingPaidChange());

		// Crash again before the settled character was saved: the same refund ID changes nothing.
		assertTrue(PaidChangeService.recover(sameFileAgain).contains("went back"));
		assertEquals(10_000, wallet.get(Account.POUCH));
		assertEquals(1, wallet.count(false));
		assertEquals(2, wallet.attempts.stream().filter(call -> !call.debit()).count());
	}

	@Test
	void aRefusedOrMissingDebitIsDroppedWithoutARefund() throws Exception {
		wallet.set(Account.POUCH, 10_000);
		wallet.set(Account.BANK, 10_000);
		wallet.rejectPouch = true;
		RPCharacter character = characterWithClass("WARRIOR");
		List<JSONObject> onDisk = new ArrayList<>();
		PaymentsFixture.saves((owner, saved) -> onDisk.add(serialise(saved)));

		ChargeResult paid = PaidChangeService.charge(PLAYER, CLASS_RULE, character);
		assertEquals(Account.BANK, paid.pending().account());
		assertEquals(3, onDisk.size(), "pouch intent, its drop, then the bank intent");
		assertFalse(onDisk.get(1).containsKey("paid-change-pending"));

		RPCharacter refusedOnDisk = characterWithClass("WARRIOR");
		CharacterStageChangeFields.load(refusedOnDisk, onDisk.get(0));
		assertEquals(Posting.REFUSED, wallet.stored.get(refusedOnDisk.getPendingPaidChange().payment().debitOpId()));
		assertEquals(PaidChangeService.Outcome.DISCARDED, PaidChangeService.resolve(refusedOnDisk));
		assertNull(refusedOnDisk.getPendingPaidChange());

		// Saved, then the server stopped before the debit was posted.
		RPCharacter neverPosted = characterWithClass("WARRIOR");
		CharacterStageChangeFields.load(neverPosted, onDisk.get(0));
		wallet.stored.clear();
		assertNull(PaidChangeService.recover(neverPosted));
		assertNull(neverPosted.getPendingPaidChange());
		assertEquals(0, wallet.count(false));
		assertEquals(0, neverPosted.getPaidChangeCount(CLASS_STAGE));
	}

	@Test
	void aLedgerThatGoesDownMidDebitKeepsTheHoldUntilItCanSay() {
		wallet.set(Account.POUCH, 10_000);
		RPCharacter character = characterWithClass("WARRIOR");
		wallet.postingsDown = true;
		assertEquals(ChargeStatus.UNAVAILABLE, PaidChangeService.charge(PLAYER, CLASS_RULE, character).status());
		assertNull(character.getPendingPaidChange(), "a debit the ledger never recorded is dropped");

		wallet.postingsDown = false;
		wallet.loseReplies = true;
		wallet.statusDown = true;
		assertEquals(ChargeStatus.UNAVAILABLE, PaidChangeService.charge(PLAYER, CLASS_RULE, character).status());
		assertEquals(0, wallet.get(Account.POUCH));
		assertTrue(character.getPendingPaidChange() != null);
		assertTrue(PaidChangeService.recover(character).contains("payment for a class change couldn't be checked yet"));
		assertTrue(character.getPendingPaidChange() != null);

		wallet.loseReplies = false;
		wallet.statusDown = false;
		assertTrue(PaidChangeService.recover(character).contains("went back to your pouch"));
		assertEquals(10_000, wallet.get(Account.POUCH));
	}

	@Test
	void aLostReplyWhoseDebitAppliedStillCountsAsPaid() {
		wallet.set(Account.POUCH, 10_000);
		wallet.loseReplies = true;
		RPCharacter character = characterWithClass("WARRIOR");
		assertEquals(ChargeStatus.PAID, PaidChangeService.charge(PLAYER, CLASS_RULE, character).status());
		assertEquals(0, wallet.get(Account.POUCH));
	}

	@Test
	void aConflictingRefundStaysHeldAndIsLogged() {
		wallet.set(Account.POUCH, 10_000);
		RPCharacter character = characterWithClass("WARRIOR");
		Payment payment = PaidChangeService.charge(PLAYER, CLASS_RULE, character).pending().payment();
		wallet.deposit(payment.refundOpId(), "someone else", payment.payer(), Account.BANK, 1);

		assertEquals(PaidChangeService.Outcome.REFUND_FAILED, PaidChangeService.resolve(character));
		assertEquals(0, wallet.get(Account.POUCH));
		assertTrue(character.getPendingPaidChange() != null);
	}

	@Test
	void legacyHoldsRefundTheAccountsHeirOnceHeirsAreReady() throws Exception {
		UUID account = UUID.randomUUID();
		CharacterRef heir = new CharacterRef(account, UUID.randomUUID());
		boolean[] ready = {false};
		Map<UUID, CharacterRef> heirs = new HashMap<>();
		PaymentsFixture.heirs(() -> ready[0], owner -> Optional.ofNullable(heirs.get(owner)));
		RPCharacter character = characterWithClass("WARRIOR");
		JSONObject old = new JSONObject();
		old.putAll(Map.of("stage", CLASS_STAGE, "label", "class", "payer", account.toString(), "account", "BANK",
				"amount", "100.00", "before", PaidChangeService.snapshot(character)));
		CharacterStageChangeFields.load(character, json(Map.of("paid-change-pending", old)));

		Payment legacy = character.getPendingPaidChange().payment();
		assertTrue(legacy.isLegacy());
		assertEquals("refund-legacy:" + character.getId() + ":" + CLASS_STAGE, legacy.refundOpId());
		assertEquals(10_000, legacy.cents());
		assertEquals(legacy, reload(character, "WARRIOR").getPendingPaidChange().payment());

		assertTrue(PaidChangeService.recover(character).contains("refund couldn't go through yet"));
		ready[0] = true;
		assertEquals(PaidChangeService.Outcome.REFUND_FAILED, PaidChangeService.resolve(character), "no heir yet");
		heirs.put(account, heir);
		RPCharacter stale = reload(character, "WARRIOR");
		assertTrue(PaidChangeService.recover(character).contains("100 denars payment for a class change went back to your bank"));
		assertNull(character.getPendingPaidChange());
		assertEquals(new FakeLedger.Call(legacy.refundOpId(), DenarWallet.REFUND, heir, Account.BANK, 10_000, false),
				wallet.applied.get(0));
		PaidChangeService.recover(stale);
		assertEquals(1, wallet.count(false));
		assertEquals(10_000, wallet.get(Account.BANK));

	}

	@Test
	void aLegacyHoldWhoseCharacterChangedStillWaitsForTheHeirAndRefundsOnce() {
		UUID account = UUID.randomUUID();
		CharacterRef heir = new CharacterRef(account, UUID.randomUUID());
		boolean[] ready = {false};
		PaymentsFixture.heirs(() -> ready[0], owner -> Optional.of(heir));
		RPCharacter character = characterWithClass("WARRIOR");
		JSONObject old = new JSONObject();
		old.putAll(Map.of("stage", CLASS_STAGE, "label", "class", "payer", account.toString(), "account", "POUCH",
				"amount", "100", "before", PaidChangeService.snapshot(character)));
		CharacterStageChangeFields.load(character, json(Map.of("paid-change-pending", old)));
		character.setMMOClass("MAGE");

		assertEquals(PaidChangeService.Outcome.REFUND_FAILED, PaidChangeService.resolve(character));
		assertTrue(character.getPendingPaidChange() != null, "a legacy hold is never cleared as kept");
		assertEquals(0, character.getPaidChangeCount(CLASS_STAGE));
		assertTrue(wallet.attempts.isEmpty());

		ready[0] = true;
		String message = PaidChangeService.recover(character);
		assertTrue(message.contains("earlier 100 denars payment for a class change went back to your pouch"), message);
		assertNull(character.getPendingPaidChange());
		assertEquals(0, character.getPaidChangeCount(CLASS_STAGE));
		assertEquals(List.of(new FakeLedger.Call("refund-legacy:" + character.getId() + ":" + CLASS_STAGE,
				DenarWallet.REFUND, heir, Account.POUCH, 10_000, false)), wallet.applied);
	}

	@Test
	void legacyFreeHoldsAndUnreadableEntriesLoadSafely() {
		RPCharacter free = characterWithClass("WARRIOR");
		CharacterStageChangeFields.load(free, json(Map.of("paid-change-pending", Map.of("stage", CLASS_STAGE,
				"label", "class", "payer", PLAYER.toString(), "amount", "0", "before", "x"))));
		assertNull(free.getPendingPaidChange().payment());
		CharacterStageChangeFields.load(free, json(Map.of("paid-change-pending", Map.of("stage", CLASS_STAGE,
				"label", "class", "before", "x"))));
		assertNull(free.getPendingPaidChange().payment());

		RPCharacter broken = characterWithClass("WARRIOR");
		CharacterStageChangeFields.load(broken, json(Map.of("paid-change-pending", Map.of("stage", CLASS_STAGE,
				"before", "x", "payment", Map.of("account", "POUCH", "cents", 5L)))));
		assertNull(broken.getPendingPaidChange());
		CharacterStageChangeFields.load(broken, json(Map.of("paid-change-pending", Map.of("stage", CLASS_STAGE,
				"before", "x", "payment", Map.of("account", "POUCH", "cents", 5L, "refund-op", " ")))));
		assertNull(broken.getPendingPaidChange());
	}

	@Test
	void pricesConvertToWholeCents() {
		assertEquals(1_235, Payments.cents(new BigDecimal("12.345")));
		assertEquals(new BigDecimal("12.34"), Payments.denars(1_234));
		assertEquals("12.34 denars", PaidChangeService.formatCents(1_234));
	}

	private static RPCharacter reload(RPCharacter character, String classId) throws Exception {
		RPCharacter loaded = characterWithClass(classId);
		loaded.setId(character.getId());
		CharacterStageChangeFields.load(loaded, serialise(character));
		return loaded;
	}

	private static JSONObject serialise(RPCharacter character) {
		HashMap<String, Object> saved = new HashMap<>();
		CharacterStageChangeFields.save(saved, character);
		try {
			return (JSONObject) new JSONParser().parse(new JSONObject(saved).toJSONString());
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	@SuppressWarnings("unchecked")
	private static JSONObject json(Map<String, ?> values) {
		JSONObject json = new JSONObject();
		json.putAll(values);
		return json;
	}
}
