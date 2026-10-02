package net.tfminecraft.rpcharacters.pvp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.evilrp.*;
import net.tfminecraft.rpcharacters.identity.DisplayIdentityService;
import net.tfminecraft.rpcharacters.loaders.PvpLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.objects.trait.Trait;
import net.tfminecraft.rpcharacters.permadeath.*;
import net.tfminecraft.rpcharacters.tutorial.TutorialService;
import net.tfminecraft.rpcharacters.utils.TraitChangeService;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;

class PvpStrikeServiceTest extends PvpRuntimeFixture {
    void knockout(){PvpStartSessions.begin(List.of(victim.getUniqueId()),System.currentTimeMillis(),60_000);PvpStrikeService.handleKnockout(victim,killer);}
    void offlineVerdict(StrikeChoice choice){knockout();online.remove(victim.getUniqueId());assertTrue(PvpStrikeService.choose(killer,victim.getUniqueId(),choice));}
    void join(){online.put(victim.getUniqueId(),victim);PvpStrikeService.handleJoin(victim);runNext();}
    Path verdictFile(){return folder.resolve("pending-strikes.yml");}

    @Test void onlyEligibleKnockoutsAndDeathsOfferDecisions() {
        assertFalse(PvpStrikeService.hasPendingDecision(null));PvpStrikeService.handleJoin(victim);assertTrue(delayed.isEmpty());assertFalse(PvpStrikeService.handleDeath(victim,false));PvpStrikeService.handleKnockout(victim,null);assertFalse(PvpStrikeService.hasPendingDecision(victim));
        PvpStartSessions.begin(List.of(victim.getUniqueId()),System.currentTimeMillis(),60_000);battles.when(()->PermadeathBattleExemption.isInStartedBattle(victim)).thenReturn(true);PvpStrikeService.handleKnockout(victim,killer);assertFalse(PvpStrikeService.hasPendingDecision(victim));battles.when(()->PermadeathBattleExemption.isInStartedBattle(victim)).thenReturn(false);
        loaded.remove(victim);assertFalse(PvpStrikeService.handleDeath(victim,true));loaded.put(victim,data);doReturn(false).when(data).hasActiveCharacter();assertFalse(PvpStrikeService.handleDeath(victim,true));doReturn(true).when(data).hasActiveCharacter();character.setStatus(Status.DEAD);assertFalse(PvpStrikeService.handleDeath(victim,true));character.setStatus(Status.ALIVE);assertTrue(PvpStrikeService.handleDeath(victim,true));assertTrue(PvpStrikeService.hasPendingDecision(victim));
    }

    @Test void promptsExposeOnlyAllowedCommandsAndAccessibleHoverText() {
        evil.when(()->EvilRpService.applyDecay(eq(character),anyLong())).thenReturn(true);knockout();verify(manager).savePlayer(victim);var prompt=components.get(killer).getFirst();assertTrue(PlainTextComponentSerializer.plainText().serialize(prompt).contains("[Strike 1/3]"));assertEquals(Set.of("/rpcharacter spare "+victim.getUniqueId(),"/rpcharacter strike "+victim.getUniqueId()),commands(prompt));assertFalse(PvpStrikeService.choose(other,victim.getUniqueId(),StrikeChoice.SPARE));assertFalse(PvpStrikeService.choose(killer,null,StrikeChoice.SPARE));assertFalse(PvpStrikeService.choose(killer,victim.getUniqueId(),StrikeChoice.WOUND));assertTrue(PvpStrikeService.hasPendingDecision(victim));
        assertTrue(PvpStrikeService.choose(killer,victim.getUniqueId(),StrikeChoice.SPARE));assertFalse(PvpStrikeService.hasPendingDecision(victim));assertTrue(text(victim).contains("spared"));assertTrue(text(killer).contains("spared"));
        character.setEvilRpStrikes(1);knockout();assertTrue(PlainTextComponentSerializer.plainText().serialize(components.get(killer).getLast()).contains("Strike 2/3"));
    }

    @Test void evilAndLastStrikePromptsOfferInjuriesAndKill() {
        evil.when(()->EvilRpService.isInSession(character)).thenReturn(true);knockout();var prompt=components.get(killer).getLast();assertEquals(4,commands(prompt).size());assertTrue(commands(prompt).contains("/rpcharacter wound "+victim.getUniqueId()));assertTrue(commands(prompt).contains("/rpcharacter maim "+victim.getUniqueId()));assertTrue(commands(prompt).contains("/rpcharacter kill "+victim.getUniqueId()));assertTrue(PlainTextComponentSerializer.plainText().serialize(prompt).contains("evil RP session"));
    }

    @Test void normalStrikeTargetsTheOriginalCharacterAndEndsItsEvilSession() {
        character.setEvilRpSessionEndsAtMs(12_345);knockout();var replacement=new RPCharacter(victim);replacement.setName("Other persona");data.addCharacter(replacement);doReturn(replacement).when(data).getActiveCharacter();assertTrue(PvpStrikeService.choose(killer,victim.getUniqueId(),StrikeChoice.STRIKE));assertEquals(1,character.getEvilRpStrikes());assertEquals(0,replacement.getEvilRpStrikes());assertEquals(0,character.getEvilRpSessionEndsAtMs());assertTrue(text(killer).contains("You struck"));assertFalse(PvpStrikeService.hasPendingDecision(victim));
    }

    @Test void aDeathAfterKnockoutDoesNotKillTheEntityTwice() {
        character.setEvilRpStrikes(2);knockout();assertTrue(PvpStrikeService.handleDeath(victim,false));assertTrue(PvpStrikeService.choose(killer,victim.getUniqueId(),StrikeChoice.STRIKE));evil.verify(()->EvilRpService.applyStrike(victim,character,killer,false));assertEquals(Status.DEAD,character.getStatus());assertNull(PvpStrikeService.graveContext(victim,false).killerId());
    }

    @Test void executionsExposeOneShotGraveContextAndCancelledKillsRemoveIt() {
        PvpStrikeService.start();character.setEvilRpStrikes(2);knockout();assertTrue(PvpStrikeService.choose(killer,victim.getUniqueId(),StrikeChoice.STRIKE));timer.run();var grave=PvpStrikeService.graveContext(victim,false);assertTrue(grave.inPvpStart());assertFalse(grave.evilVictim());assertEquals(killer.getUniqueId(),grave.killerId());assertFalse(PvpStrikeService.graveContext(victim,false).inPvpStart());assertTrue(text(killer).contains("You killed"));
        evil.when(()->EvilRpService.applyStrike(victim,character,killer,true)).thenReturn(StrikeOutcome.DEATH);character.setStatus(Status.ALIVE);character.setEvilRpStrikes(2);knockout();PvpStrikeService.choose(killer,victim.getUniqueId(),StrikeChoice.STRIKE);assertFalse(PvpStrikeService.graveContext(victim,false).inPvpStart());assertTrue(text(killer).contains("couldn't kill"));
    }

    @Test void evilExecutionUsesKillBoundaryAndPreservesGraveOwner() {
        evil.when(()->EvilRpService.isInSession(character)).thenReturn(true);knockout();var pending=PvpStrikeService.graveContext(victim,false);assertTrue(pending.inPvpStart());assertTrue(pending.evilVictim());assertNull(pending.killerId());assertTrue(PvpStrikeService.choose(killer,victim.getUniqueId(),StrikeChoice.STRIKE));evil.verify(()->EvilRpService.killByStrike(victim,character,killer,true));verify(manager).savePlayer(victim);assertTrue(PvpStrikeService.graveContext(victim,false).evilVictim());
        evil.when(()->EvilRpService.isInSession(victim)).thenReturn(true);assertTrue(PvpStrikeService.graveContext(victim,true).evilVictim());battles.when(()->PermadeathBattleExemption.isInStartedBattle(victim)).thenReturn(true);assertFalse(PvpStrikeService.graveContext(victim,true).evilVictim());
    }

    @Test void failedInjuryLeavesDecisionOpenThenMaimAndWoundPersistWithoutStrikes() {
        character.setEvilRpStrikes(2);knockout();assertFalse(PvpStrikeService.choose(killer,victim.getUniqueId(),StrikeChoice.MAIM));assertTrue(PvpStrikeService.hasPendingDecision(victim));assertTrue(text(killer).contains("no permanent injuries"));injuries.when(()->PermadeathService.givePermanentInjury(victim,character)).thenReturn(injury);assertTrue(PvpStrikeService.choose(killer,victim.getUniqueId(),StrikeChoice.MAIM));assertEquals(2,character.getEvilRpStrikes());assertTrue(text(victim).contains("permanent injury"));verify(victim).sendTitle(contains("Maimed"),eq("Injured"),eq(10),eq(400),eq(20));
        knockout();assertFalse(PvpStrikeService.choose(killer,victim.getUniqueId(),StrikeChoice.WOUND));injuries.when(()->PermadeathService.giveRandomInjury(victim,character)).thenReturn(injury);assertTrue(PvpStrikeService.choose(killer,victim.getUniqueId(),StrikeChoice.WOUND));assertTrue(text(victim).contains("healing injury"));verify(manager,times(2)).savePlayer(victim);
    }

    @Test void missingOrAlreadyDeadCharactersCannotBeStruckOrInjured() {
        knockout();data.getCharacters().clear();assertTrue(PvpStrikeService.choose(killer,victim.getUniqueId(),StrikeChoice.STRIKE));evil.verify(()->EvilRpService.applyStrike(any(),any(),any(),anyBoolean()),never());data.addCharacter(character);character.setStatus(Status.ALIVE);character.setEvilRpStrikes(2);knockout();character.setStatus(Status.DEAD);assertTrue(PvpStrikeService.choose(killer,victim.getUniqueId(),StrikeChoice.MAIM));injuries.verifyNoInteractions();
    }

    @Test void offlineVerdictsSurviveRestartAndApplyOnlyAfterTheOwnerReturns() throws Exception {
        offlineVerdict(StrikeChoice.STRIKE);assertTrue(PvpStrikeService.hasPendingDecision(victim));var saved=YamlConfiguration.loadConfiguration(verdictFile().toFile());assertEquals(character.getId(),saved.getString(victim.getUniqueId()+".character"));assertEquals("strike",saved.getString(victim.getUniqueId()+".choice"));PvpStrikeService.start();assertTrue(PvpStrikeService.hasPendingDecision(victim));join();assertEquals(1,character.getEvilRpStrikes());assertFalse(PvpStrikeService.hasPendingDecision(victim));assertTrue(YamlConfiguration.loadConfiguration(verdictFile().toFile()).getKeys(false).isEmpty());
    }

    @Test void delayedVerdictsRetryMissingDataAndRespectLogout() {
        offlineVerdict(StrikeChoice.STRIKE);online.put(victim.getUniqueId(),victim);when(victim.isOnline()).thenReturn(false);PvpStrikeService.handleJoin(victim);runNext();assertTrue(PvpStrikeService.hasPendingDecision(victim));when(victim.isOnline()).thenReturn(true);loaded.remove(victim);PvpStrikeService.handleJoin(victim);for(int i=0;i<11;i++)runNext();assertTrue(delayed.isEmpty());assertTrue(PvpStrikeService.hasPendingDecision(victim));loaded.put(victim,data);PvpStrikeService.handleJoin(victim);PvpStrikeService.handleJoin(victim);runNext();runNext();assertEquals(1,character.getEvilRpStrikes());
    }

    @Test void offlineInjuriesApplyOnJoinOrSpareWhenNoInjuryRemains() {
        character.setEvilRpStrikes(2);offlineVerdict(StrikeChoice.WOUND);join();assertTrue(text(victim).contains("no injuries left"));assertEquals(2,character.getEvilRpStrikes());
        injuries.when(()->PermadeathService.givePermanentInjury(victim,character)).thenReturn(injury);offlineVerdict(StrikeChoice.MAIM);online.remove(killer.getUniqueId());join();assertTrue(text(victim).contains("maimed"));assertEquals(2,character.getEvilRpStrikes());
    }

    @Test void offlineSpareDoesNotCreateAVerdictAndMissingManagedDataQueuesOne() {
        knockout();online.remove(victim.getUniqueId());assertTrue(PvpStrikeService.choose(killer,victim.getUniqueId(),StrikeChoice.SPARE));assertFalse(Files.exists(verdictFile()));online.put(victim.getUniqueId(),victim);knockout();loaded.remove(victim);assertTrue(PvpStrikeService.choose(killer,victim.getUniqueId(),StrikeChoice.STRIKE));assertTrue(PvpStrikeService.hasPendingDecision(victim));
    }

    @Test void expiredDecisionsSparePlayersAndStopPeriodicTaskOnShutdown() throws Exception {
        config.when(PvpLoader::getDecisionMs).thenReturn(1_000L);PvpStrikeService.start();var first=timerTask;knockout();timer.run();assertTrue(PvpStrikeService.hasPendingDecision(victim));Thread.sleep(1_025);timer.run();assertFalse(PvpStrikeService.hasPendingDecision(victim));assertTrue(text(killer).contains("didn't choose in time"));assertTrue(text(victim).contains("spared"));PvpStrikeService.start();verify(first).cancel();PvpStrikeService.shutdown();verify(timerTask).cancel();
    }

    @Test void anExpiredDecisionCannotBeExecutedBeforeTheNextPeriodicSweep() throws Exception {
        config.when(PvpLoader::getDecisionMs).thenReturn(1_000L);knockout();Thread.sleep(1_025);assertFalse(PvpStrikeService.choose(killer,victim.getUniqueId(),StrikeChoice.STRIKE),"The decision deadline must apply even before the next expiry sweep runs");assertEquals(0,character.getEvilRpStrikes());assertFalse(PvpStrikeService.hasPendingDecision(victim));
    }

    @Test void invalidSavedVerdictsAreSkippedButUnknownChoicesUseStrike() throws Exception {
        var valid=victim.getUniqueId().toString();Files.writeString(verdictFile(),"plain: text\nbad:\n  killer: invalid\n"+valid+":\n  character: "+character.getId()+"\n  killer: "+killer.getUniqueId()+"\n  choice: unknown\n  evil: false\n  died: true\n");PvpStrikeService.start();verify(logger).warning(contains("Skipping bad pending strike"));assertTrue(PvpStrikeService.hasPendingDecision(victim));PvpStrikeService.handleJoin(victim);runNext();evil.verify(()->EvilRpService.applyStrike(victim,character,killer,false));
    }

    @Test void offlineVerdictWriteFailureMustKeepTheChoiceRetryable() throws Exception {
        Files.createDirectory(verdictFile());Files.writeString(verdictFile().resolve("keep"),"unchanged");knockout();online.remove(victim.getUniqueId());assertFalse(PvpStrikeService.choose(killer,victim.getUniqueId(),StrikeChoice.STRIKE),"A verdict that was not persisted must not be accepted as safely queued");assertTrue(PvpStrikeService.hasPendingDecision(victim));assertEquals("unchanged",Files.readString(verdictFile().resolve("keep")));verify(logger).warning(contains("Could not save pending-strikes.yml"));
        Files.delete(verdictFile().resolve("keep"));Files.delete(verdictFile());assertTrue(PvpStrikeService.choose(killer,victim.getUniqueId(),StrikeChoice.STRIKE));assertTrue(Files.isRegularFile(verdictFile()));join();assertEquals(1,character.getEvilRpStrikes());assertFalse(PvpStrikeService.hasPendingDecision(victim));
    }

    @Test void failedDurableVerdictRemovalDoesNotExecuteOrLoseTheQueuedStrike() throws Exception {
        offlineVerdict(StrikeChoice.STRIKE);var saved=Files.readString(verdictFile());Files.delete(verdictFile());Files.createDirectory(verdictFile());Files.writeString(verdictFile().resolve("keep"),saved);join();
        assertEquals(0,character.getEvilRpStrikes(),"Executing before durable removal would apply this verdict again after a restart");assertTrue(PvpStrikeService.hasPendingDecision(victim));assertEquals(saved,Files.readString(verdictFile().resolve("keep")));
        Files.delete(verdictFile().resolve("keep"));Files.delete(verdictFile());Files.writeString(verdictFile(),saved);PvpStrikeService.handleJoin(victim);runNext();assertEquals(1,character.getEvilRpStrikes());assertFalse(PvpStrikeService.hasPendingDecision(victim));assertTrue(YamlConfiguration.loadConfiguration(verdictFile().toFile()).getKeys(false).isEmpty());
    }

    @Test void failedReplacementRestoresTheEarlierPersistedVerdict() throws Exception {
        offlineVerdict(StrikeChoice.STRIKE);String original=Files.readString(verdictFile());
        // Another defeat can occur after login while the first verdict waits for its join task.
        online.put(victim.getUniqueId(),victim);character.setEvilRpStrikes(2);knockout();online.remove(victim.getUniqueId());Files.delete(verdictFile());Files.createDirectory(verdictFile());Files.writeString(verdictFile().resolve("keep"),original);
        assertFalse(PvpStrikeService.choose(killer,victim.getUniqueId(),StrikeChoice.WOUND));assertTrue(PvpStrikeService.choose(killer,victim.getUniqueId(),StrikeChoice.SPARE),"The rejected replacement must leave its pending decision available");
        Files.delete(verdictFile().resolve("keep"));Files.delete(verdictFile());Files.writeString(verdictFile(),original);join();assertEquals(3,character.getEvilRpStrikes(),"The earlier strike must survive a failed attempt to replace it with a wound");assertEquals(Status.DEAD,character.getStatus());injuries.verifyNoInteractions();assertFalse(PvpStrikeService.hasPendingDecision(victim));
    }

    @Test void aPartialVerdictWritePreservesThePreviousYamlAndRemovesTheStagingFile() throws Exception {
        String original="# previous valid verdict state\n";Files.writeString(verdictFile(),original);knockout();online.remove(victim.getUniqueId());var staged=new AtomicReference<Path>();
        try(var files=mockStatic(Files.class,call->{
            if(call.getMethod().getName().equals("writeString")){Path path=call.getArgument(0);staged.set(path);Files.write(path,"partial: [".getBytes(StandardCharsets.UTF_8));throw new IOException("disk full during staged write");}
            return call.callRealMethod();
        })) {assertFalse(PvpStrikeService.choose(killer,victim.getUniqueId(),StrikeChoice.STRIKE));}
        assertEquals(original,Files.readString(verdictFile()));assertNotNull(staged.get());assertNotEquals(verdictFile(),staged.get());assertFalse(Files.exists(staged.get()));assertTrue(PvpStrikeService.hasPendingDecision(victim));try(var files=Files.list(folder)){assertEquals(List.of(verdictFile()),files.toList());}
    }

    @Test void unsupportedAtomicPublicationKeepsOldVerdictsAndAllowsRetry() throws Exception {
        String original="# previous valid verdict state\n";Files.writeString(verdictFile(),original);knockout();online.remove(victim.getUniqueId());var atomicRejected=new AtomicBoolean();
        try(var files=mockStatic(Files.class,call->{
            if(call.getMethod().getName().equals("move")){CopyOption[] options=(CopyOption[])call.getRawArguments()[2];assertTrue(Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE));atomicRejected.set(true);throw new AtomicMoveNotSupportedException(call.getArgument(0).toString(),call.getArgument(1).toString(),"unsupported filesystem");}
            return call.callRealMethod();
        })) {assertFalse(PvpStrikeService.choose(killer,victim.getUniqueId(),StrikeChoice.STRIKE));}
        assertTrue(atomicRejected.get());assertEquals(original,Files.readString(verdictFile()));try(var files=Files.list(folder)){assertEquals(List.of(verdictFile()),files.toList());}assertTrue(PvpStrikeService.choose(killer,victim.getUniqueId(),StrikeChoice.STRIKE));assertTrue(Files.readString(verdictFile()).contains(victim.getUniqueId().toString()));
    }

    @Test void failedStagingCleanupPreservesBothErrorsAndNeverChangesTheSavedQueue() throws Exception {
        String original="# previous valid verdict state\n";Files.writeString(verdictFile(),original);knockout();online.remove(victim.getUniqueId());var staged=new AtomicReference<Path>();var writeFailure=new IOException("disk full");var cleanupFailure=new IOException("cleanup denied");
        try {
            try(var files=mockStatic(Files.class,call->{
                if(call.getMethod().getName().equals("writeString")){Path path=call.getArgument(0);staged.set(path);Files.write(path,"partial".getBytes(StandardCharsets.UTF_8));throw writeFailure;}
                if(call.getMethod().getName().equals("deleteIfExists")&&Objects.equals(staged.get(),call.getArgument(0)))throw cleanupFailure;
                return call.callRealMethod();
            })) {assertFalse(PvpStrikeService.choose(killer,victim.getUniqueId(),StrikeChoice.STRIKE));}
            assertEquals(original,Files.readString(verdictFile()));assertNotNull(staged.get());assertArrayEquals(new Throwable[]{cleanupFailure},writeFailure.getSuppressed());assertEquals("partial",Files.readString(staged.get()));assertTrue(PvpStrikeService.hasPendingDecision(victim));
        } finally {if(staged.get()!=null)Files.deleteIfExists(staged.get());}
    }

    static Set<String> commands(Component component){var out=new HashSet<String>();if(component.clickEvent()!=null){out.add(component.clickEvent().value());assertNotNull(component.hoverEvent());}for(var child:component.children())out.addAll(commands(child));return out;}
}

abstract class PvpRuntimeFixture {
    @TempDir Path folder;RuntimeTestState state;RPCharacters plugin;Logger logger;Player victim,killer,other;PlayerData data;RPCharacter character;PlayerManager manager;World world;Trait injury;
    BukkitScheduler scheduler;Runnable timer;BukkitTask timerTask;final Deque<PendingTask> delayed=new ArrayDeque<>();final List<PendingTask> allTasks=new ArrayList<>();final Map<UUID,Player> online=new LinkedHashMap<>();final Map<Player,PlayerData> loaded=new HashMap<>();final Map<Player,List<String>> messages=new HashMap<>();final Map<Player,List<Component>> components=new HashMap<>();final List<AutoCloseable> mocks=new ArrayList<>();
    MockedStatic<Bukkit> bukkit;MockedStatic<PvpLoader> config;MockedStatic<EvilRpService> evil;MockedStatic<PermadeathService> injuries;MockedStatic<PermadeathBattleExemption> battles;MockedStatic<TutorialService> tutorials;
    record PendingTask(Runnable run,long delay,BukkitTask task){}
    @BeforeEach void setupPvp() {
        MockBukkit.mock();state=new RuntimeTestState(RPCharacters.class,PvpStrikeService.class,PvpLoader.class,PvpStartSessions.class,PvpSituations.class);Cache.attributes=new ArrayList<>();Cache.professions=new ArrayList<>();PvpStartSessions.clear();PvpSituations.clear();PvpStrikeService.shutdown();
        plugin=mock(RPCharacters.class);logger=mock(Logger.class);when(plugin.getLogger()).thenReturn(logger);when(plugin.getDataFolder()).thenReturn(folder.toFile());RPCharacters.plugin=plugin;world=mock(World.class);when(world.getPlayers()).thenAnswer(call->new ArrayList<>(online.values()));victim=player("Victim",0);killer=player("Killer",2);other=player("Other",100);when(victim.getKiller()).thenReturn(killer);
        character=new RPCharacter(victim);character.setName("Aria");data=spy(new PlayerData(victim));data.addCharacter(character);doReturn(true).when(data).hasActiveCharacter();doReturn(character).when(data).getActiveCharacter();loaded.put(victim,data);manager=mock(PlayerManager.class);var root=boundary(RPCharacters.class);root.when(RPCharacters::getPlayerManager).thenReturn(manager);var players=boundary(PlayerManager.class);players.when(()->PlayerManager.get(any(Player.class))).thenAnswer(call->loaded.get(call.getArgument(0)));
        bukkit=mockStatic(Bukkit.class,CALLS_REAL_METHODS);mocks.add(bukkit);bukkit.when(()->Bukkit.getPlayer(any(UUID.class))).thenAnswer(call->online.get(call.getArgument(0)));scheduler=mock(BukkitScheduler.class);bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);when(scheduler.runTaskLater(eq(plugin),any(Runnable.class),anyLong())).thenAnswer(call->{var t=new PendingTask(call.getArgument(1),call.getArgument(2),mock(BukkitTask.class));delayed.add(t);allTasks.add(t);return t.task;});when(scheduler.runTaskTimer(eq(plugin),any(Runnable.class),anyLong(),anyLong())).thenAnswer(call->{timer=call.getArgument(1);timerTask=mock(BukkitTask.class);return timerTask;});
        config=mockStatic(PvpLoader.class,CALLS_REAL_METHODS);mocks.add(config);var identities=boundary(DisplayIdentityService.class);identities.when(()->DisplayIdentityService.resolveDisplay(any(Player.class))).thenAnswer(call->((Player)call.getArgument(0)).getName());evil=boundary(EvilRpService.class);evil.when(()->EvilRpService.applyStrike(any(Player.class),any(RPCharacter.class),any(),anyBoolean())).thenAnswer(call->{RPCharacter c=call.getArgument(1);c.setEvilRpStrikes(c.getEvilRpStrikes()+1);if(c.getEvilRpStrikes()>=3)c.setStatus(Status.DEAD);return StrikeOutcome.forStrike(c.getEvilRpStrikes());});evil.when(()->EvilRpService.killByStrike(any(),any(),any(),anyBoolean())).thenAnswer(call->{((RPCharacter)call.getArgument(1)).setStatus(Status.DEAD);return true;});injuries=boundary(PermadeathService.class);battles=boundary(PermadeathBattleExemption.class);tutorials=boundary(TutorialService.class);injury=mock(Trait.class);var traits=boundary(TraitChangeService.class);traits.when(()->TraitChangeService.resolveGainedMessage(injury)).thenReturn("Injured");
    }
    <T> MockedStatic<T> boundary(Class<T> type){var b=mockStatic(type);mocks.add(b);return b;}
    Player player(String name,double x){var p=mock(Player.class);var id=UUID.randomUUID();when(p.getUniqueId()).thenReturn(id);when(p.getName()).thenReturn(name);when(p.isOnline()).thenReturn(true);when(p.getWorld()).thenReturn(world);when(p.getLocation()).thenAnswer(call->new Location(world,x,64,0));online.put(id,p);messages.put(p,new ArrayList<>());components.put(p,new ArrayList<>());doAnswer(call->{messages.get(p).add(call.getArgument(0));return null;}).when(p).sendMessage(anyString());doAnswer(call->{components.get(p).add(call.getArgument(0));return null;}).when(p).sendMessage(any(Component.class));return p;}
    String text(Player p){return String.join("\n",messages.get(p));}
    void runNext(){assertFalse(delayed.isEmpty());delayed.removeFirst().run.run();}
    @AfterEach void cleanupPvp() throws Exception {PvpStrikeService.shutdown();for(int i=mocks.size()-1;i>=0;i--)mocks.get(i).close();MockBukkit.unmock();state.close();}
}
