package net.tfminecraft.rpcharacters.professions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.luckperms.api.*;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.model.user.*;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.PermissionNode;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.loaders.*;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.*;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class ProfessionCommandHandlerTest {
    @TempDir Path temp;
    ServerMock server; PlayerMock player; CommandSender console; Command command; RuntimeTestState state;
    RPCharacters plugin; PlayerManager manager; PlayerData data; RPCharacter character; ProfessionUpgradeDefinition upgrade;
    ProfessionCommandHandler handler; MockedConstruction<ProfessionInventoryManager> menus;
    final List<AutoCloseable> boundaries=new ArrayList<>(); final Map<Player,PlayerData> loaded=new LinkedHashMap<>(); final List<Player> online=new ArrayList<>(); final Deque<Runnable> scheduled=new ArrayDeque<>();
    MockedStatic<Bukkit> bukkit; MockedStatic<ProfessionIntegrator> integrator; MockedStatic<ProfessionPointService> points; MockedStatic<ProfessionTopService> top;
    @BeforeEach void setup() {
        server=MockBukkit.mock(); state=new RuntimeTestState(RPCharacters.class,ProfessionRegistry.class,ProfessionListener.class);
        Cache.attributes=new ArrayList<>(); Cache.professions=new ArrayList<>(); Cache.professionPermContext="main";
        player=spy(server.addPlayer("AriaOwner")); doReturn(true).when(player).hasPermission(ProfessionPermissions.ADMIN); console=mock(CommandSender.class); when(console.hasPermission(ProfessionPermissions.ADMIN)).thenReturn(true);
        plugin=mock(RPCharacters.class); when(plugin.getDataFolder()).thenReturn(temp.toFile()); RPCharacters.plugin=plugin;
        character=new RPCharacter(player); character.setName("Aria"); data=spy(new PlayerData(player)); doReturn(character).when(data).getActiveCharacter(); loaded.put(player,data); online.add(player);
        var players=boundary(PlayerManager.class); players.when(() -> PlayerManager.get(any(Player.class))).thenAnswer(call -> loaded.get(call.getArgument(0)));
        manager=mock(PlayerManager.class); var rpc=boundary(RPCharacters.class); rpc.when(RPCharacters::getPlayerManager).thenReturn(manager);
        bukkit=mockStatic(Bukkit.class,CALLS_REAL_METHODS); boundaries.add(bukkit); bukkit.when(() -> Bukkit.getPlayerExact("AriaOwner")).thenReturn(player); bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(call -> List.copyOf(online));
        var scheduler=mock(BukkitScheduler.class); when(scheduler.runTaskLater(eq(plugin),any(Runnable.class),eq(2L))).thenAnswer(call -> {scheduled.add(call.getArgument(1));return mock(BukkitTask.class);}); bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        integrator=boundary(ProfessionIntegrator.class); points=boundary(ProfessionPointService.class); top=boundary(ProfessionTopService.class);
        var spec=new ProfessionItemSpec(null,"IRON_PICKAXE","§aMining I",null,List.of(),false,List.of("Mine faster"));
        upgrade=new ProfessionUpgradeDefinition("mining_1","mining",spec,3,"perk",List.of(),List.of()); ProfessionRegistry.setProfessions(List.of(new ProfessionDefinition("mining","Mining",null,List.of(upgrade)))); ProfessionRegistry.setUpgrades(List.of(upgrade)); ProfessionListener.pendingRemoval.clear();
        menus=mockConstruction(ProfessionInventoryManager.class); boundaries.add(menus); handler=new ProfessionCommandHandler(); command=mock(Command.class); when(command.getName()).thenReturn("profession");
    }
    @AfterEach void teardown() throws Exception {for(int i=boundaries.size()-1;i>=0;i--)boundaries.get(i).close(); MockBukkit.unmock();state.close();}
    <T> MockedStatic<T> boundary(Class<T> type) {var result=mockStatic(type);boundaries.add(result);return result;}
    void run(CommandSender sender,String... args) {assertTrue(handler.onCommand(sender,command,"profession",args));}
    void message(CommandSender sender,String text) {verify(sender,atLeastOnce()).sendMessage(contains(text));}
    Player addOnline(String name,PlayerData account) {var p=mock(Player.class);when(p.getName()).thenReturn(name);when(p.getUniqueId()).thenReturn(UUID.randomUUID());when(p.isOnline()).thenReturn(true);online.add(p);if(account!=null)loaded.put(p,account);return p;}

    @Test void commandIdentityMenuConsoleAndUnknownSubcommandsHaveExplicitResults() {
        when(command.getName()).thenReturn("unrelated");assertFalse(handler.onCommand(console,command,"other",new String[0]));when(command.getName()).thenReturn("profession");run(console);message(console,"Players only");run(player);verify(menus.constructed().getFirst()).openMainMenu(player);assertFalse(handler.onCommand(console,command,"profession",new String[]{"unknown"}));
    }

    @Test void administrativeCommandsRejectMissingPermissionBeforeDoingWork() {
        when(console.hasPermission(ProfessionPermissions.ADMIN)).thenReturn(false);
        for(String sub:List.of("reload","givepoints","removeupgrade","reset","restoreall","refund","fixperms"))run(console,sub);
        message(console,"do not have access"); verifyNoInteractions(manager); points.verifyNoInteractions();integrator.verifyNoInteractions();
    }

    @Test void topAndGivePointsValidateArgumentsResolveTargetsAndDelegate() {
        run(console,"top");message(console,"Usage");run(console,"top","mining");top.verify(() -> ProfessionTopService.showTop(console,"mining"));
        run(console,"givepoints");run(console,"givepoints","missing","AriaOwner","2");run(console,"givepoints","mining","missing","2");message(console,"Invalid player or profession");
        run(console,"givepoints","mining","AriaOwner","3");points.verify(() -> ProfessionPointService.grantPoints(player,"mining",3));message(console,"Mining");
    }

    @Test void malformedOrNonpositivePointGrantsNeverThrowOrClaimSuccess() {
        assertAll(List.of("bad","2147483648","0","-1").stream().map(amount -> (org.junit.jupiter.api.function.Executable)() -> {
            clearInvocations(console);points.clearInvocations();assertDoesNotThrow(() -> run(console,"givepoints","mining","AriaOwner",amount));points.verifyNoInteractions();verify(console,never()).sendMessage(contains("Gave"));message(console,"positive");
        }));
    }

    @Test void uppercaseCommandsDispatchIndependentlyOfServerLocale() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));run(console,"GIVEPOINTS","mining","AriaOwner","2");points.verify(() -> ProfessionPointService.grantPoints(player,"mining",2));
    }

    @Test void reloadReadsExpectedFilesAndReappliesOnlyActiveCharactersInOrder() {
        var empty=mock(PlayerData.class);addOnline("NoData",null);addOnline("NoCharacter",empty);
        try(var globals=mockConstruction(ProfessionsGlobalLoader.class);var professions=mockStatic(ProfessionLoader.class)) {
            run(console,"reload");verify(globals.constructed().getFirst()).load(new File(temp.toFile(),"professions.yml"));professions.verify(() -> ProfessionLoader.reload(new File(temp.toFile(),"professions")));
        }
        integrator.verify(() -> ProfessionIntegrator.remove(player,character));integrator.verify(() -> ProfessionIntegrator.apply(player,character));message(console,"reloaded");
        integrator.clearInvocations();run(console,"restoreall");integrator.verify(() -> ProfessionIntegrator.apply(player,character));integrator.verifyNoMoreInteractions();
    }

    @Test void staffRemovalAndResetUseHeldUpgradesAndSaveOnlyActualCharacters() {
        run(console,"removeupgrade");run(console,"removeupgrade","missing","mining_1");run(console,"removeupgrade","AriaOwner","missing");message(console,"Invalid player or upgrade");
        character.addProfessionUpgrade("mining_1");run(console,"removeupgrade","AriaOwner","mining_1");assertFalse(character.hasProfessionUpgrade("mining_1"));assertTrue(character.getForfeitedProfessionPoints().isEmpty());integrator.verify(() -> ProfessionIntegrator.removeUpgrade(player,upgrade));verify(manager).savePlayer(player);
        run(console,"reset");run(console,"reset","missing");character.addProfessionUpgrade("mining_1");character.addProfessionUpgrade("removed-definition");run(console,"reset","AriaOwner");assertTrue(character.getProfessionUpgrades().isEmpty());verify(manager,times(2)).savePlayer(player);
        loaded.remove(player);ProfessionCommandHandler.removeUpgradeFromActiveCharacter(player,upgrade,false);ProfessionCommandHandler.resetActiveCharacterUpgrades(player,false);loaded.put(player,data);doReturn(null).when(data).getActiveCharacter();ProfessionCommandHandler.removeUpgradeFromActiveCharacter(player,upgrade,false);ProfessionCommandHandler.resetActiveCharacterUpgrades(player,false);doReturn(character).when(data).getActiveCharacter();ProfessionCommandHandler.removeUpgradeFromActiveCharacter(player,upgrade,false);verifyNoMoreInteractions(manager);
    }

    @Test void playerConfirmationForfeitsCostAndConsumesOnlyThePendingRemoval() {
        run(console,"confirm");run(player,"confirm");message(player,"Nothing to confirm");character.addProfessionUpgrade("mining_1");ProfessionListener.pendingRemoval.put(player,upgrade);run(player,"confirm");assertFalse(character.hasProfessionUpgrade("mining_1"));assertEquals(3,character.getSpentPointsOnProfession("mining"));assertTrue(ProfessionListener.pendingRemoval.isEmpty());message(player,"Mining I");verify(manager).savePlayer(player);
    }

    @Test void refundClearsUpgradeForfeitsAndLifetimeMigrationBeforeBootstrapping() {
        run(console,"refund");run(console,"refund","missing");message(console,"No player found");character.addProfessionUpgrade("mining_1");character.addForfeitedProfessionPoints("mining",4);data.setAccountProfessionPoints("mining",10);data.setProfessionPointsInitialized(true);
        run(console,"refund","AriaOwner");assertTrue(character.getProfessionUpgrades().isEmpty());assertTrue(character.getForfeitedProfessionPoints().isEmpty());assertTrue(data.getAccountProfessionPointsMap().isEmpty());assertFalse(data.isProfessionPointsInitialized());message(player,"Mining I");points.verify(() -> ProfessionPointService.bootstrapLifetimeFromMmoCore(player));
        doReturn(null).when(data).getActiveCharacter();ProfessionCommandHandler.refundActiveCharacter(player);loaded.remove(player);ProfessionCommandHandler.refundActiveCharacter(player);points.verify(() -> ProfessionPointService.bootstrapLifetimeFromMmoCore(player),times(3));
    }

    @Test void completionFiltersProfessionPlayersAndUpgradesByPermissionAndPrefix() {
        assertEquals(List.of("top","confirm","reload","givepoints","removeupgrade","reset","restoreall","refund","fixperms"),complete(console,""));assertEquals(List.of("givepoints"),complete(console,"GIV"));assertEquals(List.of("mining"),complete(console,"top","m"));assertEquals(List.of("mining"),complete(console,"givepoints",null));
        for(String sub:List.of("removeupgrade","reset","refund"))assertEquals(List.of("AriaOwner"),complete(console,sub,"Ar"));assertEquals(List.of("AriaOwner"),complete(console,"givepoints","mining","Ar"));assertEquals(List.of("mining_1"),complete(console,"removeupgrade","AriaOwner","min"));assertTrue(complete(console,"unknown","x").isEmpty());assertTrue(complete(console,"unknown","x","y").isEmpty());assertTrue(complete(console,"givepoints","mining","AriaOwner","1").isEmpty());
        when(console.hasPermission(ProfessionPermissions.ADMIN)).thenReturn(false);assertEquals(List.of("top","confirm"),complete(console,""));assertTrue(complete(console,"givepoints","").isEmpty());assertTrue(complete(console,"reset","").isEmpty());assertTrue(complete(console,"removeupgrade","AriaOwner","").isEmpty());
    }
    List<String> complete(CommandSender sender,String... args) {return handler.onTabComplete(sender,command,"profession",args);}

    @Test void emptyTabArgumentsReturnNoCompletionsInsteadOfIndexingPastTheArray() {assertEquals(List.of(),assertDoesNotThrow(() -> complete(console)));}

    @Test void permissionRepairRemovesOnlyMatchingNodesAndSavesOnlyChangedUsers() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        var noChar=mock(PlayerData.class);Player empty=addOnline("NoCharacter",noChar);Player absent=addOnline("NoData",null);
        try(var repair=new RepairBoundary()) {
            var matching=permission("PROFESSIONS.MINING",true);var otherContext=permission("professions.smithing",false);var otherPermission=permission("chat.use",true);var nonPermission=mock(Node.class);
            User changed=repair.user(player,List.of(matching,otherContext,otherPermission,nonPermission));User unchanged=repair.user(empty,List.of(otherContext));repair.user(absent,List.of());
            run(console,"fixperms");assertEquals(3,repair.callbacks.size());repair.runCallbacks();verify(changed.data()).remove(matching);verify(changed.data(),never()).remove(otherContext);verify(changed.data(),never()).remove(otherPermission);verify(repair.users).saveUser(changed);verify(repair.users,never()).saveUser(unchanged);assertEquals(3,scheduled.size());while(!scheduled.isEmpty())scheduled.remove().run();integrator.verify(() -> ProfessionIntegrator.apply(player,character));integrator.verifyNoMoreInteractions();message(console,"Fixing profession permissions");
        }
    }

    @Test void delayedPermissionRepairMustApplyTheCurrentCharacterAfterASwitch() {
        try(var repair=new RepairBoundary()) {
            var applied=new ArrayList<RPCharacter>();integrator.when(() -> ProfessionIntegrator.apply(eq(player),any(RPCharacter.class))).thenAnswer(call -> {applied.add(call.getArgument(1));return null;});
            repair.user(player,List.of());run(console,"fixperms");var current=new RPCharacter(player);doReturn(current).when(data).getActiveCharacter();repair.runCallbacks();scheduled.remove().run();assertEquals(List.of(current),applied);
        }
    }

    @Test void delayedPermissionRepairMustNotRestoreACharacterAfterLogout() {
        try(var repair=new RepairBoundary()) {
            repair.user(player,List.of());run(console,"fixperms");repair.runCallbacks();doReturn(false).when(player).isOnline();loaded.remove(player);scheduled.remove().run();integrator.verifyNoInteractions();
        }
    }

    PermissionNode permission(String name,boolean matchingContext) {var node=mock(PermissionNode.class);var contexts=mock(ImmutableContextSet.class);when(contexts.contains("server","main")).thenReturn(matchingContext);when(node.getPermission()).thenReturn(name);when(node.getContexts()).thenReturn(contexts);return node;}
    final class RepairBoundary implements AutoCloseable {
        final LuckPerms luckPerms=mock(LuckPerms.class);final UserManager users=mock(UserManager.class);final MockedStatic<LuckPermsProvider> provider=mockStatic(LuckPermsProvider.class);final List<Runnable> callbacks=new ArrayList<>();
        RepairBoundary() {when(luckPerms.getUserManager()).thenReturn(users);provider.when(LuckPermsProvider::get).thenReturn(luckPerms);}
        @SuppressWarnings("unchecked") User user(Player target,List<Node> nodes) {var user=mock(User.class);var data=mock(NodeMap.class);when(user.data()).thenReturn(data);when(data.toCollection()).thenReturn(nodes);CompletableFuture<User> future=mock(CompletableFuture.class);when(users.loadUser(target.getUniqueId())).thenReturn(future);when(future.thenAcceptAsync(any())).thenAnswer(call -> {Consumer<User> consumer=call.getArgument(0);callbacks.add(() -> consumer.accept(user));return CompletableFuture.completedFuture(null);});return user;}
        void runCallbacks() {callbacks.forEach(Runnable::run);callbacks.clear();}
        public void close() {provider.close();}
    }
}
