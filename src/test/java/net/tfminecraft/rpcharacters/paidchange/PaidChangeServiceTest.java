package net.tfminecraft.rpcharacters.paidchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import net.tfminecraft.rpcharacters.database.CharacterPaidChangeFields;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet.Account;
import net.tfminecraft.rpcharacters.paidchange.PaidChangeService.ChargeResult;
import net.tfminecraft.rpcharacters.paidchange.PaidChangeService.ChargeStatus;

class PaidChangeServiceTest {

	private static final long DAY_MS = 86_400_000L;
	private static final UUID PLAYER = UUID.randomUUID();
	private static final PaidChangeRule CLASS_RULE =
			new PaidChangeRule("class", "class_selection_stage", "class", List.of(100.0, 1000.0, 3000.0));

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
		assertEquals(100.0, CLASS_RULE.costAfter(0));
		assertEquals(1000.0, CLASS_RULE.costAfter(1));
		assertEquals(3000.0, CLASS_RULE.costAfter(2));
		assertEquals(3000.0, CLASS_RULE.costAfter(7));
	}

	@Test
	void chargesThePouchFirstThenTheBank() {
		wallet.set(Account.POUCH, 150);
		wallet.set(Account.BANK, 5000);
		RPCharacter character = characterWithClass("WARRIOR");

		ChargeResult first = PaidChangeService.charge(PLAYER, CLASS_RULE, character);
		assertEquals(ChargeStatus.PAID, first.status());
		assertEquals(Account.POUCH, first.pending().account());
		assertEquals(50.0, wallet.get(Account.POUCH));

		character.setPaidChangeCount("class", 1);
		ChargeResult second = PaidChangeService.charge(PLAYER, CLASS_RULE, character);
		assertEquals(Account.BANK, second.pending().account());
		assertEquals(1000.0, second.pending().amount());
		assertEquals(4000.0, wallet.get(Account.BANK));
	}

	@Test
	void refusesWhenNoAccountCoversTheCost() {
		wallet.set(Account.POUCH, 60);
		wallet.set(Account.BANK, 60);

		ChargeResult result = PaidChangeService.charge(PLAYER, CLASS_RULE, characterWithClass("WARRIOR"));
		assertEquals(ChargeStatus.INSUFFICIENT_FUNDS, result.status());
		assertEquals(100.0, result.cost());
		assertEquals(60.0, wallet.get(Account.POUCH));
		assertEquals(60.0, wallet.get(Account.BANK));
	}

	@Test
	void unavailableWithoutDenarEconomy() {
		wallet.available = false;
		wallet.set(Account.POUCH, 500);
		assertFalse(PaidChangeService.canPayToOpen(stage("class_selection_stage", 5 * DAY_MS)));
		assertEquals(ChargeStatus.UNAVAILABLE,
				PaidChangeService.charge(PLAYER, CLASS_RULE, characterWithClass("WARRIOR")).status());
	}

	@Test
	void backingOutWithoutAChangeRefunds() {
		wallet.set(Account.POUCH, 100);
		RPCharacter character = characterWithClass("WARRIOR");
		ChargeResult result = PaidChangeService.charge(PLAYER, CLASS_RULE, character);
		assertEquals(0.0, wallet.get(Account.POUCH));

		assertFalse(PaidChangeService.resolve(result.pending(), character));
		assertEquals(100.0, wallet.get(Account.POUCH));
		assertEquals(0, character.getPaidChangeCount("class"));
	}

	@Test
	void keepingTheChangeKeepsThePaymentAndRaisesTheNextPrice() {
		wallet.set(Account.BANK, 100);
		RPCharacter character = characterWithClass("WARRIOR");
		ChargeResult result = PaidChangeService.charge(PLAYER, CLASS_RULE, character);

		character.setMMOClass("mage");
		assertTrue(PaidChangeService.resolve(result.pending(), character));
		assertEquals(0.0, wallet.get(Account.BANK));
		assertEquals(1, character.getPaidChangeCount("class"));
		assertEquals(1000.0, CLASS_RULE.costAfter(character.getPaidChangeCount("class")));
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

		character.setPaidChangeCount("class", 1);
		String second = String.join("\n", PaidChangeService.summaryLore(stage, character, true));
		assertTrue(second.contains("Click to change for 1,000 denars"), second);
		assertTrue(second.contains("change after costs §e3,000 denars"), second);

		character.setPaidChangeCount("class", 2);
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
		assertEquals(75.0, race.costAfter(3));

		wallet.set(Account.POUCH, 1000);
		wallet.set(Account.BANK, 50);
		ChargeResult result = PaidChangeService.charge(PLAYER, race, characterWithClass("WARRIOR"));
		assertEquals(Account.BANK, result.pending().account());
	}

	@Test
	void paidChangeCountsSurviveASaveAndLoad() throws Exception {
		RPCharacter character = new RPCharacter(null);
		character.setPaidChangeCount("class", 2);
		HashMap<String, Object> saved = new HashMap<>();
		CharacterPaidChangeFields.save(saved, character);
		JSONObject reparsed = (JSONObject) new JSONParser().parse(new JSONObject(saved).toJSONString());

		RPCharacter loaded = new RPCharacter(null);
		CharacterPaidChangeFields.load(loaded, reparsed);
		assertEquals(2, loaded.getPaidChangeCount("class"));

		HashMap<String, Object> clean = new HashMap<>();
		CharacterPaidChangeFields.save(clean, new RPCharacter(null));
		assertTrue(clean.isEmpty());
	}

	@Test
	void denarsFormatWithSeparators() {
		assertEquals("100 denars", PaidChangeService.formatDenars(100));
		assertEquals("3,000 denars", PaidChangeService.formatDenars(3000));
		assertEquals("1 denar", PaidChangeService.formatDenars(1));
		assertEquals("2.50 denars", PaidChangeService.formatDenars(2.5));
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
		private final Map<Account, Double> balances = new EnumMap<>(Account.class);
		boolean available = true;

		void set(Account account, double amount) {
			balances.put(account, amount);
		}

		double get(Account account) {
			return balances.getOrDefault(account, 0.0);
		}

		@Override
		public boolean available() {
			return available;
		}

		@Override
		public double balance(UUID playerId, Account account) {
			return get(account);
		}

		@Override
		public boolean withdraw(UUID playerId, Account account, double amount) {
			if (get(account) < amount) {
				return false;
			}
			balances.put(account, get(account) - amount);
			return true;
		}

		@Override
		public boolean deposit(UUID playerId, Account account, double amount) {
			balances.put(account, get(account) + amount);
			return true;
		}
	}
}
