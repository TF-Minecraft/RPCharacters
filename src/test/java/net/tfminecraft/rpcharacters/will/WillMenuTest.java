package net.tfminecraft.rpcharacters.will;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.body.CharacterSelectView;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.estate.EstateHooks;
import net.tfminecraft.rpcharacters.estate.EstateHooks.Held;
import net.tfminecraft.rpcharacters.estate.EstateLink;
import net.tfminecraft.rpcharacters.ingest.RosterSyncService;
import net.tfminecraft.rpcharacters.loaders.RaceLoader;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.races.Race;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet;
import net.tfminecraft.rpcharacters.will.WillForecast.Guild;
import net.tfminecraft.rpcharacters.will.WillScreens.Button;
import net.tfminecraft.rpcharacters.will.WillScreens.Screen;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.CharacterStatus;
import net.tfminecraft.tlibs.character.Characters;

class WillMenuTest {
    private static final long DAY = 86_400_000L;
    private RuntimeTestState state;
    private final List<AutoCloseable> statics = new ArrayList<>();
    private final List<Screen> shown = new ArrayList<>();
    private final Map<ClickEvent, ClickCallback<Audience>> links = new HashMap<>();
    private final List<Runnable> later = new ArrayList<>();
    private final WillServiceTest.Characters characters = new WillServiceTest.Characters();
    private MockedStatic<Characters> identity;
    private MockedStatic<EstateLink> estates;
    private EstateHooks hooks;
    private DenarWallet wallet;
    private Database database;
    private Player player;
    private World world;
    private PlayerData data;
    private RPCharacter aria;
    private CharacterSession session;
    private CharacterInfo kira;
    private CharacterInfo bob;
    private long now = 100 * DAY;

    private <T> MockedStatic<T> stub(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }

    @BeforeEach
    void setUp() {
        state = new RuntimeTestState(WillMenu.class, WillService.class, WillView.class, WillForecast.class);
        WillMenu.clearPortraits();
        WillMenu.screens = (viewer, screen) -> shown.add(screen);
        WillMenu.clickEvents = (callback, options) -> {
            assertSame(WillScreens.ONCE, options);
            ClickEvent event = ClickEvent.runCommand("/link " + links.size());
            links.put(event, callback);
            return event;
        };
        WillMenu.mainThread = later::add;
        WillMenu.later = (task, ticks) -> {
            assertEquals(WillMenu.PORTRAIT_WAIT_TICKS, ticks);
            task.run();
        };
        WillMenu.clock = () -> now;
        WillMenu.portraits = (owner, id, fallback) -> new CompletableFuture<>();
        wallet = mock(DenarWallet.class);
        WillMenu.wallet = wallet;
        WillView.online = id -> null;
        WillView.skulls = character -> null;
        WillForecast.guilds = id -> WillForecast.NONE;
        database = mock(Database.class);
        when(database.trySaveCharacterDurably(any(), any())).thenReturn(true);
        WillService.database = database;
        WillService.lookup = characters;
        world = mock(World.class);
        player = player(0);
        data = new PlayerData(player.getUniqueId());
        aria = new RPCharacter(WillQueueTest.owner());
        aria.setName("§aAria");
        data.addCharacter(aria);
        session = new CharacterSession(new CharacterRef(data.getUniqueId(), UUID.fromString(aria.getId())), 1);
        kira = characters.add("Kira", CharacterStatus.ALIVE);
        bob = characters.add("Bob", CharacterStatus.ALIVE);
        stub(PlayerManager.class).when(() -> PlayerManager.get(player)).thenReturn(data);
        stub(RosterSyncService.class);
        identity = stub(Characters.class);
        identity.when(() -> Characters.currentSession(any(Player.class))).thenReturn(Optional.empty());
        identity.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(session));
        identity.when(() -> Characters.isCurrent(session)).thenReturn(true);
        identity.when(() -> Characters.info(any(UUID.class))).thenAnswer(call -> characters.info(call.getArgument(0)));
        identity.when(() -> Characters.findByName(anyString())).thenReturn(List.of());
        identity.when(() -> Characters.findByName("Kira")).thenReturn(List.of(kira));
        estates = stub(EstateLink.class);
        hooks = mock(EstateHooks.class);
        estates.when(EstateLink::current).thenReturn(hooks);
        Race elf = mock(Race.class);
        when(elf.getName()).thenReturn("§2Elf");
        stub(RaceLoader.class).when(() -> RaceLoader.getByString("elf")).thenReturn(elf);
        when(world.getPlayers()).thenReturn(List.of(player));
    }

    private Player player(double x) {
        Player one = mock(Player.class);
        when(one.getUniqueId()).thenReturn(UUID.randomUUID());
        when(one.getWorld()).thenReturn(world);
        when(one.getLocation()).thenReturn(new Location(world, x, 64, 0));
        when(one.isOnline()).thenReturn(true);
        return one;
    }

    @AfterEach
    void tearDown() throws Exception {
        for (int i = statics.size() - 1; i >= 0; i--) statics.get(i).close();
        state.close();
    }

    private Screen last() {
        return shown.getLast();
    }

    private static String plain(Component component) {
        // Inline icons read as "[item/paper] "; the tests read the words.
        return PlainTextComponentSerializer.plainText().serialize(component).replaceAll("\\[(item|block)/[a-z_]+\\] ", "");
    }

    private String body() {
        List<String> lines = new ArrayList<>();
        for (Component line : last().lines()) lines.add(plain(line));
        return String.join("\n", lines);
    }

    private List<String> buttons() {
        return last().buttons().stream().map(Button::label).toList();
    }

    private void click(String label, Map<String, String> values) {
        Button button = last().buttons().stream().filter(b -> b.label().equals(label)).findFirst().orElseThrow();
        button.action().accept(values::get);
    }

    private void click(String label) {
        click(label, Map.of());
    }

    /** Clicks a body link by its text, as {@code audience}. */
    private void link(String text, Audience audience) {
        for (Component line : last().lines()) {
            ClickEvent event = find(line, text);
            if (event != null) {
                links.get(event).accept(audience);
                return;
            }
        }
        fail("No link " + text + " in " + body());
    }

    private void link(String text) {
        link(text, player);
    }

    private static ClickEvent find(Component component, String text) {
        if (component instanceof TextComponent t && t.content().equals(text) && component.clickEvent() != null) {
            return component.clickEvent();
        }
        for (Component child : component.children()) {
            ClickEvent found = find(child, text);
            if (found != null) return found;
        }
        return null;
    }

    private void holdings(Held... held) {
        when(hooks.holdings(UUID.fromString(aria.getId()))).thenReturn(List.of(held));
    }

    private static Held held(String participant, Map<String, Integer> counts, String... labels) {
        return new Held(participant, counts, List.of(labels));
    }

    private void standardEstate() {
        holdings(held("denareconomy", Map.of("cents", 16340), "Money: 163.40 in the pouch, 0.00 in the bank"),
                held("simplefactions-vehicles", Map.of("vehicles", 1), "Passenger Car at 1 2 3"),
                held("thievery", Map.of("locks", 1, "guild-locks", 1), "Door at 1 2 3", "Chest at 4 5 6 (guild)"),
                held("birdmessenger", Map.of("letters", 1), "Letter abcd from Kira"),
                held("rpcharacters-body", Map.of(), "Ender chest"));
    }

    private Guild.Member ironhold(Optional<CharacterInfo> leader, boolean youLead, Optional<CharacterInfo> realmLeader) {
        return new Guild.Member("Ironhold", leader, youLead, realmLeader);
    }

    @Test
    void noHeirSaysWhereEachThingWouldGoAndNamingFromYourCharactersSaves() {
        standardEstate();
        WillForecast.guilds = id -> ironhold(Optional.of(bob), false, Optional.empty());
        RPCharacter bran = new RPCharacter(WillQueueTest.owner());
        bran.setName("Bran");
        data.addCharacter(bran);
        CharacterInfo branInfo = new CharacterInfo(new CharacterRef(data.getUniqueId(), UUID.fromString(bran.getId())),
                "Bran", CharacterStatus.ALIVE, "elf", Set.of(), 0, null);
        characters.infos.put(branInfo.ref().characterId(), branInfo);
        WillMenu.open(player);
        assertEquals("Will of Aria", last().title());
        assertNull(last().portrait());
        String body = body();
        assertTrue(body.contains("⚠ No heir named\nIf Aria died for good today:"), body);
        assertTrue(body.contains("163.40 in the pouch, 0.00 in the bank → Ironhold's treasury"), body);
        assertTrue(body.contains("1 vehicle → Bob"), body);
        assertTrue(body.contains("1 lock → Bob"), body);
        assertTrue(body.contains("1 letter → back to their writers"), body);
        assertTrue(body.contains("1 guild or faction lock → Bob"), body);
        assertFalse(body.contains("Ender chest"), body);
        assertTrue(body.contains("Bob leads Ironhold. Your other characters get nothing unless you name one."), body);
        assertEquals(List.of("Name an heir", "Close"), buttons());
        click("Name an heir");
        assertEquals("Name an heir", last().title());
        assertEquals("heir", last().input().key());
        assertTrue(body().contains("Your characters\nBran Elf"), body());
        assertFalse(body().contains("Nearby"));
        link("Bran");
        assertEquals(List.of("Name Bran (Elf, " + bran.getId().substring(0, 8) + ", on your account) as your heir?"),
                last().lines().stream().map(WillMenuTest::plain).toList());
        click("Confirm");
        verify(player).sendMessage("Your heir is named.");
        assertEquals(branInfo.ref().characterId(), aria.getWill().heir());
    }

    private CompletableFuture<BufferedImage> pending(MockedStatic<CharacterSelectView> select, List<Runnable> waits) {
        select.when(() -> CharacterSelectView.placeholder(anyString())).thenAnswer(call -> Component.text("[" + call.getArgument(0) + "]"));
        select.when(() -> CharacterSelectView.bust(any())).thenReturn(Component.text("[bust]"));
        WillMenu.later = (task, ticks) -> waits.add(task);
        CompletableFuture<BufferedImage> skin = new CompletableFuture<>();
        WillMenu.portraits = (owner, id, fallback) -> skin;
        return skin;
    }

    @Test
    void aLivingHeirGetsAPortraitAndGuildLocksGoElsewhere() {
        standardEstate();
        when(wallet.available()).thenReturn(true);
        when(wallet.balance(session.ref(), DenarWallet.Account.POUCH)).thenReturn(16340L);
        when(wallet.balance(session.ref(), DenarWallet.Account.BANK)).thenReturn(0L);
        WillForecast.guilds = id -> ironhold(Optional.empty(), false, Optional.of(bob));
        aria.setWill(new CharacterWill(kira.ref().characterId(), 1, now - 3 * DAY, null));
        Player kiraOwner = player(0);
        WillView.online = id -> id.equals(kira.ref().ownerId()) ? kiraOwner : null;
        List<Runnable> waits = new ArrayList<>();
        try (var select = mockStatic(CharacterSelectView.class)) {
            CompletableFuture<BufferedImage> skin = pending(select, waits);
            select.when(() -> CharacterSelectView.accountSkin(kiraOwner)).thenReturn(null);
            WillMenu.portraits = (owner, id, fallback) -> {
                assertEquals(kira.ref().ownerId(), owner);
                assertEquals(kira.ref().characterId().toString(), id);
                return skin;
            };
            WillMenu.open(player);
            assertTrue(shown.isEmpty());
            skin.complete(new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB));
            later.removeFirst().run();
            assertEquals("[bust]", plain(last().portrait()));
            String body = body();
            assertTrue(body.contains("Kira\nElf · named 3 days ago"), body);
            assertTrue(body.contains("Kira would get\n163.40 denars carried, 0 denars banked\n1 vehicle\n1 lock\n1 letter"), body);
            assertTrue(body.contains("Goes elsewhere\n1 guild or faction lock → Bob"), body);
            assertEquals(List.of("Change heir", "Revoke", "Close"), buttons());
            // The wait ends with the screen already shown: nothing more.
            int before = shown.size();
            waits.removeFirst().run();
            assertEquals(before, shown.size());
            // Opening again uses the cached bust at once.
            WillMenu.open(player);
            assertEquals(before + 1, shown.size());
            assertEquals("[bust]", plain(last().portrait()));
            assertTrue(later.isEmpty() && waits.isEmpty());
        }
    }

    @Test
    void aSlowPortraitIsCachedButNeverReopensOrReplacesAScreen() {
        aria.setWill(new CharacterWill(kira.ref().characterId(), 1, now, null));
        List<Runnable> waits = new ArrayList<>();
        try (var select = mockStatic(CharacterSelectView.class)) {
            CompletableFuture<BufferedImage> skin = pending(select, waits);
            WillMenu.open(player);
            waits.removeFirst().run();
            assertEquals("[No portrait]", plain(last().portrait()));
            assertTrue(body().contains("Kira\nElf · named today"), body());
            assertTrue(body().contains("Kira would get\nNothing recorded today."), body());
            click("Close");
            skin.complete(new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB));
            int before = shown.size();
            later.removeFirst().run();
            assertEquals(before, shown.size());
            WillMenu.open(player);
            assertEquals("[bust]", plain(last().portrait()));
            // A newer /will replaces a waiting one; the older one never shows.
            WillMenu.clearPortraits();
            CompletableFuture<BufferedImage> second = new CompletableFuture<>();
            WillMenu.portraits = (owner, id, fallback) -> second;
            WillMenu.open(player);
            WillMenu.open(player);
            before = shown.size();
            waits.removeFirst().run();
            assertEquals(before, shown.size());
            waits.removeFirst().run();
            assertEquals(before + 1, shown.size());
            // A player gone or playing another character by then sees nothing.
            for (int step = 0; step < 2; step++) {
                WillMenu.clearPortraits();
                WillMenu.open(player);
                if (step == 0) when(player.isOnline()).thenReturn(false);
                else identity.when(() -> Characters.isCurrent(session)).thenReturn(false);
                before = shown.size();
                waits.removeFirst().run();
                assertEquals(before, shown.size());
                when(player.isOnline()).thenReturn(true);
                identity.when(() -> Characters.isCurrent(session)).thenReturn(true);
            }
        }
    }

    @Test
    void portraitFailuresShowNoPortraitAndAStoppedSchedulerIsIgnored() {
        aria.setWill(new CharacterWill(kira.ref().characterId(), 1, null, null));
        List<Runnable> waits = new ArrayList<>();
        try (var select = mockStatic(CharacterSelectView.class)) {
            pending(select, waits);
            WillMenu.mainThread = Runnable::run;
            WillMenu.portraits = (owner, id, fallback) -> { throw new IllegalStateException("no site"); };
            WillMenu.open(player);
            assertEquals("[No portrait]", plain(last().portrait()));
            assertTrue(body().contains("Kira\nElf\n"), body());
            WillMenu.clearPortraits();
            WillMenu.portraits = (owner, id, fallback) -> null;
            WillMenu.open(player);
            assertEquals("[No portrait]", plain(last().portrait()));
            WillMenu.clearPortraits();
            WillMenu.portraits = (owner, id, fallback) -> CompletableFuture.failedFuture(new IllegalStateException("down"));
            WillMenu.open(player);
            assertEquals("[No portrait]", plain(last().portrait()));
            WillMenu.clearPortraits();
            WillMenu.mainThread = task -> { throw new IllegalStateException("disabled"); };
            WillMenu.portraits = (owner, id, fallback) -> CompletableFuture.completedFuture(null);
            int before = shown.size();
            WillMenu.open(player);
            assertEquals(before, shown.size());
            waits.getLast().run();
            assertEquals("[No portrait]", plain(last().portrait()));
        }
    }

    @Test
    void anUnreadableHeirWaitsAndGuildLocksFollowTheRules() {
        standardEstate();
        aria.setWill(new CharacterWill(kira.ref().characterId(), 1, now, null));
        characters.unreadable.add(kira.ref().characterId());
        WillForecast.guilds = id -> ironhold(Optional.of(bob), false, Optional.empty());
        WillMenu.open(player);
        String body = body();
        assertTrue(body.contains("⚠ Your heir can't be checked right now\nIf Aria died now, the estate would wait until the heir can be read."), body);
        assertTrue(body.contains("Your estate today\n163.40 in the pouch, 0.00 in the bank\n1 vehicle\n1 lock\n1 letter\n1 guild or faction lock"), body);
        assertFalse(body.contains("→"), body);
        holdings();
        WillMenu.open(player);
        assertFalse(body().contains("Your estate today"), body());
        characters.unreadable.clear();
        standardEstate();
        // A living heir without a guild takes the guild locks too.
        WillForecast.guilds = id -> WillForecast.NONE;
        WillMenu.open(player);
        assertTrue(body().contains("1 letter\n1 guild or faction lock\nHow wills work"), body());
        assertFalse(body().contains("Goes elsewhere"), body());
        // Unknown guild: the rule without names.
        WillForecast.guilds = id -> null;
        WillMenu.open(player);
        assertTrue(body().contains("Goes elsewhere\n1 guild or faction lock → your guild's leader, else your realm's"), body());
        // Leading the guild: its next leader, else the realm's leader.
        WillForecast.guilds = id -> ironhold(Optional.empty(), true, Optional.of(bob));
        WillMenu.open(player);
        assertTrue(body().contains("1 guild or faction lock → Ironhold's next leader, else Bob"), body());
    }

    @Test
    void deadOrMissingHeirsAndEachGuildCaseExplainTheNoWillRules() {
        holdings(held("simplefactions-vehicles", Map.of("vehicles", 2), "A", "B"),
                held("denareconomy", Map.of("cents", 5), "Money: 0.05 in the pouch, 0.00 in the bank"),
                held("thievery", Map.of("guild-locks", 1), "Chest at 4 5 6 (faction)"));
        characters.infos.put(kira.ref().characterId(), new CharacterInfo(kira.ref(), "Kira", CharacterStatus.DEAD, "elf",
                Set.of(), 0, null));
        aria.setWill(new CharacterWill(kira.ref().characterId(), 1, now, null));
        WillForecast.guilds = id -> ironhold(Optional.empty(), true, Optional.of(bob));
        WillMenu.open(player);
        String body = body();
        assertTrue(body.contains("⚠ Your heir, Kira, has died"), body);
        assertTrue(body.contains("2 vehicles → Ironhold's next leader"), body);
        assertTrue(body.contains("1 guild or faction lock → Ironhold's next leader"), body);
        assertTrue(body.contains("You lead Ironhold, so its next leader would take it."), body);
        aria.setWill(new CharacterWill(UUID.randomUUID(), 2, now, null));
        WillForecast.guilds = id -> ironhold(Optional.empty(), false, Optional.empty());
        WillMenu.open(player);
        body = body();
        assertTrue(body.contains("⚠ Your heir can't be found now"), body);
        assertTrue(body.contains("2 vehicles → Ironhold's next leader"), body);
        assertTrue(body.contains("1 guild or faction lock → Ironhold's next leader"), body);
        assertTrue(body.contains("Ironhold has no leader, so property waits until one is appointed."), body);
        WillForecast.guilds = id -> WillForecast.NONE;
        WillMenu.open(player);
        body = body();
        assertTrue(body.contains("\n0.05 in the pouch, 0.00 in the bank\n2 vehicles\n"), body);
        assertTrue(body.contains("You're in no guild, so it would wait 7 days in case of a revive, then staff would hold it."), body);
        WillForecast.guilds = id -> { throw new IllegalStateException("broken"); };
        WillMenu.open(player);
        assertTrue(body().contains("Money goes to your guild and property to its leader. Without a guild, staff hold it after 7 days."));
        WillForecast.guilds = id -> null;
        estates.when(EstateLink::current).thenReturn(null);
        WillMenu.open(player);
        assertTrue(body().contains("Your estate holds nothing recorded today."), body());
        // A living heir: guild locks go elsewhere only while the character is in a guild.
        aria.setWill(new CharacterWill(bob.ref().characterId(), 3, now, null));
        estates.when(EstateLink::current).thenReturn(hooks);
        WillForecast.guilds = id -> WillForecast.NONE;
        WillMenu.open(player);
        assertFalse(body().contains("Goes elsewhere"), body());
        WillForecast.guilds = id -> ironhold(Optional.empty(), true, Optional.empty());
        WillMenu.open(player);
        assertTrue(body().contains("Goes elsewhere\n1 guild or faction lock → Ironhold's next leader"), body());
        WillForecast.guilds = id -> ironhold(Optional.empty(), false, Optional.empty());
        WillMenu.open(player);
        assertTrue(body().contains("1 guild or faction lock → Ironhold's next leader"), body());
        holdings(held("simplefactions-vehicles", Map.of("vehicles", 2), "A", "B"));
        WillMenu.open(player);
        assertFalse(body().contains("Goes elsewhere"), body());
    }

    @Test
    void theWalletFallsBackToDenarEconomysLine() {
        holdings(held("denareconomy", Map.of("cents", 5), "Money: 0.05 in the pouch, 0.00 in the bank"));
        when(wallet.available()).thenThrow(new IllegalStateException("no ledger"));
        WillMenu.open(player);
        assertFalse(body().contains("carried"), body());
        assertTrue(body().contains("0.05 in the pouch, 0.00 in the bank"), body());
    }

    @Test
    void theRulesPageAndLinksAnswerOnlyThisPlayerWhileTheCharacterIsCurrent() {
        WillMenu.open(player);
        int before = shown.size();
        link("How wills work", mock(Player.class));
        assertEquals(before, shown.size());
        link("How wills work");
        assertEquals("How wills work", last().title());
        assertTrue(body().startsWith("1 Name one living character as your heir."), body());
        assertTrue(body().contains("6 Leading a guild or realm never passes by will."), body());
        click("Back");
        assertEquals("Will of Aria", last().title());
        identity.when(() -> Characters.isCurrent(session)).thenReturn(false);
        link("How wills work");
        verify(player).sendMessage("Your character changed. Open /will again.");
        assertEquals("Will of Aria", last().title());
    }

    @Test
    void revokingClearsTheWill() {
        aria.setWill(new CharacterWill(kira.ref().characterId(), 1, 1L, null));
        WillMenu.portraits = (owner, id, fallback) -> CompletableFuture.completedFuture(null);
        WillMenu.mainThread = Runnable::run;
        try (var select = mockStatic(CharacterSelectView.class)) {
            select.when(() -> CharacterSelectView.placeholder(anyString())).thenReturn(Component.empty());
            WillMenu.open(player);
            click("Revoke");
            assertEquals(List.of("Revoke your will? Nobody will be named."), last().lines().stream().map(WillMenuTest::plain).toList());
            click("Back");
            assertEquals("Will of Aria", last().title());
            click("Revoke");
            click("Confirm");
        }
        verify(player).sendMessage("Your will is revoked.");
        assertFalse(aria.getWill().named());
        WillMenu.open(player);
        click("Close");
        verify(player, times(2)).closeDialog();
    }

    @Test
    void searchingNearbyPlayersAndSeveralMatchesAreOffered() {
        Player near = player(5);
        Player far = player(100);
        Player idle = player(3);
        when(world.getPlayers()).thenReturn(List.of(player, near, far, idle));
        CharacterInfo bran = characters.add("Bran", CharacterStatus.ALIVE);
        identity.when(() -> Characters.currentSession(near)).thenReturn(Optional.of(new CharacterSession(bran.ref(), 2)));
        RPCharacter broken = new RPCharacter(WillQueueTest.owner());
        broken.setId("not-a-uuid");
        data.addCharacter(broken);
        WillMenu.open(player);
        click("Name an heir");
        assertFalse(body().contains("Your characters"), body());
        assertTrue(body().contains("Nearby\nBran 5 blocks"), body());
        link("Bran");
        assertTrue(plain(last().lines().getFirst()).startsWith("Name Bran (Elf"));
        click("Back");
        click("Name an heir");
        click("Find", new HashMap<>());
        assertEquals("No match", last().title());
        click("Back");
        CharacterInfo own = new CharacterInfo(new CharacterRef(data.getUniqueId(), UUID.randomUUID()), "Kira",
                CharacterStatus.ALIVE, null, Set.of(), 0, null);
        CharacterInfo self = new CharacterInfo(session.ref(), "Kira", CharacterStatus.ALIVE, "elf", Set.of(), 0, null);
        characters.infos.put(own.ref().characterId(), own);
        identity.when(() -> Characters.findByName("Kira")).thenReturn(List.of(kira, own, self));
        click("Find", Map.of("heir", "Kira"));
        assertEquals("Several characters", last().title());
        assertEquals(2, last().input().options().size());
        assertTrue(last().input().options().get(1)[1].endsWith(", on your account)"));
        int before = shown.size();
        click("Choose", Map.of("pick", "nobody"));
        assertEquals(before, shown.size());
        click("Choose", Map.of("pick", own.ref().characterId().toString()));
        assertTrue(plain(last().lines().getFirst()).startsWith("Name Kira (null, "));
        // An alt with no race shows its name alone.
        RPCharacter odda = new RPCharacter(WillQueueTest.owner());
        data.addCharacter(odda);
        characters.infos.put(UUID.fromString(odda.getId()), new CharacterInfo(new CharacterRef(data.getUniqueId(),
                UUID.fromString(odda.getId())), "Odda", CharacterStatus.ALIVE, null, Set.of(), 0, null));
        click("Back");
        click("Name an heir");
        assertTrue(body().contains("Your characters\nOdda\nNearby"), body());
    }

    @Test
    void aChangedSessionAndFailedSavesAreExplained() {
        WillMenu.open(player);
        identity.when(() -> Characters.isCurrent(session)).thenReturn(false);
        click("Name an heir");
        verify(player).sendMessage("Your character changed. Open /will again.");
        identity.when(() -> Characters.isCurrent(session)).thenReturn(true);
        when(database.trySaveCharacterDurably(any(), any())).thenReturn(false);
        WillMenu.open(player);
        click("Name an heir");
        click("Find", Map.of("heir", "Kira"));
        click("Confirm");
        verify(player).sendMessage("Your will could not be saved. Try again or contact staff.");
        characters.unreadable.add(kira.ref().characterId());
        WillMenu.open(player);
        click("Name an heir");
        click("Find", Map.of("heir", "Kira"));
        click("Confirm");
        verify(player).sendMessage("That character can't be checked right now. Try again later.");
        characters.unreadable.clear();
        characters.infos.put(kira.ref().characterId(), new CharacterInfo(kira.ref(), "Kira", CharacterStatus.DEAD, "elf",
                Set.of(), 0, null));
        WillMenu.open(player);
        click("Name an heir");
        click("Find", Map.of("heir", "Kira"));
        click("Confirm");
        verify(player).sendMessage("That character can't be your heir.");
    }

    @Test
    void openingNeedsAPlayedCharacterAndTheCommandOpensTheMenu() {
        identity.when(() -> Characters.currentSession(player)).thenReturn(Optional.empty());
        WillMenu.open(player);
        verify(player).sendMessage("Play a character to make its will.");
        try (var menu = mockStatic(WillMenu.class)) {
            assertTrue(new WillCommand().onCommand(player, null, "will", new String[0]));
            menu.verify(() -> WillMenu.open(player));
            var console = mock(org.bukkit.command.CommandSender.class);
            assertTrue(new WillCommand().onCommand(console, null, "will", new String[0]));
            verify(console).sendMessage("Only a player can make a will.");
        }
        Player stranger = player(0);
        identity.when(() -> Characters.currentSession(stranger)).thenReturn(Optional.of(session));
        WillMenu.open(stranger);
        verify(stranger).sendMessage("Play a character to make its will.");
    }
}
