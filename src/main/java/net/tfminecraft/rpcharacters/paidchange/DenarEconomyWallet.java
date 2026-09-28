package net.tfminecraft.rpcharacters.paidchange;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * DenarEconomy accounts, reached through DenarEconomy's own class loader. RPCharacters can't
 * declare DenarEconomy as a dependency: DenarEconomy already loads after RPCharacters through
 * TLibs, ItemsAdder and BirdMessenger, so either depend would make a load cycle.
 */
final class DenarEconomyWallet implements DenarWallet {
	private static final Logger LOG = Logger.getLogger("RPCharacters");

	private Plugin boundTo;
	private Method balance;
	private Method apply;
	private Class<? extends Enum> accounts;

	@Override
	public boolean available() {
		return bind();
	}

	@Override
	public double balance(UUID playerId, Account account) {
		if (!bind()) {
			return 0.0;
		}
		try {
			return ((Number) balance.invoke(null, playerId, toDenar(account))).doubleValue();
		} catch (ReflectiveOperationException | RuntimeException e) {
			LOG.log(Level.WARNING, "[RPCharacters] Could not read a DenarEconomy balance", e);
			return 0.0;
		}
	}

	@Override
	public boolean withdraw(UUID playerId, Account account, double amount) {
		return amount > 0.0 && apply(playerId, account, -amount);
	}

	@Override
	public boolean deposit(UUID playerId, Account account, double amount) {
		return amount > 0.0 && apply(playerId, account, amount);
	}

	/** OfflineModifier.apply refuses a withdrawal the account can't cover. */
	private boolean apply(UUID playerId, Account account, double amount) {
		if (!bind()) {
			return false;
		}
		try {
			return Boolean.TRUE.equals(apply.invoke(null, playerId, toDenar(account), amount));
		} catch (ReflectiveOperationException | RuntimeException e) {
			LOG.log(Level.WARNING, "[RPCharacters] Could not change a DenarEconomy balance", e);
			return false;
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private Object toDenar(Account account) {
		return Enum.valueOf((Class) accounts, account == Account.BANK ? "BANK" : "POUCH");
	}

	/** Looks the API up again whenever DenarEconomy is (re)loaded. */
	@SuppressWarnings("unchecked")
	private synchronized boolean bind() {
		Plugin plugin = Bukkit.getPluginManager().getPlugin("DenarEconomy");
		if (plugin == null || !plugin.isEnabled()) {
			return false;
		}
		if (plugin == boundTo) {
			return true;
		}
		try {
			ClassLoader loader = plugin.getClass().getClassLoader();
			Class<?> modifier = Class.forName("net.tfminecraft.denareconomy.accounts.OfflineModifier", true, loader);
			accounts = (Class<? extends Enum>) Class.forName("net.tfminecraft.denareconomy.enums.Accounts", true, loader);
			balance = modifier.getMethod("balance", UUID.class, accounts);
			apply = modifier.getMethod("apply", UUID.class, accounts, double.class);
			boundTo = plugin;
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			LOG.log(Level.WARNING, "[RPCharacters] DenarEconomy has no OfflineModifier API; paid changes are off", e);
			return false;
		}
	}
}
