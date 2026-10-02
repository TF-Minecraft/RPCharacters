package net.tfminecraft.rpcharacters.api;

import static net.tfminecraft.rpcharacters.api.ProvinceSystemClient.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.tfminecraft.rpcharacters.RuntimeTestState;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

class ProvinceSystemClientTest {
    RuntimeTestState state;
    MockedStatic<GatewayClient> gateway;

    @BeforeEach void setup() {
        state = new RuntimeTestState(); gateway = mockStatic(GatewayClient.class);
        gateway.when(() -> GatewayClient.request(anyString(), anyString(), nullable(String.class)))
            .thenReturn(GatewayClient.Result.success("{}"));
        gateway.when(() -> GatewayClient.requestBytes(anyString(), anyString(), any(byte[].class), anyString()))
            .thenReturn(GatewayClient.Result.success("uploaded"));
    }
    @AfterEach void restore() { gateway.close(); state.close(); }
    void respond(String json) {
        gateway.when(() -> GatewayClient.request(anyString(), anyString(), nullable(String.class)))
            .thenReturn(GatewayClient.Result.success(json));
    }

    @Test void resultFactoriesExposeSuccessPayloadsAndFailureDetails() {
        var catalog = CatalogPushResult.success(1, 2, 3, 4, "now");
        assertTrue(catalog.ok); assertEquals(1, catalog.stages); assertEquals(2, catalog.races);
        assertEquals(3, catalog.traits); assertEquals(4, catalog.classes); assertEquals("now", catalog.updatedAt); assertNull(catalog.error);
        assertEquals("offline", CatalogPushResult.fail("offline").error);
        var wipe = RealmWipeResult.success("dev", 7, 2); assertTrue(wipe.ok); assertEquals("dev", wipe.realmId);
        assertEquals(7, wipe.total); assertEquals(2, wipe.pngsDeleted); assertNull(wipe.error);
        assertFalse(RealmWipeResult.fail("offline").ok);
        assertEquals("body", SimpleResult.success("body").body); assertNull(SimpleResult.success(null).error);
        assertEquals("offline", SimpleResult.fail("offline").error); assertNull(SimpleResult.fail("offline").body);
    }

    @Test void simpleRoutesUseExpectedMethodsAndPreserveResponseAndFailure() {
        respond("accepted");
        assertEquals("accepted", fetchPendingCreates().body); assertEquals("accepted", ackCreates("creates").body);
        assertEquals("accepted", fetchPendingLoreItems().body); assertEquals("accepted", ackLoreItems("items").body);
        assertEquals("accepted", pushRoster("roster").body);
        gateway.verify(() -> GatewayClient.request("GET", "/characters/plugin/pending", null));
        gateway.verify(() -> GatewayClient.request("POST", "/characters/plugin/applied", "creates"));
        gateway.verify(() -> GatewayClient.request("GET", "/characters/plugin/lore-items/pending", null));
        gateway.verify(() -> GatewayClient.request("POST", "/characters/plugin/lore-items/applied", "items"));
        gateway.verify(() -> GatewayClient.request("PUT", "/characters/plugin/roster", "roster"));
        gateway.when(() -> GatewayClient.request(anyString(), anyString(), nullable(String.class)))
            .thenReturn(GatewayClient.Result.fail("offline"));
        assertEquals("offline", fetchPendingCreates().error); assertEquals("offline", pushCreationCatalog("{}").error);
        assertEquals("offline", wipeRealmCharacterData("dev").error);
    }

    @SuppressWarnings("deprecation")
    @Test void loreClaimQueriesValidateAndEncodeIdentifiersAndDefaultKit() {
        for (String empty : Arrays.asList(null, " ")) {
            assertFalse(fetchLoreItemClaimStatus(empty, "character", "kit").ok);
            assertFalse(fetchLoreItemClaimStatus("player", empty, "kit").ok);
            assertFalse(clearLoreItemCustomisations(empty, "character", "kit").ok);
            assertFalse(clearLoreItemCustomisations("player", empty, "kit").ok);
            assertFalse(clearLoreItemCustomisations("player", "character", empty).ok);
        }
        gateway.verifyNoInteractions();
        assertTrue(fetchLoreItemClaimStatus(" owner+é ", " char one ", " custom kit ").ok);
        gateway.verify(() -> GatewayClient.request("GET", "/characters/plugin/lore-items/claim-status?player_uuid=owner%2B%C3%A9&character_id=char+one&kit_id=custom+kit", null));
        assertTrue(fetchLoreItemClaimStatus("player", "character", null).ok);
        assertTrue(fetchLoreItemClaimStatus("player", "character", " ").ok);
        assertTrue(fetchLoreItemClaimStatus("player", "character").ok);
        gateway.verify(() -> GatewayClient.request("GET", "/characters/plugin/lore-items/claim-status?player_uuid=player&character_id=character&kit_id=starter", null), times(3));
        assertTrue(clearLoreItemCustomisations(" player ", " character ", " kit ").ok);
        gateway.verify(() -> GatewayClient.request("DELETE", "/characters/plugin/lore-items/customisations?player_uuid=player&character_id=character&kit_id=kit", null));
    }

    @Test void queryFailuresReportExceptionMessagesAndFallbacks() {
        for (String message : Arrays.asList("offline", null)) {
            gateway.when(() -> GatewayClient.request(anyString(), anyString(), nullable(String.class)))
                .thenThrow(new IllegalStateException(message));
            String expected = message == null ? "encode failed" : message;
            assertEquals(expected, fetchLoreItemClaimStatus("p", "c", "k").error);
            assertEquals(expected, clearLoreItemCustomisations("p", "c", "k").error);
            assertEquals(expected, wipeRealmCharacterData("dev").error);
        }
    }

    @Test void claimFlagsRequireRootBooleanValues() {
        for (String missing : Arrays.asList(null, " ", "{}", "{\"pending_skin\":false}")) assertFalse(claimStatusPendingSkin(missing));
        assertTrue(claimStatusPendingSkin("{\"pending_skin\":true}")); assertTrue(claimStatusPendingPack("{\"pending_pack\":true}"));
        assertFalse(claimStatusFlagTrue("{}", null)); assertFalse(claimStatusFlagTrue("{}", " "));
        assertFalse(claimStatusPendingSkin("\"pending_skin\""));
        assertFalse(claimStatusPendingSkin("{\"other\":{\"pending_skin\":true},\"pending_skin\":false}"));
        assertFalse(claimStatusPendingPack("{\"pending_pack\":trueish}"));
    }

    @Test void catalogResponseUsesRootCountsAndDecodedJsonStrings() {
        respond("{\"metadata\":{\"stages\":99,\"updated_at\":\"wrong\"},\"stages\":1,\"races\":2,\"traits\":3,\"classes\":4,\"updated_at\":\"now\"}");
        var result = pushCreationCatalog("catalog"); assertTrue(result.ok);
        assertEquals(1, result.stages); assertEquals(2, result.races); assertEquals(3, result.traits); assertEquals(4, result.classes);
        assertEquals("now", result.updatedAt);
        gateway.verify(() -> GatewayClient.request("PUT", "/characters/plugin/creation-catalog", "catalog"));
    }

    @Test void catalogStringsDecodeUnicodeAndPreserveLiteralEscapeSequences() {
        respond("{\"updated_at\":\"\\u00e9\\b\\f\\/ literal\\\\n quote\\\" line\\n tab\\t return\\r\"}");
        assertEquals("é\b\f/ literal\\n quote\" line\n tab\t return\r", pushCreationCatalog("{}").updatedAt);
    }

    @Test void missingMalformedAndOverflowingCountsUseEmptyDefaults() {
        for (String response : Arrays.asList(null, "{}", "[]", "{\"other\":1,\"stages\":99999999999999999999}",
                "{\"stages\":2147483648}", "{\"stages\":-2147483649}", "{\"stages\":1.5}",
                "{\"stages\":\"2\",\"updated_at\":false}", "not json")) {
            respond(response); var result = pushCreationCatalog("{}");
            assertEquals(0, result.stages); assertNull(result.updatedAt);
        }
        respond("{\"stages\":-1}"); assertEquals(-1, pushCreationCatalog("{}").stages);
    }

    @Test void realmWipesValidateEncodeAndReadTotals() {
        assertFalse(wipeRealmCharacterData(null).ok); assertFalse(wipeRealmCharacterData(" ").ok); gateway.verifyNoInteractions();
        respond("{\"realm_id\":\"dev\",\"total\":3,\"pngs_deleted\":2}");
        var result = wipeRealmCharacterData(" dev realm "); assertTrue(result.ok); assertEquals("dev", result.realmId);
        assertEquals(3, result.total); assertEquals(2, result.pngsDeleted);
        gateway.verify(() -> GatewayClient.request("DELETE", "/characters/plugin/realm-data?realm_id=dev+realm", null));
        respond("{}"); assertEquals("dev", wipeRealmCharacterData(" dev ").realmId);
    }

    @Test void characterDeletionFiltersIdsAndWritesStructuredPayload() throws Exception {
        assertFalse(deleteCharacters(null, List.of("one")).ok); assertFalse(deleteCharacters(" ", List.of("one")).ok);
        assertFalse(deleteCharacters("dev", null).ok); assertFalse(deleteCharacters("dev", Arrays.asList(null, " ")).ok);
        gateway.verifyNoInteractions();
        assertTrue(deleteCharacters(" dev ", Arrays.asList(null, " ", " one ", "q\"uote")).ok);
        gateway.verify(() -> GatewayClient.request(eq("POST"), eq("/characters/plugin/characters/delete"), argThat(body -> {
            try {
                JSONObject parsed = (JSONObject) new JSONParser().parse(body);
                return "dev".equals(parsed.get("realm_id")) && List.of("one", "q\"uote").equals(parsed.get("character_ids"));
            } catch (Exception ex) { return false; }
        })));
    }

    @Test void wardrobeRoutesRejectUnsafePathsAndSupportNullActiveSlots() {
        for (String invalid : Arrays.asList(null, " ", "a/b", "a\\b", "a..b")) {
            assertFalse(fetchWardrobe(invalid, "char").ok); assertFalse(fetchWardrobe("player", invalid).ok);
            assertFalse(setWardrobeActive(invalid, "char", "skin1").ok); assertFalse(setWardrobeActive("player", invalid, "skin1").ok);
            assertFalse(ackWardrobe(invalid, "char", List.of()).ok); assertFalse(ackWardrobe("player", invalid, List.of()).ok);
        }
        gateway.verifyNoInteractions(); assertTrue(fetchWardrobe(" player ", " char ").ok);
        gateway.verify(() -> GatewayClient.request("GET", "/characters/plugin/wardrobe/player/char", null));
        assertTrue(setWardrobeActive("player", "char", null).ok); assertTrue(setWardrobeActive("player", "char", " ").ok);
        gateway.verify(() -> GatewayClient.request("POST", "/characters/plugin/wardrobe/player/char/active", "{\"slot\":null}"), times(2));
        assertTrue(setWardrobeActive("player", "char", " SKIN1 ").ok);
        gateway.verify(() -> GatewayClient.request("POST", "/characters/plugin/wardrobe/player/char/active", "{\"slot\":\"skin1\"}"));
        assertTrue(ackWardrobe("player", "char", null).ok);
        gateway.verify(() -> GatewayClient.request("POST", "/characters/plugin/wardrobe/player/char/ack", "{\"slots\":[]}"));
        assertTrue(ackWardrobe("player", "char", Arrays.asList(null, " ", " SKIN1 ", "Q\"\\X")).ok);
        gateway.verify(() -> GatewayClient.request("POST", "/characters/plugin/wardrobe/player/char/ack", "{\"slots\":[\"skin1\",\"q\\\"\\\\x\"]}"));
    }

    @Test void wardrobeSlotIdsUseRootLocale() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        setWardrobeActive("player", "char", "SKIN1"); ackWardrobe("player", "char", List.of("SKIN1"));
        var bodies = org.mockito.ArgumentCaptor.forClass(String.class);
        gateway.verify(() -> GatewayClient.request(eq("POST"), anyString(), bodies.capture()), times(2));
        assertEquals(List.of("{\"slot\":\"skin1\"}", "{\"slots\":[\"skin1\"]}"), bodies.getAllValues());
    }

    @Test void outgoingWardrobeJsonEscapesEveryControlCharacter() throws Exception {
        String slot = "skin\n\r\t\b\f\u0001end";
        setWardrobeActive("p", "c", slot); ackWardrobe("p", "c", List.of(slot));
        var bodies = org.mockito.ArgumentCaptor.forClass(String.class);
        gateway.verify(() -> GatewayClient.request(eq("POST"), anyString(), bodies.capture()), times(2));
        for (String body : bodies.getAllValues()) assertTrue(body.chars().noneMatch(c -> c < 0x20), "JSON must escape control characters");
        assertEquals(slot, ((JSONObject) new JSONParser().parse(bodies.getAllValues().get(0))).get("slot"));
        assertEquals(List.of(slot), ((JSONObject) new JSONParser().parse(bodies.getAllValues().get(1))).get("slots"));
    }

    @Test void pngUploadsValidateStemsAndByteBodiesAndPreserveGatewayErrors() {
        byte[] png = {1, 2, 3};
        for (String invalid : Arrays.asList(null, " ", ".png", "a/b", "a\\b", "a..b", "a b", ".hidden", "trailing."))
            assertFalse(putKitSkin(invalid, png).ok);
        assertFalse(putKitSkin("skin", null).ok); assertFalse(putKitSkin("skin", new byte[0]).ok);
        assertFalse(putWardrobeMaskedTemplate(null).ok); assertFalse(putWardrobeMaskedTemplate(new byte[0]).ok);
        gateway.verifyNoInteractions();
        assertEquals("uploaded", putKitSkin(" Skin-1_test.PNG ", png).body);
        gateway.verify(() -> GatewayClient.requestBytes("PUT", "/characters/plugin/kit-skins/Skin-1_test", png, "image/png"));
        assertTrue(putKitSkin("skin.part", png).ok); assertTrue(putWardrobeMaskedTemplate(png).ok);
        gateway.verify(() -> GatewayClient.requestBytes("PUT", "/characters/plugin/wardrobe-templates/masked", png, "image/png"));
        gateway.when(() -> GatewayClient.requestBytes(anyString(), anyString(), any(byte[].class), anyString()))
            .thenReturn(GatewayClient.Result.fail("offline"));
        assertEquals("offline", putKitSkin("skin", png).error); assertEquals("offline", putWardrobeMaskedTemplate(png).error);
    }

    @Test void pendingListsAcceptOnlyObjectRowsAndIgnoreMalformedEnvelopes() {
        for (String invalid : Arrays.asList(null, " ", "[]", "{}", "{\"creates\":false,\"items\":null}", "not json")) {
            assertTrue(parsePendingCreates(invalid).isEmpty()); assertTrue(parsePendingLoreItems(invalid).isEmpty());
        }
        var creates = parsePendingCreates("{\"creates\":[null,3,\"str\",{\"id\":\"one\"},{\"id\":\"two\"}]}");
        assertEquals(List.of("one", "two"), creates.stream().map(o -> o.get("id")).toList());
        var items = parsePendingLoreItems("{\"items\":[false,[],{\"id\":\"one\"},{\"id\":\"two\"}]}");
        assertEquals(List.of("one", "two"), items.stream().map(o -> o.get("id")).toList());
    }
}
