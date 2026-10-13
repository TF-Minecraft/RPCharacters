package net.tfminecraft.rpcharacters.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterStatus;

class CharacterDirectoryTest {

	@TempDir Path root;
	private final UUID owner = UUID.fromString("aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa");
	private final UUID other = UUID.fromString("bbbbbbbb-bbbb-4bbb-bbbb-bbbbbbbbbbbb");
	private final UUID id = UUID.fromString("cccccccc-cccc-4ccc-cccc-cccccccccccc");
	private final UUID second = UUID.fromString("dddddddd-dddd-4ddd-dddd-dddddddddddd");
	private final List<String> warnings = new ArrayList<>();

	@Test
	void absentRootsAndEmptyAccountsAreKnownEmpty() throws Exception {
		CharacterDirectory missing = CharacterDirectory.load(root.resolve("new-install"), warnings::add);
		assertEquals(List.of(), missing.characters(owner).orElseThrow());
		assertTrue(missing.info(id).isEmpty());
		assertTrue(missing.lastPlayed(owner).isEmpty());
		assertEquals(List.of(), load().characters(owner).orElseThrow());
		account(owner, "{}");
		Files.createDirectories(root.resolve("characterdata").resolve(owner.toString()));
		CharacterDirectory empty = load();
		assertEquals(List.of(), empty.characters(owner).orElseThrow());
		assertEquals(List.of(), empty.characters(other).orElseThrow());
		assertTrue(warnings.isEmpty());
	}

	@Test
	void loadsCanonicalCompleteImmutableSnapshotsWithoutRewritingOrOfflineCharacterConstruction() throws Exception {
		Path alive = save(owner, id + ".json", json(id, "&aReal Name", "ALIVE",
				"\"alias\":\"Hidden Alias\",\"hidden\":true,\"online-playtime-seconds\":2147483648,\"last-played-at-ms\":1000"));
		String before = Files.readString(alive);
		save(owner, second.toString(), json(second, "Deceased", "dead", "\"last-played-at-ms\":2000"));
		UUID missingId = UUID.randomUUID();
		save(owner, missingId + ".json", json(missingId, "Missing", "MISSING",
				"\"created-at\":1700000000,\"playtime-seconds\":1700000000"));
		CharacterDirectory directory;
		try (var constructions = mockConstruction(RPCharacter.class)) {
			directory = load();
			assertTrue(constructions.constructed().isEmpty());
		}
		List<CharacterInfo> characters = directory.characters(owner).orElseThrow();
		assertEquals(3, characters.size());
		CharacterInfo live = directory.info(id).orElseThrow();
		assertEquals(owner, live.ref().ownerId());
		assertEquals("Real Name", live.name());
		assertEquals("Human", live.raceId());
		assertEquals(Set.of("strong", "wise"), live.traits());
		assertEquals(2147483648L, live.playtimeSeconds());
		assertTrue(live.alive());
		assertEquals(Instant.ofEpochMilli(1000), live.lastPlayedAt());
		assertEquals(CharacterStatus.MISSING, directory.info(missingId).orElseThrow().status());
		assertEquals(0, directory.info(missingId).orElseThrow().playtimeSeconds());
		assertNull(directory.info(missingId).orElseThrow().lastPlayedAt());
		assertEquals(CharacterStatus.DEAD, directory.lastPlayed(owner).orElseThrow().status());
		assertThrows(UnsupportedOperationException.class, characters::clear);
		assertThrows(UnsupportedOperationException.class, () -> live.traits().add("new"));
		assertEquals(before, Files.readString(alive));
		assertTrue(warnings.isEmpty());
	}

	@Test
	void timestampTiesAreDeterministicAndLegacyRecordsHaveNoLastPlayed() throws Exception {
		save(owner, id + ".json", json(id, "First", "ALIVE", "\"last-played-at-ms\":1000"));
		save(owner, second + ".json", json(second, "Second", "MISSING", "\"last-played-at-ms\":1000"));
		save(other, UUID.randomUUID() + ".json", "broken");
		CharacterDirectory directory = load();
		assertEquals(second, directory.lastPlayed(owner).orElseThrow().ref().characterId());
		assertTrue(directory.lastPlayed(other).isEmpty());
		Files.delete(root.resolve("characterdata").resolve(owner.toString()).resolve(second + ".json"));
		save(owner, id + ".json", json(id, "Legacy", "ALIVE", "\"last-played-at-ms\":null"));
		assertTrue(load().lastPlayed(owner).isEmpty());
	}

	@Test
	void queriesDoNotReadDiskAndKeepEarlierSnapshotsAfterUpserts() throws Exception {
		save(owner, id + ".json", json(id, "Saved", "ALIVE", ""));
		CharacterDirectory directory = load();
		List<CharacterInfo> old = directory.characters(owner).orElseThrow();
		RPCharacter live = live(id);
		Race race = mock(Race.class);
		when(race.getId()).thenReturn("Elf");
		when(live.getRace()).thenReturn(race);
		Trait trait = mock(Trait.class);
		when(trait.getId()).thenReturn("BRAVE");
		List<Trait> traits = new ArrayList<>(List.of(trait));
		when(live.getTraits()).thenReturn(traits);
		when(live.getLastPlayedAt()).thenReturn(Instant.ofEpochMilli(3000));
		directory.upsert(owner, live);
		traits.clear();
		try (var files = mockStatic(Files.class, invocation -> {
			throw new AssertionError("Query tried to read disk: " + invocation.getMethod().getName());
		})) {
			CharacterInfo current = directory.info(id).orElseThrow();
			assertEquals("Live Name", current.name());
			assertEquals("Elf", current.raceId());
			assertEquals(Set.of("brave"), current.traits());
			assertEquals(100, current.playtimeSeconds());
			assertEquals(CharacterStatus.DEAD, current.status());
			assertEquals(current, directory.lastPlayed(owner).orElseThrow());
			assertEquals(List.of(current), directory.characters(owner).orElseThrow());
			assertEquals("Saved", old.getFirst().name());
			directory.remove(other, id.toString());
			assertTrue(directory.info(id).isPresent());
			directory.remove(owner, id.toString());
			assertEquals(List.of(), directory.characters(owner).orElseThrow());
			assertTrue(directory.info(id).isEmpty());
		}
	}

	@Test
	void snapshotsAllowNoRaceAndStripColorsWithoutUsingEffectiveAlias() {
		RPCharacter character = live(id);
		CharacterInfo snapshot = CharacterDirectory.snapshot(owner, character);
		assertNull(snapshot.raceId());
		assertNull(snapshot.lastPlayedAt());
		assertEquals("Live Name", snapshot.name());
		verify(character, never()).getEffectiveDisplayPlain();
		verify(character, never()).isHidden();
	}

	@Test
	void corruptAccountQuarantineSurvivesUpsertAndRemovalUntilFullReload() throws Exception {
		Path account = account(owner, "{broken");
		save(owner, id + ".json", json(id, "Character", "ALIVE", ""));
		save(other, second + ".json", json(second, "Orphaned account", "ALIVE", ""));
		CharacterDirectory directory = load();
		assertTrue(directory.characters(owner).isEmpty());
		assertTrue(directory.info(id).isEmpty());
		assertTrue(directory.characters(other).isPresent());
		directory.upsert(owner, live(id));
		directory.remove(owner, id.toString());
		assertTrue(directory.characters(owner).isEmpty());
		Files.writeString(account, "{}");
		assertTrue(directory.characters(owner).isEmpty());
		assertTrue(load().info(id).isPresent());
		assertTrue(warnings.stream().anyMatch(warning -> warning.contains("Cannot read account")));
	}

	@ParameterizedTest
	@ValueSource(strings = {"{broken", "[]", "null", "{\"traits\":null}",
			"{\"name\":null}", "{\"name\":\" \"}", "{\"name\":\"&a\"}",
			"{\"status\":\"UNKNOWN\"}", "{\"traits\":{}}", "{\"traits\":[42]}",
			"{\"race\":true}", "{\"online-playtime-seconds\":-1}",
			"{\"online-playtime-seconds\":1.5}", "{\"online-playtime-seconds\":\"120\"}",
			"{\"last-played-at-ms\":1.5}", "{\"id\":\"1-1-1-1-1\"}", "{\"id\":\"bad\"}"})
	void malformedRowsQuarantineEntireOwnerWithoutDiscardingOtherOwners(String malformed) throws Exception {
		String broken = malformed;
		if (malformed.startsWith("{\"")) {
			org.json.simple.JSONObject base = (org.json.simple.JSONObject) new org.json.simple.parser.JSONParser()
					.parse(json(id, "Broken", "ALIVE", ""));
			@SuppressWarnings("unchecked")
			java.util.Map<String, Object> overrides = (java.util.Map<String, Object>) new org.json.simple.parser.JSONParser().parse(malformed);
			base.putAll(overrides);
			broken = base.toJSONString();
		}
		save(owner, id + ".json", broken);
		save(owner, second + ".json", json(second, "Good sibling", "ALIVE", ""));
		UUID unaffected = UUID.randomUUID();
		save(other, unaffected + ".json", json(unaffected, "Other owner", "ALIVE", ""));
		CharacterDirectory directory = load();
		assertTrue(directory.characters(owner).isEmpty());
		assertTrue(directory.info(id).isEmpty());
		assertTrue(directory.info(second).isEmpty());
		assertTrue(directory.info(unaffected).isPresent());
		assertFalse(warnings.isEmpty());
	}

	@Test
	void duplicateUuidAcrossOwnersQuarantinesEveryClaimantEvenAfterRemovalAndRepair() throws Exception {
		save(owner, id + ".json", json(id, "Owner one", "ALIVE", ""));
		save(other, id + ".json", json(id, "Owner two", "ALIVE", ""));
		CharacterDirectory directory = load();
		assertTrue(directory.info(id).isEmpty());
		assertTrue(directory.characters(owner).isEmpty());
		assertTrue(directory.characters(other).isEmpty());
		directory.remove(other, id.toString());
		directory.upsert(owner, live(id));
		assertTrue(directory.info(id).isEmpty());
		assertTrue(warnings.stream().anyMatch(warning -> warning.contains("Duplicate character UUID")));
	}

	@Test
	void liveDuplicateAlsoQuarantinesAllOwnersAndFreshOwnerCanClaimNormallyRemovedId() throws Exception {
		CharacterDirectory directory = load();
		directory.upsert(owner, live(id));
		directory.remove(owner, id.toString());
		directory.upsert(other, live(id));
		assertEquals(other, directory.info(id).orElseThrow().ref().ownerId());
		directory.upsert(owner, live(id));
		assertTrue(directory.info(id).isEmpty());
		assertTrue(directory.characters(owner).isEmpty());
		assertTrue(directory.characters(other).isEmpty());
	}

	@ParameterizedTest
	@ValueSource(strings = {"invalid-name.json", "dddddddd-dddd-4ddd-dddd-dddddddddddd.json"})
	void mismatchedOrInvalidFilenameStillClaimsReadableIdToRejectCrossOwnerDuplicates(String filename) throws Exception {
		save(owner, filename, json(id, "Mismatched", "ALIVE", ""));
		save(other, id + ".json", json(id, "Duplicate", "ALIVE", ""));
		CharacterDirectory directory = load();
		assertTrue(directory.info(id).isEmpty());
		assertTrue(directory.characters(owner).isEmpty());
		assertTrue(directory.characters(other).isEmpty());
		assertFalse(warnings.isEmpty());
	}

	@Test
	void corruptFileStillClaimsItsFilenameIdToRejectCrossOwnerDuplicates() throws Exception {
		save(owner, id + ".json", "{broken");
		save(other, id + ".json", json(id, "Duplicate", "ALIVE", ""));
		CharacterDirectory directory = load();
		assertTrue(directory.info(id).isEmpty());
		assertTrue(directory.characters(other).isEmpty());
	}

	@Test
	void duplicateFilesWithinOneOwnerAreNotSilentlyOverwritten() throws Exception {
		save(owner, id + ".json", json(id, "First", "ALIVE", ""));
		save(owner, id.toString(), json(id, "Second", "ALIVE", ""));
		assertTrue(load().characters(owner).isEmpty());
		assertTrue(warnings.stream().anyMatch(warning -> warning.contains("Duplicate character file")));
	}

	@Test
	void duplicateAccountAndOwnerPathsAreQuarantined() throws Exception {
		account(owner, "{}");
		Files.writeString(root.resolve("playerdata").resolve(owner.toString().toUpperCase() + ".json"), "{}");
		save(other, id + ".json", json(id, "One", "ALIVE", ""));
		Files.createDirectory(root.resolve("characterdata").resolve(other.toString().toUpperCase()));
		CharacterDirectory directory = load();
		assertTrue(directory.characters(owner).isEmpty());
		assertTrue(directory.characters(other).isEmpty());
		assertTrue(warnings.stream().anyMatch(warning -> warning.contains("Duplicate account file")));
		assertTrue(warnings.stream().anyMatch(warning -> warning.contains("Duplicate owner directory")));
	}

	@Test
	void ignoresOnlyKnownAtomicStagingFilesAndAcceptsMissingRace() throws Exception {
		account(owner, "{}");
		Files.writeString(root.resolve("playerdata").resolve(".rpcharacters-player.tmp"), "incomplete");
		save(owner, ".rpcharacters-save.tmp", "incomplete");
		save(owner, id + ".json", json(id, "Race unknown", "ALIVE", "").replace("\"race\":\"Human\",", ""));
		assertNull(load().info(id).orElseThrow().raceId());
		assertTrue(warnings.isEmpty());
		save(owner, "unknown.bak", "{}");
		assertTrue(load().characters(owner).isEmpty());
	}

	@Test
	void unreadableOwnerFolderAndCharacterAndAccountAreUnknown() throws Exception {
		Path file = save(owner, id + ".json", json(id, "Readable", "ALIVE", ""));
		Path account = account(owner, "{}");
		for (Path unavailable : List.of(file.getParent(), file, account)) {
			try (var files = mockStatic(Files.class, invocation -> {
				String method = invocation.getMethod().getName();
				if (unavailable.equals(invocation.getArgument(0))
						&& (method.equals("list") || method.equals("newBufferedReader"))) {
					throw new IOException("unavailable");
				}
				return invocation.callRealMethod();
			})) {
				assertTrue(load().characters(owner).isEmpty());
			}
		}
		assertTrue(warnings.stream().anyMatch(warning -> warning.contains("Cannot list characters")));
		assertTrue(warnings.stream().anyMatch(warning -> warning.contains("Cannot read character")));
		assertTrue(warnings.stream().anyMatch(warning -> warning.contains("Cannot read account")));
	}

	@Test
	void vanishedOwnerFolderAndUnexpectedSubdirectoryAreUnknown() throws Exception {
		Path characters = Files.createDirectory(root.resolve("characterdata"));
		Files.createSymbolicLink(characters.resolve(owner.toString()), root.resolve("missing-owner"));
		assertTrue(load().characters(owner).isEmpty());
		Files.delete(characters.resolve(owner.toString()));
		Files.createDirectories(characters.resolve(owner.toString()).resolve(id + ".json"));
		assertTrue(load().characters(owner).isEmpty());
	}

	@ParameterizedTest
	@ValueSource(strings = {"playerdata", "characterdata"})
	void unavailableOrNonDirectoryRootsAbortInitialization(String directory) throws Exception {
		Path unavailable = Files.createDirectory(root.resolve(directory));
		try (var files = mockStatic(Files.class, invocation -> {
			if (invocation.getMethod().getName().equals("list") && unavailable.equals(invocation.getArgument(0))) {
				throw new IOException("root unavailable");
			}
			return invocation.callRealMethod();
		})) {
			assertThrows(IOException.class, this::load);
		}
		Files.delete(unavailable);
		Files.writeString(unavailable, "not a directory");
		assertThrows(IOException.class, this::load);
	}

	@ParameterizedTest
	@ValueSource(strings = {"playerdata", "characterdata"})
	void strayEntriesBesideAccountsAreNamedAndSkippedWithoutHidingAnyAccount(String directory) throws Exception {
		account(owner, "{}");
		save(owner, id + ".json", json(id, "Kept", "ALIVE", ""));
		Path folder = root.resolve(directory);
		List<Path> strays = List.of(Files.writeString(folder.resolve(owner + ".json.bak"), "{}"),
				Files.writeString(folder.resolve(".DS_Store"), "finder"),
				Files.createDirectory(folder.resolve("not-an-owner")));
		CharacterDirectory loaded = load();
		assertEquals("Kept", loaded.info(id).orElseThrow().name());
		assertEquals(1, loaded.characters(owner).orElseThrow().size());
		for (Path stray : strays) {
			assertTrue(warnings.contains("Skipped " + stray + ": the name is not an account UUID"), stray.toString());
		}
		assertEquals(strays.size(), warnings.size());
	}

	@Test
	void legacyRecordsWithoutTimestampsFallBackToTheCharacterStillFlaggedActive() throws Exception {
		UUID third = UUID.fromString("eeeeeeee-eeee-4eee-aeee-eeeeeeeeeeee");
		save(owner, id + ".json", json(id, "Benched", "ALIVE", "\"active\":\"false\""));
		save(owner, second + ".json", json(second, "Current", "ALIVE", "\"active\":\"true\""));
		save(other, third + ".json", json(third, "Inactive", "ALIVE", "\"active\":\"false\""));
		CharacterDirectory directory = load();
		assertEquals(second, directory.lastPlayed(owner).orElseThrow().ref().characterId());
		assertTrue(directory.lastPlayed(other).isEmpty(), "Inactive legacy records do not imply a last character");

		save(owner, second + ".json", json(second, "Fell", "DEAD", "\"active\":true"));
		CharacterInfo dead = load().lastPlayed(owner).orElseThrow();
		assertEquals(second, dead.ref().characterId());
		assertEquals(CharacterStatus.DEAD, dead.status());

		save(owner, id + ".json", json(id, "Played since", "ALIVE", "\"active\":\"false\",\"last-played-at-ms\":1000"));
		assertEquals(id, load().lastPlayed(owner).orElseThrow().ref().characterId(), "A timestamp beats a flag alone");
	}

	@Test
	void liveActiveFlagsAndRemovalsUpdateTheLegacyFallback() throws Exception {
		CharacterDirectory directory = load();
		RPCharacter live = live(id);
		when(live.isActive()).thenReturn(true);
		directory.upsert(owner, live);
		assertEquals(id, directory.lastPlayed(owner).orElseThrow().ref().characterId());
		directory.remove(other, id.toString());
		assertEquals(id, directory.lastPlayed(owner).orElseThrow().ref().characterId());
		when(live.isActive()).thenReturn(false);
		directory.upsert(owner, live);
		assertTrue(directory.lastPlayed(owner).isEmpty());
	}

	@Test
	void nonDirectoryDataRootAndDanglingRootAreNotNewInstalls() throws Exception {
		Path file = Files.writeString(root.resolve("file"), "not a directory");
		assertThrows(IOException.class, () -> CharacterDirectory.load(file, warnings::add));
		Path dangling = Files.createSymbolicLink(root.resolve("dangling"), root.resolve("absent"));
		assertThrows(IOException.class, () -> CharacterDirectory.load(dangling, warnings::add));
	}

	@Test
	void badLiveMetadataAndInvalidRemovalNeverHealQuarantine() throws Exception {
		CharacterDirectory directory = load();
		RPCharacter bad = live(id);
		when(bad.getName()).thenReturn(null);
		directory.upsert(owner, bad);
		directory.upsert(owner, live(id));
		assertTrue(directory.characters(owner).isEmpty());
		assertTrue(directory.info(id).isEmpty());
		directory.remove(other, "1-1-1-1-1");
		directory.upsert(other, live(second));
		assertTrue(directory.characters(other).isEmpty());
		assertTrue(warnings.stream().anyMatch(warning -> warning.contains("Invalid live character")));
		assertTrue(warnings.stream().anyMatch(warning -> warning.contains("Invalid removed character ID")));
	}

	@Test
	void deathIdsSurviveReloadAndRevivalAndLegacyDeathsHaveStableDistinctKeys() throws Exception {
		UUID death=UUID.randomUUID();
		save(owner,id+".json",json(id,"Dead","DEAD","\"death-id\":\""+death+"\""));
		save(owner,second+".json",json(second,"Legacy","DEAD",""));
		CharacterDirectory directory=load();
		assertEquals(death,directory.deathId(id).orElseThrow());
        directory.remove(other, id.toString());
        assertEquals(death, directory.deathId(id).orElseThrow());
		UUID legacy=directory.deathId(second).orElseThrow();
        directory.upsert(owner, directory.info(second).orElseThrow(), false);
        assertEquals(legacy, directory.deathId(second).orElseThrow());
		assertEquals(legacy,load().deathId(second).orElseThrow());
		RPCharacter old=live(second); directory.upsert(owner,old);
		assertEquals(legacy,directory.deathId(second).orElseThrow());
		when(old.getStatus()).thenReturn(Status.ALIVE); directory.upsert(owner,old);
		assertEquals(legacy,directory.deathId(second).orElseThrow());
		UUID later=UUID.randomUUID(); when(old.getDeathId()).thenReturn(later);
		when(old.getStatus()).thenReturn(Status.DEAD); directory.upsert(owner,old);
		assertEquals(later,directory.deathId(second).orElseThrow()); assertNotEquals(legacy,later);
		directory.remove(owner,second.toString()); assertTrue(directory.deathId(second).isEmpty());
		save(owner,id+".json",json(id,"Dead","DEAD","\"death-id\":\"bad\""));
		assertTrue(load().deathId(id).isEmpty());
	}

	@Test
	void findsEveryLivingCharacterByRealNameFromMemoryOnly() throws Exception {
		UUID dead = UUID.randomUUID();
		UUID missing = UUID.randomUUID();
		UUID namesake = UUID.randomUUID();
		UUID quarantinedOwner = UUID.randomUUID();
		UUID quarantined = UUID.randomUUID();
		save(owner, id + ".json", json(id, "&aKira", "ALIVE", "\"alias\":\"Hidden Alias\""));
		save(owner, dead + ".json", json(dead, "Kira", "DEAD", ""));
		save(owner, missing + ".json", json(missing, "Kira", "MISSING", ""));
		save(other, namesake + ".json", json(namesake, "KIRA", "ALIVE", ""));
		account(quarantinedOwner, "{broken");
		save(quarantinedOwner, quarantined + ".json", json(quarantined, "Kira", "ALIVE", ""));
		CharacterDirectory directory = load();
		try (var files = mockStatic(Files.class, invocation -> {
			throw new AssertionError("Query tried to read disk: " + invocation.getMethod().getName());
		})) {
			List<CharacterInfo> found = directory.findByName(" kira ");
			assertEquals(Set.of(id, namesake), Set.copyOf(found.stream().map(info -> info.ref().characterId()).toList()));
			assertThrows(UnsupportedOperationException.class, found::clear);
			assertEquals(List.of(), directory.findByName("Hidden Alias"));
			assertEquals(List.of(), directory.findByName("Kir"));
			assertEquals(List.of(), directory.findByName(""));
			directory.remove(other, namesake.toString());
			assertEquals(List.of(directory.info(id).orElseThrow()), directory.findByName("Kira"));
		}
		assertThrows(NullPointerException.class, () -> directory.findByName(null));
	}

	private CharacterDirectory load() throws IOException {
		return CharacterDirectory.load(root, warnings::add);
	}

	private Path account(UUID account, String json) throws IOException {
		return Files.writeString(Files.createDirectories(root.resolve("playerdata")).resolve(account + ".json"), json);
	}

	private Path save(UUID account, String filename, String json) throws IOException {
		Path folder = Files.createDirectories(root.resolve("characterdata").resolve(account.toString()));
		return Files.writeString(folder.resolve(filename), json);
	}

	private String json(UUID character, String name, String status, String extras) {
		return "{\"id\":\"" + character + "\",\"name\":\"" + name + "\",\"status\":\"" + status
				+ "\",\"race\":\"Human\",\"traits\":[\"STRONG\",\"wise\",\"Strong\"]"
				+ (extras.isEmpty() ? "" : "," + extras) + "}";
	}

	private RPCharacter live(UUID characterId) {
		RPCharacter character = mock(RPCharacter.class);
		when(character.getId()).thenReturn(characterId.toString());
		when(character.getName()).thenReturn("&bLive Name");
		when(character.getStatus()).thenReturn(Status.DEAD);
		when(character.getTraits()).thenReturn(List.of());
		when(character.getOnlinePlaytimeSeconds()).thenReturn(100);
		return character;
	}
}
