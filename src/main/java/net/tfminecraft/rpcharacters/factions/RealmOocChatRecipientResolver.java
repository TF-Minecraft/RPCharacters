package net.tfminecraft.rpcharacters.factions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.chat.ChatChannel;
import net.tfminecraft.rpcharacters.chat.ChatRecipientFilters;
import net.tfminecraft.rpcharacters.chat.ChatRecipientResolver;
import net.tfminecraft.simplefactions.managers.FactionManager;
import net.tfminecraft.simplefactions.objects.Faction;

/** Resolves ROOC recipients to online members of the sender's top-overlord realm. */
public final class RealmOocChatRecipientResolver implements ChatRecipientResolver {

	@Override
	public Set<Player> resolve(Player sender, ChatChannel channel) {
		if (sender == null || channel == null) {
			return Collections.emptySet();
		}

		Faction faction = FactionManager.getByMember(sender.getName());
		if (faction == null) {
			return Collections.emptySet();
		}

		Faction overlord = topOverlord(faction);
		Set<String> realmMembers = new HashSet<>();
		for (String member : overlord.getCompleteMemberList()) {
			if (member != null) {
				realmMembers.add(member.toLowerCase(Locale.ROOT));
			}
		}

		List<Player> candidates = new ArrayList<>();
		for (Player online : Bukkit.getOnlinePlayers()) {
			if (realmMembers.contains(online.getName().toLowerCase(Locale.ROOT))) {
				candidates.add(online);
			}
		}
		return ChatRecipientFilters.filterCandidates(sender, channel, candidates);
	}

	private static Faction topOverlord(Faction faction) {
		Faction current = faction;
		Set<String> visited = new HashSet<>();
		while (current.getId() != null && visited.add(current.getId().toLowerCase(Locale.ROOT))) {
			Faction overlord = current.getOverlord();
			if (overlord == null || overlord == current) {
				return current;
			}
			current = overlord;
		}
		return current;
	}
}
