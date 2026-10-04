package net.tfminecraft.rpcharacters.mmocore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;
import io.lumine.mythic.lib.skill.handler.SkillHandler;
import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.api.player.profess.*;
import net.Indyuce.mmocore.api.player.attribute.*;
import net.Indyuce.mmocore.manager.*;
import net.Indyuce.mmocore.manager.data.PlayerDataManager;
import net.Indyuce.mmocore.skill.ClassSkill;
import net.Indyuce.mmocore.experience.droptable.ExperienceTable;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.utils.RPTexts;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.*;

class ClassServiceTest extends MmoServiceFixture {
    MockedStatic<AttributePointService> attributePoints;
    @BeforeEach void setupClassService(){attributePoints=boundary(AttributePointService.class);}

    @Test void publicGuardsAndUnknownPlayersDoNotChangeProgressOrPools(){
        ClassService.trackFromPlayer(null);ClassService.migrateSkillPointsIfNeeded(null);ClassService.grantSkillPoints(null,1);ClassService.grantSkillPoints(player,0);ClassService.grantSkillPoints(player,-1);ClassService.applyFreeSkillPoints(null);ClassService.syncSkillPoints(null);ClassService.scheduleSyncSkillPoints(null);ClassService.restoreAccountProgression(null);ClassService.sanitizeForeignSkillLevels((Player)null);assertTrue(ClassService.applyClass(null,"mage"));assertTrue(ClassService.applyClass(player,null));assertTrue(ClassService.applyClass(player," "));assertFalse(ClassService.isOnClass(null,"mage"));assertFalse(ClassService.isOnClass(player,null));assertFalse(ClassService.isOnClass(player," "));assertFalse(ClassService.isOnClass(player,"missing"));ClassService.restoreAccountProgression(player);assertEquals(5,level.get());assertEquals(30.0,experience.get());
        players.when(() -> PlayerManager.get(player)).thenReturn(null);ClassService.migrateSkillPointsIfNeeded(player);ClassService.grantSkillPoints(player,3);ClassService.applyFreeSkillPoints(player);ClassService.syncSkillPoints(player);verify(manager,never()).savePlayer(any());assertTrue(later.isEmpty());
    }

    @Test void migrationGrantAndFreePoolReflectLifetimeAndCurrentSpend(){
        skillLevels.put("known",3);skillPoints.set(4);ClassService.migrateSkillPointsIfNeeded(player);verify(manager,never()).savePlayer(any());when(account.needsSkillPointsMigration()).thenReturn(true);ClassService.migrateSkillPointsIfNeeded(player);assertEquals(6,accountSkills.get());verify(manager).savePlayer(player);ClassService.grantSkillPoints(player,3);assertEquals(9,accountSkills.get());assertEquals(7,skillPoints.get());accountSkills.set(1);ClassService.applyFreeSkillPoints(player);assertEquals(0,skillPoints.get());
    }

    @Test void migrationCannotWrapLargeSkillPoolsIntoNegativeLifetimePoints(){
        skillPoints.set(Integer.MAX_VALUE);skillLevels.put("known",2);when(account.needsSkillPointsMigration()).thenReturn(true);ClassService.migrateSkillPointsIfNeeded(player);assertEquals(Integer.MAX_VALUE,accountSkills.get(),"Representable lifetime points must saturate rather than wrap negative and erase the account pool");
    }

    @Test void excessiveUnspentSkillPointsAreClampedWithoutOneWritePerPoint(){
        skillPoints.set(Integer.MAX_VALUE);accountSkills.set(2);var writes=new AtomicInteger();doAnswer(call->{assertTrue(writes.incrementAndGet()<=1000,"Clamping a large free pool must not block the server with one setter call per point");skillPoints.set(call.getArgument(0));return null;}).when(mmo).setSkillPoints(anyInt());assertTrue(ClassService.applyClass(player,"warrior"));assertEquals(2,skillPoints.get());assertTrue(writes.get()<10);
    }

    @Test void realAccountSkillGrantSaturatesRatherThanErasingItsBalance(){
        var realAccount=new net.tfminecraft.rpcharacters.objects.PlayerData(owner);realAccount.setAccountSkillPointsTotal(Integer.MAX_VALUE-1);players.when(() -> PlayerManager.get(player)).thenReturn(realAccount);ClassService.grantSkillPoints(player,10);assertEquals(Integer.MAX_VALUE,realAccount.getAccountSkillPointsTotal());assertEquals(Integer.MAX_VALUE,skillPoints.get());realAccount.addAccountSkillPoints(Integer.MAX_VALUE);assertEquals(Integer.MAX_VALUE,realAccount.getAccountSkillPointsTotal());verify(manager).savePlayer(player);
    }

    @Test void syncingSanitizesForeignSkillsReportsBreakdownAndSchedulesOnlineOnly(){
        skillLevels.put("known",3);skillLevels.put("foreign",4);skillLevels.put("base",1);skillLevels.put("null-level",null);skillPoints.set(4);accountSkills.set(2);addSkill("known","Known Skill");addSkill("base","Base Skill");Cache.skillPointsAdminDebugMessages=true;ClassService.syncSkillPoints(player,player);assertEquals(1,skillLevels.get("foreign"));assertEquals(3,skillLevels.get("known"));assertEquals(6,accountSkills.get());assertEquals(4,skillPoints.get());texts.verify(() -> RPTexts.sendPrefixed(eq(player),contains("Known Skill lvl3 (+2)")));texts.verify(() -> RPTexts.sendPrefixed(eq(player),argThat(message->org.bukkit.ChatColor.stripColor(message).contains("Stripped foreign skills: foreign"))));
        clearInvocations(manager);ClassService.syncSkillPoints(player);verify(manager,never()).savePlayer(player);skillLevels.clear();ClassService.syncSkillPoints(player,player);texts.verify(() -> RPTexts.sendPrefixed(eq(player),contains("none")));Cache.skillPointsAdminDebugMessages=false;ClassService.syncSkillPoints(player,player);
        ClassService.scheduleSyncSkillPoints(player);ClassService.scheduleSyncSkillPoints(player,player);assertEquals(2,later.size());when(player.isOnline()).thenReturn(false);later.removeFirst().run();when(player.isOnline()).thenReturn(true);later.removeFirst().run();assertEquals(skillPoints.get(),accountSkills.get());
    }

    @Test void sameClassSanitizesSavedAndLiveMapsClampsExcessAndReappliesAttributes(){
        var target=current.get();var saved=saved(target);savedLevels(saved).put("foreign",8);savedLevels(saved).put("known",2);savedLevels(saved).put("empty",null);savedLevels(saved).put("base",1);skillLevels.put("known",5);skillLevels.put("foreign",9);skillPoints.set(3);accountSkills.set(2);when(account.hasActiveCharacter()).thenReturn(true);
        assertTrue(ClassService.isOnClass(player,"WARRIOR"));assertTrue(ClassService.applyClass(player,"warrior"));assertEquals(1,savedLevels(saved).get("foreign"));assertEquals(2,savedLevels(saved).get("known"));assertEquals(1,skillLevels.get("foreign"));assertEquals(3,skillLevels.get("known"));assertEquals(0,skillPoints.get());attributePoints.verify(() -> AttributePointService.applyFreeAttributePoints(player,character));assertFalse(ClassService.isApplying(owner));
    }

    @Test void changedClassRestoresTrackedProgressAndSuppressesReentrantRestoration(){
        var mage=playerClass("mage",1);ClassService.trackFromPlayer(player);level.set(1);experience.set(0.0);var info=saved(mage);doAnswer(c->{assertTrue(ClassService.isApplying(owner));ClassService.restoreAccountProgression(player);assertEquals(1,level.get());current.set(mage);level.set(99);experience.set(99.0);return null;}).when(info).load(mage,mmo);
        assertTrue(ClassService.applyClass(player,"mage"));assertSame(mage,current.get());assertEquals(5,level.get());assertEquals(30.0,experience.get());assertFalse(ClassService.isApplying(owner));level.set(2);experience.set(1.0);ClassService.restoreAccountProgression(player);assertEquals(5,level.get());assertEquals(30.0,experience.get());
    }

    @Test void firstVisitUsesDefaultClassInformationAndCurrentProgression(){
        var mage=playerClass("mage",1);try(var constructed=mockConstruction(SavedClassInformation.class,(info,context)->{when(info.mapSkillLevels()).thenReturn(new HashMap<>());when(info.mapAttributeLevels()).thenReturn(new HashMap<>());doAnswer(c->{assertTrue(ClassService.isApplying(owner));current.set(mage);return null;}).when(info).load(mage,mmo);})){assertTrue(ClassService.applyClass(player,"mage"));assertEquals(1,constructed.constructed().size());assertEquals(5,level.get());assertEquals(30.0,experience.get());}
        players.when(() -> PlayerManager.get(player)).thenReturn(null);assertTrue(ClassService.applyClass(player,"mage"));
    }

    @Test void newSubclassKeepsCreationAttributesAndSpentAndFreePoints() {
        var strength = instance("strength", 7, 3);
        var dexterity = instance("dexterity", 2, 2);
        var zero = instance("intelligence", 0, 0);
        allocation.set(new HashMap<>(Map.of("strength", 4)));
        when(account.hasActiveCharacter()).thenReturn(true);
        attributePoints.when(() -> AttributePointService.applyFreeAttributePoints(player, character)).thenCallRealMethod();
        var subclass = playerClass("berserker", 1);
        try (var constructed = mockConstruction(SavedClassInformation.class, (info, context) -> {
            when(info.mapSkillLevels()).thenReturn(new HashMap<>());
            when(info.mapAttributeLevels()).thenReturn(new HashMap<>(Map.of("strength", 0, "intelligence", 99)));
            doAnswer(call -> {
                current.set(subclass);
                instances.values().forEach(instance -> instance.setBase(0));
                info.mapAttributeLevels().forEach((id, value) -> instances.get(id).setBase(value));
                attributePointsBalance.set(0);
                return null;
            }).when(info).load(subclass, mmo);
        })) {
            assertTrue(ClassService.applyClass(player, "berserker"));
            assertEquals(1, constructed.constructed().size());
        }
        assertEquals(7, strength.getBase());
        assertEquals(2, dexterity.getBase());
        assertEquals(0, zero.getBase());
        assertEquals(Map.of("strength", 4), allocation.get());
        assertEquals(6, attributePointsBalance.get());
        assertEquals(10, accountAttributes.get());
        assertEquals(5, level.get());
        assertEquals(30.0, experience.get());
    }

    @Test void returningClassUsesCurrentAttributesInsteadOfItsStaleAllocation() {
        saved(current.get());
        var strength = instance("strength", 8, 3);
        var dexterity = instance("dexterity", 0, 0);
        var target = playerClass("mage", 1);
        var info = saved(target);
        info.mapAttributeLevels().putAll(Map.of("strength", 1, "dexterity", 9));
        doAnswer(call -> {
            current.set(target);
            info.mapAttributeLevels().forEach((id, value) -> instances.get(id).setBase(value));
            return null;
        }).when(info).load(target, mmo);
        assertTrue(ClassService.applyClass(player, "mage"));
        assertEquals(8, strength.getBase());
        assertEquals(0, dexterity.getBase());
        strength.setBase(10);
        assertTrue(ClassService.applyClass(player, "mage"));
        assertEquals(10, strength.getBase(), "Reapplying the same class must not stack or reset attributes");
        assertTrue(ClassService.applyClass(player, "warrior"));
        assertTrue(ClassService.applyClass(player, "mage"));
        assertEquals(10, strength.getBase(), "Repeated changes must keep the latest character allocation");
    }

    @Test void appliedClassesClaimExpTableRewardsMissedBelowTheSharedLevel(){
        var mage=playerClass("mage",1);var table=mock(ExperienceTable.class);when(mage.hasExperienceTable()).thenReturn(true);when(mage.getExperienceTable()).thenReturn(table);saved(mage);
        doAnswer(c->{skillPoints.addAndGet(2);return null;}).when(table).claim(mmo,5,mage);
        assertTrue(ClassService.applyClass(player,"mage"));for(int claimed=1;claimed<=5;claimed++){int at=claimed;verify(table).claim(mmo,at,mage);}verify(table,never()).claim(mmo,6,mage);assertEquals(accountSkills.get(),skillPoints.get(),"Points a replayed trigger gives must not grow the account pool");
        clearInvocations(table);assertTrue(ClassService.applyClass(player,"mage"));verify(table,times(5)).claim(eq(mmo),anyInt(),eq(mage));
    }

    @Test void unstableMmoDataAndConcurrentModificationFailSoftAndReleaseApplyingFlag(){
        ready.when(() -> MmoCorePlayerReady.isReady(player)).thenReturn(false);assertFalse(ClassService.applyClass(player,"warrior"));ready.when(() -> MmoCorePlayerReady.isReady(player)).thenReturn(true);assertTrue(ClassService.applyClass(player,"missing"));var mage=playerClass("mage",1);var info=saved(mage);doThrow(new ConcurrentModificationException("changed")).when(info).load(mage,mmo);assertFalse(ClassService.applyClass(player,"mage"));assertFalse(ClassService.isApplying(owner));verify(logger).log(eq(Level.WARNING),contains("attribute data changed"),any(ConcurrentModificationException.class));RPCharacters.plugin=null;assertFalse(ClassService.applyClass(player,"mage"));assertFalse(ClassService.isApplying(owner));
    }

    @Test void inconsistentSpentCounterWithoutDowngradeableSkillsTerminatesSafely(){
        when(mmo.countSkillPointsSpent()).thenReturn(2);accountSkills.set(0);skillPoints.set(0);skillLevels.put("base",1);skillLevels.put("unknown",null);assertTrue(ClassService.applyClass(player,"warrior"));assertEquals(1,skillLevels.get("base"));assertEquals(0,skillPoints.get());ClassService.sanitizeForeignSkillLevels(player);
    }

    @Test void classDisplayAndLoreReadRealYamlAndKeepTextFormatting() throws Exception {
        var hidden=playerClass("hidden",0);var visible=playerClass("visible",1);write("classes/hidden.yml","options:\n  display: false\n");write("classes/visible.yml","name: Visible\n");assertFalse(MmoCoreClassGuiHelper.isClassDisplayed(hidden));assertTrue(MmoCoreClassGuiHelper.isClassDisplayed(visible));assertTrue(MmoCoreClassGuiHelper.isClassDisplayed(current.get()));when(visible.getDescription()).thenReturn(Arrays.asList("&aLore",null));when(visible.getAttributeDescription()).thenReturn(List.of("&bStrength"));texts.when(RPTexts::spacer).thenReturn(" ");assertEquals(Arrays.asList("§aLore",""," ","§bStrength"),MmoCoreClassGuiHelper.buildClassLore(visible));when(visible.getDescription()).thenReturn(null);when(visible.getAttributeDescription()).thenReturn(null);assertTrue(MmoCoreClassGuiHelper.buildClassLore(visible).isEmpty());
    }

    @Test void classSlotsReadBothMmoYamlShapesCacheAndExplicitLocaleIndependentOverrides() throws Exception {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));var mage=playerClass("mage",-1);var hidden=playerClass("hidden",2);write("classes/hidden.yml","options:\n  display: false\n");write("gui/class-select.yml","ignored: scalar\nbutton:\n  function: not-a-class\nempty:\n  function: class-empty\nfirst:\n  function: class-MAGE\n  slots: [12, 13]\n");MmoCoreClassGuiHelper.invalidateSlotCache();var first=MmoCoreClassGuiHelper.buildClassOptions(54);assertEquals(List.of("mage","warrior"),first.getOptions().stream().map(SelectableItem::getId).toList());assertEquals(List.of(12,10),first.getSlots());
        write("gui/class-select.yml","items:\n  first:\n    function: class-MAGE\n    slots: [14]\n");assertEquals(12,MmoCoreClassGuiHelper.buildClassOptions(54).getSlots().getFirst());MmoCoreClassGuiHelper.invalidateSlotCache();assertEquals(14,MmoCoreClassGuiHelper.buildClassOptions(54).getSlots().getFirst());Map<String,Integer> overrides=new HashMap<>();overrides.put(" MAGE ",15);overrides.put(null,13);overrides.put("bad",null);var overridden=MmoCoreClassGuiHelper.buildClassOptions(54,overrides);assertEquals(List.of(15,10),overridden.getSlots());verify(logger).warning(contains("stages.yml class-slots"));
        Files.delete(folder.resolve("gui/class-select.yml"));MmoCoreClassGuiHelper.invalidateSlotCache();assertEquals(List.of(10,11),MmoCoreClassGuiHelper.buildClassOptions(54,Map.of()).getSlots());
    }

    @Test void insufficientGuiCapacityKeepsOptionsAndSlotsAligned(){
        for(int index=0;index<35;index++)playerClass("class"+index,index+1);var result=MmoCoreClassGuiHelper.buildClassOptions(27,Map.of("warrior",10));assertEquals(result.getOptions().size(),result.getSlots().size(),"An option without a slot shifts or hides the corresponding class");assertEquals(result.getSlots().size(),new HashSet<>(result.getSlots()).size());assertTrue(result.getSlots().stream().allMatch(slot->slot>=0&&slot<27));
    }

    @Test void explicitInvalidAndDuplicateSlotsNeverOverwriteAnotherClass(){
        playerClass("mage",1);playerClass("rogue",2);playerClass("cleric",3);var result=MmoCoreClassGuiHelper.buildClassOptions(54,Map.of("warrior",10,"mage",10,"rogue",-1,"cleric",100));assertEquals(4,result.getOptions().size());assertEquals(4,new HashSet<>(result.getSlots()).size(),"Every selectable class needs its own visible slot");assertTrue(result.getSlots().stream().allMatch(slot->slot>=0&&slot<54),"Configured slots must be within the inventory");
    }

    @Test void fallbackCannotConsumeASlotExplicitlyReservedForALaterClass(){
        playerClass("mage",2);var result=MmoCoreClassGuiHelper.buildClassOptions(54,Map.of("mage",10));assertEquals(10,result.getSlots().get(1));assertNotEquals(result.getSlots().getFirst(),result.getSlots().get(1),"Earlier fallback must respect later configured positions");
    }
}

abstract class MmoServiceFixture {
    @TempDir Path folder;RuntimeTestState state;MMOCore oldMmo;RPCharacters plugin;Logger logger;Player player;UUID owner;net.Indyuce.mmocore.api.player.PlayerData mmo;net.tfminecraft.rpcharacters.objects.PlayerData account;RPCharacter character;PlayerManager manager;ClassManager classes;AttributeManager attributeManager;PlayerAttributes mmoAttributes;
    final AtomicInteger level=new AtomicInteger(5),skillPoints=new AtomicInteger(),attributePointsBalance=new AtomicInteger(),accountSkills=new AtomicInteger(10),accountAttributes=new AtomicInteger(10);final AtomicReference<Double> experience=new AtomicReference<>(30.0);final AtomicReference<PlayerClass> current=new AtomicReference<>();final Map<String,Integer> skillLevels=new LinkedHashMap<>();final Map<String,PlayerClass> classMap=new LinkedHashMap<>();final Map<PlayerClass,SavedClassInformation> saved=new HashMap<>();final Map<SavedClassInformation,Map<String,Integer>> savedMaps=new HashMap<>();final Map<String,PlayerAttributes.AttributeInstance> instances=new LinkedHashMap<>();final AtomicReference<Map<String,Integer>> allocation=new AtomicReference<>(new HashMap<>());final Map<String,Integer> creationBases=new HashMap<>();final List<Runnable> later=new ArrayList<>();final List<AutoCloseable> closeables=new ArrayList<>();
    MockedStatic<PlayerManager> players;MockedStatic<MmoCorePlayerReady> ready;MockedStatic<RPTexts> texts;
    @BeforeEach void setupMmo() throws Exception {
        MockBukkit.mock();state=new RuntimeTestState(RPCharacters.class,ClassService.class,MmoCoreClassGuiHelper.class);Cache.attributes=new ArrayList<>();Cache.professions=new ArrayList<>();Cache.ignoredAttributes=new HashSet<>();MmoCoreClassGuiHelper.invalidateSlotCache();oldMmo=MMOCore.plugin;MMOCore.plugin=mock(MMOCore.class);when(MMOCore.plugin.getDataFolder()).thenReturn(folder.toFile());classes=mock(ClassManager.class);attributeManager=mock(AttributeManager.class);field("classManager",classes);field("attributeManager",attributeManager);var dataManager=mock(PlayerDataManager.class);field("playerDataManager",dataManager);
        plugin=mock(RPCharacters.class);logger=mock(Logger.class);when(plugin.getLogger()).thenReturn(logger);RPCharacters.plugin=plugin;owner=UUID.randomUUID();player=mock(Player.class);when(player.getUniqueId()).thenReturn(owner);when(player.getName()).thenReturn("MmoTest");when(player.isOnline()).thenReturn(true);mmo=mock(net.Indyuce.mmocore.api.player.PlayerData.class);account=mock(net.tfminecraft.rpcharacters.objects.PlayerData.class);character=mock(RPCharacter.class);manager=mock(PlayerManager.class);
        when(account.getAccountSkillPointsTotal()).thenAnswer(c->accountSkills.get());doAnswer(c->{accountSkills.set(c.getArgument(0));return null;}).when(account).setAccountSkillPointsTotal(anyInt());doAnswer(c->{accountSkills.addAndGet(c.getArgument(0));return null;}).when(account).addAccountSkillPoints(anyInt());when(account.getAccountAttributePointsTotal()).thenAnswer(c->accountAttributes.get());doAnswer(c->{accountAttributes.set(c.getArgument(0));return null;}).when(account).setAccountAttributePointsTotal(anyInt());doAnswer(c->{accountAttributes.addAndGet(c.getArgument(0));return null;}).when(account).addAccountAttributePoints(anyInt());when(account.getActiveCharacter()).thenReturn(character);
        when(mmo.getLevel()).thenAnswer(c->level.get());doAnswer(c->{level.set(c.getArgument(0));return null;}).when(mmo).setLevel(anyInt());when(mmo.getExperience()).thenAnswer(c->experience.get());doAnswer(c->{experience.set(c.getArgument(0));return null;}).when(mmo).setExperience(anyDouble());when(mmo.getSkillPoints()).thenAnswer(c->skillPoints.get());doAnswer(c->{skillPoints.set(c.getArgument(0));return null;}).when(mmo).setSkillPoints(anyInt());when(mmo.getAttributePoints()).thenAnswer(c->attributePointsBalance.get());doAnswer(c->{attributePointsBalance.set(c.getArgument(0));return null;}).when(mmo).setAttributePoints(anyInt());when(mmo.mapSkillLevels()).thenReturn(skillLevels);when(mmo.countSkillPointsSpent()).thenAnswer(c->skillLevels.values().stream().filter(Objects::nonNull).mapToInt(n->Math.max(0,n-1)).sum());doAnswer(c->{skillLevels.put(c.getArgument(0),c.getArgument(1));return null;}).when(mmo).setSkillLevel(anyString(),anyInt());when(mmo.getProfess()).thenAnswer(c->current.get());when(mmo.hasSavedClass(any(PlayerClass.class))).thenAnswer(c->saved.containsKey(c.getArgument(0)));when(mmo.getClassInfo(any(PlayerClass.class))).thenAnswer(c->saved.get(c.getArgument(0)));
        when(classes.get(anyString())).thenAnswer(c->classMap.get(c.<String>getArgument(0).toLowerCase(Locale.ROOT)));when(classes.getAll()).thenAnswer(c->classMap.values());current.set(playerClass("warrior",0));when(character.getOwner()).thenReturn(player);when(character.getExtraAttributeAllocation()).thenAnswer(c->allocation.get());doAnswer(c->{allocation.set(new HashMap<>(c.<Map<String,Integer>>getArgument(0)));return null;}).when(character).setExtraAttributeAllocation(anyMap());when(character.getSpentExtraAttributePoints()).thenAnswer(c->allocation.get().values().stream().filter(Objects::nonNull).mapToInt(Integer::intValue).sum());when(character.getCreationBaseAmount(anyString())).thenAnswer(c->creationBases.getOrDefault(c.<String>getArgument(0).toLowerCase(Locale.ROOT),0));
        mmoAttributes=mock(PlayerAttributes.class);when(mmo.getAttributes()).thenReturn(mmoAttributes);when(mmoAttributes.getInstances()).thenAnswer(c->instances.values());when(mmoAttributes.getInstance(anyString())).thenAnswer(c->instances.get(c.<String>getArgument(0).toLowerCase(Locale.ROOT)));boundary(net.Indyuce.mmocore.api.player.PlayerData.class).when(() -> net.Indyuce.mmocore.api.player.PlayerData.get(player)).thenReturn(mmo);players=boundary(PlayerManager.class);players.when(() -> PlayerManager.get(player)).thenReturn(account);boundary(RPCharacters.class).when(RPCharacters::getPlayerManager).thenReturn(manager);ready=boundary(MmoCorePlayerReady.class);ready.when(() -> MmoCorePlayerReady.isReady(player)).thenReturn(true);texts=boundary(RPTexts.class);var scheduler=mock(BukkitScheduler.class);boundary(Bukkit.class).when(Bukkit::getScheduler).thenReturn(scheduler);when(scheduler.runTaskLater(any(Plugin.class),any(Runnable.class),eq(1L))).thenAnswer(c->{later.add(c.getArgument(1));return mock(BukkitTask.class);});
    }
    @AfterEach void cleanupMmo() throws Exception {for(int i=closeables.size()-1;i>=0;i--)closeables.get(i).close();MMOCore.plugin=oldMmo;state.close();MockBukkit.unmock();}
    <T>MockedStatic<T> boundary(Class<T> type){var mocked=mockStatic(type);closeables.add(mocked);return mocked;}
    void field(String name,Object value)throws Exception{Field f=MMOCore.class.getField(name);f.setAccessible(true);f.set(MMOCore.plugin,value);}
    PlayerClass playerClass(String id,int order){var result=mock(PlayerClass.class);when(result.getId()).thenReturn(id);when(result.getName()).thenReturn(id);when(result.getDisplayOrder()).thenReturn(order);when(result.hasSkill(anyString())).thenAnswer(c->c.<String>getArgument(0).equals("known")||c.<String>getArgument(0).equals("base"));when(result.getSkills()).thenReturn(new ArrayList<>());classMap.put(id.toLowerCase(Locale.ROOT),result);return result;}
    SavedClassInformation saved(PlayerClass target){var info=mock(SavedClassInformation.class);var map=new HashMap<String,Integer>();savedMaps.put(info,map);when(info.mapSkillLevels()).thenReturn(map);when(info.mapAttributeLevels()).thenReturn(new HashMap<>());doAnswer(c->{map.put(c.getArgument(0),c.getArgument(1));return null;}).when(info).registerSkillLevel(anyString(),anyInt());doAnswer(c->{current.set(target);return null;}).when(info).load(target,mmo);saved.put(target,info);return info;}
    Map<String,Integer> savedLevels(SavedClassInformation info){return savedMaps.get(info);}
    void addSkill(String id,String name){var classSkill=mock(ClassSkill.class);SkillHandler<?> handler=mock(SkillHandler.class);when(handler.getName()).thenReturn(name);doReturn(handler).when(classSkill).getSkill();current.get().getSkills().add(classSkill);when(mmo.getSkillLevel(handler)).thenAnswer(c->skillLevels.getOrDefault(id,1));}
    PlayerAttributes.AttributeInstance instance(String id,int amount,int base){var instance=mock(PlayerAttributes.AttributeInstance.class);var value=new AtomicInteger(amount);when(instance.getId()).thenReturn(id);when(instance.getBase()).thenAnswer(c->value.get());doAnswer(c->{value.set(c.getArgument(0));return null;}).when(instance).setBase(anyInt());instances.put(id.toLowerCase(Locale.ROOT),instance);creationBases.put(id.toLowerCase(Locale.ROOT),base);return instance;}
    void write(String relative,String content)throws Exception{Path path=folder.resolve(relative);Files.createDirectories(path.getParent());Files.writeString(path,content);}
}
