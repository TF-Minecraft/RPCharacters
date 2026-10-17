package net.tfminecraft.rpcharacters.wardrobe;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.api.ProvinceSystemClient;
import net.tfminecraft.rpcharacters.playerlist.SkinPortrait;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.Characters;

/** Gives a static miniature from an explicitly selected character outfit. */
public final class PlushieCommand implements CommandExecutor, TabCompleter {

	public static final String PERMISSION = "rpchar.plushie.give";
	private static final List<String> SLOTS = List.of("base", "extra_1", "extra_2");
	private final Set<UUID> pending = new HashSet<>();

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!sender.hasPermission(PERMISSION)) {
			sender.sendMessage("You do not have permission to create plushies.");
			return true;
		}
		if (!(sender instanceof Player player)) {
			sender.sendMessage("Run this command in game to receive the plushie.");
			return true;
		}
		int outfit = 1;
		int nameLength = args.length;
		if (args.length > 1 && args[args.length - 1].matches("-?\\d+")) {
			try {
				outfit = Integer.parseInt(args[--nameLength]);
			} catch (NumberFormatException invalid) {
				outfit = 0;
			}
		}
		if (nameLength == 0 || outfit < 1 || outfit > SLOTS.size()) {
			sender.sendMessage("Usage: /" + label + " <character name or UUID> [1|2|3]");
			return true;
		}
		String name = String.join(" ", Arrays.copyOf(args, nameLength)).trim();
		var identity = RPCharacters.getCharacterIdentity();
		List<CharacterInfo> matches;
		try {
			matches = identity.info(UUID.fromString(name)).stream().toList();
		} catch (IllegalArgumentException notUuid) {
			matches = identity.findByName(name);
		}
		if (matches.size() != 1) {
			sender.sendMessage(matches.isEmpty() ? "Character not found: " + name
					: "More than one character has that name. Use their character UUID: "
							+ matches.stream().map(info -> info.ref().characterId().toString()).toList());
			return true;
		}
		UUID playerId = player.getUniqueId();
		if (!pending.add(playerId)) {
			sender.sendMessage("Your previous plushie is still loading.");
			return true;
		}
		CharacterInfo character = matches.getFirst();
		String slot = SLOTS.get(outfit - 1);
		int selectedOutfit = outfit;
		var session = Characters.currentSession(player).orElse(null);
		player.sendMessage("Creating " + character.name() + "'s plushie from outfit " + outfit + ". A new outfit may take a moment…");
		Bukkit.getScheduler().runTaskAsynchronously(RPCharacters.plugin, () -> loadTexture(character, slot, null, 90, texture -> {
			Bukkit.getScheduler().runTask(RPCharacters.plugin, () -> {
				pending.remove(playerId);
				if (!player.isOnline() || !player.hasPermission(PERMISSION)) return;
				if (session == null ? Characters.currentSession(player).isPresent() : !Characters.isCurrent(session)) {
					player.sendMessage("Your character changed while loading. Run the command again.");
					return;
				}
				if (texture == null) {
					player.sendMessage("Could not create that plushie. The outfit may be unavailable or generation failed. Try again shortly. No plushie was created.");
					return;
				}
				ItemStack head = createHead(character.name(), texture);
				if (!player.getInventory().addItem(head).isEmpty()) {
					player.sendMessage("Your inventory is full. Make room and try again.");
					return;
				}
				player.sendMessage("Created " + character.name() + " Plushie from wardrobe outfit " + selectedOutfit + ".");
			});
		}));
		return true;
	}

	static void loadTexture(CharacterInfo character, String slot, String source, int remaining, Consumer<String> complete) {
		String normalized = null;
		try {
			String id = character.ref().characterId().toString();
			var result = ProvinceSystemClient.requestPlushieSkin(character.ref().ownerId().toString(), id, slot, source);
			if (!result.ok) {
				complete.accept(null);
				return;
			}
			JsonObject body = JsonParser.parseString(result.body).getAsJsonObject();
			String digest = body.get("source").getAsString();
			if (!id.equalsIgnoreCase(body.get("character_id").getAsString())
					|| !slot.equals(body.get("slot").getAsString())
					|| !digest.matches("[a-f0-9]{64}") || (source != null && !source.equals(digest))) {
				complete.accept(null);
				return;
			}
			if ("pending".equals(body.get("status").getAsString()) && remaining > 0) {
				Bukkit.getScheduler().runTaskLaterAsynchronously(RPCharacters.plugin,
						() -> loadTexture(character, slot, digest, remaining - 1, complete), 20L);
				return;
			}
			if ("ready".equals(body.get("status").getAsString()) && !body.get("signature").getAsString().isBlank()) {
				normalized = normalizeTexture(body.get("value").getAsString());
			}
		} catch (RuntimeException unavailable) {
			// Malformed responses never fall back to a live player skin.
		}
		complete.accept(normalized);
	}

	private static String normalizeTexture(String value) {
		var texture = SkinPortrait.parseTextures(value);
		if (texture == null) return null;
		// A URL-only property excludes changing profile timestamps, identities and signatures.
		JsonObject skin = new JsonObject();
		skin.addProperty("url", texture.url());
		JsonObject textures = new JsonObject();
		textures.add("SKIN", skin);
		JsonObject profile = new JsonObject();
		profile.add("textures", textures);
		return Base64.getEncoder().encodeToString(profile.toString().getBytes(StandardCharsets.UTF_8));
	}

	static ItemStack createHead(String characterName, String texture) {
		ItemStack head = new ItemStack(Material.PLAYER_HEAD);
		SkullMeta meta = (SkullMeta) head.getItemMeta();
		UUID id = UUID.nameUUIDFromBytes(("rpcharacters:head:" + texture).getBytes(StandardCharsets.UTF_8));
		var profile = Bukkit.createProfile(id);
		profile.setProperty(new ProfileProperty("textures", texture));
		meta.setPlayerProfile(profile);
		meta.displayName(Component.text(characterName + " Plushie").decoration(TextDecoration.ITALIC, false));
		head.setItemMeta(meta);
		return head;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (!sender.hasPermission(PERMISSION) || args.length < 2) return List.of();
		return List.of("1", "2", "3").stream().filter(number -> number.startsWith(args[args.length - 1])).toList();
	}
}
