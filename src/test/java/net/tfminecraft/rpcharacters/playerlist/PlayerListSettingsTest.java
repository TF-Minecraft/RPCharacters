package net.tfminecraft.rpcharacters.playerlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PlayerListSettingsTest {

	private static PlayerListSettings settings() {
		Map<String, String> tags = new LinkedHashMap<>();
		tags.put("staff", "[Staff]");
		tags.put("legacy", "[Legacy]");
		tags.put("ascended", "[Ascended]");
		tags.put("commoner", "[Commoner]");
		return new PlayerListSettings("rpchar.playerlist", true, true, "t", "h", "Who's online", tags);
	}

	@Test
	void rankIsFirstListedGroupThePlayerIsIn() {
		// Legacy inherits ascended and commoner; the higher tier wins.
		Set<String> groups = Set.of("commoner", "ascended", "legacy");
		assertEquals("legacy", settings().rank(groups::contains));
	}

	@Test
	void rankIsNullWithoutAListedGroup() {
		assertNull(settings().rank(Set.of("default")::contains));
	}

	@Test
	void unrankedPlayersSortLast() {
		PlayerListSettings settings = settings();
		assertEquals(0, settings.rankIndex("staff"));
		assertEquals(3, settings.rankIndex("commoner"));
		assertEquals(4, settings.rankIndex(null));
		assertEquals(4, settings.rankIndex("unknown"));
	}

	@Test
	void tagIsEmptyWithoutARank() {
		assertEquals("[Ascended]", settings().tag("ascended"));
		assertEquals("", settings().tag(null));
	}
}
