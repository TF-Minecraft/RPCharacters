package net.tfminecraft.rpcharacters.clues.discovery;

public final class ClueDiscoverySettings {

	private int investigationPointsMax = 20;
	private long investigationRegenCycleMs = 30_000L;

	private boolean passiveDiscoveryEnabled = true;
	private int passiveIntervalSeconds = 15;
	private double passiveRadius = 4.0;
	private double passiveBaseChance = 0.08;
	private double passiveMinPotency = 0.1;

	private boolean activeDiscoveryEnabled = true;
	private int activeCooldownSeconds = 3;
	private int activeInvestigationCost = 1;
	private double activeRadius = 3.0;
	private double activeBaseChance = 0.35;
	private double activeMinPotency = 0.1;

	private double wisdomWeight = 0.02;
	private double intelligenceWeight = 0.015;

	private double potencyInitial = 1.0;
	private double potencyDecayPerHour = 0.02;
	private double potencyMinForDiscovery = 0.1;
	private double potencyMinForReadable = 0.15;
	private boolean potencyExpireWhenZero = true;

	private boolean targetInteractEnabled = true;
	private double targetInteractLossMin = 0.0;
	private double targetInteractLossMax = 0.25;
	private double targetInteractZeroLossChance = 0.4;

	private boolean footTrafficEnabled = true;
	private double footTrafficRadius = 4.0;
	private double footTrafficChancePerCheck = 0.05;
	private int footTrafficMaxEventsPerHour = 6;
	private boolean footTrafficOnlyUndiscovered = true;
	private double footTrafficLossMin = 0.0;
	private double footTrafficLossMax = 0.08;

	private double readabilityFullClarity = 0.85;
	private double readabilityMinAudible = 0.12;
	private String readabilityTooFaintPlaceholder = "&7*You find a trace, but cannot make it out.*";

	private String messageDiscovered = "&7*Something seems off.*";
	private String messageNoInvestigationPoints = "&cOut of focus. Rest up first.";
	private String messageAttributeTooLow = "&cYou are not perceptive enough to use this lens.";
	private String messageNoClueNearby = "";

	public int getInvestigationPointsMax() {
		return investigationPointsMax;
	}

	public void setInvestigationPointsMax(int investigationPointsMax) {
		this.investigationPointsMax = Math.max(1, investigationPointsMax);
	}

	public long getInvestigationRegenCycleMs() {
		return investigationRegenCycleMs;
	}

	public void setInvestigationRegenCycleMs(long investigationRegenCycleMs) {
		this.investigationRegenCycleMs = Math.max(0L, investigationRegenCycleMs);
	}

	public boolean isPassiveDiscoveryEnabled() {
		return passiveDiscoveryEnabled;
	}

	public void setPassiveDiscoveryEnabled(boolean passiveDiscoveryEnabled) {
		this.passiveDiscoveryEnabled = passiveDiscoveryEnabled;
	}

	public int getPassiveIntervalSeconds() {
		return passiveIntervalSeconds;
	}

	public void setPassiveIntervalSeconds(int passiveIntervalSeconds) {
		this.passiveIntervalSeconds = Math.max(1, passiveIntervalSeconds);
	}

	public double getPassiveRadius() {
		return passiveRadius;
	}

	public void setPassiveRadius(double passiveRadius) {
		this.passiveRadius = Math.max(0.5, passiveRadius);
	}

	public double getPassiveBaseChance() {
		return passiveBaseChance;
	}

	public void setPassiveBaseChance(double passiveBaseChance) {
		this.passiveBaseChance = passiveBaseChance;
	}

	public double getPassiveMinPotency() {
		return passiveMinPotency;
	}

	public void setPassiveMinPotency(double passiveMinPotency) {
		this.passiveMinPotency = passiveMinPotency;
	}

	public boolean isActiveDiscoveryEnabled() {
		return activeDiscoveryEnabled;
	}

	public void setActiveDiscoveryEnabled(boolean activeDiscoveryEnabled) {
		this.activeDiscoveryEnabled = activeDiscoveryEnabled;
	}

	public int getActiveCooldownSeconds() {
		return activeCooldownSeconds;
	}

	public void setActiveCooldownSeconds(int activeCooldownSeconds) {
		this.activeCooldownSeconds = Math.max(0, activeCooldownSeconds);
	}

	public int getActiveInvestigationCost() {
		return activeInvestigationCost;
	}

	public void setActiveInvestigationCost(int activeInvestigationCost) {
		this.activeInvestigationCost = Math.max(0, activeInvestigationCost);
	}

	public double getActiveRadius() {
		return activeRadius;
	}

	public void setActiveRadius(double activeRadius) {
		this.activeRadius = Math.max(0.5, activeRadius);
	}

	public double getActiveBaseChance() {
		return activeBaseChance;
	}

	public void setActiveBaseChance(double activeBaseChance) {
		this.activeBaseChance = activeBaseChance;
	}

	public double getActiveMinPotency() {
		return activeMinPotency;
	}

	public void setActiveMinPotency(double activeMinPotency) {
		this.activeMinPotency = activeMinPotency;
	}

	public double getWisdomWeight() {
		return wisdomWeight;
	}

	public void setWisdomWeight(double wisdomWeight) {
		this.wisdomWeight = wisdomWeight;
	}

	public double getIntelligenceWeight() {
		return intelligenceWeight;
	}

	public void setIntelligenceWeight(double intelligenceWeight) {
		this.intelligenceWeight = intelligenceWeight;
	}

	public double getPotencyInitial() {
		return potencyInitial;
	}

	public void setPotencyInitial(double potencyInitial) {
		this.potencyInitial = Math.max(0, potencyInitial);
	}

	public double getPotencyDecayPerHour() {
		return potencyDecayPerHour;
	}

	public void setPotencyDecayPerHour(double potencyDecayPerHour) {
		this.potencyDecayPerHour = Math.max(0, potencyDecayPerHour);
	}

	public double getPotencyMinForDiscovery() {
		return potencyMinForDiscovery;
	}

	public void setPotencyMinForDiscovery(double potencyMinForDiscovery) {
		this.potencyMinForDiscovery = potencyMinForDiscovery;
	}

	public double getPotencyMinForReadable() {
		return potencyMinForReadable;
	}

	public void setPotencyMinForReadable(double potencyMinForReadable) {
		this.potencyMinForReadable = potencyMinForReadable;
	}

	public boolean isPotencyExpireWhenZero() {
		return potencyExpireWhenZero;
	}

	public void setPotencyExpireWhenZero(boolean potencyExpireWhenZero) {
		this.potencyExpireWhenZero = potencyExpireWhenZero;
	}

	public boolean isTargetInteractEnabled() {
		return targetInteractEnabled;
	}

	public void setTargetInteractEnabled(boolean targetInteractEnabled) {
		this.targetInteractEnabled = targetInteractEnabled;
	}

	public double getTargetInteractLossMin() {
		return targetInteractLossMin;
	}

	public void setTargetInteractLossMin(double targetInteractLossMin) {
		this.targetInteractLossMin = targetInteractLossMin;
	}

	public double getTargetInteractLossMax() {
		return targetInteractLossMax;
	}

	public void setTargetInteractLossMax(double targetInteractLossMax) {
		this.targetInteractLossMax = targetInteractLossMax;
	}

	public double getTargetInteractZeroLossChance() {
		return targetInteractZeroLossChance;
	}

	public void setTargetInteractZeroLossChance(double targetInteractZeroLossChance) {
		this.targetInteractZeroLossChance = Math.max(0, Math.min(1, targetInteractZeroLossChance));
	}

	public boolean isFootTrafficEnabled() {
		return footTrafficEnabled;
	}

	public void setFootTrafficEnabled(boolean footTrafficEnabled) {
		this.footTrafficEnabled = footTrafficEnabled;
	}

	public double getFootTrafficRadius() {
		return footTrafficRadius;
	}

	public void setFootTrafficRadius(double footTrafficRadius) {
		this.footTrafficRadius = Math.max(0.5, footTrafficRadius);
	}

	public double getFootTrafficChancePerCheck() {
		return footTrafficChancePerCheck;
	}

	public void setFootTrafficChancePerCheck(double footTrafficChancePerCheck) {
		this.footTrafficChancePerCheck = footTrafficChancePerCheck;
	}

	public int getFootTrafficMaxEventsPerHour() {
		return footTrafficMaxEventsPerHour;
	}

	public void setFootTrafficMaxEventsPerHour(int footTrafficMaxEventsPerHour) {
		this.footTrafficMaxEventsPerHour = Math.max(0, footTrafficMaxEventsPerHour);
	}

	public boolean isFootTrafficOnlyUndiscovered() {
		return footTrafficOnlyUndiscovered;
	}

	public void setFootTrafficOnlyUndiscovered(boolean footTrafficOnlyUndiscovered) {
		this.footTrafficOnlyUndiscovered = footTrafficOnlyUndiscovered;
	}

	public double getFootTrafficLossMin() {
		return footTrafficLossMin;
	}

	public void setFootTrafficLossMin(double footTrafficLossMin) {
		this.footTrafficLossMin = footTrafficLossMin;
	}

	public double getFootTrafficLossMax() {
		return footTrafficLossMax;
	}

	public void setFootTrafficLossMax(double footTrafficLossMax) {
		this.footTrafficLossMax = footTrafficLossMax;
	}

	public double getReadabilityFullClarity() {
		return readabilityFullClarity;
	}

	public void setReadabilityFullClarity(double readabilityFullClarity) {
		this.readabilityFullClarity = readabilityFullClarity;
	}

	public double getReadabilityMinAudible() {
		return readabilityMinAudible;
	}

	public void setReadabilityMinAudible(double readabilityMinAudible) {
		this.readabilityMinAudible = readabilityMinAudible;
	}

	public String getReadabilityTooFaintPlaceholder() {
		return readabilityTooFaintPlaceholder;
	}

	public void setReadabilityTooFaintPlaceholder(String readabilityTooFaintPlaceholder) {
		this.readabilityTooFaintPlaceholder = readabilityTooFaintPlaceholder != null
				? readabilityTooFaintPlaceholder : "";
	}

	public String getMessageDiscovered() {
		return messageDiscovered;
	}

	public void setMessageDiscovered(String messageDiscovered) {
		this.messageDiscovered = messageDiscovered != null ? messageDiscovered : "";
	}

	public String getMessageNoInvestigationPoints() {
		return messageNoInvestigationPoints;
	}

	public void setMessageNoInvestigationPoints(String messageNoInvestigationPoints) {
		this.messageNoInvestigationPoints = messageNoInvestigationPoints != null ? messageNoInvestigationPoints : "";
	}

	public String getMessageAttributeTooLow() {
		return messageAttributeTooLow;
	}

	public void setMessageAttributeTooLow(String messageAttributeTooLow) {
		this.messageAttributeTooLow = messageAttributeTooLow != null ? messageAttributeTooLow : "";
	}

	public String getMessageNoClueNearby() {
		return messageNoClueNearby;
	}

	public void setMessageNoClueNearby(String messageNoClueNearby) {
		this.messageNoClueNearby = messageNoClueNearby != null ? messageNoClueNearby : "";
	}
}
