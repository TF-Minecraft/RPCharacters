package net.tfminecraft.rpcharacters.estate;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
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

import net.tfminecraft.tlibs.character.estate.EstateState;

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
        if (waiting.isEmpty()) return notYet(player.getUniqueId(), characterId);
        return deliver(player, waiting.getFirst(), "recipient " + characterId);
    }

    /**
     * Why nothing can be collected yet, for an estate that will give this character something. Wills are secret,
     * so an estate not yet decided is named only to an heir on the testator's own account.
     */
    private String notYet(UUID account, UUID characterId) {
        Instant now = book.now();
        for (EstateRecord record : book.all()) {
            if (record.revivedAt != null) continue;
            if ((record.state == EstateState.OPEN || record.state == EstateState.HELD) && characterId.equals(record.willHeir)
                    && account.equals(record.testator.ownerId())) {
                if (record.state == EstateState.HELD) {
                    return "Staff are holding the estate of " + record.name + ". It passes to you once they release it.";
                }
                if (now.isBefore(record.dueAt)) {
                    return "The estate of " + record.name + " passes to you " + in(Duration.between(now, record.dueAt))
                            + ". Collect it then.";
                }
                return "The estate of " + record.name + " is due and passes to you once it can be decided. Try again later.";
            }
            if (record.settling() && record.decision.property().recipient().character()
                    .filter(ref -> ref.characterId().equals(characterId)).isPresent()) {
                EstateRecord.Report body = record.participants.get(BodyEstate.ID);
                if (body == null || body.settles(record.decision.revision())) continue;
                return body.outcome().equals("BLOCKED")
                        ? "The estate of " + record.name + " needs staff before it can be handed over. Contact staff."
                        : "The estate of " + record.name + " is being handed over to you. Try again in a few minutes.";
            }
        }
        return "You have no inheritance to collect.";
    }

    /** "in 40 minutes", "in about 17 hours", "in about 3 days". */
    static String in(Duration left) {
        long minutes = Math.max(1, (left.toSeconds() + 59) / 60);
        if (minutes < 90) return "in " + minutes + (minutes == 1 ? " minute" : " minutes");
        long hours = Math.round(minutes / 60.0);
        if (hours < 48) return "in about " + hours + " hours";
        return "in about " + Math.round(hours / 24.0) + " days";
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
