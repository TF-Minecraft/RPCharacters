package net.tfminecraft.rpcharacters.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;

public final class ChatChannel {

	private final String id;
	private final List<String> commands;
	private final String usePermission;
	private final String readPermission;
	private final String colorCodePermission;
	private final String format;
	private final int range;
	private final int cooldownSeconds;
	private final boolean requireCharacter;
	private final boolean masked;
	private final boolean bubble;
	private final boolean smartMessages;
	private final String messageColorPrefix;
	private final String recipientResolverId;

	public ChatChannel(String id, ConfigurationSection config) {
		this.id = id;
		List<String> cmds = config.getStringList("commands");
		this.commands = cmds != null ? new ArrayList<>(cmds) : new ArrayList<>();
		this.usePermission = config.getString("use-perm", "");
		this.readPermission = config.getString("read-perm", usePermission);
		this.colorCodePermission = config.getString("color-code-perm", "");
		this.format = config.getString("format", "");
		this.range = config.getInt("range", 0);
		this.cooldownSeconds = Math.max(0, config.getInt("cooldown", 0));
		this.requireCharacter = config.getBoolean("require-character", true);
		this.masked = config.getBoolean("masked", false);
		this.bubble = config.getBoolean("bubble", false);
		this.smartMessages = config.getBoolean("smart-messages", false);
		this.messageColorPrefix = ChatFormatUtil.extractMessageColorPrefix(format);
		this.recipientResolverId = config.getString("recipient-resolver", "");
	}

	public String getId() {
		return id;
	}

	public List<String> getCommands() {
		return Collections.unmodifiableList(commands);
	}

	public String getUsePermission() {
		return usePermission;
	}

	public String getReadPermission() {
		return readPermission;
	}

	public String getColorCodePermission() {
		return colorCodePermission;
	}

	public String getFormat() {
		return format;
	}

	public int getRange() {
		return range;
	}

	public int getCooldownSeconds() {
		return cooldownSeconds;
	}

	public boolean isGlobal() {
		return range <= 0;
	}

	public boolean requiresActiveCharacter() {
		return requireCharacter;
	}

	public boolean isMasked() {
		return masked;
	}

	public boolean hasSpeechBubble() {
		return bubble;
	}

	public boolean isSmartMessages() {
		return smartMessages;
	}

	public String getMessageColorPrefix() {
		return messageColorPrefix;
	}

	public String getRecipientResolverId() {
		return recipientResolverId;
	}

	public boolean usesRecipientResolver() {
		return recipientResolverId != null && !recipientResolverId.isBlank();
	}
}
