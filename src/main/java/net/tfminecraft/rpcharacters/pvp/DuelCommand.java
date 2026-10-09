package net.tfminecraft.rpcharacters.pvp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.tfminecraft.rpcharacters.loaders.DuelLoader;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/** {@code /duel}: challenge the player you look at (or name), then answer, withdraw or yield. */
public final class DuelCommand implements CommandExecutor, TabCompleter, Listener {

	public static final String COMMAND = "duel";
	private static final String[] SUBCOMMANDS = { "accept", "decline", "cancel", "yield", "help" };

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!COMMAND.equalsIgnoreCase(command.getName())) {
			return false;
		}
		if (!(sender instanceof Player player)) {
			RPTexts.send(sender, DuelLoader.message("players-only"));
			return true;
		}
		String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
		String from = args.length > 1 ? args[1] : null;
		switch (sub) {
			case "" -> challengeLookedAt(player);
			case "accept" -> Duels.accept(player, from);
			case "decline" -> Duels.decline(player, from);
			case "cancel" -> Duels.cancel(player);
			case "yield" -> Duels.yield(player);
			case "help" -> RPTexts.send(player, DuelLoader.message("usage"));
			default -> {
				Player target = Bukkit.getPlayerExact(args[0]);
				if (target == null) {
					RPTexts.send(player, DuelLoader.message("not-found").replace("{name}", args[0]));
				} else {
					Duels.challenge(player, target);
				}
			}
		}
		return true;
	}

	private void challengeLookedAt(Player player) {
		if (player.getTargetEntity(DuelLoader.getChallengeRange()) instanceof Player target) {
			Duels.challenge(player, target);
			return;
		}
		RPTexts.send(player, DuelLoader.message("no-target"));
		RPTexts.send(player, DuelLoader.message("usage"));
	}

	/** Player names are left out so the list never gives away who is behind a mask. */
	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		List<String> out = new ArrayList<>();
		if (args.length == 1) {
			String prefix = args[0].toLowerCase(Locale.ROOT);
			for (String option : SUBCOMMANDS) {
				if (option.startsWith(prefix)) {
					out.add(option);
				}
			}
		}
		return out;
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onDamage(EntityDamageEvent event) {
		Duels.screenDamage(event);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onDamageTaken(EntityDamageEvent event) {
		Duels.recordDamage(event);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		Duels.handleQuit(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onDeath(PlayerDeathEvent event) {
		Duels.handleDeath(event.getEntity());
	}
}
