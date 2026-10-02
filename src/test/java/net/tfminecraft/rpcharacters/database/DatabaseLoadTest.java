package net.tfminecraft.rpcharacters.database;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.logging.*;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.kit.*;
import net.tfminecraft.rpcharacters.loaders.*;
import net.tfminecraft.rpcharacters.mail.MailRecipientDirectory;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeData;
import net.tfminecraft.rpcharacters.objects.races.*;
import net.tfminecraft.rpcharacters.objects.trait.*;
import net.tfminecraft.rpcharacters.persona.PermissionGroupService;
import net.tfminecraft.rpcharacters.playtime.CharacterPlaytimeDirectory;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.json.simple.*;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

class DatabaseLoadTest extends DatabaseFixture {
    @Test void abandonedAtomicStagingFilesAreNeverLoadedOrCountedAsCharacters() throws Exception {
        write(characterFile("alpha"), characterJson("alpha"));
        Path staging = characterFolder.resolve(".rpcharacters-crash.tmp");
        write(staging, characterJson("alpha"));
        var data = new PlayerData(owner); db.loadCharacters(data);
        assertEquals(List.of("alpha"), data.getCharacters().stream().map(RPCharacter::getId).toList());
        assertEquals(List.of("alpha"), db.listCharacterFileIds(owner));
        assertTrue(Files.exists(staging), "Loading must leave crash evidence intact");
    }

    @Test void completeOnlineAndOfflineRecordsRestoreAccountAndCharacterState() throws Exception {
        write(playerFile,richPlayerJson()); write(characterFile("alpha"),richCharacterJson("alpha"));
        var online=db.loadPlayer(player); assertComplete(online); assertEquals(List.of("strength.2","dexterity.1"),online.takePendingMmoAttributeRemoves());
        var offline=db.loadPlayerData(owner); assertComplete(offline); assertNull(offline.getPlayer());
        bukkit.when(() -> Bukkit.getPlayer(owner)).thenReturn(player); assertComplete(db.loadPlayerData(owner));
        mail.verify(() -> MailRecipientDirectory.upsert(eq(owner),any(RPCharacter.class)),times(3));
    }

    @Test void legacyCooldownKitClaimsAndAbsentRecordsHaveExplicitDefaults() throws Exception {
        assertNull(db.loadPlayer(nullPlayer())); assertNull(db.loadPlayerData(null));
        assertNull(db.loadPlayer(player)); assertEquals(owner,db.loadPlayerData(owner).getUniqueId());
        bukkit.when(() -> Bukkit.getPlayer(owner)).thenReturn(player); assertSame(player,db.loadPlayerData(owner).getPlayer());
        JSONObject legacy=obj("completed stages",array("legacy"),"eighteen","false","cooldown",5,"account-playtime-seconds",1_650_000_000,"last-kit-grant-ms",1234L);
        write(playerFile,legacy);
        for(boolean online:List.of(true,false)) {
            bukkit.when(() -> Bukkit.getPlayer(owner)).thenReturn(online?player:null); var loaded=db.loadPlayerData(owner);
            assertEquals(9000L,loaded.getLastCharacterSwitchAtMs()); assertEquals(1234L,loaded.getLastKitGrantAtMs()); assertFalse(loaded.isEighteen()); assertEquals(1_650_000_000,loaded.getCreatedAtEpochSeconds()); assertTrue(loaded.needsSkillPointsMigration());
        }
        write(playerFile,obj()); bukkit.when(() -> Bukkit.getPlayer(owner)).thenReturn(null);
        assertTrue(db.loadPlayerData(owner).getCompletedStages().isEmpty());
    }

    @Test void nativeBooleanPlayerFieldsAndMissingCompletedStagesLoadOnline() throws Exception {
        write(playerFile,obj("eighteen",true)); var loaded=db.loadPlayer(player);
        assertNotNull(loaded,"A native JSON boolean and optional missing stage list are valid"); assertTrue(loaded.isEighteen()); assertTrue(loaded.getCompletedStages().isEmpty());
        var offline=db.loadPlayerData(owner); assertNotNull(offline); assertTrue(offline.isEighteen()); assertTrue(offline.getCompletedStages().isEmpty());
        write(playerFile,obj("eighteen",false)); assertFalse(db.loadPlayer(player).isEighteen()); assertFalse(db.loadPlayerData(owner).isEighteen());
    }

    @Test void nativeBooleanCharacterActivationLoadsWithoutDroppingCharacter() throws Exception {
        var json=characterJson("alpha"); json.put("active",true); write(characterFile("alpha"),json);
        var loaded=new PlayerData(owner); db.loadCharacters(loaded);
        assertEquals(1,loaded.getCharacters().size()); assertTrue(loaded.getCharacters().getFirst().isActive());
    }

    @Test void corruptExistingAccountNeverMasqueradesAsANewAccount() throws Exception {
        Files.writeString(playerFile,"{corrupt existing account");
        assertNull(db.loadPlayerData(owner),"Callers must not save an empty replacement over corrupt account data");
        bukkit.when(() -> Bukkit.getPlayer(owner)).thenReturn(player); assertNull(db.loadPlayerData(owner));
        assertEquals("{corrupt existing account",Files.readString(playerFile));
    }

    @Test void characterListingDistinguishesMissingUnreadableAndNonDirectoryPaths() throws Exception {
        assertTrue(db.listCharacterFileIds(null).isEmpty()); assertTrue(db.listCharacterFileIds(owner).isEmpty());
        Files.writeString(characterFolder,"blocking regular file"); assertNull(db.listCharacterFileIds(owner)); db.loadCharacters(new PlayerData(owner)); Files.delete(characterFolder);
        Files.createDirectories(characterFolder); Files.createDirectory(characterFolder.resolve("folder")); Files.writeString(characterFolder.resolve("alpha.json"),"{}"); Files.writeString(characterFolder.resolve("legacy"),"{}"); Files.writeString(characterFolder.resolve(".json"),"{}");
        assertEquals(Set.of("alpha","legacy"),new HashSet<>(db.listCharacterFileIds(owner)));
        var permissions=Files.getPosixFilePermissions(characterFolder);
        try {
            Files.setPosixFilePermissions(characterFolder,PosixFilePermissions.fromString("---------"));
            assertNull(db.listCharacterFileIds(owner)); db.loadCharacters(new PlayerData(owner)); verify(logger).severe(contains("Could not list character files"));
            RPCharacters.plugin=null; db.loadCharacters(new PlayerData(owner));
        } finally {Files.setPosixFilePermissions(characterFolder,permissions);}
    }

    @Test void malformedCharactersStayOnDiskAndDoNotPreventHealthySiblingsLoading() throws Exception {
        Files.createDirectories(characterFolder); Files.createDirectory(characterFolder.resolve("folder")); var broken=characterFolder.resolve("broken.json"); Files.writeString(broken,"not-json"); write(characterFile("good"),characterJson("good"));
        var data=new PlayerData(owner); db.loadCharacters(data); assertEquals(List.of("good"),data.getCharacters().stream().map(RPCharacter::getId).toList()); assertEquals("not-json",Files.readString(broken));
        verify(logger).log(eq(Level.SEVERE),contains("Skipped character file"),any(Throwable.class));
        RPCharacters.plugin=null; db.loadCharacters(new PlayerData(owner)); assertEquals("not-json",Files.readString(broken));
    }

    @Test void unknownRaceUsesConfiguredFallbackAndUnknownTraitsAreIgnored() throws Exception {
        var json=characterJson("legacy"); json.put("race","removed-race"); json.put("traits",array("strong","missing")); json.remove("class"); json.remove("clues");
        write(characterFile("legacy"),json); var data=new PlayerData(owner); db.loadCharacters(data); var loaded=data.getCharacters().getFirst();
        assertSame(race,loaded.getRace()); assertEquals(List.of("strong"),loaded.getTraits().stream().map(Trait::getId).toList()); assertTrue(loaded.getPlayerClues().isEmpty()); assertEquals("aria",loaded.getSlug()); assertFalse(loaded.hasMMOClass()); assertEquals(RPCharacter.MAX_FOOD_VALUE,loaded.getFoodValue());
    }

    @Test void expiredInactiveTraitsAreRemovedWhileOtherPersistedStateSurvives() throws Exception {
        var json=characterJson("inactive"); json.put("traits",array("healing","fuel")); json.put("trait-state",obj("healing",obj("expires-at-ms",1L),"fuel",obj("duration-remaining-ms",60_000L,"fuel",7D)));
        write(characterFile("inactive"),json); var data=new PlayerData(owner); db.loadCharacters(data); var loaded=data.getCharacters().getFirst();
        assertEquals(List.of("fuel"),loaded.getTraits().stream().map(Trait::getId).toList()); assertEquals(7D,loaded.getFuel("fuel")); assertTrue(loaded.getDurationRemainingMs("fuel")>0); assertTrue(Files.readString(characterFile("inactive")).contains("healing"),"Loading must not silently rewrite source bytes");
    }

    @Test void optionalMalformedShapesDoNotDiscardOtherwiseValidCharacters() throws Exception {
        var shapes=List.of(
            obj("conversations","invalid","conversation-last-at","invalid","last-location","invalid","trait-state","invalid","extra-attribute-allocation","invalid","kit-statuses","invalid","kit-customisations","invalid","name-colour","invalid","pvp-lethal",null),
            obj("last-location",obj("world"," ","x",1,"y",2,"z",3),"pvp-lethal","false","kit-status","eligible"),
            obj("last-location",obj("world","world","x","bad","y",2,"z",3),"last-kit-claims","invalid"));
        int index=0; for(var extra:shapes) {var json=characterJson("shape"+index); json.putAll(extra); write(characterFile("shape"+index++),json);}
        var data=new PlayerData(owner); db.loadCharacters(data); assertEquals(3,data.getCharacters().size());
        for(var character:data.getCharacters()) {assertFalse(character.hasLastLocation()); assertTrue(character.getConversationCounts().isEmpty()); assertTrue(character.getExtraAttributeAllocation().isEmpty());}
        assertFalse(data.getCharacterById("shape1").isPvpLethal()); assertEquals(KitStatus.ELIGIBLE,data.getCharacterById("shape1").getKitStatus());
        write(playerFile,obj("completed stages",array(),"last-kit-claims","invalid")); assertTrue(db.loadPlayer(player).getLastKitClaimAtMsMap().isEmpty());
    }

    @Test void kitStyleIdentifiersUseLocaleIndependentNormalization() throws Exception {
        Locale.setDefault(Locale.forLanguageTag("tr-TR")); var json=characterJson("alpha"); json.put("status","alive"); json.put("kit-customisations",obj("sword",obj("name-styles",array("ITALIC")))); write(characterFile("alpha"),json);
        var data=new PlayerData(owner); db.loadCharacters(data); assertEquals(List.of("italic"),data.getCharacters().getFirst().getKitCustomisations().get("sword").getNameStyles());
    }

    private void assertComplete(PlayerData loaded) {
        assertNotNull(loaded); assertEquals(owner,loaded.getUniqueId()); assertTrue(loaded.isEighteen()); assertEquals(List.of("intro","race"),loaded.getCompletedStages()); assertEquals(1234567890123L,loaded.getLastCharacterSwitchAtMs()); assertEquals(1700000000,loaded.getCreatedAtEpochSeconds());
        assertEquals(12,loaded.getAccountSkillPointsTotal()); assertEquals(6,loaded.getAccountAttributePointsTotal()); assertTrue(loaded.isProfessionPointsInitialized()); assertEquals(3,loaded.getAccountProfessionPoints("forager")); assertEquals(7,loaded.getInvestigationPoints()); assertEquals(1234567890000L,loaded.getLastInvestigationRegenMs()); assertTrue(loaded.hasDismissedTutorial("intro")); assertEquals(2000L,loaded.getLastKitClaimAtMs("extra"));
        assertEquals(1,loaded.getCharacters().size()); var c=loaded.getCharacters().getFirst(); assertEquals("alpha",c.getId()); assertEquals("Aria",c.getName()); assertEquals(77,c.getOnlinePlaytimeSeconds()); assertEquals(3,c.getConversationCount("friend")); assertEquals(9876543210123L,c.getConversationLastAtMs().get("friend"));
        assertEquals("Alias",c.getAlias()); assertEquals("woman",c.getGender()); assertEquals("Character description",c.getPersonaDescription()); assertEquals(List.of("#ffffff","#112233"),c.getNameColour().getHexCodes()); assertTrue(c.isNameColourStaffOverride()); assertEquals("1/1/1700",c.getBirthday()); assertEquals("aria-custom",c.getSlug()); assertFalse(c.isMailListed()); assertTrue(c.isHidden());
        assertEquals(KitStatus.GRANTED,c.getKitStatus()); assertEquals(KitStatus.ELIGIBLE,c.getKitStatus("extra")); var kit=c.getKitCustomisations().get("sword"); assertEquals("Blade",kit.getDisplayName()); assertEquals(List.of("Personal lore"),kit.getLore()); assertEquals("blade-skin",kit.getSkinSlug()); assertEquals("items/blade",kit.getPath()); assertEquals("staff",kit.getIaNamespace()); assertEquals(List.of("#ffffff"),kit.getNameColours()); assertEquals(List.of("bold"),kit.getNameStyles()); assertEquals("tfmc_submissions",c.getKitCustomisations().get("minimal").getIaNamespace());
        assertEquals(2,c.getForfeitedProfessionPoints().get("forager")); assertEquals(List.of("upgrade-1"),c.getProfessionUpgrades()); assertEquals(Map.of("strength",3),c.getExtraAttributeAllocation()); assertEquals("world",c.getLastLocationWorld()); assertEquals(12.5,c.getLastLocationX()); assertFalse(c.isPvpLethal()); assertEquals(123,c.getFoodValue()); assertEquals(21,c.getDietScore()); assertEquals(30,c.getRawDietScore()); assertEquals("healthy",c.getLastDietTierId()); assertEquals(1,c.getEvilRpStrikes()); assertEquals(20D,c.getFuel("fuel")); assertTrue(c.getDurationRemainingMs("healing")>0);
    }
}

abstract class DatabaseFixture {
    @TempDir Path temp;
    RuntimeTestState state; Database db; UUID owner; Player player; Race race; RPCharacters plugin; Logger logger;
    Path playerFile,characterFolder; MockedStatic<Bukkit> bukkit; MockedStatic<MailRecipientDirectory> mail; MockedStatic<CharacterPlaytimeDirectory> playtime; MockedStatic<PermissionGroupService> permissions;
    @BeforeEach void setupDatabase() throws Exception {
        MockBukkit.mock(); state=new RuntimeTestState(RPCharacters.class,RaceLoader.class,TraitLoader.class);
        Cache.attributes=new ArrayList<>(); Cache.professions=new ArrayList<>(); Cache.backgroundTraitTypes=new ArrayList<>(); Cache.maxClues=50; Cache.clueMaxLength=200; Cache.clueMinLength=1;
        RaceLoader.oList=new ArrayList<>(); TraitLoader.oList=new ArrayList<>();
        plugin=mock(RPCharacters.class); logger=mock(Logger.class); when(plugin.getLogger()).thenReturn(logger); RPCharacters.plugin=plugin;
        owner=UUID.randomUUID(); player=mock(Player.class); when(player.getUniqueId()).thenReturn(owner); when(player.getName()).thenReturn("DbTest"+owner.toString().substring(0,8));
        bukkit=mockStatic(Bukkit.class,CALLS_REAL_METHODS); bukkit.when(() -> Bukkit.getPlayer(owner)).thenReturn(null);
        mail=mockStatic(MailRecipientDirectory.class); playtime=mockStatic(CharacterPlaytimeDirectory.class); permissions=mockStatic(PermissionGroupService.class); permissions.when(() -> PermissionGroupService.migrateLegacyCooldownMinutes(anyInt())).thenReturn(9000L);
        race=mock(Race.class); var data=mock(RaceData.class); when(race.getId()).thenReturn("human"); when(race.getName()).thenReturn("Human"); when(race.getRaceData()).thenReturn(data); when(data.getAttributeData()).thenReturn(new AttributeData()); RaceLoader.oList.add(race);
        trait("strong",false,false); trait("healing",true,false); trait("fuel",false,true);
        playerFile=Path.of("plugins/RPCharacters/data/playerdata",owner+".json"); characterFolder=Path.of("plugins/RPCharacters/data/characterdata",owner.toString()); Files.createDirectories(playerFile.getParent()); Files.createDirectories(characterFolder.getParent()); db=new Database();
    }
    @AfterEach void cleanupDatabase() throws Exception {
        permissions.close(); playtime.close(); mail.close(); bukkit.close(); MockBukkit.unmock(); state.close();
        Files.deleteIfExists(playerFile); removeOwnedTree(characterFolder);
    }
    static void removeOwnedTree(Path root) throws IOException {if(!Files.exists(root))return; try(var files=Files.walk(root)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    Trait trait(String id,boolean duration,boolean fuel) {var trait=mock(Trait.class);var data=mock(TraitData.class);when(trait.getId()).thenReturn(id);when(trait.getName()).thenReturn(id);when(trait.getTraitData()).thenReturn(data);when(trait.getDesc()).thenReturn(List.of());when(data.getKey()).thenReturn("physical");when(data.getAttributeData()).thenReturn(new AttributeData());when(trait.hasDuration()).thenReturn(duration);when(trait.getDurationMs()).thenReturn(3600000L);when(trait.hasFuelTemplate()).thenReturn(fuel);when(trait.getFuelCapacity()).thenReturn(100D);TraitLoader.oList.add(trait);return trait;}
    Player nullPlayer() {var result=mock(Player.class);when(result.getUniqueId()).thenReturn(UUID.randomUUID());return result;}
    Path characterFile(String id) throws IOException {Files.createDirectories(characterFolder);return characterFolder.resolve(id+".json");}
    void write(Path path,JSONObject json) throws IOException {Files.createDirectories(path.getParent());Files.writeString(path,json.toJSONString());}
    JSONObject read(Path path) throws Exception {return (JSONObject)new JSONParser().parse(Files.readString(path));}
    @SuppressWarnings("unchecked") static JSONObject obj(Object... pairs) {var result=new JSONObject();for(int i=0;i<pairs.length;i+=2)result.put(pairs[i],pairs[i+1]);return result;}
    @SuppressWarnings("unchecked") static JSONArray array(Object... values) {var result=new JSONArray();Collections.addAll(result,values);return result;}
    JSONObject characterJson(String id) {return obj("id",id,"name","Aria","status","ALIVE","active","false","race","human","traits",array(),"clues",array("Blue coat"),"created-at",1700000000,"class","warrior");}
    JSONObject richPlayerJson() {return obj("completed stages",array("intro","race"),"eighteen","true","to remove",array("strength.2","dexterity.1"),"last-character-switch-ms",1234567890123L,"created-at",1700000000,"account-skill-points-total",12,"account-attribute-points-total",6,"profession-points-initialized","true","account-profession-points",obj("forager",3),"investigation-points",7,"investigation-regen-ms",1234567890000L,"dismissed-tutorials",array("intro"),"last-kit-claims",obj("starter",1000L,"extra",2000L,"","bad","invalid","bad"));}
    @SuppressWarnings("unchecked") JSONObject richCharacterJson(String id) {
        var json=characterJson(id); json.putAll(obj("active","true","traits",array("strong","healing","fuel"),"online-playtime-seconds",77,"conversations",obj("friend",3,"bad","bad"),"conversation-last-at",obj("friend",9876543210123L,"bad","bad"),"alias","Alias","gender","woman","description","Character description","name-colour",obj("colours",array("#ffffff","#112233")),"name-colour-staff","true","birthday","1/1/1700","slug","aria-custom","mail-listed","false","hidden","true","kit-statuses",obj("starter","granted","extra","eligible","missing",null),"kit-customisations",obj("sword",obj("display-name","Blade","lore",array("Personal lore",null),"skin-slug","blade-skin","path","items/blade","ia-namespace","staff","name-colours",array(" #ffffff ",null," "),"name-styles",array(" BOLD ",null," ")),"minimal",obj(),"invalid","invalid"),"forfeited-profession-points",obj("forager",2,"ignored","bad"),"profession-upgrades",array("upgrade-1"),"extra-attribute-allocation",obj("strength",3,"dexterity",0,"invalid","bad"),"trait-state",obj("healing",obj("expires-at-ms",System.currentTimeMillis()+3600000L),"fuel",obj("duration-remaining-ms",60000L,"fuel",20D),"invalid","bad"),"last-location",obj("world","world","x",12.5,"y",64D,"z",-4D),"pvp-lethal",false,"food-value",123,"diet-score",21,"raw-diet-score",30,"last-diet-tier","healthy","evil-rp",obj("strikes",1,"session-ends-at",1234567899999L)));
        return json;
    }
    RPCharacter character(String id,boolean active) {return new RPCharacter(player,id,"Aria",active,Status.ALIVE,race,new ArrayList<>(TraitLoader.oList),"warrior",List.of("Blue coat"));}
}
