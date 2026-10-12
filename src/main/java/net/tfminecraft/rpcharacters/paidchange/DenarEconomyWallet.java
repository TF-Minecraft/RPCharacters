package net.tfminecraft.rpcharacters.paidchange;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import net.tfminecraft.tlibs.character.CharacterRef;

/**
 * DenarEconomy's ledger API ({@code Denars}), reached through DenarEconomy's own class loader.
 * RPCharacters can't declare DenarEconomy as a dependency: DenarEconomy already loads after
 * RPCharacters through TLibs, ItemsAdder and BirdMessenger, so either depend would make a load
 * cycle. {@link CharacterRef} comes from TLibs, which both plugins share.
 */
public final class DenarEconomyWallet implements DenarWallet {
	private static final Logger LOG = Logger.getLogger("RPCharacters");

	private Plugin boundTo;
	private Method ready;
	private Method pouch;
	private Method bank;
	private Method balance;
	private Method transfer;
	private Method status;
	private String mint;

	@Override
	public boolean available() {
		return bind() && ready();
	}

	@Override
	public long balance(CharacterRef character, Account account) {
		if (!bind()) {
			return 0L;
		}
		try {
			return ((Number) balance.invoke(null, accountId(character, account))).longValue();
		} catch (ReflectiveOperationException | RuntimeException e) {
			LOG.log(Level.WARNING, "[RPCharacters] Could not read a DenarEconomy balance", e);
			return 0L;
		}
	}

	@Override
	public Posting withdraw(String opId, String reason, CharacterRef character, Account account, long cents) {
		return cents <= 0 ? Posting.REFUSED : transfer(opId, reason, character, account, cents, true);
	}

	@Override
	public Posting deposit(String opId, String reason, CharacterRef character, Account account, long cents) {
		return cents <= 0 ? Posting.REFUSED : transfer(opId, reason, character, account, cents, false);
	}

	private Posting transfer(String opId, String reason, CharacterRef character, Account account, long cents,
			boolean debit) {
		if (!bind()) {
			return Posting.UNAVAILABLE;
		}
		try {
			String own = accountId(character, account);
			Object result = transfer.invoke(null, opId, reason, debit ? own : mint, debit ? mint : own, cents);
			return Posting.valueOf(((Enum<?>) result).name());
		} catch (ReflectiveOperationException | RuntimeException e) {
			LOG.log(Level.WARNING, "[RPCharacters] Could not post " + opId + " to DenarEconomy", e);
			return Posting.UNAVAILABLE;
		}
	}

	@Override
	public Optional<Posting> status(String opId) {
		// A closed ledger also answers "never posted", so only trust the answer from an open one.
		if (!available()) {
			return Optional.of(Posting.UNAVAILABLE);
		}
		try {
			Optional<?> stored = (Optional<?>) status.invoke(null, opId);
			if (!ready()) {
				return Optional.of(Posting.UNAVAILABLE);
			}
			return stored.map(result -> Posting.valueOf(((Enum<?>) result).name()));
		} catch (ReflectiveOperationException | RuntimeException e) {
			LOG.log(Level.WARNING, "[RPCharacters] Could not read " + opId + " from DenarEconomy", e);
			return Optional.of(Posting.UNAVAILABLE);
		}
	}

	private String accountId(CharacterRef character, Account account) throws ReflectiveOperationException {
		return (String) (account == Account.BANK ? bank : pouch).invoke(null, character);
	}

	private boolean ready() {
		try {
			return Boolean.TRUE.equals(ready.invoke(null));
		} catch (ReflectiveOperationException | RuntimeException e) {
			LOG.log(Level.WARNING, "[RPCharacters] Could not ask DenarEconomy whether its ledger is open", e);
			return false;
		}
	}

	/** Looks the API up again whenever DenarEconomy is (re)loaded. */
	private synchronized boolean bind() {
		Plugin plugin = Bukkit.getPluginManager().getPlugin("DenarEconomy");
		if (plugin == null || !plugin.isEnabled()) {
			return false;
		}
		if (plugin == boundTo) {
			return true;
		}
		try {
			Class<?> denars = Class.forName("net.tfminecraft.denareconomy.api.Denars", true,
					plugin.getClass().getClassLoader());
			Method[] api = {denars.getMethod("ready"), denars.getMethod("pouch", CharacterRef.class),
					denars.getMethod("bank", CharacterRef.class), denars.getMethod("balance", String.class),
					denars.getMethod("transfer", String.class, String.class, String.class, String.class, long.class),
					denars.getMethod("status", String.class)};
			mint = (String) denars.getField("MINT").get(null);
			ready = api[0];
			pouch = api[1];
			bank = api[2];
			balance = api[3];
			transfer = api[4];
			status = api[5];
			boundTo = plugin;
			return true;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			LOG.log(Level.WARNING, "[RPCharacters] DenarEconomy has no Denars ledger API; paid changes are off", e);
			return false;
		}
	}
}
