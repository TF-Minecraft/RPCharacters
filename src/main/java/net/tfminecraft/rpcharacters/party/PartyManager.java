package net.tfminecraft.rpcharacters.party;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.tfminecraft.rpcharacters.loaders.PartyLoader;
import net.tfminecraft.rpcharacters.utils.ClueFormatter;

public final class PartyManager {

	public static final String PARTY_RESOLVER_ID = "rpcharacters:party";

	private static final PartyManager INSTANCE = new PartyManager();

	private final Map<UUID, Party> memberIndex = new ConcurrentHashMap<>();
	private final Map<UUID, Party> partiesById = new ConcurrentHashMap<>();
	private final Map<UUID, PartyInvite> pendingInvites = new ConcurrentHashMap<>();
	private PartyStore store;

	private PartyManager() {}

	public static PartyManager get() {
		return INSTANCE;
	}

	public void load(Path file) {
		PartyStore nextStore = new PartyStore(file);
		try {
			List<Party> loaded = nextStore.load();
			memberIndex.clear();
			partiesById.clear();
			pendingInvites.clear();
			for (Party party : loaded) {
				partiesById.put(party.getId(), party);
				for (UUID member : party.getMemberIds()) memberIndex.put(member, party);
			}
			store = nextStore;
		} catch (IOException e) {
			throw new UncheckedIOException("Unable to load persistent parties", e);
		}
	}

	private void save() {
		if (store == null) return;
		try {
			store.save(partiesById.values());
		} catch (IOException e) {
			throw new UncheckedIOException("Unable to save persistent parties", e);
		}
	}

	public Party getParty(UUID memberId) {
		if (memberId == null) {
			return null;
		}
		return memberIndex.get(memberId);
	}

	public PartyInvite getPendingInvite(UUID targetId) {
		if (targetId == null) {
			return null;
		}
		PartyInvite invite = pendingInvites.get(targetId);
		if (invite == null) {
			return null;
		}
		if (invite.isExpired()) {
			pendingInvites.remove(targetId);
			return null;
		}
		return invite;
	}

	public PartyResult create(UUID leaderId, String rawName) {
		if (leaderId == null) {
			return PartyResult.fail(PartyLoader.getInvalidName());
		}
		if (memberIndex.containsKey(leaderId)) {
			return PartyResult.fail(PartyLoader.getAlreadyInParty());
		}

		String name = sanitizeName(rawName);
		if (name == null) {
			return PartyResult.fail(PartyLoader.getInvalidName());
		}

		Party party = new Party(UUID.randomUUID(), name, leaderId);
		partiesById.put(party.getId(), party);
		memberIndex.put(leaderId, party);
		save();
		return PartyResult.ok(PartyLoader.getCreated().replace("{name}", name));
	}

	public PartyResult invite(UUID leaderId, UUID targetId) {
		if (leaderId == null || targetId == null) {
			return PartyResult.fail(PartyLoader.getTargetNotFound().replace("{player}", ""));
		}
		if (leaderId.equals(targetId)) {
			return PartyResult.fail(PartyLoader.getTargetInParty());
		}

		Party party = memberIndex.get(leaderId);
		if (party == null) {
			return PartyResult.fail(PartyLoader.getNotInParty());
		}
		if (!party.isLeader(leaderId)) {
			return PartyResult.fail(PartyLoader.getNotLeader());
		}
		if (memberIndex.containsKey(targetId)) {
			return PartyResult.fail(PartyLoader.getTargetInParty());
		}

		PartyInvite existing = pendingInvites.get(targetId);
		if (existing != null && !existing.isExpired()) {
			return PartyResult.fail(PartyLoader.getTargetHasInvite());
		}

		long expiresAt = System.currentTimeMillis() + PartyLoader.getInviteExpirySeconds() * 1000L;
		pendingInvites.put(targetId, new PartyInvite(party.getId(), leaderId, targetId, expiresAt));
		return PartyResult.withNotify(
				PartyResult.Kind.OK,
				PartyLoader.getInvitedLeader()
						.replace("{player}", displayName(targetId))
						.replace("{name}", party.getName()),
				List.of(targetId));
	}

	public PartyResult join(UUID targetId) {
		if (targetId == null) {
			return PartyResult.fail(PartyLoader.getNoInvite());
		}
		if (memberIndex.containsKey(targetId)) {
			return PartyResult.fail(PartyLoader.getAlreadyInParty());
		}

		PartyInvite invite = pendingInvites.get(targetId);
		if (invite == null) {
			return PartyResult.fail(PartyLoader.getNoInvite());
		}
		if (invite.isExpired()) {
			pendingInvites.remove(targetId);
			return PartyResult.fail(PartyLoader.getInviteExpired());
		}

		Party party = partiesById.get(invite.getPartyId());
		if (party == null) {
			pendingInvites.remove(targetId);
			return PartyResult.fail(PartyLoader.getInviteExpired());
		}
		if (!party.isLeader(invite.getLeaderId())) {
			pendingInvites.remove(targetId);
			return PartyResult.fail(PartyLoader.getInviteExpired());
		}

		party.addMember(targetId);
		memberIndex.put(targetId, party);
		pendingInvites.remove(targetId);

		List<UUID> notify = new ArrayList<>(party.getMemberIds());
		save();
		notify.remove(targetId);
		return PartyResult.withNotify(
				PartyResult.Kind.MEMBER_JOINED,
				PartyLoader.getJoined().replace("{name}", party.getName()),
				notify);
	}

	public PartyResult leave(UUID memberId) {
		Party party = memberIndex.get(memberId);
		if (party == null) {
			return PartyResult.fail(PartyLoader.getNotInParty());
		}

		if (party.isLeader(memberId)) {
			return disband(party, PartyLoader.getDisbanded());
		}

		removeMember(party, memberId);
		List<UUID> notify = new ArrayList<>(party.getMemberIds());
		return PartyResult.withNotify(
				PartyResult.Kind.MEMBER_LEFT,
				PartyLoader.getMemberLeft().replace("{player}", displayName(memberId)),
				notify);
	}

	public PartyResult kick(UUID leaderId, UUID targetId) {
		if (leaderId == null || targetId == null) {
			return PartyResult.fail(PartyLoader.getTargetNotFound().replace("{player}", ""));
		}
		if (leaderId.equals(targetId)) {
			return PartyResult.fail(PartyLoader.getCannotKickSelf());
		}

		Party party = memberIndex.get(leaderId);
		if (party == null) {
			return PartyResult.fail(PartyLoader.getNotInParty());
		}
		if (!party.isLeader(leaderId)) {
			return PartyResult.fail(PartyLoader.getNotLeader());
		}
		if (!party.isMember(targetId)) {
			return PartyResult.fail(PartyLoader.getTargetNotFound().replace("{player}", displayName(targetId)));
		}

		removeMember(party, targetId);
		return PartyResult.withNotify(
				PartyResult.Kind.MEMBER_KICKED,
				PartyLoader.getMemberKicked().replace("{player}", displayName(targetId)),
				List.of(targetId));
	}

	public void handleQuit(Player player) {
		if (player == null) {
			return;
		}
		handleQuit(player.getUniqueId());
	}

	public void handleQuit(UUID memberId) {
		if (memberId == null) {
			return;
		}

		pendingInvites.remove(memberId);
		// Membership and leadership survive disconnects. Only explicit leave/kick removes them.
	}

	public List<String> buildInfoLines(Party party) {
		List<String> lines = new ArrayList<>();
		if (party == null) {
			return lines;
		}
		lines.add(PartyLoader.getInfoHeader()
				.replace("{name}", party.getName())
				.replace("{leader}", displayName(party.getLeaderId())));
		for (UUID memberId : party.getMemberIds()) {
			lines.add(PartyLoader.getInfoLine().replace("{player}", displayName(memberId)));
		}
		return lines;
	}

	static void clearForTests() {
		INSTANCE.memberIndex.clear();
		INSTANCE.partiesById.clear();
		INSTANCE.pendingInvites.clear();
		INSTANCE.store = null;
	}

	private PartyResult disband(Party party, String leaderMessage) {
		List<UUID> notify = new ArrayList<>(party.getMemberIds());
		for (UUID memberId : party.getMemberIds()) {
			memberIndex.remove(memberId);
		}
		partiesById.remove(party.getId());
		pendingInvites.entrySet().removeIf(entry -> entry.getValue().getPartyId().equals(party.getId()));
		save();
		return PartyResult.withNotify(PartyResult.Kind.DISBANDED, leaderMessage, notify);
	}

	private void removeMember(Party party, UUID memberId) {
		party.removeMember(memberId);
		memberIndex.remove(memberId);
		if (party.getMemberIds().isEmpty()) {
			partiesById.remove(party.getId());
		}
		save();
	}

	private static String sanitizeName(String rawName) {
		if (rawName == null) {
			return null;
		}
		String trimmed = ClueFormatter.stripColor(rawName.trim());
		if (trimmed.isEmpty() || trimmed.length() > PartyLoader.getMaxNameLength()) {
			return null;
		}
		return trimmed;
	}

	static String displayName(UUID playerId) {
		if (playerId == null) {
			return "Unknown";
		}
		if (Bukkit.getServer() != null) {
			Player online = Bukkit.getPlayer(playerId);
			if (online != null) {
				return online.getName();
			}
			String offline = Bukkit.getOfflinePlayer(playerId).getName();
			if (offline != null) {
				return offline;
			}
		}
		return playerId.toString();
	}
}
