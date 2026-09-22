package net.tfminecraft.rpcharacters.professions;

import org.bukkit.command.CommandSender;

public final class ProfessionPermissions {
	public static final String ADMIN = "professions.admin";

	private ProfessionPermissions() {}

	public static boolean isAdmin(CommandSender sender) {
		return sender.hasPermission(ADMIN);
	}
}
