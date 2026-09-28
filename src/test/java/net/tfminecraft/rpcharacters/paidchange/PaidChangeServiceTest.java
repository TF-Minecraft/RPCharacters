package net.tfminecraft.rpcharacters.paidchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.configuration.file.YamlConfiguration;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.rpcharacters.creation.Stage;
import net.tfminecraft.rpcharacters.creation.StageEditLock;
import net.tfminecraft.rpcharacters.creation.StageRevisions;
import net.tfminecraft.rpcharacters.database.CharacterStageChangeFields;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet.Account;
import net.tfminecraft.rpcharacters.paidchange.PaidChangeService.ChargeResult;
import net.tfminecraft.rpcharacters.paidchange.PaidChangeService.ChargeStatus;

class PaidChangeServiceTest {

	private static final long DAY_MS = 86_400_000L;
	private static final UUID PLAYER = UUID.randomUUID();
	private static final String CLASS_STAGE = "class_selection_stage";
	private static final PaidChangeRule CLASS_RULE =
			new PaidChangeRule("class", "class_selection_stage", "class", List.of(d("100"), d("1000"), d("3000")));

	private final FakeWallet wallet = new FakeWallet();

	@BeforeEach
	void setUp() {
		PaidChangeService.setWallet(wallet);
		PaidChangeService.configure(List.of(CLASS_RULE), List.of(Account.POUCH, Account.BANK));
	}

	@AfterEach
	void tearDown() {
		PaidChangeService.configure(List.of(), List.of());
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
		wallet.set(Account.POUCH, d("150"));
		wallet.set(Account.BANK, d("5000"));
		RPCharacter character = characterWithClass("WARRIOR");

		ChargeResult first = PaidChangeService.charge(PLAYER, CLASS_RULE, character);
		assertEquals(ChargeStatus.PAID, first.status());
		assertEquals(Account.POUCH, first.pending().account());
		assertEquals(d("50"), wallet.get(Account.POUCH));

		character.setPaidChangeCount(CLASS_STAGE, 1);
		ChargeResult second = PaidChangeService.charge(PLAYER, CLASS_RULE, character);
		assertEquals(Account.BANK, second.pending().account());
		assertEquals(d("1000"), second.pending().amount());
		assertEquals(d("4000"), wallet.get(Account.BANK));
	}

	@Test
	void refusesWhenNoAccountCoversTheCost() {
		wallet.set(Account.POUCH, d("60"));
		wallet.set(Account.BANK, d("60"));

		ChargeResult result = PaidChangeService.charge(PLAYER, CLASS_RULE, characterWithClass("WARRIOR"));
		assertEquals(ChargeStatus.INSUFFICIENT_FUNDS, result.status());
		assertEquals(d("100"), result.cost());
		assertEquals(d("60"), wallet.get(Account.POUCH));
		assertEquals(d("60"), wallet.get(Account.BANK));
	}

	@Test
	void unavailableWithoutDenarEconomy() {
		wallet.available = false;
		wallet.set(Account.POUCH, d("500"));
		assertFalse(PaidChangeService.canPayToOpen(stage("class_selection_stage", 5 * DAY_MS)));
		assertEquals(ChargeStatus.UNAVAILABLE,
				PaidChangeService.charge(PLAYER, CLASS_RULE, characterWithClass("WARRIOR")).status());
	}

	@Test
	void backingOutWithoutAChangeRefunds() {
		wallet.set(Account.POUCH, d("100"));
		RPCharacter character = characterWithClass("WARRIOR");
		ChargeResult result = PaidChangeService.charge(PLAYER, CLASS_RULE, character);
		assertEquals(d("0"), wallet.get(Account.POUCH));
		character.setPendingPaidChange(result.pending());

		assertEquals(PaidChangeService.Outcome.REFUNDED, PaidChangeService.resolve(character));
		assertEquals(d("100"), wallet.get(Account.POUCH));
		assertEquals(0, character.getPaidChangeCount(CLASS_STAGE));
		assertNull(character.getPendingPaidChange());
	}

	@Test
	void aFailedRefundStaysHeldAndIsRetried() {
		wallet.set(Account.POUCH, d("100"));
		RPCharacter character = characterWithClass("WARRIOR");
		character.setPendingPaidChange(PaidChangeService.charge(PLAYER, CLASS_RULE, character).pending());

		wallet.depositsFail = true;
		assertEquals(PaidChangeService.Outcome.REFUND_FAILED, PaidChangeService.resolve(character));
		assertEquals(d("0"), wallet.get(Account.POUCH));
		assertTrue(character.getPendingPaidChange() != null);

		wallet.depositsFail = false;
		String message = PaidChangeService.recover(character);
		assertTrue(message.contains("100 denars went back to your pouch"), message);
		assertEquals(d("100"), wallet.get(Account.POUCH));
		assertNull(character.getPendingPaidChange());
		assertNull(PaidChangeService.recover(character));
	}

	@Test
	void aSavedHoldSurvivesACrashAndSettlesOnRecovery() throws Exception {
		wallet.set(Account.BANK, d("1000"));
		RPCharacter character = characterWithClass("WARRIOR");
		character.setPaidChangeCount(CLASS_STAGE, 1);
		character.setPendingPaidChange(PaidChangeService.charge(PLAYER, CLASS_RULE, character).pending());
		character.setMMOClass("mage");

		HashMap<String, Object> saved = new HashMap<>();
		CharacterStageChangeFields.save(saved, character);
		JSONObject reparsed = (JSONObject) new JSONParser().parse(new JSONObject(saved).toJSONString());
		RPCharacter loaded = characterWithClass("MAGE");
		CharacterStageChangeFields.load(loaded, reparsed);
		PendingPaidChange held = loaded.getPendingPaidChange();
		assertEquals(Account.BANK, held.account());
		assertEquals(d("1000"), held.amount());
		assertEquals(PLAYER, held.payerId());

		String message = PaidChangeService.recover(loaded);
		assertTrue(message.contains("class change is paid for. The next one costs 3,000 denars"), message);
		assertEquals(2, loaded.getPaidChangeCount(CLASS_STAGE));
		assertEquals(d("0"), wallet.get(Account.BANK));
		assertNull(loaded.getPendingPaidChange());
	}

	@Test
	void keepingTheChangeKeepsThePaymentAndRaisesTheNextPrice() {
		wallet.set(Account.BANK, d("100"));
		RPCharacter character = characterWithClass("WARRIOR");
		character.setPendingPaidChange(PaidChangeService.charge(PLAYER, CLASS_RULE, character).pending());

		character.setMMOClass("mage");
		assertEquals(PaidChangeService.Outcome.KEPT, PaidChangeService.resolve(character));
		assertEquals(d("0"), wallet.get(Account.BANK));
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

		wallet.set(Account.POUCH, d("1000"));
		wallet.set(Account.BANK, d("50"));
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
		wallet.set(Account.POUCH, d("0.3"));
		RPCharacter character = characterWithClass("WARRIOR");
		for (int i = 0; i < 3; i++) {
			assertEquals(ChargeStatus.PAID, PaidChangeService.charge(PLAYER, cheap, character).status());
		}
		assertEquals(d("0"), wallet.get(Account.POUCH));
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

	private static final class FakeWallet implements DenarWallet {
		private final Map<Account, BigDecimal> balances = new EnumMap<>(Account.class);
		boolean available = true;
		boolean depositsFail;

		void set(Account account, BigDecimal amount) {
			balances.put(account, amount.setScale(2));
		}

		BigDecimal get(Account account) {
			return balances.getOrDefault(account, d("0"));
		}

		@Override
		public boolean available() {
			return available;
		}

		@Override
		public BigDecimal balance(UUID playerId, Account account) {
			return get(account);
		}

		@Override
		public boolean withdraw(UUID playerId, Account account, BigDecimal amount) {
			if (get(account).compareTo(amount) < 0) {
				return false;
			}
			balances.put(account, get(account).subtract(amount));
			return true;
		}

		@Override
		public boolean deposit(UUID playerId, Account account, BigDecimal amount) {
			if (depositsFail) {
				return false;
			}
			balances.put(account, get(account).add(amount));
			return true;
		}
	}
}
