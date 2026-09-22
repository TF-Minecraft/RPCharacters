package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;

public final class PartyLoader implements LoaderInterface {

	private static int inviteExpirySeconds = 60;
	private static int maxNameLength = 32;

	private static String playersOnly = "&cPlayers only.";
	private static String usage = "&7Usage: /rpcharacter party create <name> | invite <player> | join | leave | kick <player> | info";
	private static String created = "&aCreated party &e{name}&a.";
	private static String invitedTarget = "&a{leader} invited you to party &e{name}&a. Type &e/rpcharacter party join &ato accept.";
	private static String invitedLeader = "&aInvited &e{player} &ato party &e{name}&a.";
	private static String joined = "&aYou joined party &e{name}&a.";
	private static String joinedNotify = "&a{player} joined the party.";
	private static String noInvite = "&cYou have no party invite to accept.";
	private static String inviteExpired = "&cThat party invite has expired.";
	private static String alreadyInParty = "&cYou are already in a party.";
	private static String notInParty = "&cYou are not in a party.";
	private static String notLeader = "&cOnly the party leader can do that.";
	private static String disbanded = "&eThe party has been disbanded.";
	private static String memberLeft = "&e{player} left the party.";
	private static String memberKicked = "&c{player} was removed from the party.";
	private static String kickedNotify = "&cYou were removed from the party.";
	private static String targetNotFound = "&cCould not find the player {player}.";
	private static String targetInParty = "&cThat player is already in a party.";
	private static String targetHasInvite = "&cThat player is already considering another party invite.";
	private static String invalidName = "&cEnter a valid party name.";
	private static String cannotKickSelf = "&cUse /rpcharacter party leave to leave the party.";
	private static String infoHeader = "&7Party &e{name} &7— leader: &e{leader}";
	private static String infoLine = "&7- &f{player}";

	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		inviteExpirySeconds = Math.max(1, config.getInt("invite-expiry-seconds", 60));
		maxNameLength = Math.max(1, config.getInt("max-name-length", 32));

		playersOnly = config.getString("messages.players-only", playersOnly);
		usage = config.getString("messages.usage", usage);
		created = config.getString("messages.created", created);
		invitedTarget = config.getString("messages.invited-target", invitedTarget);
		invitedLeader = config.getString("messages.invited-leader", invitedLeader);
		joined = config.getString("messages.joined", joined);
		joinedNotify = config.getString("messages.joined-notify", joinedNotify);
		noInvite = config.getString("messages.no-invite", noInvite);
		inviteExpired = config.getString("messages.invite-expired", inviteExpired);
		alreadyInParty = config.getString("messages.already-in-party", alreadyInParty);
		notInParty = config.getString("messages.not-in-party", notInParty);
		notLeader = config.getString("messages.not-leader", notLeader);
		disbanded = config.getString("messages.disbanded", disbanded);
		memberLeft = config.getString("messages.member-left", memberLeft);
		memberKicked = config.getString("messages.member-kicked", memberKicked);
		kickedNotify = config.getString("messages.kicked-notify", kickedNotify);
		targetNotFound = config.getString("messages.target-not-found", targetNotFound);
		targetInParty = config.getString("messages.target-in-party", targetInParty);
		targetHasInvite = config.getString("messages.target-has-invite", targetHasInvite);
		invalidName = config.getString("messages.invalid-name", invalidName);
		cannotKickSelf = config.getString("messages.cannot-kick-self", cannotKickSelf);
		infoHeader = config.getString("messages.info-header", infoHeader);
		infoLine = config.getString("messages.info-line", infoLine);

		playersOnly = migratePartyCommand(playersOnly);
		usage = migratePartyCommand(usage);
		created = migratePartyCommand(created);
		invitedTarget = migratePartyCommand(invitedTarget);
		invitedLeader = migratePartyCommand(invitedLeader);
		joined = migratePartyCommand(joined);
		joinedNotify = migratePartyCommand(joinedNotify);
		noInvite = migratePartyCommand(noInvite);
		inviteExpired = migratePartyCommand(inviteExpired);
		alreadyInParty = migratePartyCommand(alreadyInParty);
		notInParty = migratePartyCommand(notInParty);
		notLeader = migratePartyCommand(notLeader);
		disbanded = migratePartyCommand(disbanded);
		memberLeft = migratePartyCommand(memberLeft);
		memberKicked = migratePartyCommand(memberKicked);
		kickedNotify = migratePartyCommand(kickedNotify);
		targetNotFound = migratePartyCommand(targetNotFound);
		targetInParty = migratePartyCommand(targetInParty);
		targetHasInvite = migratePartyCommand(targetHasInvite);
		invalidName = migratePartyCommand(invalidName);
		cannotKickSelf = migratePartyCommand(cannotKickSelf);
		infoHeader = migratePartyCommand(infoHeader);
		infoLine = migratePartyCommand(infoLine);
	}

	static String migratePartyCommand(String message) {
		if (message == null || message.isEmpty()) {
			return message;
		}
		return message.replace("/party", "/rpcharacter party");
	}

	public static int getInviteExpirySeconds() {
		return inviteExpirySeconds;
	}

	public static int getMaxNameLength() {
		return maxNameLength;
	}

	public static String getPlayersOnly() {
		return playersOnly;
	}

	public static String getUsage() {
		return usage;
	}

	public static String getCreated() {
		return created;
	}

	public static String getInvitedTarget() {
		return invitedTarget;
	}

	public static String getInvitedLeader() {
		return invitedLeader;
	}

	public static String getJoined() {
		return joined;
	}

	public static String getJoinedNotify() {
		return joinedNotify;
	}

	public static String getNoInvite() {
		return noInvite;
	}

	public static String getInviteExpired() {
		return inviteExpired;
	}

	public static String getAlreadyInParty() {
		return alreadyInParty;
	}

	public static String getNotInParty() {
		return notInParty;
	}

	public static String getNotLeader() {
		return notLeader;
	}

	public static String getDisbanded() {
		return disbanded;
	}

	public static String getMemberLeft() {
		return memberLeft;
	}

	public static String getMemberKicked() {
		return memberKicked;
	}

	public static String getKickedNotify() {
		return kickedNotify;
	}

	public static String getTargetNotFound() {
		return targetNotFound;
	}

	public static String getTargetInParty() {
		return targetInParty;
	}

	public static String getTargetHasInvite() {
		return targetHasInvite;
	}

	public static String getInvalidName() {
		return invalidName;
	}

	public static String getCannotKickSelf() {
		return cannotKickSelf;
	}

	public static String getInfoHeader() {
		return infoHeader;
	}

	public static String getInfoLine() {
		return infoLine;
	}
}
