package net.tfminecraft.rpcharacters.progress;

import java.util.function.BooleanSupplier;

import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

/**
 * Chooses which pools a progression change touches. While progress is shared they are the account's.
 * While it is per character they are the live character's: the one whose MMOCore copy
 * {@link CharacterProgressService#activate} applied in this join. Otherwise, as in the character room,
 * there are none and progression changes do nothing.
 */
public final class ProgressRouting {
	private ProgressRouting() {}

	public static boolean enabled() {
		return CharacterProgressService.active() != null;
	}

	public static ProgressPools pools(PlayerData account) {
		if (account == null) {
			return null;
		}
		return enabled() ? live(account, account.getActiveCharacter()) : new AccountPools(account);
	}

	/** As {@link #pools(PlayerData)}, but only when {@code character} is the live one. */
	public static ProgressPools pools(PlayerData account, RPCharacter character) {
		if (account == null) {
			return null;
		}
		return enabled() ? live(account, character) : new AccountPools(account);
	}

	/** The live character's progress, or null while progress is shared or no character is live. */
	public static CharacterProgress activeProgress(Player player) {
		if (player == null || !enabled()) {
			return null;
		}
		PlayerData account = PlayerManager.get(player);
		return account == null ? null : live(account, account.getActiveCharacter());
	}

	/**
	 * For delayed work: stays true only while the character live now is still live, so a reward
	 * scheduled for one character never lands on the next. While progress is shared it only checks
	 * that the player is online.
	 */
	public static BooleanSupplier sessionGuard(Player player) {
		if (!enabled()) {
			return player::isOnline;
		}
		PlayerData account = PlayerManager.get(player);
		RPCharacter character = account == null ? null : account.getActiveCharacter();
		CharacterProgress progress = account == null ? null : live(account, character);
		CharacterProgressService service = CharacterProgressService.active();
		Object token = service.token(account);
		return () -> progress != null && player.isOnline() && PlayerManager.get(player) == account
				&& CharacterProgressService.active() == service && service.token(account) == token
				&& live(account, account.getActiveCharacter()) == progress;
	}

	private static CharacterProgress live(PlayerData account, RPCharacter character) {
		CharacterProgressService service = CharacterProgressService.active();
		return service != null && character != null && service.holds(account, character)
				? character.getProgress() : null;
	}
}
