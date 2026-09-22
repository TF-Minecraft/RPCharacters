package net.tfminecraft.rpcharacters.mmocore;

import java.util.Locale;

import net.tfminecraft.rpcharacters.Cache;

public final class IgnoredAttributes {

	private IgnoredAttributes() {}

	public static boolean isIgnored(String attributeId) {
		if (attributeId == null) {
			return false;
		}
		return Cache.ignoredAttributes.contains(attributeId.trim().toLowerCase(Locale.ROOT));
	}
}
