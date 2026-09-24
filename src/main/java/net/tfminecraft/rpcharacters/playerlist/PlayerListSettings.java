package net.tfminecraft.rpcharacters.playerlist;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Settings from {@code player-list.yml}. Rank tags are ordered: a player shows
 * the tag of the first listed group they belong to, and the list is sorted in
 * that order.
 */
public final class PlayerListSettings {

	private final String permission;
	private final boolean quickAction;
	private final boolean portrait;
	private final String title;
	private final String header;
	private final String quickActionLabel;
	private final Map<String, String> rankTags;

	public PlayerListSettings(String permission, boolean quickAction, boolean portrait, String title,
			String header, String quickActionLabel, Map<String, String> rankTags) {
		this.permission = permission;
		this.quickAction = quickAction;
		this.portrait = portrait;
		this.title = title;
		this.header = header;
		this.quickActionLabel = quickActionLabel;
		this.rankTags = Collections.unmodifiableMap(new LinkedHashMap<>(rankTags));
	}

	public static PlayerListSettings defaults() {
		return new PlayerListSettings("rpchar.playerlist", true, true, "&lOnline players",
				"&7Online players: &f&l{online}\n&8Hover a name to see who is playing them",
				"Who's online", Map.of());
	}

	public String permission() {
		return permission;
	}

	public boolean quickAction() {
		return quickAction;
	}

	public boolean portrait() {
		return portrait;
	}

	public String title() {
		return title;
	}

	public String header() {
		return header;
	}

	public String quickActionLabel() {
		return quickActionLabel;
	}

	public Map<String, String> rankTags() {
		return rankTags;
	}

	/** Groups in priority order. */
	public List<String> rankOrder() {
		return List.copyOf(rankTags.keySet());
	}

	/**
	 * The first configured group the player belongs to, or {@code null}.
	 *
	 * @param inGroup whether the player is in a (lowercase) group
	 */
	public String rank(Predicate<String> inGroup) {
		Objects.requireNonNull(inGroup, "inGroup");
		for (String group : rankTags.keySet()) {
			if (inGroup.test(group)) {
				return group;
			}
		}
		return null;
	}

	/** Sort position of a rank; unranked players sort last. */
	public int rankIndex(String rank) {
		int index = rank == null ? -1 : rankOrder().indexOf(rank);
		return index < 0 ? rankTags.size() : index;
	}

	public String tag(String rank) {
		return rank == null ? "" : rankTags.getOrDefault(rank, "");
	}
}
