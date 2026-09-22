package net.tfminecraft.rpcharacters.chat.smart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Material;

public final class SmartMessageSettings {

	private boolean enabled = true;
	private boolean debugMessages = false;
	private boolean genericMuffle = true;
	private boolean senderHearsSelf = true;
	private double fadeStartPercent = 0.6;
	private double minAudible = 0.05;
	private double fullClarity = 0.85;
	private double lowIntelligibilityThreshold = 0.15;
	private String lowIntelligibilityPlaceholder = "&7*muffled voices*";
	private double rayStep = 0.5;
	private double occlusionMaxWeight = 3.0;
	private double occlusionCurve = 2.2;
	private boolean listenerAnchorSearch = true;
	private boolean listenerAnchorDiagonals = true;
	private boolean listenerAnchorEarlyExitClearLos = true;
	private double collisionFillThreshold = 0.30;
	private double defaultBlockAttenuation = 0.28;
	private Map<Material, Double> blockAttenuation = new HashMap<>();
	private List<MuffleRule> muffleRules = new ArrayList<>();

	private boolean charismaHearingEnabled = true;
	private String charismaAttribute = "charisma";
	private double charismaMaxBoost = 0.20;
	private double charismaScale = 40.0;
	private double charismaPlaceholderMultiplier = 0.5;

	private boolean anonymousMuffledVoiceEnabled = true;
	private double anonymousMuffledMaxIntelligibility = 0.65;
	private String anonymousMuffledDisplay = "???";

	private boolean placeholderSuppressionEnabled = true;

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

	public boolean isGenericMuffle() {
		return genericMuffle;
	}

	public void setGenericMuffle(boolean genericMuffle) {
		this.genericMuffle = genericMuffle;
	}

	public boolean isSenderHearsSelf() {
		return senderHearsSelf;
	}

	public void setSenderHearsSelf(boolean senderHearsSelf) {
		this.senderHearsSelf = senderHearsSelf;
	}

	public double getFadeStartPercent() {
		return fadeStartPercent;
	}

	public void setFadeStartPercent(double fadeStartPercent) {
		this.fadeStartPercent = fadeStartPercent;
	}

	public double getMinAudible() {
		return minAudible;
	}

	public void setMinAudible(double minAudible) {
		this.minAudible = minAudible;
	}

	public double getFullClarity() {
		return fullClarity;
	}

	public void setFullClarity(double fullClarity) {
		this.fullClarity = fullClarity;
	}

	public double getLowIntelligibilityThreshold() {
		return lowIntelligibilityThreshold;
	}

	public void setLowIntelligibilityThreshold(double lowIntelligibilityThreshold) {
		this.lowIntelligibilityThreshold = lowIntelligibilityThreshold;
	}

	public String getLowIntelligibilityPlaceholder() {
		return lowIntelligibilityPlaceholder;
	}

	public void setLowIntelligibilityPlaceholder(String lowIntelligibilityPlaceholder) {
		this.lowIntelligibilityPlaceholder = lowIntelligibilityPlaceholder;
	}

	public double getRayStep() {
		return rayStep;
	}

	public void setRayStep(double rayStep) {
		this.rayStep = rayStep;
	}

	public double getOcclusionMaxWeight() {
		return occlusionMaxWeight;
	}

	public void setOcclusionMaxWeight(double occlusionMaxWeight) {
		this.occlusionMaxWeight = occlusionMaxWeight;
	}

	public double getOcclusionCurve() {
		return occlusionCurve;
	}

	public void setOcclusionCurve(double occlusionCurve) {
		this.occlusionCurve = occlusionCurve;
	}

	public boolean isListenerAnchorSearch() {
		return listenerAnchorSearch;
	}

	public void setListenerAnchorSearch(boolean listenerAnchorSearch) {
		this.listenerAnchorSearch = listenerAnchorSearch;
	}

	public boolean isListenerAnchorDiagonals() {
		return listenerAnchorDiagonals;
	}

	public void setListenerAnchorDiagonals(boolean listenerAnchorDiagonals) {
		this.listenerAnchorDiagonals = listenerAnchorDiagonals;
	}

	public boolean isListenerAnchorEarlyExitClearLos() {
		return listenerAnchorEarlyExitClearLos;
	}

	public void setListenerAnchorEarlyExitClearLos(boolean listenerAnchorEarlyExitClearLos) {
		this.listenerAnchorEarlyExitClearLos = listenerAnchorEarlyExitClearLos;
	}

	public double getCollisionFillThreshold() {
		return collisionFillThreshold;
	}

	public void setCollisionFillThreshold(double collisionFillThreshold) {
		this.collisionFillThreshold = collisionFillThreshold;
	}

	public double getDefaultBlockAttenuation() {
		return defaultBlockAttenuation;
	}

	public void setDefaultBlockAttenuation(double defaultBlockAttenuation) {
		this.defaultBlockAttenuation = defaultBlockAttenuation;
	}

	public Double getBlockAttenuationOverride(Material material) {
		if (material == null) {
			return null;
		}
		return blockAttenuation.get(material);
	}

	public double getBlockAttenuation(Material material) {
		if (material == null) {
			return defaultBlockAttenuation;
		}
		Double override = blockAttenuation.get(material);
		if (override != null) {
			return override;
		}
		return SoundOcclusionRules.attenuationFromBlastResistance(material, defaultBlockAttenuation);
	}

	public void setBlockAttenuation(Map<Material, Double> blockAttenuation) {
		this.blockAttenuation = blockAttenuation != null ? new HashMap<>(blockAttenuation) : new HashMap<>();
	}

	public List<MuffleRule> getMuffleRules() {
		return Collections.unmodifiableList(muffleRules);
	}

	public void setMuffleRules(List<MuffleRule> muffleRules) {
		this.muffleRules = muffleRules != null ? new ArrayList<>(muffleRules) : new ArrayList<>();
	}

	public boolean isCharismaHearingEnabled() {
		return charismaHearingEnabled;
	}

	public void setCharismaHearingEnabled(boolean charismaHearingEnabled) {
		this.charismaHearingEnabled = charismaHearingEnabled;
	}

	public String getCharismaAttribute() {
		return charismaAttribute;
	}

	public void setCharismaAttribute(String charismaAttribute) {
		this.charismaAttribute = charismaAttribute != null ? charismaAttribute : "charisma";
	}

	public double getCharismaMaxBoost() {
		return charismaMaxBoost;
	}

	public void setCharismaMaxBoost(double charismaMaxBoost) {
		this.charismaMaxBoost = charismaMaxBoost;
	}

	public double getCharismaScale() {
		return charismaScale;
	}

	public void setCharismaScale(double charismaScale) {
		this.charismaScale = charismaScale;
	}

	public double getCharismaPlaceholderMultiplier() {
		return charismaPlaceholderMultiplier;
	}

	public void setCharismaPlaceholderMultiplier(double charismaPlaceholderMultiplier) {
		this.charismaPlaceholderMultiplier = charismaPlaceholderMultiplier;
	}

	public boolean isAnonymousMuffledVoiceEnabled() {
		return anonymousMuffledVoiceEnabled;
	}

	public void setAnonymousMuffledVoiceEnabled(boolean anonymousMuffledVoiceEnabled) {
		this.anonymousMuffledVoiceEnabled = anonymousMuffledVoiceEnabled;
	}

	public double getAnonymousMuffledMaxIntelligibility() {
		return anonymousMuffledMaxIntelligibility;
	}

	public void setAnonymousMuffledMaxIntelligibility(double anonymousMuffledMaxIntelligibility) {
		this.anonymousMuffledMaxIntelligibility = anonymousMuffledMaxIntelligibility;
	}

	public String getAnonymousMuffledDisplay() {
		return anonymousMuffledDisplay;
	}

	public void setAnonymousMuffledDisplay(String anonymousMuffledDisplay) {
		this.anonymousMuffledDisplay = anonymousMuffledDisplay != null ? anonymousMuffledDisplay : "???";
	}

	public boolean isPlaceholderSuppressionEnabled() {
		return placeholderSuppressionEnabled;
	}

	public void setPlaceholderSuppressionEnabled(boolean placeholderSuppressionEnabled) {
		this.placeholderSuppressionEnabled = placeholderSuppressionEnabled;
	}
}
