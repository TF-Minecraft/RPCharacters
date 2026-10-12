package net.tfminecraft.rpcharacters.paidchange;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.tfminecraft.tlibs.character.CharacterRef;

/** An in-memory ledger with DenarEconomy's rules: idempotent operation IDs, no overdrafts. */
public final class FakeLedger implements DenarWallet {
    public record Call(String opId, String reason, CharacterRef character, Account account, long cents, boolean debit) {}

    public final Map<Account, Long> balances = new EnumMap<>(Account.class);
    public final Map<String, Posting> stored = new LinkedHashMap<>();
    public final List<Call> applied = new ArrayList<>();
    public final List<Call> attempts = new ArrayList<>();
    private final Map<String, String> fingerprints = new HashMap<>();
    /** available() answer. */
    public boolean available = true;
    /** Every posting answers UNAVAILABLE and records nothing. */
    public boolean postingsDown;
    /** Only deposits answer UNAVAILABLE. */
    public boolean depositsDown;
    /** Postings apply but the caller hears UNAVAILABLE, as if the reply was lost. */
    public boolean loseReplies;
    /** status() can't say. */
    public boolean statusDown;
    /** Debits from the pouch are refused regardless of balance. */
    public boolean rejectPouch;
    public Runnable afterPosting = () -> {};

    public long get(Account account) {
        return balances.getOrDefault(account, 0L);
    }

    public void set(Account account, long cents) {
        balances.put(account, cents);
    }

    public long count(boolean debit) {
        return applied.stream().filter(call -> call.debit() == debit).count();
    }

    @Override public boolean available() { return available; }

    @Override public long balance(CharacterRef character, Account account) { return get(account); }

    @Override public Posting withdraw(String opId, String reason, CharacterRef character, Account account, long cents) {
        return post(new Call(opId, reason, character, account, cents, true));
    }

    @Override public Posting deposit(String opId, String reason, CharacterRef character, Account account, long cents) {
        return post(new Call(opId, reason, character, account, cents, false));
    }

    @Override public Optional<Posting> status(String opId) {
        return statusDown ? Optional.of(Posting.UNAVAILABLE) : Optional.ofNullable(stored.get(opId));
    }

    private Posting post(Call call) {
        attempts.add(call);
        if (postingsDown || (depositsDown && !call.debit())) {
            return Posting.UNAVAILABLE;
        }
        String fingerprint = call.reason() + "|" + call.character() + "|" + call.account() + "|" + call.cents() + "|" + call.debit();
        Posting result;
        if (stored.containsKey(call.opId())) {
            result = fingerprints.get(call.opId()).equals(fingerprint) ? stored.get(call.opId()) : Posting.CONFLICT;
        } else {
            boolean refused = call.debit() && (get(call.account()) < call.cents() || (rejectPouch && call.account() == Account.POUCH));
            result = refused ? Posting.REFUSED : Posting.APPLIED;
            if (!refused) {
                balances.merge(call.account(), call.debit() ? -call.cents() : call.cents(), Long::sum);
                applied.add(call);
            }
            stored.put(call.opId(), result);
            fingerprints.put(call.opId(), fingerprint);
        }
        afterPosting.run();
        return loseReplies ? Posting.UNAVAILABLE : result;
    }
}
