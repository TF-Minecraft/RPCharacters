package net.tfminecraft.rpcharacters.wardrobe;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonObject;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.api.ProvinceSystemClient;
import net.tfminecraft.rpcharacters.api.ProvinceSystemClient.SimpleResult;
import net.tfminecraft.rpcharacters.identity.CharacterIdentityService;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.CharacterStatus;
import net.tfminecraft.tlibs.character.Characters;

class PlushieCommandTest {

	private static final UUID OWNER = UUID.randomUUID();
	private static final UUID CHARACTER = UUID.randomUUID();
	private static final String URL = "https://textures.minecraft.net/texture/" + "a".repeat(64);
	private static final CharacterInfo GRINKLE = new CharacterInfo(new CharacterRef(OWNER, CHARACTER),
			"Grinkle", CharacterStatus.ALIVE, null, Set.of(), 0, null);
	private final PlushieCommand command = new PlushieCommand();
	private final Player player = mock(Player.class);
	private final PlayerInventory inventory = mock(PlayerInventory.class);
	private final CharacterIdentityService identity = mock(CharacterIdentityService.class);
	private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
	private final SkullMeta meta = mock(SkullMeta.class);
	private final PlayerProfile profile = mock(PlayerProfile.class);
	private final ArrayDeque<Runnable> async = new ArrayDeque<>();
	private final ArrayDeque<Runnable> main = new ArrayDeque<>();
	private MockedStatic<Bukkit> bukkit;
	private MockedStatic<RPCharacters> rp;
	private MockedStatic<Characters> characters;
	private MockedStatic<ProvinceSystemClient> client;
	private MockedConstruction<ItemStack> items;

	@BeforeEach void setup() {
		bukkit = mockStatic(Bukkit.class);
		rp = mockStatic(RPCharacters.class);
		characters = mockStatic(Characters.class);
		client = mockStatic(ProvinceSystemClient.class);
		items = mockConstruction(ItemStack.class, (item, context) -> when(item.getItemMeta()).thenReturn(meta));
		bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
		bukkit.when(() -> Bukkit.createProfile(any(UUID.class))).thenReturn(profile);
		rp.when(RPCharacters::getCharacterIdentity).thenReturn(identity);
		when(identity.findByName("Grinkle")).thenReturn(List.of(GRINKLE));
		when(player.getUniqueId()).thenReturn(UUID.randomUUID());
		when(player.isOnline()).thenReturn(true);
		when(player.hasPermission(PlushieCommand.PERMISSION)).thenReturn(true);
		when(player.getInventory()).thenReturn(inventory);
		when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());
		characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.empty());
		doAnswer(call -> { async.add(call.getArgument(1)); return null; })
				.when(scheduler).runTaskAsynchronously(nullable(Plugin.class), any(Runnable.class));
		doAnswer(call -> { main.add(call.getArgument(1)); return null; })
				.when(scheduler).runTask(nullable(Plugin.class), any(Runnable.class));
		for (String slot : List.of("base", "extra_1", "extra_2")) {
			respond(slot, SimpleResult.success(response(CHARACTER.toString(), slot, texture(URL, 1), "signed")));
		}
	}

	@AfterEach void close() {
		items.close(); client.close(); characters.close(); rp.close(); bukkit.close();
	}

	private void run(String... args) {
		assertTrue(command.onCommand(player, null, "plushie", args));
	}

	private void finish() {
		async.remove().run();
		main.remove().run();
	}

	private void respond(String slot, SimpleResult result) {
		client.when(() -> ProvinceSystemClient.fetchWardrobeSkin(OWNER.toString(), CHARACTER.toString(), slot)).thenReturn(result);
	}

	@Test void selectsTheNumberedOutfitOffThreadAndDeliversAStaticPlushieOnMain() {
		run("Grinkle", "2");
		client.verifyNoInteractions();
		verifyNoInteractions(inventory);
		async.remove().run();
		verifyNoInteractions(inventory);
		client.verify(() -> ProvinceSystemClient.fetchWardrobeSkin(OWNER.toString(), CHARACTER.toString(), "extra_1"));
		client.verifyNoMoreInteractions();
		main.remove().run();
		verify(inventory).addItem(items.constructed().getFirst());
		verify(meta).displayName(Component.text("Grinkle Plushie").decoration(TextDecoration.ITALIC, false));
		verify(meta).setPlayerProfile(profile);
		var property = ArgumentCaptor.forClass(ProfileProperty.class);
		verify(profile).setProperty(property.capture());
		assertEquals("textures", property.getValue().getName());
		assertEquals(texture(URL, null), property.getValue().getValue());
		assertFalse(property.getValue().isSigned());
		verify(player).sendMessage("Created Grinkle Plushie from wardrobe outfit 2.");
	}

	@Test void supportsBaseDefaultMultiwordNamesAndExactCharacterIds() {
		run("Grinkle"); finish();
		when(identity.findByName("Grinkle the Great")).thenReturn(List.of(GRINKLE));
		run("Grinkle", "the", "Great", "3"); finish();
		when(identity.info(CHARACTER)).thenReturn(Optional.of(GRINKLE));
		run(CHARACTER.toString(), "1"); finish();
		client.verify(() -> ProvinceSystemClient.fetchWardrobeSkin(OWNER.toString(), CHARACTER.toString(), "base"), times(2));
		client.verify(() -> ProvinceSystemClient.fetchWardrobeSkin(OWNER.toString(), CHARACTER.toString(), "extra_2"));
	}

	@Test void refusesUnauthorisedConsoleInvalidAndAmbiguousRequestsBeforeLoading() {
		when(player.hasPermission(PlushieCommand.PERMISSION)).thenReturn(false);
		run("Grinkle");
		verify(player).sendMessage(contains("permission"));
		CommandSender console = mock(CommandSender.class);
		when(console.hasPermission(PlushieCommand.PERMISSION)).thenReturn(true);
		command.onCommand(console, null, "plushie", new String[] {"Grinkle"});
		verify(console).sendMessage(contains("in game"));
		when(player.hasPermission(PlushieCommand.PERMISSION)).thenReturn(true);
		run();
		for (String number : List.of("0", "-1", "4", "999999999999999999999")) run("Grinkle", number);
		run("Unknown");
		verify(player).sendMessage("Character not found: Unknown");
		when(identity.findByName("Grinkle")).thenReturn(List.of(GRINKLE, GRINKLE));
		run("Grinkle");
		verify(player).sendMessage(contains("More than one character"));
		verifyNoInteractions(scheduler, inventory);
	}

	@Test void limitsPendingRequestsAndDoesNotDeliverAfterLogoutOrPermissionLoss() {
		run("Grinkle"); run("Grinkle");
		assertEquals(1, async.size());
		verify(player).sendMessage("Your previous plushie is still loading.");
		when(player.isOnline()).thenReturn(false);
		finish();
		when(player.isOnline()).thenReturn(true);
		run("Grinkle");
		when(player.hasPermission(PlushieCommand.PERMISSION)).thenReturn(false);
		finish();
		verifyNoInteractions(inventory);
	}

	@Test void neverDeliversIntoAnotherCharacterAfterASwitch() {
		CharacterSession session = mock(CharacterSession.class);
		characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(session));
		run("Grinkle"); finish();
		verify(player).sendMessage(contains("character changed"));
		verifyNoInteractions(inventory);
		characters.when(() -> Characters.isCurrent(session)).thenReturn(true);
		run("Grinkle"); finish();
		verify(inventory).addItem(any(ItemStack.class));
	}

	@Test void unavailableOutfitsGiveNothingAndFullInventoriesDoNotDropItems() {
		respond("base", SimpleResult.fail("offline"));
		run("Grinkle"); finish();
		verify(player).sendMessage(contains("No plushie was created"));
		verifyNoInteractions(inventory);
		respond("base", SimpleResult.success(response(CHARACTER.toString(), "base", texture(URL, 1), "signed")));
		when(inventory.addItem(any(ItemStack.class))).thenAnswer(call -> new HashMap<>(Map.of(0, call.getArgument(0, ItemStack.class))));
		run("Grinkle"); finish();
		verify(player).sendMessage("Your inventory is full. Make room and try again.");
		verify(player, never()).getWorld();
	}

	@Test void enteringACharacterWhileLoadingAlsoCancelsDelivery() {
		run("Grinkle");
		characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(mock(CharacterSession.class)));
		finish();
		verify(player).sendMessage(contains("character changed"));
		verifyNoInteractions(inventory);
	}

	@Test void rejectsWrongCharactersWrongSlotsUnsignedAndMalformedSkinsWithoutFallback() {
		for (String body : List.of("{}", "[]", "invalid",
				response("other-character", "base", texture(URL, 1), "signed"),
				response(CHARACTER.toString(), "extra_1", texture(URL, 1), "signed"),
				response(CHARACTER.toString(), "base", texture(URL, 1), " "),
				response(CHARACTER.toString(), "base", "invalid", "signed"),
				response(CHARACTER.toString(), "base", texture("https://example.com/skin.png", 1), "signed"))) {
			respond("base", SimpleResult.success(body));
			assertNull(PlushieCommand.loadTexture(GRINKLE, "base"));
		}
		respond("base", SimpleResult.success(null));
		assertNull(PlushieCommand.loadTexture(GRINKLE, "base"));
	}

	@Test void ignoresSourceTimestampsAndUsesTheSameStaticIdentityForCopies() {
		String first = PlushieCommand.loadTexture(GRINKLE, "base");
		respond("base", SimpleResult.success(response(CHARACTER.toString(), "base", texture(URL, 999), "new-signature")));
		String later = PlushieCommand.loadTexture(GRINKLE, "base");
		assertEquals(first, later);
		PlushieCommand.createHead("Grinkle", first);
		PlushieCommand.createHead("Grinkle", later);
		UUID expected = UUID.nameUUIDFromBytes(("rpcharacters:head:" + first).getBytes(StandardCharsets.UTF_8));
		bukkit.verify(() -> Bukkit.createProfile(expected), times(2));
		verify(profile, times(2)).setProperty(new ProfileProperty("textures", first));
	}

	@Test void outfitCompletionIsPermissionScoped() {
		assertEquals(List.of(), command.onTabComplete(player, null, "plushie", new String[] {"Grinkle"}));
		assertEquals(List.of("1", "2", "3"), command.onTabComplete(player, null, "plushie", new String[] {"Grinkle", ""}));
		assertEquals(List.of("2"), command.onTabComplete(player, null, "plushie", new String[] {"Grinkle", "2"}));
		when(player.hasPermission(PlushieCommand.PERMISSION)).thenReturn(false);
		assertEquals(List.of(), command.onTabComplete(player, null, "plushie", new String[] {"Grinkle", ""}));
	}

	private static String texture(String url, Integer timestamp) {
		JsonObject skin = new JsonObject(); skin.addProperty("url", url);
		JsonObject textures = new JsonObject(); textures.add("SKIN", skin);
		JsonObject profile = new JsonObject(); profile.add("textures", textures);
		if (timestamp != null) profile.addProperty("timestamp", timestamp);
		return Base64.getEncoder().encodeToString(profile.toString().getBytes(StandardCharsets.UTF_8));
	}

	private static String response(String id, String slot, String value, String signature) {
		JsonObject body = new JsonObject();
		body.addProperty("character_id", id); body.addProperty("slot", slot);
		body.addProperty("value", value); body.addProperty("signature", signature);
		return body.toString();
	}
}
