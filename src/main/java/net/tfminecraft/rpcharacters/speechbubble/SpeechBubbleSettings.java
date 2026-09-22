package net.tfminecraft.rpcharacters.speechbubble;

public final class SpeechBubbleSettings {

	private boolean enabled = true;
	private boolean debugMessages = false;
	private float scale = 0.6f;
	private int maxCharactersPerLine = 32;
	private double lineSpacing = 0.22;
	private double firstLineOffset = 0.18;
	private double heightAboveHead = 1.0;
	private int maxStackedUtterances = 5;
	private int utteranceTimeoutSeconds = 5;
	private double bobAmplitude = 0.08;
	private int bobPeriodTicks = 40;
	private double followLerpFactor = 0.35;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean isDebugMessages() {
		return debugMessages;
	}

	public void setDebugMessages(boolean debugMessages) {
		this.debugMessages = debugMessages;
	}

	public float getScale() {
		return scale;
	}

	public void setScale(float scale) {
		this.scale = scale;
	}

	public int getMaxCharactersPerLine() {
		return maxCharactersPerLine;
	}

	public void setMaxCharactersPerLine(int maxCharactersPerLine) {
		this.maxCharactersPerLine = maxCharactersPerLine;
	}

	public double getLineSpacing() {
		return lineSpacing;
	}

	public void setLineSpacing(double lineSpacing) {
		this.lineSpacing = lineSpacing;
	}

	public double getFirstLineOffset() {
		return firstLineOffset;
	}

	public void setFirstLineOffset(double firstLineOffset) {
		this.firstLineOffset = firstLineOffset;
	}

	public double getHeightAboveHead() {
		return heightAboveHead;
	}

	public void setHeightAboveHead(double heightAboveHead) {
		this.heightAboveHead = heightAboveHead;
	}

	public int getMaxStackedUtterances() {
		return maxStackedUtterances;
	}

	public void setMaxStackedUtterances(int maxStackedUtterances) {
		this.maxStackedUtterances = maxStackedUtterances;
	}

	public int getUtteranceTimeoutSeconds() {
		return utteranceTimeoutSeconds;
	}

	public void setUtteranceTimeoutSeconds(int utteranceTimeoutSeconds) {
		this.utteranceTimeoutSeconds = utteranceTimeoutSeconds;
	}

	public double getBobAmplitude() {
		return bobAmplitude;
	}

	public void setBobAmplitude(double bobAmplitude) {
		this.bobAmplitude = bobAmplitude;
	}

	public int getBobPeriodTicks() {
		return bobPeriodTicks;
	}

	public void setBobPeriodTicks(int bobPeriodTicks) {
		this.bobPeriodTicks = bobPeriodTicks;
	}

	public double getFollowLerpFactor() {
		return followLerpFactor;
	}

	public void setFollowLerpFactor(double followLerpFactor) {
		this.followLerpFactor = followLerpFactor;
	}
}
