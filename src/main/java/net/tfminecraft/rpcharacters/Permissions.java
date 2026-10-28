package net.tfminecraft.rpcharacters;

import org.bukkit.command.CommandSender;

public class Permissions {
private Permissions() {}
public static String Permission_Admin;
/** Players place and remove their own clues with /rpcharacter placeclue and removeclue. */
public static final String PLACE_CLUE = "rpchar.clue.place";
    
    static {
        Permissions.Permission_Admin = "rpcharacters.admin";
    }
    
    public static boolean isAdmin(final CommandSender commandSender) {
        return commandSender.hasPermission(Permissions.Permission_Admin);
    }

    public static boolean canPlaceClues(final CommandSender commandSender) {
        return isAdmin(commandSender) || commandSender.hasPermission(PLACE_CLUE);
    }
}
