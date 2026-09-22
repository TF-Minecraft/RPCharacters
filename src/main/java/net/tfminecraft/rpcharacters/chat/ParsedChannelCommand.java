package net.tfminecraft.rpcharacters.chat;

public final class ParsedChannelCommand {

	private final String label;
	private final ChatChannel channel;
	private final String message;

	public ParsedChannelCommand(String label, ChatChannel channel, String message) {
		this.label = label;
		this.channel = channel;
		this.message = message;
	}

	public String label() {
		return label;
	}

	public ChatChannel channel() {
		return channel;
	}

	public String message() {
		return message;
	}

	public boolean hasMessage() {
		return message != null && !message.isBlank();
	}
}
