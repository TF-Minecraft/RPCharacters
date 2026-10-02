package net.tfminecraft.rpcharacters.professions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.objects.races.Race;

class ProfessionMenusTest {
    ServerMock server;PlayerMock player;RPCharacters plugin;PlayerManager manager;PlayerData data;RPCharacter character;RuntimeTestState state;ProfessionListener listener;ProfessionInventoryManager menus;ProfessionDefinition profession;ProfessionUpgradeDefinition one,two;List<AutoCloseable> mocks=new ArrayList<>();List<Runnable> delayed=new ArrayList<>();List<Event> events=new ArrayList<>();Map<Player,PlayerData> loaded=new HashMap<>();
    @BeforeEach void setup(){server=MockBukkit.mock();state=new RuntimeTestState(RPCharacters.class,ProfessionRegistry.class,ProfessionListener.class);Cache.attributes=new ArrayList<>();Cache.professions=new ArrayList<>();Cache.professionMaxSpendingPoints=20;plugin=mock(RPCharacters.class);when(plugin.getServer()).thenReturn(server);when(plugin.isEnabled()).thenReturn(true);RPCharacters.plugin=plugin;player=server.addPlayer();data=new PlayerData(player);var cfg=new YamlConfiguration();cfg.set("name","Human");character=new RPCharacter(player,UUID.randomUUID().toString(),"Smith",true,Status.ALIVE,new Race("human",cfg),new ArrayList<>(),null);data.getCharacters().add(character);loaded.put(player,data);data.setAccountProfessionPoints("smith",10);
        var players=boundary(PlayerManager.class);players.when(()->PlayerManager.get(any(Player.class))).thenAnswer(c->loaded.get(c.getArgument(0)));manager=mock(PlayerManager.class);var root=boundary(RPCharacters.class);root.when(RPCharacters::getPlayerManager).thenReturn(manager);boundary(ProfessionIntegrator.class);
        var scheduler=mock(BukkitScheduler.class);when(scheduler.runTaskLater(eq(plugin),any(Runnable.class),anyLong())).thenAnswer(c->{delayed.add(c.getArgument(1));return mock(BukkitTask.class);});var pm=mock(PluginManager.class);doAnswer(c->{events.add(c.getArgument(0));return null;}).when(pm).callEvent(any());var b=mockStatic(Bukkit.class,CALLS_REAL_METHODS);mocks.add(b);b.when(Bukkit::getScheduler).thenReturn(scheduler);b.when(Bukkit::getPluginManager).thenReturn(pm);
        one=upgrade("one",1,List.of(),List.of("Useful", "§7Cost: old", "§7Requires old", " "));two=upgrade("two",3,List.of("one"),List.of());register(List.of(one,two));listener=new ProfessionListener();menus=new ProfessionInventoryManager();
    }
    @AfterEach void cleanup()throws Exception{try{for(int i=mocks.size()-1;i>=0;i--)mocks.get(i).close();state.close();}finally{MockBukkit.unmock();}}
    <T> MockedStatic<T> boundary(Class<T> type){var b=mockStatic(type);mocks.add(b);return b;}
    ProfessionItemSpec spec(String name,List<String> lore){return new ProfessionItemSpec(null,"IRON_PICKAXE",name,null,List.of(),false,lore);}
    ProfessionUpgradeDefinition upgrade(String id,int cost,List<String> requires,List<String> lore){return new ProfessionUpgradeDefinition(id,"smith",spec(id,lore),cost,"perk",requires,List.of());}
    void register(List<ProfessionUpgradeDefinition> upgrades){profession=new ProfessionDefinition("smith","Smith",spec("Smith",List.of("Forge")),upgrades);ProfessionRegistry.setProfessions(List.of(profession));ProfessionRegistry.setUpgrades(upgrades);}
    InventoryClickEvent click(int raw){return new InventoryClickEvent(player.getOpenInventory(),InventoryType.SlotType.CONTAINER,raw,ClickType.LEFT,InventoryAction.PICKUP_ALL);}
    void open(){menus.openMainMenu(player);listener.onMainMenuClick(click(0));assertEquals("Smith",ProfessionListener.currentProfessionMenu.get(player));}
    String text(){var list=new ArrayList<String>();String m;while((m=player.nextMessage())!=null)list.add(m);return ChatColor.stripColor(String.join("\n",list));}
    @Test void menusRequireAnActiveCharacterAndRenderIndependentTemplates(){loaded.remove(player);menus.openMainMenu(player);assertTrue(text().contains("active character"));menus.openProfessionMenu(player,profession);loaded.put(player,new PlayerData(player));menus.openMainMenu(player);menus.openProfessionMenu(player,profession);loaded.put(player,data);menus.openMainMenu(player);var inv=player.getOpenInventory().getTopInventory();assertEquals(27,inv.getSize());assertTrue(inv.getItem(0).getItemMeta().getLore().stream().anyMatch(l->l.contains("10")));assertEquals(Material.EMERALD,inv.getItem(26).getType());assertEquals(1,profession.getMenuItem().getItemMeta().getLore().size());open();assertEquals(54,player.getOpenInventory().getTopInventory().getSize());assertEquals(Material.BARRIER,player.getOpenInventory().getTopInventory().getItem(53).getType());listener.onProfessionMenuClick(click(53));assertFalse(ProfessionListener.currentProfessionMenu.containsKey(player));}
    @Test void mainMenuIgnoresUnrelatedEmptyAndOutsideClicks(){player.openInventory(server.createInventory(null,9,"Other"));listener.onMainMenuClick(click(0));listener.onMainMenuClick(click(-999));player.getOpenInventory().getTopInventory().setItem(0,new ItemStack(Material.STONE));listener.onMainMenuClick(click(0));menus.openMainMenu(player);var empty=click(2);listener.onMainMenuClick(empty);var info=click(26);listener.onMainMenuClick(info);assertTrue(info.isCancelled());assertFalse(ProfessionListener.currentProfessionMenu.containsKey(player));}
    @Test void bottomInventoryItemsCannotTriggerProfessionMenuActions(){menus.openMainMenu(player);player.getOpenInventory().setItem(27,profession.getMenuItem());var bottom=click(27);assertNotNull(bottom.getCurrentItem());assertSame(player.getInventory(),bottom.getClickedInventory());listener.onMainMenuClick(bottom);assertFalse(ProfessionListener.currentProfessionMenu.containsKey(player),"Only the menu inventory can select a profession");}
    @Test void submenuIgnoresUnrelatedMissingCharactersAndUnknownItems(){player.openInventory(server.createInventory(null,9,"Other"));listener.onProfessionMenuClick(click(-999));listener.onProfessionMenuClick(click(0));player.getOpenInventory().getTopInventory().setItem(0,new ItemStack(Material.STONE));listener.onProfessionMenuClick(click(0));ProfessionListener.currentProfessionMenu.put(player,"Smith");listener.onProfessionMenuClick(click(0));open();loaded.remove(player);listener.onProfessionMenuClick(click(0));loaded.put(player,new PlayerData(player));listener.onProfessionMenuClick(click(0));loaded.put(player,data);player.getOpenInventory().getTopInventory().setItem(0,new ItemStack(Material.STONE));listener.onProfessionMenuClick(click(0));assertTrue(character.getProfessionUpgrades().isEmpty());}
    @Test void purchasesValidateCapBalanceAndPrerequisitesBeforeChangingTheCharacter(){open();Cache.professionMaxSpendingPoints=0;listener.onProfessionMenuClick(click(0));assertTrue(text().contains("maximum"));Cache.professionMaxSpendingPoints=20;data.setAccountProfessionPoints("smith",0);listener.onProfessionMenuClick(click(0));assertTrue(text().contains("cannot afford"));data.setAccountProfessionPoints("smith",10);listener.onProfessionMenuClick(click(1));assertTrue(text().contains("requires"));assertTrue(character.getProfessionUpgrades().isEmpty());verifyNoInteractions(manager);}
    @Test void purchaseSavesPublishesAndUpdatesTheClickedMenuItem(){open();var click=click(0);listener.onProfessionMenuClick(click);assertTrue(click.isCancelled());assertTrue(character.hasProfessionUpgrade("one"));assertEquals(9,ProfessionPointService.getFreePoints(player,"smith"));verify(manager).savePlayer(player);var event=(ProfessionUpgradePurchasedEvent)events.stream().filter(ProfessionUpgradePurchasedEvent.class::isInstance).findFirst().orElseThrow();assertSame(player,event.getPlayer());assertSame(character,event.getCharacter());assertEquals("one",event.getUpgradeId());assertEquals(1,event.getCost());assertSame(ProfessionUpgradePurchasedEvent.getHandlerList(),event.getHandlers());assertTrue(player.getOpenInventory().getTopInventory().getItem(0).getItemMeta().getLore().stream().anyMatch(l->l.contains("UNLOCKED")));}
    @Test void bottomInventoryCannotPurchaseAnUpgrade(){open();player.getOpenInventory().setItem(54,one.getMenuItem());var bottom=click(54);assertNotNull(bottom.getCurrentItem());assertSame(player.getInventory(),bottom.getClickedInventory());listener.onProfessionMenuClick(bottom);assertFalse(character.hasProfessionUpgrade("one"));verifyNoInteractions(manager);}
    @Test void removalRejectsPrerequisitesAndExpiresOnlyItsOwnConfirmation(){character.addProfessionUpgrade("one");character.addProfessionUpgrade("two");open();listener.onProfessionMenuClick(click(0));assertTrue(text().contains("requirement"));character.removeProfessionUpgrade("two");listener.onProfessionMenuClick(click(0));assertSame(one,ProfessionListener.pendingRemoval.get(player));assertEquals(1,delayed.size());ProfessionListener.pendingRemoval.put(player,two);delayed.getFirst().run();assertSame(two,ProfessionListener.pendingRemoval.get(player),"An old timeout must not cancel a newer confirmation");}
    @Test void ordinaryRemovalConfirmationExpires(){character.addProfessionUpgrade("one");open();listener.onProfessionMenuClick(click(0));delayed.getFirst().run();assertFalse(ProfessionListener.pendingRemoval.containsKey(player));assertTrue(character.hasProfessionUpgrade("one"));}
    @Test void spendingCapCannotOverflow(){var huge=upgrade("huge",Integer.MAX_VALUE,List.of(),List.of());register(List.of(huge,one));character.addProfessionUpgrade("huge");data.setAccountProfessionPoints("smith",Integer.MAX_VALUE);Cache.professionMaxSpendingPoints=Integer.MAX_VALUE;open();listener.onProfessionMenuClick(click(1));assertFalse(character.hasProfessionUpgrade("one"));assertTrue(text().contains("maximum"));}
    @Test void loreRebuildsDynamicRequirementsAndCostsWithoutChangingDescription(){var missing=upgrade("missing",2,List.of("one","gone"),List.of("Description", "§7Cost: 9", "Requires another", " "));var lore=ProfessionLoreBuilder.buildUpgradeLore(missing,null,false);assertEquals("Description",lore.getFirst());assertTrue(lore.stream().anyMatch(l->l.contains("gone")));assertFalse(lore.stream().anyMatch(l->l.contains("another")));character.addProfessionUpgrade("one");var met=ProfessionLoreBuilder.buildUpgradeLore(missing,character,true);assertTrue(met.stream().anyMatch(l->l.contains("✔")));assertTrue(met.stream().anyMatch(l->l.contains("UNLOCKED")));assertTrue(ProfessionLoreBuilder.buildUpgradeLore(one,character,false).stream().anyMatch(l->l.contains("1 Point")));assertTrue(ProfessionLoreBuilder.buildUpgradeItem(two,null).hasItemMeta());}
    @Test void pointGrantsRequireLoadedAccountsAndPersistPositiveAwards(){assertEquals(10,ProfessionPointService.getLifetimePoints(player,"smith"));ProfessionPointService.grantPoints(null,"smith",1);ProfessionPointService.grantPoints(player,null,1);ProfessionPointService.grantPoints(player,"smith",0);loaded.remove(player);assertEquals(0,ProfessionPointService.getLifetimePoints(player,"smith"));assertEquals(0,ProfessionPointService.getFreePoints(player,"smith"));ProfessionPointService.grantPoints(player,"smith",1);loaded.put(player,new PlayerData(player));assertEquals(0,ProfessionPointService.getFreePoints(player,"smith"));loaded.put(player,data);ProfessionPointService.grantPoints(player,"smith",2);assertEquals(12,data.getAccountProfessionPoints("smith"));verify(manager).savePlayer(player);}
    @Test void professionPointGrantsSaturateInsteadOfErasingBalances(){data.setAccountProfessionPoints("smith",Integer.MAX_VALUE);ProfessionPointService.grantPoints(player,"smith",2);assertEquals(Integer.MAX_VALUE,data.getAccountProfessionPoints("smith"));}
    @Test void professionAccountKeysDoNotDependOnServerLocale(){Locale.setDefault(Locale.forLanguageTag("tr-TR"));data.setAccountProfessionPoints("MINING",4);assertEquals(4,data.getAccountProfessionPoints("mining"));}

    @Test void nonPlayerInventoryViewersCannotSelectOrPurchaseProfessions() {
        // InventoryClickEvent exposes HumanEntity, which also permits external NPC implementations.
        var viewer = mock(org.bukkit.entity.HumanEntity.class);
        var top = server.createInventory(null, 27, "§8Profession Menu");
        var item = profession.getMenuItem();
        top.setItem(0, item);
        var view = mock(InventoryView.class);
        when(view.getTitle()).thenReturn("§8Profession Menu");
        when(view.getTopInventory()).thenReturn(top);
        var event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(view);
        when(event.getClickedInventory()).thenReturn(top);
        when(event.getCurrentItem()).thenReturn(item);
        when(event.getWhoClicked()).thenReturn(viewer);
        assertDoesNotThrow(() -> listener.onMainMenuClick(event));
        verify(event).setCancelled(true);
        clearInvocations(event);
        assertDoesNotThrow(() -> listener.onProfessionMenuClick(event));
        verify(event, never()).setCancelled(anyBoolean());
        assertTrue(ProfessionListener.currentProfessionMenu.isEmpty());
        assertTrue(character.getProfessionUpgrades().isEmpty());
        verifyNoInteractions(manager);
    }

    @Test void matchingUpgradeNamesInOtherProfessionsCannotRedirectAPurchase() {
        var foreign = new ProfessionUpgradeDefinition("foreign", "mining", spec("one", List.of()),
                5, "perk", List.of(), List.of());
        var mining = new ProfessionDefinition("mining", "Mining", spec("Mining", List.of()), List.of(foreign));
        ProfessionRegistry.setProfessions(List.of(mining, profession));
        ProfessionRegistry.setUpgrades(List.of(foreign, one, two));
        ProfessionListener.currentProfessionMenu.put(player, "Smith");
        menus.openProfessionMenu(player, profession);
        listener.onProfessionMenuClick(click(0));
        assertTrue(character.hasProfessionUpgrade("one"));
        assertFalse(character.hasProfessionUpgrade("foreign"));
        assertEquals(9, ProfessionPointService.getFreePoints(player, "smith"));
        verify(manager).savePlayer(player);
        var purchases = events.stream().filter(ProfessionUpgradePurchasedEvent.class::isInstance)
                .map(ProfessionUpgradePurchasedEvent.class::cast).toList();
        assertEquals(1, purchases.size());
        assertEquals("one", purchases.getFirst().getUpgradeId());
    }

    @Test void satisfiedPrerequisitesAllowTheDependentUpgradeAndPreserveEarlierOwnership() {
        character.addProfessionUpgrade("one");
        open();
        var event = click(1);
        listener.onProfessionMenuClick(event);
        assertTrue(event.isCancelled());
        assertTrue(character.hasProfessionUpgrade("one"));
        assertTrue(character.hasProfessionUpgrade("two"));
        assertEquals(6, ProfessionPointService.getFreePoints(player, "smith"));
        verify(manager).savePlayer(player);
        var purchase = events.stream().filter(ProfessionUpgradePurchasedEvent.class::isInstance)
                .map(ProfessionUpgradePurchasedEvent.class::cast).findFirst().orElseThrow();
        assertEquals("two", purchase.getUpgradeId());
        assertEquals(3, purchase.getCost());
        assertTrue(player.getOpenInventory().getTopInventory().getItem(1).getItemMeta().getLore()
                .stream().anyMatch(line -> line.contains("UNLOCKED")));
        assertTrue(text().contains("Purchased two"));
    }
}
