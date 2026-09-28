package net.tfminecraft.rpcharacters.paidchange;

import java.util.UUID;

import org.bukkit.Bukkit;

import net.tfminecraft.denareconomy.accounts.OfflineModifier;
import net.tfminecraft.denareconomy.enums.Accounts;

/** DenarEconomy accounts. DenarEconomy is a soft dependency, so check {@link #available()} first. */
final class DenarEconomyWallet implements DenarWallet {

	@Override
	public boolean available() {
		return Bukkit.getPluginManager().isPluginEnabled("DenarEconomy");
	}

	@Override
	public double balance(UUID playerId, Account account) {
		return OfflineModifier.balance(playerId, toDenar(account));
	}

	@Override
	public boolean withdraw(UUID playerId, Account account, double amount) {
		return amount > 0.0 && OfflineModifier.apply(playerId, toDenar(account), -amount);
	}

	@Override
	public boolean deposit(UUID playerId, Account account, double amount) {
		return amount > 0.0 && OfflineModifier.apply(playerId, toDenar(account), amount);
	}

	private static Accounts toDenar(Account account) {
		return account == Account.BANK ? Accounts.BANK : Accounts.POUCH;
	}
}
