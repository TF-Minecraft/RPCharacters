package net.tfminecraft.rpcharacters.tutorial;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class TutorialLoaderTest {

	@Test
	void serverTutorialsWinAndMissingOnesComeFromTheJar() {
		YamlConfiguration server = new YamlConfiguration();
		server.set("tutorials.evil-rp.lines", List.of("server text"));
		YamlConfiguration bundled = new YamlConfiguration();
		bundled.set("tutorials.evil-rp.lines", List.of("jar text"));
		bundled.set("tutorials.pvp-strikes.lines", List.of("new tutorial"));

		Map<String, List<String>> loaded = new HashMap<>();
		TutorialLoader.addMissing(loaded, server);
		TutorialLoader.addMissing(loaded, bundled);

		assertEquals(List.of("server text"), loaded.get("evil-rp"));
		assertEquals(List.of("new tutorial"), loaded.get("pvp-strikes"));
	}
}
