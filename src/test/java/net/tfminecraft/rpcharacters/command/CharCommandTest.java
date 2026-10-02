package net.tfminecraft.rpcharacters.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.*;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.calendar.BirthdayValidator;
import net.tfminecraft.rpcharacters.identity.*;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.persona.*;
import net.tfminecraft.rpcharacters.profile.ProfileManager;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;

class CharCommandTest {
    RuntimeTestState state; Player player; CommandSender console; PlayerData data;
    RPCharacter character; PlayerManager manager; PersonaCooldownManager cooldown;
    final List<AutoCloseable> boundaries=new ArrayList<>();
    MockedStatic<PlayerManager> players; MockedStatic<PermissionGroupService> groups;
    MockedStatic<BirthdayValidator> birthdays; MockedStatic<ProfileManager> profiles;
    MockedStatic<TempAliasService> aliases;

    @BeforeEach void setup() {
        MockBukkit.mock(); state=new RuntimeTestState(RPCharacters.class);
        Cache.attributes=new ArrayList<>(); Cache.professions=new ArrayList<>();
        Cache.personaGenders=List.of("Woman","Man","Other");
        Cache.personaDisplayNameMinLength=3; Cache.personaDisplayNameMaxLength=24;
        Cache.personaAliasAllowedChars="abcdefghijklmnopqrstuvwxyz '-";
        Cache.characterDescriptionMinLength=3; Cache.characterDescriptionMaxLength=80;
        Cache.personaAliasCooldownSeconds=10; Cache.personaGenderCooldownSeconds=20; Cache.personaDescriptionCooldownSeconds=30;
        player=mock(Player.class); when(player.getName()).thenReturn("AriaOwner"); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.hasPermission(Cache.personaSetPermission)).thenReturn(true);
        when(player.hasPermission(Cache.profilePermission)).thenReturn(true);
        console=mock(CommandSender.class); when(console.hasPermission(Cache.personaOverridePermission)).thenReturn(true);
        character=new RPCharacter(player); character.setName("Aria");
        data=spy(new PlayerData(player)); doReturn(true).when(data).hasActiveCharacter(); doReturn(character).when(data).getActiveCharacter();
        manager=mock(PlayerManager.class); cooldown=mock(PersonaCooldownManager.class);
        players=boundary(PlayerManager.class); players.when(() -> PlayerManager.get(player)).thenReturn(data);
        var rpc=boundary(RPCharacters.class); rpc.when(RPCharacters::getPlayerManager).thenReturn(manager);
        var bukkit=boundary(Bukkit.class); bukkit.when(() -> Bukkit.getPlayerExact("AriaOwner")).thenReturn(player);
        var cooldowns=boundary(PersonaCooldownManager.class); cooldowns.when(PersonaCooldownManager::get).thenReturn(cooldown);
        groups=boundary(PermissionGroupService.class); groups.when(() -> PermissionGroupService.canUseNameColour(player)).thenReturn(true);
        groups.when(() -> PermissionGroupService.validateNameColourHexes(eq(player),anyList(),anyBoolean())).thenReturn(Optional.empty());
        birthdays=boundary(BirthdayValidator.class); profiles=boundary(ProfileManager.class); aliases=boundary(TempAliasService.class);
    }
    @AfterEach void teardown() throws Exception {for(int i=boundaries.size()-1;i>=0;i--)boundaries.get(i).close(); MockBukkit.unmock(); state.close();}
    <T> MockedStatic<T> boundary(Class<T> type) {var mock=mockStatic(type); boundaries.add(mock); return mock;}
    void run(String... args) {assertTrue(CharCommand.handle(player,"char",args));}
    void override(String field,String... values) {var args=new ArrayList<>(List.of("override","AriaOwner",field)); args.addAll(Arrays.asList(values)); assertTrue(CharCommand.handle(console,"char",args.toArray(String[]::new)));}
    void message(CommandSender sender,String text) {verify(sender,atLeastOnce()).sendMessage(contains(text));}

    @Test void dispatchRecognizesPersonaNamesAndRejectsConsoleOrUnknownCommands() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR")); assertTrue(CharCommand.isPersonaSubcommand("BIRTHDAY")); assertFalse(CharCommand.isPersonaSubcommand(null)); assertFalse(CharCommand.isPersonaSubcommand("unknown"));
        assertTrue(CharCommand.handle(console,"rpcharacter",new String[0])); message(console,"Usage: /rpcharacter");
        for(String sub:List.of("profile","alias")) assertTrue(CharCommand.handle(console,"char",new String[]{sub}));
        message(console,"Only players"); run("unknown"); message(player,"Unknown subcommand"); verifyNoInteractions(manager);
    }

    @Test void permissionsAndMissingActiveCharactersNeverMutateOrSave() {
        when(player.hasPermission(Cache.personaSetPermission)).thenReturn(false); groups.when(() -> PermissionGroupService.canUseNameColour(player)).thenReturn(false);
        for(String sub:List.of("alias","gender","description","birthday","namecolour")) {run(sub,"anything"); message(player,"permission");}
        when(player.hasPermission(Cache.personaSetPermission)).thenReturn(true); groups.when(() -> PermissionGroupService.canUseNameColour(player)).thenReturn(true);
        players.when(() -> PlayerManager.get(player)).thenReturn(null);
        for(String sub:List.of("alias","gender","description","birthday","namecolour","mail")) run(sub,"clear");
        run("mail");
        message(player,"active character"); players.when(() -> PlayerManager.get(player)).thenReturn(data); doReturn(false).when(data).hasActiveCharacter(); run("alias","Alice");
        verifyNoInteractions(manager); assertNull(character.getAlias());
    }

    @Test void aliasValidatesAppliesCooldownAndClearBypassesTheCooldown() {
        run("alias"); message(player,"Usage"); run("alias","!"); message(player,"at least");
        run("alias","Alice","Smith"); assertEquals("Alice Smith",character.getAlias()); verify(cooldown).applyCooldown(player,"alias",10); verify(manager).savePlayer(player);
        when(cooldown.isOnCooldown(player,"alias",10)).thenReturn(true); when(cooldown.getRemainingSeconds(player,"alias")).thenReturn(7);
        run("alias","Different"); assertEquals("Alice Smith",character.getAlias()); message(player,"7");
        run("alias","clear"); assertNull(character.getAlias()); verify(manager,times(2)).savePlayer(player);
        when(player.hasPermission(Cache.personaBypassCooldownPermission)).thenReturn(true); run("alias","Other"); assertEquals("Other",character.getAlias()); verify(cooldown,times(1)).applyCooldown(player,"alias",10);
    }

    @Test void genderValidatesConfiguredChoicesAndHonorsCooldown() {
        run("gender"); message(player,"Woman|Man|Other"); run("gender","invalid"); message(player,"Invalid gender");
        when(cooldown.isOnCooldown(player,"gender",20)).thenReturn(true); run("gender","woman"); assertNull(character.getGender());
        when(cooldown.isOnCooldown(player,"gender",20)).thenReturn(false); run("gender","wOmAn"); assertEquals("Woman",character.getGender()); verify(cooldown).applyCooldown(player,"gender",20); verify(manager).savePlayer(player);
    }

    @Test void descriptionStripsUnauthorizedColourAndPreservesPermittedColour() {
        run("description"); message(player,"Usage"); run("description","x"); message(player,"at least");
        when(cooldown.isOnCooldown(player,"description",30)).thenReturn(true); run("description","A description"); assertNull(character.getPersonaDescription());
        when(cooldown.isOnCooldown(player,"description",30)).thenReturn(false); run("description","&aGreen","coat"); assertEquals("Green coat",character.getPersonaDescription());
        when(player.hasPermission(Cache.personaDescriptionColorsPermission)).thenReturn(true); run("description","&bBlue coat"); assertEquals("&bBlue coat",character.getPersonaDescription());
        run("description","clear"); assertNull(character.getPersonaDescription()); verify(manager,times(3)).savePlayer(player); verify(cooldown,times(2)).applyCooldown(player,"description",30);
    }

    @Test void nameColoursEnforceEntitlementsParseHexAndResetStaffOverride() {
        run("namecolour"); message(player,"Usage");
        groups.when(() -> PermissionGroupService.validateNameColourHexes(player,List.of("#123456"),false)).thenReturn(Optional.of("gradient denied")); run("namecolour","#123456"); message(player,"gradient denied");
        run("namecolour","invalid"); message(player,"Invalid hex colour"); assertNull(character.getNameColour());
        character.setNameColourStaffOverride(true); run("namecolour","#ABCDEF","123456"); assertEquals(List.of("#abcdef","#123456"),character.getNameColour().getHexCodes()); assertFalse(character.isNameColourStaffOverride());
        run("namecolour","clear"); assertNull(character.getNameColour()); verify(manager,times(2)).savePlayer(player);
    }

    @Test void descriptionPermissionCoversLegacyAmpersandAndHexColourSpellings() {
        assertAll(List.of("&aGreen coat","§aGreen coat","#12ab34Green coat").stream().map(raw -> (org.junit.jupiter.api.function.Executable)() -> {
            run("description",raw); assertEquals("Green coat",character.getPersonaDescription(),raw);
            override("description",raw); assertEquals("Green coat",character.getPersonaDescription(),raw);
        }));
    }

    @Test void birthdayValidatesFormatAndRaceBeforeSavingOrClearing() {
        run("birthday"); message(player,"Usage"); run("birthday","invalid"); message(player,"Invalid birthday");
        birthdays.when(() -> BirthdayValidator.validateForCharacter(character,"0351-01-02")).thenReturn("race age denied"); run("birthday","02.01.351"); message(player,"race age denied"); assertNull(character.getBirthday());
        run("birthday","03.01.351"); assertEquals("0351-01-03",character.getBirthday()); run("birthday","clear"); assertNull(character.getBirthday()); verify(manager,times(2)).savePlayer(player);
    }

    @Test void invalidCalendarDatesReturnValidationInsteadOfThrowing() {
        assertDoesNotThrow(() -> run("birthday","31.02.351")); message(player,"Invalid birthday"); assertNull(character.getBirthday()); verifyNoInteractions(manager);
    }

    @Test void profilesCheckPermissionResolveOnlineTargetAndDelegate() {
        when(player.hasPermission(Cache.profilePermission)).thenReturn(false); run("profile"); message(player,"permission"); profiles.verifyNoInteractions();
        when(player.hasPermission(Cache.profilePermission)).thenReturn(true); run("profile","offline"); message(player,"Player not found"); run("profile"); run("profile","AriaOwner"); profiles.verify(() -> ProfileManager.showProfile(player,player,true),times(2));
    }

    @Test void mailToggleAndExplicitChoicesOnlySaveValidChanges() {
        run("mail","invalid"); run("mail","on","extra"); message(player,"Usage"); verifyNoInteractions(manager);
        run("mail"); assertFalse(character.isMailListed()); run("mail","ON"); assertTrue(character.isMailListed()); run("mail","off"); assertFalse(character.isMailListed()); verify(manager,times(3)).savePlayer(player);
    }

    @Test void staffDispatchChecksPermissionsArgumentsTargetAndActiveCharacter() {
        when(console.hasPermission(Cache.personaOverridePermission)).thenReturn(false); override("alias","Alice"); message(console,"permission");
        when(console.hasPermission(Cache.personaOverridePermission)).thenReturn(true); assertTrue(CharCommand.handle(console,"char",new String[]{"override"})); message(console,"Usage");
        CharCommand.handle(console,"char",new String[]{"override","offline","alias","Alice"}); message(console,"Player not found");
        players.when(() -> PlayerManager.get(player)).thenReturn(null); override("alias","Alice"); message(console,"no active character");
        players.when(() -> PlayerManager.get(player)).thenReturn(data); doReturn(false).when(data).hasActiveCharacter(); override("alias","Alice");
        doReturn(true).when(data).hasActiveCharacter(); override("unknown","Alice"); message(console,"Unknown field"); verifyNoInteractions(manager);
    }

    @Test void staffAliasesValidateAndTemporaryAliasesRemainSeparateFromSavedCharacters() {
        override("alias","!"); message(console,"at least"); override("alias","Alice","Smith"); assertEquals("Alice Smith",character.getAlias()); override("alias","clear"); assertNull(character.getAlias());
        override("tempalias","!"); aliases.verifyNoInteractions();
        aliases.when(() -> TempAliasService.setPlain(player,"Rejected")).thenReturn("temporary alias rejected"); override("tempalias","Rejected"); message(console,"temporary alias rejected");
        aliases.when(() -> TempAliasService.getPlain(player)).thenReturn("Visitor"); override("tempalias","Visitor"); aliases.verify(() -> TempAliasService.setPlain(player,"Visitor")); message(console,"Visitor"); override("tempalias","clear"); aliases.verify(() -> TempAliasService.clear(player)); verify(manager,times(2)).savePlayer(player);
    }

    @Test void staffGenderAndDescriptionRespectValidationAndColourPermission() {
        override("gender","invalid"); assertNull(character.getGender()); override("gender","MAN"); assertEquals("Man",character.getGender());
        override("description","x"); assertNull(character.getPersonaDescription()); override("description","&aGreen coat"); assertEquals("Green coat",character.getPersonaDescription());
        when(console.hasPermission(Cache.personaDescriptionColorsPermission)).thenReturn(true); override("description","&bBlue coat"); assertEquals("&bBlue coat",character.getPersonaDescription()); override("description","clear"); assertNull(character.getPersonaDescription()); verify(manager,times(4)).savePlayer(player);
    }

    @Test void staffNameColoursValidateSetOverrideAndClearBothFields() {
        groups.when(() -> PermissionGroupService.validateNameColourHexes(player,List.of("#123456"),true)).thenReturn(Optional.of("staff gradient denied")); override("namecolour","#123456"); message(console,"staff gradient denied");
        override("namecolour","invalid"); message(console,"Invalid hex colour"); override("namecolour","ABCDEF"); assertEquals(List.of("#abcdef"),character.getNameColour().getHexCodes()); assertTrue(character.isNameColourStaffOverride());
        override("namecolour","clear"); assertNull(character.getNameColour()); assertFalse(character.isNameColourStaffOverride()); verify(manager,times(2)).savePlayer(player);
    }

    @Test void staffBirthdayUsesTheSameFormatAndRaceValidation() {
        override("birthday","invalid"); message(console,"Invalid birthday");
        birthdays.when(() -> BirthdayValidator.validateForCharacter(character,"0351-01-02")).thenReturn("race age denied"); override("birthday","02.01.351"); message(console,"race age denied");
        override("birthday","03.01.351"); assertEquals("0351-01-03",character.getBirthday()); override("birthday","clear"); assertNull(character.getBirthday()); verify(manager,times(2)).savePlayer(player);
    }

    @Test void staffAccountAgeAcceptsHoursSecondsAndClearWithoutChangingCharacterAge() {
        character.setCreatedAtEpochSeconds(100);
        for(var entry:Map.of("2h",7200,"1800s",1800,"1.5",5400,"clear",0).entrySet()) {
            long before=Instant.now().getEpochSecond(); override("playtime",entry.getKey()); long after=Instant.now().getEpochSecond();
            assertTrue(data.getCreatedAtEpochSeconds()>=before-entry.getValue()); assertTrue(data.getCreatedAtEpochSeconds()<=after-entry.getValue()); assertEquals(100,character.getCreatedAtEpochSeconds());
        }
        verify(manager,times(4)).savePlayer(player); override("playtime","garbage"); override("playtime"," "); message(console,"Invalid age"); verifyNoMoreInteractions(manager);
    }

    @Test void invalidAgesNeverChangeAccountCreationTime() {
        assertAll(List.of("-1s","-1h","-1","NaN","Infinity","Infinityh","1e300h","999999999999999h").stream().map(value -> (org.junit.jupiter.api.function.Executable)() -> {
            data.setCreatedAtEpochSeconds(123456789); clearInvocations(manager,console); override("playtime",value);
            assertEquals(123456789,data.getCreatedAtEpochSeconds(),value+" must be rejected"); verifyNoInteractions(manager); message(console,"Invalid age");
        }));
    }
}
