package net.tfminecraft.rpcharacters.chat.smart;

public final class MuffleOutcome {

	private final String text;
	private final String stage;

	public MuffleOutcome(String text, String stage) {
		this.text = text != null ? text : "";
		this.stage = stage != null ? stage : "unknown";
	}

	public String getText() {
		return text;
	}

	public String getStage() {
		return stage;
	}

	public boolean isAudible() {
		return !text.isEmpty();
	}

	public boolean hidesSender() {
		return "placeholder".equals(stage);
	}
}
