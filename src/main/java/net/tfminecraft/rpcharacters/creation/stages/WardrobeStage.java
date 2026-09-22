package net.tfminecraft.rpcharacters.creation.stages;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.configuration.ConfigurationSection;

import net.tfminecraft.rpcharacters.creation.Stage;

/**
 * Web-only wardrobe creation stage. Skipped in-game via {@code platform: web}.
 */
public class WardrobeStage extends Stage {

	private List<String> webMessages = new ArrayList<>();

	public WardrobeStage(Stage s, ConfigurationSection config) {
		copyBaseFields(s);
		if (config.contains("web-messages")) {
			this.webMessages = config.getStringList("web-messages");
		} else if (config.contains("messages")) {
			this.webMessages = config.getStringList("messages");
		}
	}

	public WardrobeStage(WardrobeStage another) {
		copyBaseFields(another);
		this.webMessages = new ArrayList<>(another.getWebMessages());
	}

	public List<String> getWebMessages() {
		return webMessages;
	}

	public boolean hasWebMessages() {
		return webMessages != null && !webMessages.isEmpty();
	}
}
