package net.tfminecraft.rpcharacters.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IaItemPathTest {

	@Test
	void mythicTagUsesDotBetweenNamespaceAndId() {
		assertEquals(
			"tfmc_submissions.oni_mask",
			IaItemPath.mythicIaTag("ia.tfmc_submissions:oni_mask")
		);
		assertNull(IaItemPath.mythicIaTag("m.masks.ghost_mask"));
		assertNull(IaItemPath.mythicIaTag("ia.missingcolon"));
	}

	@Test
	void tagMatchIgnoresCase() {
		assertTrue(IaItemPath.tagMatches(
			"ia.tfmc_submissions:oni_mask",
			"tfmc_submissions.oni_mask"
		));
		assertTrue(IaItemPath.tagMatches(
			"IA.tfmc_submissions:oni_mask",
			"TFMC_SUBMISSIONS.oni_mask"
		));
		assertFalse(IaItemPath.tagMatches(
			"ia.tfmc_submissions:oni_mask",
			"tfmc_armorshop.oni_mask"
		));
		assertFalse(IaItemPath.tagMatches("m.masks.ghost_mask", "masks.ghost_mask"));
	}
}
