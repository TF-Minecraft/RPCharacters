package net.tfminecraft.rpcharacters.chat;

import java.util.Set;

import org.bukkit.entity.Player;

/**
 * Resolves chat recipients for channels that declare {@code recipient-resolver} in chat.yml.
 * External plugins register implementations by id (e.g. {@code simplefactions:guild}).
 */
@FunctionalInterface
public interface ChatRecipientResolver {

	Set<Player> resolve(Player sender, ChatChannel channel);
}
