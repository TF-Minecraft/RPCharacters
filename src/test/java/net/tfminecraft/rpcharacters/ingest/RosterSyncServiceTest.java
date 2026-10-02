package net.tfminecraft.rpcharacters.ingest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.api.player.profess.PlayerClass;
import net.Indyuce.mmocore.manager.ClassManager;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.api.ProvinceSystemClient;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.identity.PersonaService;
import net.tfminecraft.rpcharacters.kit.*;
import net.tfminecraft.rpcharacters.loaders.*;
import net.tfminecraft.rpcharacters.mail.MailRecipientDirectory;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.objects.attributes.*;
import net.tfminecraft.rpcharacters.objects.experience.ExperienceModifier;
import net.tfminecraft.rpcharacters.objects.races.*;
import net.tfminecraft.rpcharacters.objects.trait.*;
import net.tfminecraft.rpcharacters.persona.*;
import net.tfminecraft.rpcharacters.playtime.CharacterPlaytimeDirectory;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.*;
import org.json.simple.*;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

class RosterSyncServiceTest extends IngestFixture {
    MockedStatic<PersonaService> personas;
    MMOCore previousMmo;
    @BeforeEach void setupRoster() {personas=mockStatic(PersonaService.class);previousMmo=MMOCore.plugin;}
    @AfterEach void cleanupRoster() {MMOCore.plugin=previousMmo;personas.close();}

    @Test void asyncEntrypointsRespectMissingPluginAndPlayerThenPublishEveryOnlineRoster() {
        RosterSyncService.pushRosterNow(null); RosterSyncService.pushRosterAsync(null); RosterSyncService.pushRosterForPlayer(null);
        RPCharacters.plugin=null; RosterSyncService.pushRosterAsync(owner); RosterSyncService.pushAllOnlineAsync(); assertTrue(workers.isEmpty());
        RPCharacters.plugin=plugin; online(mockData()); bukkit.when(Bukkit::getOnlinePlayers).thenReturn(Arrays.asList(player,null));
        RosterSyncService.pushRosterForPlayer(player); RosterSyncService.pushAllOnlineAsync(); assertEquals(2,workers.size());
        runWorkers(); assertEquals(2,pushes.size()); assertEquals(owner.toString(),pushes.getFirst().get("player_uuid"));
    }

    @Test void richRosterSerializesSheetAndAccountWithoutFormattingOrHiddenTraits() throws Exception {
        Locale.setDefault(Locale.forLanguageTag("tr-TR")); PlayerData pd=mockData(); online(pd); RPCharacter c=character("alpha"); when(pd.getCharacters()).thenReturn(List.of(c));
        when(c.getName()).thenReturn("Aria");when(c.getStatus()).thenReturn(Status.DEAD);when(c.getRace()).thenReturn(race);when(race.getName()).thenReturn("§aHuman");
        when(c.hasMMOClass()).thenReturn(true);when(c.getMMOClass()).thenReturn("warrior");when(c.getCreatedAtEpochSeconds()).thenReturn(1700000000);
        Map<String,KitStatus> statuses=new LinkedHashMap<>();statuses.put("starter",KitStatus.GRANTED);statuses.put(null,KitStatus.ELIGIBLE);statuses.put("ignored",null);when(c.getKitStatuses()).thenReturn(statuses);when(c.getKitStatus(KitLoader.DEFAULT_KIT_ID)).thenReturn(KitStatus.GRANTED);
        MMOCore.plugin=mock(MMOCore.class);var classes=mock(ClassManager.class);var field=MMOCore.class.getField("classManager");field.setAccessible(true);field.set(MMOCore.plugin,classes);var warrior=mock(PlayerClass.class);when(classes.get("warrior")).thenReturn(warrior);when(warrior.getName()).thenReturn("§6Warrior");
        personas.when(() -> PersonaService.resolveAge(c)).thenReturn("24");when(c.getBirthday()).thenReturn(" 2000-01-01 ");when(c.getGender()).thenReturn(" woman ");when(c.getPersonaDescription()).thenReturn(" A traveller ");when(c.getDescription()).thenReturn(Arrays.asList(null," §a "," §aRaised by sailors "," Sails at dawn "));
        var data=mock(AttributeData.class);when(c.getAttributeData()).thenReturn(data);when(data.getModifiers()).thenReturn(Arrays.asList(null,new AttributeModifier(null,1),new AttributeModifier(" ",1),new AttributeModifier(" INTELLIGENCE ",3)));
        doReturn(Arrays.asList(null,xp(null,null,1),xp(" ","",1),xp(" MINING ","§a Miner ",12),xp("Fishing",null,-2),xp("Foraging","§a ",4))).when(data).getExperienceModifiers();
        Cache.attributes=new ArrayList<>(Arrays.asList(null," ","strength"));Cache.editableTraits=new ArrayList<>(Arrays.asList(null," PHYSICAL "));
        Trait visible=trait("strong"," PHYSICAL ");when(visible.getName()).thenReturn("§bStrong");Trait injury=trait("wound","injury");when(injury.hasDuration()).thenReturn(true);when(c.getDurationRemainingMs("wound")).thenReturn(2500L);Trait arm=trait("arm","prosthetic");when(arm.hasFuelTemplate()).thenReturn(true);when(arm.getFuelCapacity()).thenReturn(20D);when(c.getFuel("arm")).thenReturn(25D);when(arm.getName()).thenReturn(null);
        Trait noData=mock(Trait.class);when(noData.getId()).thenReturn("no-data");Trait noId=mock(Trait.class);Trait blankKey=trait("blank-key"," ");Trait nullKey=trait("null-key",null);Trait hidden=trait("secret","secret");Trait rank=trait("str2","physical");
        when(c.getTraits()).thenReturn(Arrays.asList(null,noId,noData,blankKey,nullKey,hidden,rank,visible,injury,arm));when(c.getPlayerClues()).thenReturn(Arrays.asList(null," ","§a"," §aBlue coat "));
        var kit=mock(KitDefinition.class);when(kit.getId()).thenReturn("starter");when(kit.getCooldownHours()).thenReturn(8);Map<String,KitDefinition> configured=new LinkedHashMap<>();configured.put("starter",kit);configured.put("null",null);kits.when(KitLoader::getKits).thenReturn(configured);kits.when(KitLoader::getCooldownHours).thenReturn(8);kitService.when(() -> KitService.cooldownRemainingMs(pd,"starter")).thenReturn(3500L);
        slots.when(() -> CharacterSlotService.getMaxAliveCharacters(player)).thenReturn(4);permissions.when(() -> PermissionGroupService.getNameColourStops(player)).thenReturn(2);permissions.when(() -> PermissionGroupService.getWardrobeSkinSlots(player)).thenReturn(7);
        when(pd.getCompletedStages()).thenReturn(List.of("creation_age_set_stage"));when(pd.isEighteen()).thenReturn(true);when(pd.getCreatedAtEpochSeconds()).thenReturn(1700000001);
        RosterSyncService.pushRosterNow(owner); JSONObject root=pushes.getFirst();JSONObject row=(JSONObject)((JSONArray)root.get("characters")).getFirst();
        assertEquals("Aria",row.get("name"));assertEquals("DEAD",row.get("status"));assertEquals("Human",row.get("race_name"));assertEquals("Warrior",row.get("class_name"));assertEquals("1700000000",row.get("created_at"));assertEquals(obj("starter","granted"),row.get("kit_statuses"));assertEquals("granted",row.get("kit_status"));assertEquals("24",row.get("age"));assertEquals("2000-01-01",row.get("birthday"));assertEquals("woman",row.get("gender"));assertEquals("A traveller",row.get("description"));assertEquals("Raised by sailors\nSails at dawn",row.get("background"));assertEquals(obj("intelligence",3L),row.get("attributes"));
        assertEquals(array(obj("profession","mining","alias","Miner","amount",12L),obj("profession","fishing","alias","Fishing","amount",-2L),obj("profession","foraging","alias","Foraging","amount",4L)),row.get("experience_modifiers"));
        assertEquals(array(obj("id","strong","name","Strong","key","physical"),obj("id","wound","name","wound","key","injury","duration_remaining_ms",2500L),obj("id","arm","name","","key","prosthetic","fuel_percent",100L)),row.get("traits"));assertEquals(array("Blue coat"),row.get("clues"));assertEquals(obj("starter",obj("seconds_remaining",3L,"hours",8L)),root.get("kit_cooldowns"));assertEquals(3L,root.get("kit_cooldown_seconds_remaining"));assertEquals(4L,root.get("max_alive_characters"));assertEquals(2L,root.get("name_colour_stops"));assertEquals(7L,root.get("wardrobe_skin_slots"));assertEquals(true,root.get("real_age_set"));assertEquals(true,root.get("eighteen"));assertEquals(1700000001L,root.get("account_created_at_epoch"));
        when(classes.get("warrior")).thenReturn(null);RosterSyncService.pushRosterNow(owner);assertEquals("warrior",firstCharacter().get("class_name"));
    }

    @Test void optionalFieldsAndBrokenSheetRefreshFailSoftWithoutInventingData() {
        var pd=mockData();online(pd);var c=character("minimal");var nullId=character(null);when(pd.getCharacters()).thenReturn(Arrays.asList(null,nullId,c));doThrow(new IllegalStateException("refresh failure")).when(c).update();
        when(c.getTraits()).thenReturn(null);when(c.getDescription()).thenReturn(null);when(c.getAttributeData()).thenReturn(null);when(pd.getCompletedStages()).thenReturn(List.of("age_stage"));
        RosterSyncService.pushRosterNow(owner);var row=firstCharacter();assertEquals(obj("id","minimal","name","","status","ALIVE","race",null,"class",null),row);assertEquals(false,pushes.getFirst().get("eighteen"));assertFalse(pushes.getFirst().containsKey("kit_cooldowns"));
    }

    @Test void blankOptionalValuesAndUnavailableMmoClassRemainSafe() {
        var pd=mockData();online(pd);var c=character("minimal");when(pd.getCharacters()).thenReturn(List.of(c));when(c.getRace()).thenReturn(race);when(race.getName()).thenReturn("§a ");when(c.hasMMOClass()).thenReturn(true);when(c.getMMOClass()).thenReturn("unavailable");MMOCore.plugin=null;
        when(c.getBirthday()).thenReturn(" ");when(c.getGender()).thenReturn(" ");when(c.getPersonaDescription()).thenReturn(" ");when(c.getDescription()).thenReturn(List.of("§a "));personas.when(() -> PersonaService.resolveAge(c)).thenReturn(" ");
        var data=mock(AttributeData.class);when(c.getAttributeData()).thenReturn(data);when(data.getModifiers()).thenReturn(null);when(data.getExperienceModifiers()).thenReturn(null);
        Cache.editableTraits=null;var hidden=trait("hidden","physical");var unknown=trait("unknown","injury");Cache.attributes=null;when(unknown.hasDuration()).thenReturn(true);when(c.getDurationRemainingMs("unknown")).thenReturn(-1L);when(unknown.hasFuelTemplate()).thenReturn(true);when(unknown.getFuelCapacity()).thenReturn(10D);when(c.getFuel("unknown")).thenReturn(-1D);when(c.getTraits()).thenReturn(List.of(hidden,unknown));
        RosterSyncService.pushRosterNow(owner);assertEquals("unavailable",firstCharacter().get("class_name"));assertEquals(array(obj("id","unknown","key","injury","name","unknown")),firstCharacter().get("traits"));
        Cache.editableTraits=List.of();when(c.getMMOClass()).thenReturn(" ");when(unknown.getId()).thenReturn(" ");RosterSyncService.pushRosterNow(owner);assertFalse(((JSONObject)((JSONArray)pushes.getLast().get("characters")).getFirst()).containsKey("class_name"));
    }

    @Test void diskGuardRefusesPartialAndUnreadableRostersWithoutNetworkMutation() throws Exception {
        online(mockData());Files.createDirectories(characterFolder);Files.writeString(characterFolder.resolve("saved.json"),"preserved");RosterSyncService.pushRosterNow(owner);assertTrue(pushes.isEmpty());verify(logger).warning(contains("disk 1 file(s)"));
        removeOwnedTree(characterFolder);Files.writeString(characterFolder,"not a directory");RosterSyncService.pushRosterNow(owner);verify(logger).warning(contains("disk unreadable"));RPCharacters.plugin=null;RosterSyncService.pushRosterNow(owner);assertTrue(pushes.isEmpty());
    }

    @Test void offlineAccountLoadsFromDiskAndCorruptAccountIsNeverPushed() throws Exception {
        Files.writeString(playerFile,"{broken");RosterSyncService.pushRosterNow(owner);assertTrue(pushes.isEmpty());Files.writeString(playerFile,obj("completed stages",array(),"eighteen",false).toJSONString());
        api.when(() -> ProvinceSystemClient.pushRoster(anyString())).thenReturn(ProvinceSystemClient.SimpleResult.fail("unavailable"));RosterSyncService.pushRosterNow(owner);verify(logger).warning(contains("push failed"));
    }

    private JSONObject firstCharacter(){return (JSONObject)((JSONArray)pushes.getLast().get("characters")).getFirst();}
    private ExperienceModifier xp(String profession,String alias,int amount){var m=mock(ExperienceModifier.class);when(m.getProfession()).thenReturn(profession);when(m.getAlias()).thenReturn(alias);when(m.getModifier()).thenReturn(amount);return m;}
}

abstract class IngestFixture {
    RuntimeTestState state; UUID owner; Player player; Race race; RPCharacters plugin; Logger logger; BukkitScheduler scheduler;
    Path playerFile,characterFolder; final List<Runnable> workers=new ArrayList<>();final List<JSONObject> pushes=new ArrayList<>();final List<JSONObject> acknowledgments=new ArrayList<>();
    MockedStatic<Bukkit> bukkit;MockedStatic<ProvinceSystemClient> api;MockedStatic<PlayerManager> players;MockedStatic<KitLoader> kits;MockedStatic<KitService> kitService;MockedStatic<CharacterSlotService> slots;MockedStatic<PermissionGroupService> permissions;MockedStatic<MailRecipientDirectory> mail;MockedStatic<CharacterPlaytimeDirectory> playtime;
    @BeforeEach void setupIngest() throws Exception {
        MockBukkit.mock();state=new RuntimeTestState(RPCharacters.class,RaceLoader.class,TraitLoader.class,StageLoader.class);
        Cache.attributes=new ArrayList<>();Cache.professions=new ArrayList<>();Cache.backgroundTraitTypes=new ArrayList<>();Cache.maxClues=50;Cache.clueMinLength=1;Cache.clueMaxLength=200;Cache.calendarYearOffset=0;RaceLoader.oList=new ArrayList<>();TraitLoader.oList=new ArrayList<>();StageLoader.oList=new ArrayList<>();
        plugin=mock(RPCharacters.class);logger=mock(Logger.class);when(plugin.getLogger()).thenReturn(logger);RPCharacters.plugin=plugin;owner=UUID.randomUUID();player=mock(Player.class);when(player.getUniqueId()).thenReturn(owner);when(player.getName()).thenReturn("IngestTest"+owner.toString().substring(0,8));
        bukkit=mockStatic(Bukkit.class,CALLS_REAL_METHODS);bukkit.when(() -> Bukkit.getPlayer(owner)).thenReturn(null);scheduler=mock(BukkitScheduler.class);bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTaskAsynchronously(any(org.bukkit.plugin.Plugin.class),any(Runnable.class))).thenAnswer(call->{synchronized(workers){workers.add(call.getArgument(1));}return mock(BukkitTask.class);});when(scheduler.runTask(any(org.bukkit.plugin.Plugin.class),any(Runnable.class))).thenAnswer(call->{((Runnable)call.getArgument(1)).run();return mock(BukkitTask.class);});
        api=mockStatic(ProvinceSystemClient.class);api.when(() -> ProvinceSystemClient.parsePendingCreates(anyString())).thenCallRealMethod();api.when(ProvinceSystemClient::fetchPendingCreates).thenReturn(ProvinceSystemClient.SimpleResult.success("{}"));api.when(() -> ProvinceSystemClient.pushRoster(anyString())).thenAnswer(call->{pushes.add(parse(call.getArgument(0)));return ProvinceSystemClient.SimpleResult.success("{}");});api.when(() -> ProvinceSystemClient.ackCreates(anyString())).thenAnswer(call->{acknowledgments.add(parse(call.getArgument(0)));return ProvinceSystemClient.SimpleResult.success("{}");});
        players=mockStatic(PlayerManager.class);kits=mockStatic(KitLoader.class);kits.when(KitLoader::getKits).thenReturn(Map.of());kitService=mockStatic(KitService.class);slots=mockStatic(CharacterSlotService.class);slots.when(CharacterSlotService::getHardSlotCap).thenReturn(10);slots.when(() -> CharacterSlotService.hasFreeSlot(any(Player.class),any(PlayerData.class))).thenReturn(true);permissions=mockStatic(PermissionGroupService.class);mail=mockStatic(MailRecipientDirectory.class);playtime=mockStatic(CharacterPlaytimeDirectory.class);
        race=mock(Race.class);RaceData data=mock(RaceData.class);when(race.getId()).thenReturn("human");when(race.getName()).thenReturn("Human");when(race.getRaceData()).thenReturn(data);when(data.getAttributeData()).thenReturn(new AttributeData());RaceLoader.oList.add(race);
        playerFile=Path.of("plugins/RPCharacters/data/playerdata",owner+".json");characterFolder=Path.of("plugins/RPCharacters/data/characterdata",owner.toString());Files.createDirectories(playerFile.getParent());Files.createDirectories(characterFolder.getParent());
    }
    @AfterEach void cleanupIngest() throws Exception {playtime.close();mail.close();permissions.close();slots.close();kitService.close();kits.close();players.close();api.close();bukkit.close();MockBukkit.unmock();state.close();Files.deleteIfExists(playerFile);removeOwnedTree(characterFolder);Files.deleteIfExists(Path.of("plugins/RPCharacters/logs/i",player.getName()+".txt"));}
    PlayerData mockData(){var pd=mock(PlayerData.class);when(pd.getUniqueId()).thenReturn(owner);List<RPCharacter> characters=new ArrayList<>();when(pd.getCharacters()).thenReturn(characters);doAnswer(call->{characters.add(call.getArgument(0));return null;}).when(pd).addCharacter(any(RPCharacter.class));when(pd.getCompletedStages()).thenReturn(List.of());return pd;}
    void online(PlayerData pd){bukkit.when(() -> Bukkit.getPlayer(owner)).thenReturn(player);players.when(() -> PlayerManager.exists(player)).thenReturn(true);players.when(() -> PlayerManager.get(player)).thenReturn(pd);}
    RPCharacter character(String id){var c=mock(RPCharacter.class);when(c.getId()).thenReturn(id);when(c.getKitStatuses()).thenReturn(Map.of());when(c.getTraits()).thenReturn(List.of());when(c.getPlayerClues()).thenReturn(List.of());return c;}
    Trait trait(String id,String key){var t=mock(Trait.class);var data=mock(TraitData.class);when(t.getId()).thenReturn(id);when(t.getName()).thenReturn(id);when(t.getDesc()).thenReturn(List.of());when(t.getTraitData()).thenReturn(data);when(data.getKey()).thenReturn(key);when(data.getAttributeData()).thenReturn(new AttributeData());TraitLoader.oList.add(t);return t;}
    void runWorkers(){while(!workers.isEmpty())workers.removeFirst().run();}
    void pending(JSONObject...rows){api.when(ProvinceSystemClient::fetchPendingCreates).thenReturn(ProvinceSystemClient.SimpleResult.success(obj("creates",array((Object[])rows)).toJSONString()));}
    JSONObject row(String id,JSONObject payload){return obj("id",id,"player_uuid",owner.toString(),"payload",payload);}
    JSONObject payload(){return obj("name"," Aria ","race_id","human","class_id","warrior","age",24,"gender"," woman ","description"," Traveller ");}
    JSONObject result(int index){return (JSONObject)((JSONArray)acknowledgments.getLast().get("results")).get(index);}
    static JSONObject parse(String body) throws Exception{return (JSONObject)new JSONParser().parse(body);}
    @SuppressWarnings("unchecked") static JSONObject obj(Object...pairs){var o=new JSONObject();for(int i=0;i<pairs.length;i+=2)o.put(pairs[i],pairs[i+1]);return o;}
    @SuppressWarnings("unchecked") static JSONArray array(Object...values){var a=new JSONArray();Collections.addAll(a,values);return a;}
    static void removeOwnedTree(Path root) throws IOException{if(Files.exists(root)){try(var paths=Files.walk(root)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}}
}
