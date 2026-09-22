package net.tfminecraft.rpcharacters.mmocore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.tfminecraft.rpcharacters.objects.PlayerData;

class PendingMmoAttributeRemovesTest {

	@Test
	void takePendingClearsStoredList() {
		PlayerData pd = new PlayerData(UUID.fromString("f8ffcd4b-4ab6-4e8f-a1d4-41678c8291d7"));
		pd.setPendingMmoAttributeRemoves(List.of("strength.7", "dexterity.11"));
		assertEquals(List.of("strength.7", "dexterity.11"), pd.takePendingMmoAttributeRemoves());
		assertTrue(pd.takePendingMmoAttributeRemoves().isEmpty());
	}
}
