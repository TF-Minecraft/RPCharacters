package net.tfminecraft.rpcharacters.estate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import com.google.common.hash.Hashing;

/**
 * Hands stripped bodies to their recipients, one batch of free storage slots per collect. The remaining
 * collection is written durably before any item is given, so a crash can lose at most the batch just
 * handed over and never duplicates it; each batch is logged with stack hashes (design R16).
 */
final class EstateCollections {
    private final EstateBook book;
    private final Function<byte[], ItemStack> decode;
    private final Consumer<UUID> rosterPush;
    private final Logger logger;

    EstateCollections(EstateBook book, Function<byte[], ItemStack> decode, Consumer<UUID> rosterPush, Logger logger) {
        this.book = book;
        this.decode = decode;
        this.rosterPush = rosterPush;
        this.logger = logger;
    }

    /** Collections addressed to this character with items left, oldest death first. */
    List<EstateRecord> waitingFor(UUID characterId) {
        return book.all().stream()
                .filter(r -> r.body != null && r.body.collectable() && !r.body.stacks.isEmpty()
                        && r.body.addressedTo != null && r.body.addressedTo.ref().characterId().equals(characterId))
                .sorted(Comparator.comparing((EstateRecord r) -> r.createdAt))
                .toList();
    }

    /** {@code /estate collect} by the recipient character's current session. */
    String collect(Player player, UUID characterId) {
        List<EstateRecord> waiting = waitingFor(characterId);
        if (waiting.isEmpty()) return "You have no inheritance to collect.";
        return deliver(player, waiting.getFirst(), "recipient " + characterId);
    }

    /**
     * {@code /estate collect <deathId>} by staff, for a collection no character can collect: parked for staff,
     * or {@code stranded} with a recipient who is not alive and whose own estate will never re-address it.
     */
    String collectAsStaff(Player staff, EstateRecord record, boolean stranded) {
        if (record.body == null || !record.body.collectable() || record.body.stacks.isEmpty()) {
            return "That estate has nothing to collect.";
        }
        if (record.body.addressedTo != null && !stranded) {
            return "That collection belongs to " + record.body.addressedTo.name() + "; they collect it themselves.";
        }
        return deliver(staff, record, "staff " + staff.getName());
    }

    private String deliver(Player player, EstateRecord record, String by) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] storage = inventory.getStorageContents();
        List<Integer> free = new ArrayList<>();
        for (int slot = 0; slot < storage.length; slot++) {
            if (storage[slot] == null || storage[slot].isEmpty()) free.add(slot);
        }
        if (free.isEmpty()) return "Make room in your inventory, then collect again.";
        int count = Math.min(free.size(), record.body.stacks.size());
        List<String> batch = record.body.stacks.subList(0, count);
        List<ItemStack> items = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        try {
            for (String stack : batch) {
                byte[] bytes = Base64.getDecoder().decode(stack);
                items.add(decode.apply(bytes));
                hashes.add(Hashing.sha256().hashBytes(bytes).toString());
            }
        } catch (RuntimeException error) {
            logger.log(Level.SEVERE, "Estate collection " + record.deathId + " holds an unreadable stack", error);
            return "This inheritance needs staff attention. Nothing was handed over.";
        }
        try {
            EstateRecord next = EstateBook.edit(record);
            next.body.stacks = new ArrayList<>(record.body.stacks.subList(count, record.body.stacks.size()));
            next.body.delivered += count;
            book.save(next);
        } catch (IOException error) {
            logger.log(Level.SEVERE, "Estate collection " + record.deathId + " was not saved; nothing handed over", error);
            return "Your inheritance could not be saved, so nothing was handed over. Try again or contact staff.";
        }
        for (int i = 0; i < count; i++) inventory.setItem(free.get(i), items.get(i));
        player.saveData();
        book.log("collections.log", record.deathId + " " + by + " to " + player.getUniqueId() + " " + hashes);
        rosterPush.accept(record.testator.ownerId());
        int left = record.body.stacks.size() - count;
        return "You collected " + count + (count == 1 ? " stack" : " stacks") + " from the estate of " + record.name
                + (left == 0 ? "." : "; " + left + " more wait for room. Collect again when you have space.");
    }
}
