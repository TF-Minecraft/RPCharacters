package net.tfminecraft.rpcharacters.body;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import io.lumine.mythic.lib.api.item.NBTItem;
import net.Indyuce.mmoitems.MMOItems;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.body.BodySnapshot.Container;
import net.tfminecraft.rpcharacters.database.Database;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/**
 * {@code /rpcharacter admin body ...}: staff read and change the items in a stowed character body, also while the
 * player is offline. Text only, so the console and staff bots can use it as well as staff in game:
 * <ul>
 * <li>{@code body <player>}: the account's characters;</li>
 * <li>{@code body <player> <character> list}: every item, with slot numbers and the body version;</li>
 * <li>{@code body <player> <character> put <inv|ender> <slot|auto> <version> <held|mi TYPE ID [n]|item MATERIAL [n]|base64 DATA>};</li>
 * <li>{@code body <player> <character> take <inv|ender> <slot> <version> <TYPE:ID|material> <delete|to PLAYER>}.</li>
 * </ul>
 * Changes need the version {@code list} showed and, for {@code take}, the item expected in the slot, so a command
 * typed from an older listing is refused instead of changing the wrong thing. Each change is one body revision
 * (see {@link BodyEditor}) and one line in {@code logs/body-edits.log}, which keeps every item taken as base64.
 * A character is picked by its id, the first 8 characters of its id, or its name with {@code _} for spaces.
 */
public final class BodyAdminCommand {
    public static final String SUBCOMMAND = "body";
    public static final List<String> ACTIONS = List.of("list", "put", "take");
    static final String VIEW_PERMISSION = "rpchar.body.view";
    static final String EDIT_PERMISSION = "rpchar.body.edit";
    static final String USAGE = "Usage: /rpcharacter admin body <player> [<character> list"
            + " | <character> put <inv|ender> <slot|auto> <version> <held|mi TYPE ID [amount]|item MATERIAL [amount]|base64 DATA>"
            + " | <character> take <inv|ender> <slot> <version> <TYPE:ID|material> <delete|to PLAYER>]";
    private static final Logger LOG = Logger.getLogger("RPCharacters");
    private static final String[] ARMOUR = {"boots", "leggings", "chestplate", "helmet", "offhand"};

    private BodyAdminCommand() {}

    public static boolean canUse(CommandSender sender) {
        return sender.hasPermission(VIEW_PERMISSION) || sender.hasPermission(EDIT_PERMISSION);
    }

    public static boolean handle(CommandSender sender, String[] args, int index) {
        if (!canUse(sender)) return error(sender, "You do not have permission to use this command.");
        CharacterBodies bodies = RPCharacters.getCharacterBodies();
        if (bodies == null) return error(sender, "Character bodies are not running on this server.");
        if (args.length <= index) return error(sender, USAGE);
        Account account = account(args[index]);
        if (account == null) return error(sender, "No player called " + args[index] + " has played here.");
        if (args.length == index + 1) return listCharacters(sender, bodies, account);
        RPCharacter character = character(account.data, args[index + 1]);
        if (character == null) {
            return error(sender, "No single character of " + account.name + " matches " + args[index + 1]
                    + ". Use the id from /rpcharacter admin body " + account.name + ".");
        }
        String action = args.length > index + 2 ? args[index + 2].toLowerCase(Locale.ROOT) : "list";
        UUID characterId = UUID.fromString(character.getId());
        try {
            switch (action) {
                case "list" -> listItems(sender, bodies, account, character, characterId);
                case "put", "take" -> {
                    if (!sender.hasPermission(EDIT_PERMISSION)) return error(sender, "You may only list bodies.");
                    if (character.getStatus() != Status.ALIVE) {
                        return error(sender, "Only living characters' bodies can be changed; this one is " + character.getStatus() + ".");
                    }
                    if (action.equals("put")) put(sender, bodies, account, characterId, args, index + 3);
                    else take(sender, bodies, account, characterId, args, index + 3);
                }
                default -> error(sender, USAGE);
            }
        } catch (BodyEditor.Refused | IllegalArgumentException refused) {
            error(sender, "Nothing changed: " + refused.getMessage());
        } catch (IOException | RuntimeException failure) {
            LOG.log(Level.WARNING, "[RPCharacters] Body command by " + sender.getName() + " failed", failure);
            error(sender, "Nothing changed: " + failure.getMessage());
        }
        return true;
    }

    private record Account(UUID owner, String name, PlayerData data) {
    }

    /**
     * By name or account UUID (the server's name cache forgets long-inactive players). Online accounts use their
     * live data; offline ones are read from disk and never saved here.
     */
    private static Account account(String name) {
        UUID id = uuid(name);
        if (id != null) {
            Player online = Bukkit.getPlayer(id);
            if (online != null) return account(online.getName());
            if (!Files.exists(Path.of("plugins/RPCharacters/data/playerdata", id + ".json"))) return null;
            OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
            PlayerData data = new Database().loadPlayerData(id);
            return data == null ? null : new Account(id, offline.getName() == null ? id.toString() : offline.getName(), data);
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            PlayerData data = PlayerManager.get(online);
            return data == null ? null : new Account(online.getUniqueId(), online.getName(), data);
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(name);
        if (offline == null) return null;
        PlayerData data = new Database().loadPlayerData(offline.getUniqueId());
        return data == null ? null : new Account(offline.getUniqueId(), offline.getName() == null ? name : offline.getName(), data);
    }

    private static UUID uuid(String text) {
        if (text.length() != 36) return null;
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException notUuid) {
            return null;
        }
    }

    static RPCharacter character(PlayerData data, String wanted) {
        String lower = wanted.toLowerCase(Locale.ROOT);
        String name = wanted.replace('_', ' ');
        RPCharacter found = null;
        for (RPCharacter character : data.getCharacters()) {
            String id = character.getId().toLowerCase(Locale.ROOT);
            boolean match = id.equals(lower) || lower.length() >= 8 && id.startsWith(lower)
                    || character.getName() != null && character.getName().equalsIgnoreCase(name);
            if (!match) continue;
            if (found != null) return null;
            found = character;
        }
        return found;
    }

    private static boolean listCharacters(CommandSender sender, CharacterBodies bodies, Account account) {
        if (account.data.getCharacters().isEmpty()) return error(sender, account.name + " has no characters.");
        RPTexts.send(sender, RPTexts.ACCENT + "Characters of " + account.name + ":");
        for (RPCharacter character : account.data.getCharacters()) {
            String body;
            try {
                body = bodies.store().read(account.owner, UUID.fromString(character.getId()))
                        .map(record -> "body version " + record.rev()).orElse("no stored body");
            } catch (IOException error) {
                body = "body unreadable: " + error.getMessage();
            }
            RPTexts.send(sender, RPTexts.MUTED + "- " + RPTexts.WARN + character.getName() + RPTexts.MUTED + " id "
                    + character.getId().substring(0, 8) + ", " + character.getStatus() + ", " + body);
        }
        return true;
    }

    private static void listItems(CommandSender sender, CharacterBodies bodies, Account account, RPCharacter character,
            UUID characterId) throws IOException {
        BodyEditor editor = bodies.editor();
        BodyRecord record = editor.read(account.owner, characterId);
        String blocked = character.getStatus() != Status.ALIVE ? "the character is " + character.getStatus()
                : bodies.editBlocked(account.owner, characterId);
        RPTexts.send(sender, RPTexts.ACCENT + character.getName() + RPTexts.MUTED + " (" + account.name + ") body version "
                + RPTexts.WARN + record.rev() + RPTexts.MUTED + ", saved " + record.savedAt() + " (" + record.reason() + "). "
                + (blocked == null ? RPTexts.SUCCESS + "Can be changed now." : RPTexts.ERROR + "Cannot be changed now: " + blocked));
        int shown = 0;
        for (Container container : Container.values()) {
            for (int slot = 0; slot < record.body().slotCount(container); slot++) {
                byte[] item = record.body().slot(container, slot);
                if (item.length == 0) continue;
                RPTexts.send(sender, RPTexts.MUTED + slotName(container, slot)
                        + (editor.accessorySlot(container, slot) ? " (accessory)" : "") + ": " + RPTexts.WHITE + describe(item));
                shown++;
            }
        }
        if (shown == 0) RPTexts.send(sender, RPTexts.MUTED + "The body holds no items.");
    }

    private static void put(CommandSender sender, CharacterBodies bodies, Account account, UUID characterId, String[] args,
            int at) throws IOException {
        if (args.length < at + 4) {
            error(sender, USAGE);
            return;
        }
        Container container = container(args[at]);
        int slot = args[at + 1].equalsIgnoreCase("auto") ? -1 : number(args[at + 1], "slot");
        long version = number(args[at + 2], "version");
        String source = args[at + 3].toLowerCase(Locale.ROOT);
        Player holder = null;
        byte[] item;
        switch (source) {
            case "held" -> {
                if (args.length != at + 4) throw new IllegalArgumentException(USAGE);
                if (!(sender instanceof Player player)) throw new IllegalArgumentException("Only a player can put the held item.");
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand.getType().isAir()) throw new IllegalArgumentException("Hold the item to put in.");
                holder = player;
                item = hand.serializeAsBytes();
            }
            case "mi" -> {
                if (args.length < at + 6 || args.length > at + 7) throw new IllegalArgumentException(USAGE);
                ItemStack made = MMOItems.plugin.getItem(args[at + 4].toUpperCase(Locale.ROOT), args[at + 5].toUpperCase(Locale.ROOT));
                if (made == null || made.getType().isAir()) throw new IllegalArgumentException("MMOItems has no " + args[at + 4] + " " + args[at + 5] + ".");
                item = amount(made, args, at + 6).serializeAsBytes();
            }
            case "item" -> {
                if (args.length < at + 5 || args.length > at + 6) throw new IllegalArgumentException(USAGE);
                Material material = Material.matchMaterial(args[at + 4]);
                if (material == null || !material.isItem() || material.isAir()) throw new IllegalArgumentException("No item called " + args[at + 4] + ".");
                item = amount(new ItemStack(material), args, at + 5).serializeAsBytes();
            }
            case "base64" -> {
                if (args.length != at + 5) throw new IllegalArgumentException(USAGE);
                item = Base64.getDecoder().decode(args[at + 4]);
                if (ItemStack.deserializeBytes(item).getType().isAir()) throw new IllegalArgumentException("That data holds no item.");
            }
            default -> throw new IllegalArgumentException(USAGE);
        }
        BodyEditor.Change change = bodies.editor().put(account.owner, characterId, container, slot, item, version, reason("put", sender));
        // Same tick as the write: the held item leaves the hand only once the body holds it.
        if (holder != null) {
            holder.getInventory().setItemInMainHand(null);
            // Saved now, so a crash before Paper's autosave cannot bring the item back into the hand.
            holder.saveData();
        }
        audit(sender, "put", account, characterId, container, version, change, source);
        RPTexts.send(sender, RPTexts.SUCCESS + "Put " + describe(change.item()) + " into " + slotName(container, change.slot())
                + ". Body version is now " + change.record().rev() + ".");
    }

    private static void take(CommandSender sender, CharacterBodies bodies, Account account, UUID characterId, String[] args,
            int at) throws IOException {
        if (args.length < at + 5) {
            error(sender, USAGE);
            return;
        }
        Container container = container(args[at]);
        int slot = number(args[at + 1], "slot");
        long version = number(args[at + 2], "version");
        String expected = args[at + 3];
        String destination = args[at + 4].toLowerCase(Locale.ROOT);
        Player receiver = null;
        if (destination.equals("to") && args.length == at + 6) {
            receiver = Bukkit.getPlayerExact(args[at + 5]);
            if (receiver == null) throw new IllegalArgumentException("No online player called " + args[at + 5] + ".");
            // A room, dead or respawning inventory is replaced when a body is applied, so the item would be lost.
            if (!bodies.hasLiveBody(receiver) || bodies.isSelecting(receiver) || bodies.isRespawning(receiver) || receiver.isDead()) {
                throw new IllegalArgumentException(receiver.getName() + " must be playing a character, alive, to receive the item.");
            }
        } else if (!destination.equals("delete") || args.length != at + 5) {
            throw new IllegalArgumentException(USAGE);
        }
        BodyEditor editor = bodies.editor();
        if (receiver != null) {
            byte[] held = editor.read(account.owner, characterId).body().slot(container, slot);
            if (held.length > 0 && !fits(receiver, ItemStack.deserializeBytes(held))) {
                throw new IllegalArgumentException(receiver.getName() + " has no room for that stack.");
            }
        }
        BodyEditor.Change change = editor.take(account.owner, characterId, container, slot,
                bytes -> matches(expected, bytes), version, reason("take", sender));
        String where = receiver == null ? "deleted" : "to " + receiver.getName();
        if (receiver != null) {
            // Same tick as the write: the item reaches the receiver only once the body no longer holds it.
            Player to = receiver;
            to.getInventory().addItem(ItemStack.deserializeBytes(change.item()))
                    .values().forEach(left -> to.getWorld().dropItemNaturally(to.getLocation(), left));
            // Saved now, so a crash before Paper's autosave cannot lose what the body just gave up.
            to.saveData();
        }
        audit(sender, "take", account, characterId, container, version, change, where);
        RPTexts.send(sender, RPTexts.SUCCESS + "Took " + describe(change.item()) + " out of " + slotName(container, slot)
                + " (" + where + "). Body version is now " + change.record().rev() + ".");
    }

    /** True when the whole stack fits in the receiver's storage, so nothing would be dropped on the ground. */
    static boolean fits(Player receiver, ItemStack item) {
        int need = item.getAmount();
        for (ItemStack held : receiver.getInventory().getStorageContents()) {
            if (held == null || held.getType().isAir()) return true;
            if (held.isSimilar(item)) need -= Math.max(0, held.getMaxStackSize() - held.getAmount());
            if (need <= 0) return true;
        }
        return false;
    }

    /** The expected item: MMOItems {@code TYPE:ID} for an MMOItems item, the material otherwise. */
    static boolean matches(String expected, byte[] bytes) {
        ItemStack item = ItemStack.deserializeBytes(bytes);
        String id = mmoId(item);
        if (id != null) return id.equalsIgnoreCase(expected);
        Material material = Material.matchMaterial(expected);
        return material != null && item.getType() == material;
    }

    static String describe(byte[] bytes) {
        try {
            ItemStack item = ItemStack.deserializeBytes(bytes);
            ItemMeta meta = item.getItemMeta();
            String name = meta != null && meta.hasDisplayName()
                    ? PlainTextComponentSerializer.plainText().serialize(meta.displayName()) + " " : "";
            String id = mmoId(item);
            return item.getAmount() + "x " + name + "(" + (id != null ? id : item.getType().getKey().getKey()) + ")";
        } catch (RuntimeException error) {
            return "unreadable item (" + bytes.length + " bytes)";
        }
    }

    static String mmoId(ItemStack item) {
        try {
            NBTItem nbt = NBTItem.get(item);
            return nbt.hasType() ? nbt.getType() + ":" + nbt.getString("MMOITEMS_ITEM_ID") : null;
        } catch (RuntimeException | LinkageError error) {
            return null;
        }
    }

    static String slotName(Container container, int slot) {
        if (container == Container.ENDER_CHEST) return "ender " + slot;
        return slot >= 36 && slot <= 40 ? "inv " + slot + " (" + ARMOUR[slot - 36] + ")" : "inv " + slot;
    }

    private static Container container(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "inv", "inventory" -> Container.INVENTORY;
            case "ender", "enderchest" -> Container.ENDER_CHEST;
            default -> throw new IllegalArgumentException("Say inv or ender, not " + text + ".");
        };
    }

    private static int number(String text, String what) {
        try {
            int value = Integer.parseInt(text);
            if (value >= 0) return value;
        } catch (NumberFormatException ignored) {
            // falls through to the message below
        }
        throw new IllegalArgumentException("The " + what + " must be a whole number from 0 up.");
    }

    private static ItemStack amount(ItemStack item, String[] args, int at) {
        int count = args.length > at ? number(args[at], "amount") : 1;
        if (count < 1 || count > item.getMaxStackSize()) throw new IllegalArgumentException("The amount must be 1 to " + item.getMaxStackSize() + ".");
        item.setAmount(count);
        return item;
    }

    private static String reason(String action, CommandSender sender) {
        String reason = "staff-" + action + ":" + sender.getName();
        return reason.length() > 200 ? reason.substring(0, 200) : reason;
    }

    private static void audit(CommandSender sender, String action, Account account, UUID characterId, Container container,
            long version, BodyEditor.Change change, String detail) {
        String line = Instant.now() + " " + sender.getName() + " " + action + " " + slotName(container, change.slot())
                + " of " + account.name + " " + account.owner + " character " + characterId + " version " + version + "->"
                + change.record().rev() + " " + describe(change.item()) + " " + detail + " data "
                + Base64.getEncoder().encodeToString(change.item());
        LOG.info("[RPCharacters] [Body edit] " + line);
        List<String> lines = new ArrayList<>(List.of(line));
        try {
            Path log = RPCharacters.plugin.getDataFolder().toPath().resolve("logs").resolve("body-edits.log");
            Files.createDirectories(log.getParent());
            Files.write(log, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException error) {
            LOG.log(Level.WARNING, "[RPCharacters] Could not append to body-edits.log; the console line above is the record", error);
        }
    }

    private static boolean error(CommandSender sender, String message) {
        RPTexts.send(sender, RPTexts.ERROR + message);
        return true;
    }
}
