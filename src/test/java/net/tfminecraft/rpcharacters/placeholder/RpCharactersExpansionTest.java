package net.tfminecraft.rpcharacters.placeholder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.rpcharacters.*;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.*;
import net.tfminecraft.rpcharacters.objects.races.Race;

class RpCharactersExpansionTest {
    ServerMock server; PlayerMock player; RuntimeTestState state; PlayerData data; RPCharacter character;
    MockedStatic<PlayerManager> players; RpCharactersExpansion expansion;
    @BeforeEach void setup() {
        server = MockBukkit.mock(); state = new RuntimeTestState(RPCharacters.class);
        Cache.attributes = new ArrayList<>(); Cache.professions = new ArrayList<>(); Cache.personaNoCharacterFallback = "No character";
        Cache.personaGenderDefault = "Unknown"; Cache.calendarAgeUnsetLabel = "Unset";
        RPCharacters.plugin = mock(RPCharacters.class); var meta = mock(io.papermc.paper.plugin.configuration.PluginMeta.class);
        when(meta.getVersion()).thenReturn("2.10.4"); when(RPCharacters.plugin.getPluginMeta()).thenReturn(meta);
        player = server.addPlayer(); data = new PlayerData(player);
        var cfg = new YamlConfiguration(); cfg.set("name", "Human");
        character = new RPCharacter(player, UUID.randomUUID().toString(), "Aria", true, Status.ALIVE, new Race("human", cfg), new ArrayList<>(), null);
        character.setGender("Woman"); character.setPersonaDescription("A traveller"); data.getCharacters().add(character);
        players = mockStatic(PlayerManager.class); players.when(() -> PlayerManager.get(player)).thenReturn(data);
        expansion = new RpCharactersExpansion();
    }
    @AfterEach void cleanup() { players.close(); state.close(); MockBukkit.unmock(); }
    @Test void metadataAndOfflineFieldsHaveStableFallbacks() {
        assertEquals("rpcharacters", expansion.getIdentifier()); assertEquals("Drefvelin", expansion.getAuthor());
        assertEquals("2.10.4", expansion.getVersion()); assertTrue(expansion.persist()); assertNull(expansion.onRequest(player, null));
        var offline = server.getOfflinePlayer(UUID.randomUUID());
        for (String key : List.of("name", "display", "display_tab", "display_safe")) assertEquals("No character", expansion.onRequest(offline, key));
        assertEquals("Unknown", expansion.onRequest(null, "gender"));
        for (String key : List.of("race", "age", "birthday")) assertEquals("Unset", expansion.onRequest(null, key));
        assertEquals("", expansion.onRequest(null, "description")); assertEquals("", expansion.onRequest(null, "unknown"));
    }
    @Test void onlineFieldsUseTheCurrentCharacterAndAccountFallback() {
        assertEquals("Aria", expansion.onRequest(player, "name"));
        for (String key : List.of("display", "display_tab", "display_safe")) assertEquals("§fAria", expansion.onRequest(player, key));
        assertEquals("Human", expansion.onRequest(player, "race")); assertEquals("Woman", expansion.onRequest(player, "gender"));
        assertEquals("Unset", expansion.onRequest(player, "age")); assertEquals("Unset", expansion.onRequest(player, "birthday"));
        assertEquals("A traveller", expansion.onRequest(player, "description")); assertNull(expansion.onRequest(player, "unknown"));
        data.getCharacters().clear(); assertEquals("No character", expansion.onRequest(player, "name"));
    }
    @Test void strikeAndSessionPlaceholdersTrackStateAndMissingData() {
        assertEquals("0", expansion.onRequest(player, "evilrp_strikes")); assertEquals("false", expansion.onRequest(player, "evilrp_in_session")); assertEquals("", expansion.onRequest(player, "evilrp_session_left"));
        character.setEvilRpStrikes(4); character.setEvilRpSessionEndsAtMs(System.currentTimeMillis() + 100_000);
        assertEquals("4", expansion.onRequest(player, "evilrp_strikes")); assertEquals("true", expansion.onRequest(player, "evilrp_in_session")); assertFalse(expansion.onRequest(player, "evilrp_session_left").isEmpty());
        players.when(() -> PlayerManager.get(player)).thenReturn(null);
        assertEquals("0", expansion.onRequest(player, "evilrp_strikes")); assertEquals("", expansion.onRequest(player, "evilrp_session_left"));
    }
    @Test void placeholderNamesAreIndependentOfTheServerLocale() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        assertEquals("§fAria", expansion.onRequest(player, "DISPLAY"));
        assertEquals("Unset", expansion.onRequest(null, "BIRTHDAY"));
    }
}
