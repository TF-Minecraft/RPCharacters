package net.tfminecraft.rpcharacters.will;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import net.tfminecraft.rpcharacters.estate.EstateHooks.Held;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.will.WillView.Estate;
import net.tfminecraft.rpcharacters.will.WillView.Row;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.CharacterStatus;
import net.tfminecraft.tlibs.character.Characters;

class WillViewTest {
    private RuntimeTestState state;
    private MockedStatic<Characters> identity;

    @BeforeEach
    void setUp() {
        state = new RuntimeTestState(WillView.class);
        identity = mockStatic(Characters.class);
        identity.when(() -> Characters.currentSession(any(Player.class))).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        identity.close();
        state.close();
    }

    private static Held held(String participant, Map<String, Integer> counts, String... labels) {
        return new Held(participant, counts, List.of(labels));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void holdingsAreGroupedByKindInASteadyOrder() {
        Estate estate = WillView.estate(List.of(
                held("cooking", Map.of("animals", 1), "Animal 1"),
                held("thievery", Map.of("locks", 1, "guild-locks", 2), "Door at 1 2 3", "Chest at 1 2 4 (guild)",
                        "Gate at 1 2 5 (faction)"),
                held("odd", Map.of("widgets", 3)),
                held("breedingbuddies", Map.of("animals", 2), "a", "b"),
                held("magic", Map.of("heldGear", 1), "Magic gear needs review"),
                held("mixed", Map.of("shops", 1, "stations", 0, "sites", 1), "unsorted"),
                held("rpcharacters-body", Map.of(), "Ender chest"),
                held("denareconomy", Map.of("cents", 0)),
                held("letters", Map.of("letters", 1), "Letter 1")), "9 carried");
        assertNull(estate.money());
        assertEquals(List.of("locks", "shops", "animals", "sites", "letters", "guild-locks", "widgets", "heldGear"),
                estate.rows().stream().map(Row::key).toList());
        Row animals = estate.rows().get(2);
        assertEquals(3, animals.count());
        assertEquals(List.of("Animal 1", "a", "b"), animals.labels());
        assertEquals(List.of("Door at 1 2 3"), estate.rows().getFirst().labels());
        assertEquals(List.of("Chest at 1 2 4 (guild)", "Gate at 1 2 5 (faction)"), estate.rows().get(5).labels());
        assertEquals(List.of(), estate.rows().get(1).labels());
        assertEquals("3 widgets", plain(estate.rows().get(6).text()));
        assertEquals("1 magic item for staff review", plain(estate.rows().get(7).text()));
        assertFalse(estate.isEmpty());
        assertTrue(new Estate(null, List.of()).isEmpty());
        assertFalse(new Estate("1", List.of()).isEmpty());
    }

    @Test
    void moneyComesFromTheWalletElseDenarEconomysLine() {
        Held money = held("denareconomy", Map.of("cents", 5), "Money: 0.05 in the pouch, 0.00 in the bank");
        assertEquals("9 carried", WillView.estate(List.of(money), "9 carried").money());
        assertEquals("0.05 in the pouch, 0.00 in the bank", WillView.estate(List.of(money), null).money());
        assertNull(WillView.estate(List.of(held("denareconomy", Map.of("cents", 5))), null).money());
        assertEquals("[item/gold_nugget] 1", plain(WillView.money("1")));
    }

    @Test
    void readableRowsListTheirLabelsOnHoverAndOthersDoNot() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < WillView.HOVER_LINES + 2; i++) many.add("Door " + i);
        Component locks = WillView.row(WillView.estate(List.of(new Held("thievery", Map.of("locks", many.size()), many)), null)
                .rows().getFirst());
        Component text = locks.children().get(1);
        HoverEvent<?> hover = text.hoverEvent();
        String shown = plain((Component) hover.value());
        assertTrue(shown.startsWith("Your locks\nDoor 0\n"), shown);
        assertTrue(shown.endsWith("Door 11\nand 2 more"), shown);
        assertInstanceOf(ObjectComponent.class, locks);
        Component one = WillView.row(WillView.estate(List.of(held("simplefactions-vehicles", Map.of("vehicles", 1), "Cart")), null)
                .rows().getFirst());
        assertEquals("Your vehicles\nCart", plain((Component) one.children().get(1).hoverEvent().value()));
        assertEquals("[item/minecart] 1 vehicle", plain(one));
        Component animals = WillView.row(WillView.estate(List.of(held("cooking", Map.of("animals", 1), "Animal x")), null)
                .rows().getFirst());
        assertNull(animals.children().get(1).hoverEvent());
        Component bare = WillView.row(WillView.estate(List.of(held("thievery", Map.of("locks", 1))), null).rows().getFirst());
        assertNull(bare.children().get(1).hoverEvent());
        assertEquals("Heading", plain(WillView.heading("Heading")));
        assertEquals("a\nb", plain(WillView.lines(List.of(Component.text("a"), Component.text("b")))));
        assertEquals("Aria", WillView.plain(" §aAria "));
    }

    @Test
    void facesComeFromTheLiveSkinOrYourWardrobeElseTheNameStandsAlone() {
        UUID account = UUID.randomUUID();
        CharacterInfo kira = new CharacterInfo(new CharacterRef(account, UUID.randomUUID()), "Kira", CharacterStatus.ALIVE,
                "elf", Set.of(), 0, null);
        WillView.online = id -> null;
        assertEquals("Kira", plain(WillView.named(kira, null, WillView.TEXT)));
        Player owner = mock(Player.class);
        when(owner.getUniqueId()).thenReturn(account);
        PlayerProfile textured = mock(PlayerProfile.class);
        when(textured.getProperties()).thenReturn(Set.of(new ProfileProperty("name", "x"),
                new ProfileProperty("textures", "value", "signature")));
        when(owner.getPlayerProfile()).thenReturn(textured);
        WillView.online = id -> id.equals(account) ? owner : null;
        assertEquals(Component.empty(), WillView.face(kira, null));
        identity.when(() -> Characters.currentSession(owner)).thenReturn(Optional.of(new CharacterSession(
                new CharacterRef(account, UUID.randomUUID()), 1)));
        assertEquals(Component.empty(), WillView.face(kira, null));
        identity.when(() -> Characters.currentSession(owner)).thenReturn(Optional.of(new CharacterSession(kira.ref(), 1)));
        Component live = WillView.face(kira, null);
        assertInstanceOf(ObjectComponent.class, live);
        PlayerProfile bare = mock(PlayerProfile.class);
        when(bare.getProperties()).thenReturn(Set.of());
        when(owner.getPlayerProfile()).thenReturn(bare);
        assertEquals(Component.empty(), WillView.face(kira, null));

        WillView.online = id -> null;
        PlayerData data = new PlayerData(account);
        assertEquals(Component.empty(), WillView.face(kira, data));
        RPCharacter character = new RPCharacter(WillQueueTest.owner());
        character.setId(kira.ref().characterId().toString());
        data.addCharacter(character);
        WillView.skulls = c -> null;
        assertEquals(Component.empty(), WillView.face(kira, data));
        ItemStack plainItem = mock(ItemStack.class);
        when(plainItem.getItemMeta()).thenReturn(mock(ItemMeta.class));
        WillView.skulls = c -> plainItem;
        assertEquals(Component.empty(), WillView.face(kira, data));
        ItemStack skull = mock(ItemStack.class);
        SkullMeta meta = mock(SkullMeta.class);
        when(skull.getItemMeta()).thenReturn(meta);
        WillView.skulls = c -> skull;
        assertEquals(Component.empty(), WillView.face(kira, data));
        when(meta.getPlayerProfile()).thenReturn(textured);
        assertInstanceOf(ObjectComponent.class, WillView.face(kira, data));
        assertEquals(Component.empty(), WillView.face(kira, new PlayerData(UUID.randomUUID())));
        CharacterInfo stranger = new CharacterInfo(new CharacterRef(account, UUID.randomUUID()), "Odd",
                CharacterStatus.ALIVE, null, Set.of(), 0, null);
        assertEquals(Component.empty(), WillView.face(stranger, data));
    }
}
