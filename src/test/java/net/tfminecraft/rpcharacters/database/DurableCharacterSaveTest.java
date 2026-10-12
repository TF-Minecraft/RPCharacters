package net.tfminecraft.rpcharacters.database;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet;
import net.tfminecraft.rpcharacters.paidchange.DenarWallet.Account;
import net.tfminecraft.rpcharacters.paidchange.FakeLedger;
import net.tfminecraft.rpcharacters.paidchange.Payments;
import net.tfminecraft.rpcharacters.paidchange.PendingPaidChange;

class DurableCharacterSaveTest extends DatabaseFixture {
    private final Database.ChannelSync original = Database.sync;
    private final List<String> events = new ArrayList<>();
    private final FakeLedger wallet = new FakeLedger();

    @AfterEach void restoreSync() { Database.sync = original; }

    private Payments.Charge charge(RPCharacter character) {
        PlayerData account = new PlayerData(owner);
        return Payments.take(wallet, DenarWallet.PAID_CHANGE, "class", Payments.payer(owner, character), List.of(Account.POUCH), 1_000,
                payment -> {
                    character.setPendingPaidChange(payment == null ? null : new PendingPaidChange("class", "class", payment, "before"));
                    return db.trySaveCharacterDurably(account, character);
                });
    }

    @Test void theIntentFileAndItsDirectoryAreForcedBeforeTheDebitIsPosted() throws Exception {
        RPCharacter character = character(UUID.randomUUID().toString(), false);
        Database.sync = channel -> { events.add("force"); channel.force(true); };
        wallet.set(Account.POUCH, 5_000);
        wallet.beforePosting = () -> {
            try {
                events.add("withdraw, on disk: " + Files.readString(characterFile(character.getId())).contains("paid-change-pending"));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
        assertEquals(Payments.Taken.PAID, charge(character).status());
        assertEquals(List.of("force", "force", "withdraw, on disk: true"), events);
    }

    @Test void anIntentWhoseDirectoryCannotBeForcedIsNeverDebited() {
        RPCharacter character = character(UUID.randomUUID().toString(), false);
        Database.sync = channel -> { events.add("force"); if (events.size() == 2) throw new IOException("directory sync failed"); };
        wallet.set(Account.POUCH, 5_000);
        assertEquals(Payments.Taken.UNAVAILABLE, charge(character).status());
        assertTrue(wallet.attempts.isEmpty()); assertEquals(5_000, wallet.get(Account.POUCH));
    }
}
