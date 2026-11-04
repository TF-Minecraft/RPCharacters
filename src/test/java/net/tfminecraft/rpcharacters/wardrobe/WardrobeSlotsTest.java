package net.tfminecraft.rpcharacters.wardrobe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WardrobeSlotsTest {

	private final Locale original = Locale.getDefault();

	@AfterEach void restoreLocale() {
		Locale.setDefault(original);
	}

	@Test void swappableSlotsRunFromBaseToTheFourthExtraAndCannotBeChanged() {
		assertEquals(List.of("base", "extra_1", "extra_2", "extra_3", "extra_4"), WardrobeSlots.swappable());
		assertEquals(WardrobeSlots.MAX_SWAPPABLE, WardrobeSlots.swappable().size());
		assertThrows(UnsupportedOperationException.class, () -> WardrobeSlots.swappable().add("extra_5"));
	}

	@Test void positionsAndRequiredSkinsCountBaseAsTheFirstSkin() {
		assertEquals(0, WardrobeSlots.indexOf(" BASE "));
		assertEquals(4, WardrobeSlots.indexOf("extra_4"));
		assertEquals(-1, WardrobeSlots.indexOf("masked"));
		assertEquals(-1, WardrobeSlots.indexOf("extra_5"));
		assertEquals(-1, WardrobeSlots.indexOf(null));
		assertEquals(1, WardrobeSlots.requiredSkins("base"));
		assertEquals(3, WardrobeSlots.requiredSkins("extra_2"));
		assertEquals(5, WardrobeSlots.requiredSkins("extra_4"));
		assertEquals(1, WardrobeSlots.requiredSkins("masked"));
		assertEquals(1, WardrobeSlots.requiredSkins(null));
	}

	@Test void labelsNumberEverySkinAndKeepUnknownIds() {
		assertEquals("Skin", WardrobeSlots.label(null));
		assertEquals("Base", WardrobeSlots.label("BASE"));
		assertEquals("Skin 2", WardrobeSlots.label("extra_1"));
		assertEquals("Skin 5", WardrobeSlots.label("extra_4"));
		assertEquals("Masked", WardrobeSlots.label(" Masked "));
		assertEquals("custom", WardrobeSlots.label("custom"));
	}

	@Test void commandArgumentsAcceptIdsExtrasAndSkinNumbers() {
		Locale.setDefault(Locale.forLanguageTag("tr-TR"));
		assertNull(WardrobeSlots.normalize(null));
		for (String alias : List.of("base", "1", "skin1", "SKIN_1", " Base ")) {
			assertEquals("base", WardrobeSlots.normalize(alias), alias);
		}
		for (String alias : List.of("extra_3", "EXTRA3", "4", "skin4", "skin_4")) {
			assertEquals("extra_3", WardrobeSlots.normalize(alias), alias);
		}
		for (String alias : List.of("extra_4", "extra4", "5", "skin5", "skin_5")) {
			assertEquals("extra_4", WardrobeSlots.normalize(alias), alias);
		}
		for (String bad : Arrays.asList("", "masked", "extra", "extra0", "extra5", "extra_5", "extra_x", "0", "6",
				"skin", "skin_", "skin0", "skin6", "skin_x", "100", "-1", "custom", "05", "skin_01", "extra03", "extra_03")) {
			assertNull(WardrobeSlots.normalize(bad), bad);
		}
	}
}
