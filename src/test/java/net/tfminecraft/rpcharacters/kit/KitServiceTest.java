package net.tfminecraft.rpcharacters.kit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.logging.Logger;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.api.ProvinceSystemClient;
import net.tfminecraft.rpcharacters.ingest.*;
import net.tfminecraft.rpcharacters.loaders.KitLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.tlibs.objects.api.subapi.ItemCreator;
import org.bukkit.*;
import org.bukkit.entity.Item;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.*;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class KitServiceTest {
    ServerMock server; PlayerMock player; RuntimeTestState state; RPCharacters plugin; Logger logger;
    PlayerData data; RPCharacter character; PlayerManager manager; ItemCreator creator; BukkitScheduler scheduler;
    final List<AutoCloseable> boundaries=new ArrayList<>(); final Deque<Runnable> async=new ArrayDeque<>(), main=new ArrayDeque<>();
    final Map<String,KitDefinition> kits=new LinkedHashMap<>();
    MockedStatic<Bukkit> bukkit; MockedStatic<PlayerManager> players; MockedStatic<ProvinceSystemClient> api;
    MockedStatic<KitCustomiseApplyService> customise; MockedStatic<KitCustomiseIngestService> ingest; MockedStatic<RosterSyncService> roster;
    static final KitEditableSpec EDITABLE=new KitEditableSpec("skin","base","v.PAPER",null);
    @BeforeEach void setup() {
        server=MockBukkit.mock(); state=new RuntimeTestState(RPCharacters.class,KitLoader.class,KitService.class);
        Cache.attributes=new ArrayList<>(); Cache.professions=new ArrayList<>(); player=spy(server.addPlayer("KitOwner"));
        plugin=mock(RPCharacters.class); logger=mock(Logger.class); when(plugin.getLogger()).thenReturn(logger); RPCharacters.plugin=plugin;
        character=new RPCharacter(player); character.setName("Aria"); character.setSlug("aria"); data=spy(new PlayerData(player)); data.addCharacter(character); doReturn(true).when(data).hasActiveCharacter(); doReturn(character).when(data).getActiveCharacter();
        manager=mock(PlayerManager.class); var rpc=boundary(RPCharacters.class); rpc.when(RPCharacters::getPlayerManager).thenReturn(manager);
        players=boundary(PlayerManager.class); players.when(() -> PlayerManager.exists(player)).thenReturn(true); players.when(() -> PlayerManager.get(player)).thenReturn(data);
        var loader=boundary(KitLoader.class); loader.when(() -> KitLoader.getKit(any())).thenAnswer(call -> {String id=call.getArgument(0);return id==null?null:kits.get(id.trim().toLowerCase(Locale.ROOT));}); loader.when(KitLoader::kitIds).thenAnswer(call -> kits.keySet());
        scheduler=mock(BukkitScheduler.class); when(scheduler.runTaskAsynchronously(eq(plugin),any(Runnable.class))).thenAnswer(call -> {async.add(call.getArgument(1));return mock(BukkitTask.class);}); when(scheduler.runTask(eq(plugin),any(Runnable.class))).thenAnswer(call -> {main.add(call.getArgument(1));return mock(BukkitTask.class);});
        bukkit=mockStatic(Bukkit.class,CALLS_REAL_METHODS); boundaries.add(bukkit); bukkit.when(Bukkit::getScheduler).thenReturn(scheduler); bukkit.when(() -> Bukkit.getPlayer(player.getUniqueId())).thenReturn(player);
        var tlibs=boundary(TLibs.class); var items=mock(ItemAPI.class); creator=mock(ItemCreator.class); when(items.getCreator()).thenReturn(creator); tlibs.when(TLibs::getItemAPI).thenReturn(items); when(creator.getItemFromPath("v.PAPER")).thenReturn(new ItemStack(Material.PAPER));
        api=boundary(ProvinceSystemClient.class); api.when(() -> ProvinceSystemClient.fetchLoreItemClaimStatus(anyString(),any(),anyString())).thenReturn(ProvinceSystemClient.SimpleResult.success("{}")); api.when(ProvinceSystemClient::fetchPendingLoreItems).thenReturn(ProvinceSystemClient.SimpleResult.success("[]")); api.when(() -> ProvinceSystemClient.parsePendingLoreItems(any())).thenReturn(List.of()); api.when(() -> ProvinceSystemClient.clearLoreItemCustomisations(anyString(),anyString(),anyString())).thenReturn(ProvinceSystemClient.SimpleResult.success("{}"));
        customise=boundary(KitCustomiseApplyService.class); customise.when(() -> KitCustomiseApplyService.requiredSkinsReady(any(),anyList())).thenReturn(true); ingest=boundary(KitCustomiseIngestService.class); ingest.when(() -> KitCustomiseIngestService.applyReadyForCharacterOnMain(any(),any(),anyList())).thenReturn(List.of()); roster=boundary(RosterSyncService.class);
        kit(true,0,List.of(new KitItemDefinition("v.PAPER",1,null)));
    }
    @AfterEach void teardown() throws Exception {for(int i=boundaries.size()-1;i>=0;i--)boundaries.get(i).close(); MockBukkit.unmock(); state.close();}
    <T> MockedStatic<T> boundary(Class<T> type) {var mock=mockStatic(type);boundaries.add(mock);return mock;}
    KitDefinition kit(boolean once,int hours,List<KitItemDefinition> items) {var kit=new KitDefinition("starter","Starter",hours,once,items); kits.put("starter",kit);return kit;}
    void claim() {KitService.tryClaim(player,"starter");}
    void fetch() {assertFalse(async.isEmpty()); async.remove().run();}
    void deliver() {assertFalse(main.isEmpty()); main.remove().run();}
    void complete() {claim();fetch();deliver();}
    String messages() {var lines=new ArrayList<String>();String next;while((next=player.nextMessage())!=null)lines.add(next);return String.join("\n",lines);}
    int paperCount() {return Arrays.stream(player.getInventory().getStorageContents()).filter(Objects::nonNull).filter(i -> i.getType()==Material.PAPER).mapToInt(ItemStack::getAmount).sum();}

    @Test void cooldownHelpersAndCreationEligibilityUseConfiguredKits() {
        kit(true,2,List.of()); assertEquals(0,KitService.cooldownRemainingMs(null)); assertEquals(0,KitService.cooldownRemainingMs(data,"missing")); assertFalse(KitService.isCooldownActive(data)); assertEquals(0,KitService.cooldownRemainingHoursCeil(data));
        data.setLastKitClaimAtMs("starter",0L); assertEquals(0,KitService.cooldownRemainingMs(data)); data.setLastKitClaimAtMs("starter",System.currentTimeMillis()-3_600_000L); assertTrue(KitService.isCooldownActive(data)); assertEquals(1,KitService.cooldownRemainingHoursCeil(data));
        data.setLastKitClaimAtMs("starter",1L); assertFalse(KitService.isCooldownActive(data)); kits.put("extra",new KitDefinition("extra","Extra",0,false,List.of())); KitService.onCharacterCreated(player,null,character); KitService.onCharacterCreated(player,data,null); KitService.onCharacterCreated(player,data,character); assertEquals(Map.of("starter",KitStatus.ELIGIBLE,"extra",KitStatus.ELIGIBLE),character.getKitStatuses());
    }

    @Test void claimInputGuardsRejectOfflineUnknownAndMissingActiveCharacters() {
        KitService.tryClaim(null,"starter"); doReturn(false).when(player).isOnline(); claim(); doReturn(true).when(player).isOnline(); KitService.tryClaim(player,null); KitService.tryClaim(player,"missing"); assertTrue(messages().contains("Unknown kit"));
        players.when(() -> PlayerManager.exists(player)).thenReturn(false); claim(); assertTrue(messages().contains("No character data")); players.when(() -> PlayerManager.exists(player)).thenReturn(true); players.when(() -> PlayerManager.get(player)).thenReturn(null); claim();
        players.when(() -> PlayerManager.get(player)).thenReturn(data); doReturn(false).when(data).hasActiveCharacter(); claim(); doReturn(true).when(data).hasActiveCharacter(); doReturn(null).when(data).getActiveCharacter(); claim(); assertTrue(messages().contains("active character")); assertTrue(async.isEmpty()); verifyNoInteractions(manager);
    }

    @Test void localClaimRejectsGrantedAndCoolingDownButPermitsLegacyIneligibleStatus() {
        character.setKitStatus(KitStatus.GRANTED); claim(); assertTrue(messages().contains("already claimed")); kit(true,2,List.of(new KitItemDefinition("v.PAPER",1,null))); character.setKitStatus(KitStatus.ELIGIBLE); data.setLastKitClaimAtMs("starter",System.currentTimeMillis()); claim(); assertTrue(messages().contains("wait")); assertTrue(async.isEmpty());
        data.setLastKitClaimAtMs("starter",null); character.setKitStatus(KitStatus.INELIGIBLE); complete(); assertEquals(KitStatus.GRANTED,character.getKitStatus()); assertEquals(1,paperCount());
    }

    @Test void duplicateClaimsWaitAndOnlySuccessfulDeliveryConsumesEligibility() {
        KitService.tryClaim(player," STARTER "); assertEquals(KitStatus.ELIGIBLE,character.getKitStatus()); claim(); assertEquals(1,async.size()); assertTrue(messages().contains("already in progress")); assertNull(data.getLastKitClaimAtMs("starter"));
        fetch(); assertEquals(0,paperCount()); deliver(); assertEquals(1,paperCount()); assertEquals(KitStatus.GRANTED,character.getKitStatus()); assertNotNull(data.getLastKitClaimAtMs("starter")); verify(manager).savePlayer(player); roster.verify(() -> RosterSyncService.pushRosterForPlayer(player)); assertTrue(messages().contains("claimed the Starter"));
    }

    @Test void pendingApprovalAndPendingPackKeepClaimsRetryable() {
        api.when(() -> ProvinceSystemClient.claimStatusPendingSkin("{}")).thenReturn(true); complete(); assertTrue(messages().contains("waiting for approval")); assertEquals(0,paperCount());
        api.when(() -> ProvinceSystemClient.claimStatusPendingSkin("{}")).thenReturn(false); api.when(() -> ProvinceSystemClient.claimStatusPendingPack("{}")).thenReturn(true); complete(); assertTrue(messages().contains("pending pack")); assertEquals(KitStatus.ELIGIBLE,character.getKitStatus()); verifyNoInteractions(manager); ingest.verifyNoInteractions();
    }

    @Test void deliveryRevalidatesPlayerCharacterConfigurationAndCooldown() {
        List<Runnable> invalidate=List.of(
            () -> bukkit.when(() -> Bukkit.getPlayer(player.getUniqueId())).thenReturn(null),
            () -> doReturn(false).when(player).isOnline(),
            () -> players.when(() -> PlayerManager.exists(player)).thenReturn(false),
            () -> players.when(() -> PlayerManager.get(player)).thenReturn(null),
            () -> doReturn(false).when(data).hasActiveCharacter(),
            () -> doReturn(null).when(data).getActiveCharacter(),
            () -> {var other=new RPCharacter(player);doReturn(other).when(data).getActiveCharacter();},
            () -> kits.clear(),
            () -> character.setKitStatus(KitStatus.GRANTED));
        for(Runnable change:invalidate) {resetLocal();claim();fetch();change.run();deliver();assertEquals(0,paperCount());}
        verifyNoInteractions(manager); resetLocal(); character.setId(null); claim();fetch();deliver(); assertTrue(messages().contains("active character changed"));
    }
    void resetLocal() {bukkit.when(() -> Bukkit.getPlayer(player.getUniqueId())).thenReturn(player); doReturn(true).when(player).isOnline(); players.when(() -> PlayerManager.exists(player)).thenReturn(true);players.when(() -> PlayerManager.get(player)).thenReturn(data);doReturn(true).when(data).hasActiveCharacter();doReturn(character).when(data).getActiveCharacter();character.setKitStatus(KitStatus.ELIGIBLE);kit(true,0,List.of(new KitItemDefinition("v.PAPER",1,null)));}

    @Test void fetchFailuresAndNullBodiesLogDiagnosticsAndAlwaysReleaseFlight() {
        api.when(() -> ProvinceSystemClient.fetchLoreItemClaimStatus(anyString(),any(),anyString())).thenThrow(new IllegalStateException("offline")); claim();fetch();deliver(); assertTrue(messages().contains("Could not check")); assertEquals(0,paperCount());
        api.when(() -> ProvinceSystemClient.fetchLoreItemClaimStatus(anyString(),any(),anyString())).thenThrow(new IllegalStateException()); claim();fetch(); doReturn(false).when(player).isOnline();deliver(); doReturn(true).when(player).isOnline();
        api.when(() -> ProvinceSystemClient.fetchLoreItemClaimStatus(anyString(),any(),anyString())).thenReturn(ProvinceSystemClient.SimpleResult.success(null)); api.when(ProvinceSystemClient::fetchPendingLoreItems).thenReturn(ProvinceSystemClient.SimpleResult.success(null)); complete(); assertEquals(1,paperCount()); verify(logger,atLeastOnce()).info(contains("empty body"));
    }

    @Test void longStatusBodiesAndReadyRowsAreIngestedBeforeGrant() {
        String body="x".repeat(450); api.when(() -> ProvinceSystemClient.fetchLoreItemClaimStatus(anyString(),any(),anyString())).thenReturn(ProvinceSystemClient.SimpleResult.success(body)); var row=new JSONObject(Map.of("id","row-1")); api.when(() -> ProvinceSystemClient.parsePendingLoreItems("[]")).thenReturn(List.of(row)); ingest.when(() -> KitCustomiseIngestService.applyReadyForCharacterOnMain(player,character,List.of(row))).thenReturn(List.of(row));
        complete(); ingest.verify(() -> KitCustomiseIngestService.ackAsync(List.of(row))); verify(logger).info(contains("x".repeat(400)+"...")); assertEquals(1,paperCount());
    }

    @Test void initialSchedulerRejectionDoesNotStrandFutureClaims() {
        when(scheduler.runTaskAsynchronously(eq(plugin),any(Runnable.class))).thenThrow(new IllegalStateException("scheduler unavailable")); assertEquals("scheduler unavailable",assertThrows(IllegalStateException.class,this::claim).getMessage());
        doAnswer(call -> {async.add(call.getArgument(1));return mock(BukkitTask.class);}).when(scheduler).runTaskAsynchronously(eq(plugin),any(Runnable.class)); claim(); assertEquals(1,async.size(),"Failed dispatch must release the player's claim lock"); fetch();deliver(); assertEquals(1,paperCount());
    }

    @Test void unavailableClaimStatusMustNotConsumeTheKitEntitlement() {
        api.when(() -> ProvinceSystemClient.fetchLoreItemClaimStatus(anyString(),any(),anyString())).thenReturn(ProvinceSystemClient.SimpleResult.fail("status unavailable")); complete(); assertEquals(0,paperCount()); assertEquals(KitStatus.ELIGIBLE,character.getKitStatus()); assertNull(data.getLastKitClaimAtMs("starter")); verifyNoInteractions(manager);
        api.when(() -> ProvinceSystemClient.fetchLoreItemClaimStatus(anyString(),any(),anyString())).thenReturn(ProvinceSystemClient.SimpleResult.success("{}"));complete();assertEquals(1,paperCount());assertEquals(KitStatus.GRANTED,character.getKitStatus());
    }

    @Test void unavailableCustomisationsMustNotGrantAnUncustomisedKitAndConsumeItsEntitlement() {
        api.when(ProvinceSystemClient::fetchPendingLoreItems).thenReturn(ProvinceSystemClient.SimpleResult.fail("customisations unavailable")); complete(); assertEquals(0,paperCount()); assertEquals(KitStatus.ELIGIBLE,character.getKitStatus()); assertNull(data.getLastKitClaimAtMs("starter")); verifyNoInteractions(manager);
        api.when(ProvinceSystemClient::fetchPendingLoreItems).thenReturn(ProvinceSystemClient.SimpleResult.success("[]"));complete();assertEquals(1,paperCount());assertEquals(KitStatus.GRANTED,character.getKitStatus());
    }

    @Test void skinReadinessEmptyDefinitionsAndUnbuildableItemsNeverConsumeClaims() {
        customise.when(() -> KitCustomiseApplyService.requiredSkinsReady(any(),anyList())).thenReturn(false); complete(); assertTrue(messages().contains("awaiting skins"));
        customise.when(() -> KitCustomiseApplyService.requiredSkinsReady(any(),anyList())).thenReturn(true);kit(true,0,List.of());complete(); assertTrue(messages().contains("not configured"));
        kit(true,0,List.of(new KitItemDefinition("missing",1,null)));complete(); assertTrue(messages().contains("could not be built")); assertEquals(0,paperCount()); assertNull(data.getLastKitClaimAtMs("starter")); verifyNoInteractions(manager);
    }

    @Test void customisedDeliveryPreservesMetadataAndSkipsOnlyUncustomisedBadDefinitions() {
        var custom=new KitCustomiseData("paper","Custom",List.of("Lore"),"skin","v.PAPER"); character.putKitCustomise(custom); character.getKitCustomisations().put("null",null); character.getKitCustomisations().put("blank",new KitCustomiseData(" ",null,null,null,null)); character.putKitCustomise(new KitCustomiseData("other",null,null,null,null));
        kit(false,0,List.of(new KitItemDefinition("missing",1,null),new KitItemDefinition("v.PAPER",65,EDITABLE)));
        var template=new ItemStack(Material.PAPER,3); var meta=template.getItemMeta(); meta.setDisplayName("§aCustom paper"); meta.setLore(List.of("Persistent lore")); meta.getPersistentDataContainer().set(new NamespacedKey("test","identity"),PersistentDataType.STRING,"custom-item"); template.setItemMeta(meta);
        customise.when(() -> KitCustomiseApplyService.buildStack(custom)).thenReturn(template); customise.when(() -> KitCustomiseApplyService.isSkinPresent(custom)).thenReturn(true); complete(); assertEquals(65,paperCount()); assertEquals(3,template.getAmount()); assertEquals(KitStatus.ELIGIBLE,character.getKitStatus());
        for(ItemStack stack:player.getInventory().getStorageContents()) if(stack!=null) {assertEquals("§aCustom paper",stack.getItemMeta().getDisplayName());assertEquals(List.of("Persistent lore"),stack.getItemMeta().getLore());assertEquals("custom-item",stack.getItemMeta().getPersistentDataContainer().get(new NamespacedKey("test","identity"),PersistentDataType.STRING));}
    }

    @Test void customBuildFailureAbortsBeforeAnyPartialDelivery() {
        var custom=new KitCustomiseData("paper","Custom",List.of(),null,"v.PAPER"); character.putKitCustomise(custom); kit(true,0,List.of(new KitItemDefinition("v.PAPER",1,null),new KitItemDefinition("v.PAPER",1,EDITABLE))); complete(); assertEquals(0,paperCount()); assertTrue(messages().contains("could not be built")); assertEquals(KitStatus.ELIGIBLE,character.getKitStatus()); verifyNoInteractions(manager);
    }

    @Test void inventoryOverflowDropsRealItemsAndReportsTheOverflow() {
        var world=spy(player.getWorld());doReturn(world).when(player).getWorld();var drops=new ArrayList<Item>();doAnswer(call -> {var item=(Item)call.callRealMethod();drops.add(item);return item;}).when(world).dropItemNaturally(any(Location.class),any(ItemStack.class));
        // MockBukkit's insertion scans the complete inventory, including equipment slots.
        for(int i=0;i<player.getInventory().getSize();i++)player.getInventory().setItem(i,new ItemStack(Material.STONE,64)); complete(); assertEquals(0,paperCount());assertEquals(1,drops.size()); assertEquals(Material.PAPER,drops.getFirst().getItemStack().getType()); assertEquals(1,drops.getFirst().getItemStack().getAmount()); assertTrue(messages().contains("dropped at your feet")); assertEquals(KitStatus.GRANTED,character.getKitStatus());
    }

    @Test void stackBuilderRejectsBadPathsExceptionsNullAndAirAndClonesValidStacks() {
        assertTrue(KitService.buildStacks(null,Map.of()).isEmpty()); assertTrue(KitService.buildStacks(new KitItemDefinition(null,1,null),Map.of()).isEmpty()); assertTrue(KitService.buildStacks(new KitItemDefinition(" ",1,null),Map.of()).isEmpty());
        var air=new ItemStack(Material.AIR);when(creator.getItemFromPath("throws")).thenThrow(new IllegalArgumentException("broken")); when(creator.getItemFromPath("air")).thenReturn(air); for(String path:List.of("throws","air","missing")) assertTrue(KitService.buildStacks(new KitItemDefinition(path,1,null),Map.of()).isEmpty());
        var stacks=KitService.buildStacks(new KitItemDefinition("v.PAPER",130,null),Map.of()); assertEquals(List.of(64,64,2),stacks.stream().map(ItemStack::getAmount).toList()); assertNotSame(stacks.get(0),stacks.get(1));
    }

    @Test void resetAndReclaimResolveTargetsAndOnlyResetWipesCustomisations() {
        assertFalse(KitService.resetKit(null,"aria","starter").ok); doReturn(false).when(player).isOnline(); assertFalse(KitService.reclaimKit(player,"aria","starter").ok);doReturn(true).when(player).isOnline();
        assertFalse(KitService.resetKit(player,null,"starter").ok); assertFalse(KitService.resetKit(player,"aria",null).ok); assertFalse(KitService.resetKit(player,"aria","missing").ok);
        players.when(() -> PlayerManager.exists(player)).thenReturn(false);assertFalse(KitService.reclaimKit(player,"aria","starter").ok);players.when(() -> PlayerManager.exists(player)).thenReturn(true);players.when(() -> PlayerManager.get(player)).thenReturn(null);assertFalse(KitService.resetKit(player,"aria","starter").ok);players.when(() -> PlayerManager.get(player)).thenReturn(data);assertFalse(KitService.resetKit(player,"missing","starter").ok);
        kit(true,0,List.of(new KitItemDefinition("v.PAPER",1,EDITABLE))); var custom=new KitCustomiseData("paper","Custom",List.of(),null,"v.PAPER");character.putKitCustomise(custom);character.putKitCustomise(new KitCustomiseData("other",null,null,null,null));character.setKitStatus(KitStatus.GRANTED);data.setLastKitClaimAtMs("starter",100L);
        var reclaimed=KitService.reclaimKit(player," ARIA "," STARTER ");assertTrue(reclaimed.ok);assertTrue(reclaimed.psWipeOk);assertNull(reclaimed.psWipeError);assertTrue(reclaimed.message.contains("aria"));assertSame(custom,character.getKitCustomisations().get("paper"));assertNull(data.getLastKitClaimAtMs("starter"));
        character.setSlug(null);var reset=KitService.resetKit(player,character.getId(),"starter");assertTrue(reset.ok);assertTrue(reset.message.contains(character.getId()));assertFalse(character.getKitCustomisations().containsKey("paper"));assertTrue(character.getKitCustomisations().containsKey("other"));
        api.when(() -> ProvinceSystemClient.clearLoreItemCustomisations(anyString(),anyString(),anyString())).thenReturn(ProvinceSystemClient.SimpleResult.fail("wipe unavailable")); var partial=KitService.resetKit(player,character.getId(),"starter");assertTrue(partial.ok);assertFalse(partial.psWipeOk);assertEquals("wipe unavailable",partial.psWipeError);verify(manager,times(3)).savePlayer(player);
    }

    @Test void resetInvalidatesFetchedCustomisationsBeforeTheOldClaimCanDeliverThem() {
        kit(true,0,List.of(new KitItemDefinition("v.PAPER",1,EDITABLE)));
        var oldCustomisation=new KitCustomiseData("paper","Wiped custom item",List.of("Old lore"),"old_skin","v.PAPER");
        character.putKitCustomise(oldCustomisation);data.setLastKitClaimAtMs("starter",1L);
        var oldLore=new JSONArray();oldLore.add("Old lore");
        var oldRow=new JSONObject(Map.of("character_id",character.getId(),"player_uuid",player.getUniqueId().toString(),"kit_key","paper","display_name","Wiped custom item","skin_slug","old_skin","path","v.PAPER","lore",oldLore,"ia_namespace","tfmc_submissions"));var oldRows=List.of(oldRow);
        api.when(() -> ProvinceSystemClient.parsePendingLoreItems("[]")).thenReturn(oldRows);
        ingest.when(() -> KitCustomiseIngestService.applyReadyForCharacterOnMain(player,character,oldRows)).thenAnswer(call -> {character.putKitCustomise(oldCustomisation);return oldRows;});
        var oldStack=new ItemStack(Material.PAPER);var oldMeta=oldStack.getItemMeta();oldMeta.setDisplayName("Wiped custom item");oldStack.setItemMeta(oldMeta);customise.when(() -> KitCustomiseApplyService.buildStack(oldCustomisation)).thenReturn(oldStack);
        claim();fetch();assertEquals(1,main.size());
        var reset=KitService.resetKit(player,"aria","starter");assertTrue(reset.ok);assertFalse(character.getKitCustomisations().containsKey("paper"));assertNull(data.getLastKitClaimAtMs("starter"));
        deliver();
        assertFalse(character.getKitCustomisations().containsKey("paper"),"A response fetched before the staff reset must not restore wiped customisations");
        assertEquals(0,paperCount());assertEquals(KitStatus.ELIGIBLE,character.getKitStatus());assertNull(data.getLastKitClaimAtMs("starter"));
        ingest.verify(() -> KitCustomiseIngestService.applyReadyForCharacterOnMain(player,character,oldRows),never());verify(manager).savePlayer(player);
        api.when(() -> ProvinceSystemClient.parsePendingLoreItems("[]")).thenReturn(List.of());complete();assertEquals(1,paperCount());assertEquals(KitStatus.GRANTED,character.getKitStatus());verify(manager,times(2)).savePlayer(player);
    }

    @Test void resettingAnotherKitDoesNotCancelAnUnrelatedFetchedClaim() {
        kits.put("other",new KitDefinition("other","Other",0,true,List.of()));
        claim();fetch();assertTrue(KitService.resetKit(player,"aria","other").ok);deliver();
        assertEquals(1,paperCount());assertEquals(KitStatus.GRANTED,character.getKitStatus("starter"));assertEquals(KitStatus.ELIGIBLE,character.getKitStatus("other"));
    }

    @Test void aClaimFetchingAllCharacterRowsCannotRestoreAnotherKitsWipedCustomisation() {
        kits.put("other",new KitDefinition("other","Other",0,true,List.of(new KitItemDefinition("v.DIAMOND",1,EDITABLE))));
        var oldCustomisation=new KitCustomiseData("diamond","Wiped diamond",List.of(),null,"v.DIAMOND");character.putKitCustomise(oldCustomisation);
        var oldRow=new JSONObject(Map.of("character_id",character.getId(),"player_uuid",player.getUniqueId().toString(),"kit_key","diamond","display_name","Wiped diamond","path","v.DIAMOND"));var oldRows=List.of(oldRow);
        api.when(() -> ProvinceSystemClient.parsePendingLoreItems("[]")).thenReturn(oldRows);
        ingest.when(() -> KitCustomiseIngestService.applyReadyForCharacterOnMain(player,character,oldRows)).thenAnswer(call -> {character.putKitCustomise(oldCustomisation);return oldRows;});
        claim();fetch();assertTrue(KitService.resetKit(player,"aria","other").ok);assertFalse(character.getKitCustomisations().containsKey("diamond"));deliver();
        assertFalse(character.getKitCustomisations().containsKey("diamond"),"A claim for another kit must not ingest stale rows belonging to a reset kit");
        assertEquals(KitStatus.ELIGIBLE,character.getKitStatus("other"));assertNull(data.getLastKitClaimAtMs("other"));
    }
}
