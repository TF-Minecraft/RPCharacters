package net.tfminecraft.rpcharacters.wardrobe;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.logging.Level;
import net.tfminecraft.rpcharacters.api.ProvinceSystemClient;
import net.tfminecraft.rpcharacters.identity.MaskService;
import net.tfminecraft.rpcharacters.identity.PersonaService;
import org.bukkit.ChatColor;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.junit.jupiter.api.*;

class WardrobeDescriptionsTest extends WardrobeFixture {
    static final String TEXT = "A hooded figure in a mud-stained travelling cloak.";

    @BeforeEach void owner() {
        when(character.getOwner()).thenReturn(player);
        when(character.getPersonaDescription()).thenReturn("Main description of this character.");
        player.setOp(true);
    }

    static WardrobeSlotData described(String id, String description) {
        return new WardrobeSlotData(id, true, true, true, false, "slim", null, id + "-value", id + "-signature", description);
    }

    WardrobeSnapshot wardrobe(String active, boolean supported, String extraDescription, String maskedDescription) {
        var slots = new LinkedHashMap<String, WardrobeSlotData>();
        slots.put("base", described("base", "kept but never shown"));
        slots.put("extra_1", described("extra_1", extraDescription));
        slots.put("masked", described("masked", maskedDescription));
        return new WardrobeSnapshot("alpha", active, 3, slots, supported);
    }

    static String response(String description) {
        return "{\"character_id\":\"alpha\",\"active_slot\":\"extra_1\",\"descriptions\":true,\"slots\":["
            + "{\"slot\":\"base\",\"unlocked\":true,\"filled\":true,\"texture_value\":\"b\",\"texture_signature\":\"s\"},"
            + "{\"slot\":\"extra_1\",\"unlocked\":true,\"filled\":true,\"texture_value\":\"e\",\"texture_signature\":\"s\","
            + "\"description\":" + (description == null ? "null" : "\"" + description + "\"") + "}]}";
    }

    List<String> messages() {
        var out = new ArrayList<String>();
        String next;
        while ((next = player.nextMessage()) != null) out.add(ChatColor.stripColor(next));
        return out;
    }

    boolean said(String part) {
        return messages().stream().anyMatch(line -> line.contains(part));
    }

    @Test void snapshotsReadDescriptionsAndTheSiteFlag() {
        var parsed = WardrobeSnapshot.parse(response(" " + TEXT + " "));
        assertTrue(parsed.supportsDescriptions());
        assertEquals(TEXT, parsed.getSlot("extra_1").getDescription());
        assertNull(parsed.getSlot("base").getDescription());
        assertNull(WardrobeSnapshot.parse(response("  ")).getSlot("extra_1").getDescription());
        assertFalse(WardrobeSnapshot.parse("{\"character_id\":\"alpha\",\"slots\":[]}").supportsDescriptions());
        assertFalse(snapshot().supportsDescriptions());
    }

    @Test void othersReadTheWornSkinsDescriptionThenTheCharacterDescription() {
        cache(wardrobe("extra_1", true, TEXT, "A figure in a black hood."));
        assertEquals(TEXT, PersonaService.resolveDescription(character));
        masks.when(() -> MaskService.isMasked(player)).thenReturn(true);
        assertEquals("A figure in a black hood.", PersonaService.resolveDescription(character));
        masks.when(() -> MaskService.isMasked(player)).thenReturn(false);
        cache(wardrobe("base", true, TEXT, null));
        assertEquals("Main description of this character.", PersonaService.resolveDescription(character));
        cache(wardrobe("extra_1", true, null, null));
        assertEquals("Main description of this character.", PersonaService.resolveDescription(character));
        cache(wardrobe("extra_1", false, TEXT, null));
        assertNull(WardrobeService.wornDescription(character), "Hidden until the website stores descriptions");
        cache(wardrobe("extra_1", true, TEXT, null));
        var other = otherCharacter("beta");
        when(other.getOwner()).thenReturn(player);
        assertNull(WardrobeService.wornDescription(other), "Only the active character wears the skin");
        assertNull(WardrobeService.wornDescription(null));
        player.disconnect();
        assertNull(WardrobeService.wornDescription(character));
    }

    @Test void commandSavesAndClearsThroughTheWebsiteAndUpdatesTheCache() {
        cache(wardrobe("extra_1", true, null, null));
        client.when(() -> ProvinceSystemClient.setWardrobeDescription(anyString(), anyString(), anyString(), any()))
            .thenReturn(ok(response(TEXT)));
        WardrobeCommand.handle(player, "rp", new String[]{"wardrobe", "describe", "skin2", "A", "hooded", "figure",
            "in", "a", "mud-stained", "travelling", "cloak."});
        drain();
        client.verify(() -> ProvinceSystemClient.setWardrobeDescription(player.getUniqueId().toString(), "alpha", "extra_1", TEXT));
        assertTrue(said("Description saved"));
        assertEquals(TEXT, WardrobeCache.get(player).getSlot("extra_1").getDescription());
        client.when(() -> ProvinceSystemClient.setWardrobeDescription(anyString(), anyString(), anyString(), any()))
            .thenReturn(ok(response(null)));
        WardrobeCommand.handle(player, "rp", new String[]{"wardrobe", "describe", "extra_1", "clear"});
        drain();
        client.verify(() -> ProvinceSystemClient.setWardrobeDescription(player.getUniqueId().toString(), "alpha", "extra_1", null));
        assertTrue(said("character description while you wear"));
        assertNull(WardrobeCache.get(player).getSlot("extra_1").getDescription());
    }

    @Test void commandRefusesBaseShortTextUnsupportedSitesAndMissingRights() {
        cache(wardrobe("extra_1", true, null, null));
        WardrobeCommand.handle(player, "rp", new String[]{"wardrobe", "describe", "base", "x"});
        assertTrue(said("Base uses your character description"));
        WardrobeCommand.handle(player, "rp", new String[]{"wardrobe", "describe", "extra_1", "Too", "short"});
        assertTrue(said("at least"));
        WardrobeCommand.handle(player, "rp", new String[]{"wardrobe", "describe"});
        assertTrue(said("Usage:"));
        WardrobeCommand.handle(player, "rp", new String[]{"wardrobe", "describe", "nowhere", "x"});
        assertTrue(said("Unknown skin"));
        cache(wardrobe("extra_1", false, null, null));
        WardrobeCommand.handle(player, "rp", new String[]{"wardrobe", "describe", "extra_1", TEXT});
        assertTrue(said("not available yet"));
        cache(wardrobe("extra_1", true, null, null));
        player.setOp(false);
        WardrobeCommand.handle(player, "rp", new String[]{"wardrobe", "describe", "extra_1", TEXT});
        assertTrue(said("permission"));
        assertTrue(async.isEmpty());
        client.verifyNoInteractions();
    }

    @Test void websiteFailureAndCharacterChangeAreReported() {
        cache(wardrobe("extra_1", true, null, null));
        client.when(() -> ProvinceSystemClient.setWardrobeDescription(anyString(), anyString(), anyString(), any()))
            .thenReturn(ProvinceSystemClient.SimpleResult.fail("HTTP 500"));
        WardrobeDescriptions.submit(player, "extra_1", TEXT);
        drain();
        assertTrue(said("Could not save the description"));
        verify(logger).warning(contains("Wardrobe description save failed"));
        client.when(() -> ProvinceSystemClient.setWardrobeDescription(anyString(), anyString(), anyString(), any()))
            .thenReturn(ok(response(TEXT)));
        WardrobeDescriptions.submit(player, "extra_1", TEXT);
        async.removeFirst().run();
        active.set(otherCharacter("beta"));
        main.removeFirst().run();
        assertTrue(said("active character changed"));
        assertNull(WardrobeCache.get(player).getSlot("extra_1").getDescription());
        verify(logger, never()).log(eq(Level.SEVERE), anyString());
    }

    @Test void shiftClickStartsAChatPromptThatSavesClearsOrCancels() {
        cache(wardrobe("base", true, null, null));
        client.when(() -> ProvinceSystemClient.setWardrobeDescription(anyString(), anyString(), anyString(), any()))
            .thenReturn(ok(response(TEXT)));
        WardrobeGui.openNow(player);
        var lore = player.getOpenInventory().getTopInventory().getItem(12).getItemMeta().getLore();
        assertTrue(lore.stream().anyMatch(line -> line.contains("Shift-click to set a description")));
        var listener = new WardrobeListener();
        listener.onWardrobeGuiClick(click(12, ClickType.SHIFT_LEFT));
        assertFalse(player.getOpenInventory().getTopInventory().getHolder() instanceof WardrobeGuiHolder);
        assertTrue(said("Type the description"));
        assertTrue(WardrobeDescriptionInput.isPending(player));
        var input = new WardrobeDescriptionInput();
        var tooShort = chat("Too short");
        input.onChat(tooShort);
        assertTrue(tooShort.isCancelled());
        drain();
        assertTrue(said("Type it again"));
        assertTrue(WardrobeDescriptionInput.isPending(player));
        input.onChat(chat(TEXT));
        drain();
        client.verify(() -> ProvinceSystemClient.setWardrobeDescription(player.getUniqueId().toString(), "alpha", "extra_1", TEXT));
        assertFalse(WardrobeDescriptionInput.isPending(player));
        var plain = chat("hello everyone");
        input.onChat(plain);
        assertFalse(plain.isCancelled(), "Only the next line after the prompt is taken");
        WardrobeCommand.handle(player, "rp", new String[]{"wardrobe", "describe", "extra_1"});
        messages();
        input.onChat(chat("cancel"));
        drain();
        assertTrue(said("Description unchanged"));
        WardrobeGui.openNow(player);
        listener.onWardrobeGuiClick(click(11, ClickType.SHIFT_LEFT));
        assertTrue(said("Base uses your character description"));
        assertFalse(WardrobeDescriptionInput.isPending(player));
    }

    @Test void shiftClickStillEquipsWhenTheSiteHasNoDescriptions() {
        cache(wardrobe("base", false, null, null));
        client.when(() -> ProvinceSystemClient.setWardrobeActive(anyString(), anyString(), anyString())).thenReturn(ok("{}"));
        WardrobeGui.openNow(player);
        var lore = player.getOpenInventory().getTopInventory().getItem(12).getItemMeta().getLore();
        assertTrue(lore.stream().noneMatch(line -> line.contains("description")));
        new WardrobeListener().onWardrobeGuiClick(click(12, ClickType.SHIFT_LEFT));
        drain();
        assertFalse(WardrobeDescriptionInput.isPending(player));
        assertEquals("extra_1", WardrobeCache.get(player).getActiveSlot());
        assertTrue(messages().stream().noneMatch(line -> line.contains("Tip:")));
    }

    @Test void menuLoreAndHintsTeachTheFeatureWherePlayersChangeSkins() {
        cache(wardrobe("base", true, TEXT, null));
        WardrobeGui.openNow(player);
        var top = player.getOpenInventory().getTopInventory();
        assertTrue(top.getItem(11).getItemMeta().getLore().stream().anyMatch(line -> line.contains("Uses your character description")));
        var skin = String.join(" ", top.getItem(12).getItemMeta().getLore());
        assertTrue(skin.contains("Description while worn:") && skin.contains("mud-stained"));
        WardrobeDescriptions.sendEquipHint(player, "extra_1");
        assertTrue(said("this skin's own description"));
        cache(wardrobe("extra_1", true, null, null));
        WardrobeDescriptions.sendEquipHint(player, "extra_1");
        assertTrue(said("Tip: shift-click"));
        WardrobeDescriptions.sendEquipHint(player, "base");
        WardrobeDescriptions.sendCharacterDescriptionHint(player);
        assertTrue(said("This is your main description"));
        cache(wardrobe("extra_1", true, TEXT, null));
        WardrobeDescriptions.sendCharacterDescriptionHint(player);
        assertTrue(said("own description instead"));
        cache(wardrobe("base", true, TEXT, null));
        WardrobeDescriptions.sendCharacterDescriptionHint(player);
        assertTrue(messages().isEmpty());
    }

    @Test void tabCompletionOffersDescribeSkinsAndClear() {
        cache(wardrobe("base", true, null, null));
        assertTrue(WardrobeCommand.tabComplete(player, new String[]{"wardrobe", "de"}).contains("describe"));
        assertEquals(List.of("extra_1", "masked"), WardrobeCommand.tabComplete(player, new String[]{"wardrobe", "describe", ""}));
        assertEquals(List.of("clear"), WardrobeCommand.tabComplete(player, new String[]{"wardrobe", "describe", "extra_1", "c"}));
        assertTrue(WardrobeCommand.tabComplete(player, new String[]{"wardrobe", "describe", "extra_1", "A", ""}).isEmpty());
        cache(wardrobe("base", false, null, null));
        assertFalse(WardrobeCommand.tabComplete(player, new String[]{"wardrobe", ""}).contains("describe"));
        assertTrue(WardrobeCommand.tabComplete(player, new String[]{"wardrobe", "describe", ""}).isEmpty());
    }

    InventoryClickEvent click(int rawSlot, ClickType type) {
        return new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER, rawSlot, type,
            InventoryAction.PICKUP_ALL);
    }

    @SuppressWarnings("deprecation")
    AsyncPlayerChatEvent chat(String message) {
        return new AsyncPlayerChatEvent(true, player, message, new HashSet<>());
    }
}
