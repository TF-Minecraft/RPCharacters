package net.tfminecraft.rpcharacters.objects.attributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.rpcharacters.Cache;

class AttributeDataMergeTest {

	@BeforeEach
	void seedCacheAttributes() {
		Cache.attributes = new ArrayList<>(List.of("strength", "dexterity"));
		Cache.professions = new ArrayList<>();
	}

	@AfterEach
	void clearCacheAttributes() {
		Cache.attributes = new ArrayList<>();
		Cache.professions = new ArrayList<>();
	}

	@Test
	void emptyConstructorSeedsCachedAttributesAtZero() {
		AttributeData data = new AttributeData();
		assertEquals(0, data.getAmount(new AttributeModifier("strength", 0)));
		assertEquals(0, data.getAmount(new AttributeModifier("dexterity", 0)));
	}

	@Test
	void mergeFromGiftAddsToPreviewTotals() {
		AttributeData preview = new AttributeData();
		AttributeData gift = new AttributeData();
		gift.clearAll();
		gift.addModifier(new AttributeModifier("strength", 2));

		preview.mergeFrom(gift);

		assertEquals(2, preview.getAmount(new AttributeModifier("strength", 0)));
		assertEquals(0, preview.getAmount(new AttributeModifier("dexterity", 0)));
	}

	@Test
	void mergeFromReverseRemovesGiftBonus() {
		AttributeData preview = new AttributeData();
		AttributeData gift = new AttributeData();
		gift.clearAll();
		gift.addModifier(new AttributeModifier("strength", 2));
		preview.mergeFrom(gift);

		preview.mergeFromReverse(gift);

		assertEquals(0, preview.getAmount(new AttributeModifier("strength", 0)));
	}

	@Test
	void copyConstructorDoesNotShareModifierList() {
		AttributeData live = new AttributeData();
		live.addModifier(new AttributeModifier("strength", 3));
		AttributeData preview = new AttributeData(live);

		assertEquals(3, preview.getAmount(new AttributeModifier("strength", 0)));
		assertNotSame(live.getModifiers(), preview.getModifiers());

		preview.addModifier(new AttributeModifier("strength", 1));
		assertEquals(3, live.getAmount(new AttributeModifier("strength", 0)));
		assertEquals(4, preview.getAmount(new AttributeModifier("strength", 0)));
	}
}
