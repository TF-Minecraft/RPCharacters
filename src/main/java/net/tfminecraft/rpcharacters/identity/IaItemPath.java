package net.tfminecraft.rpcharacters.identity;

/**
 * ItemsAdder item paths ({@code ia.namespace:id}) and the MythicLib {@code ia}
 * tag ArmourShop writes when a skin is applied ({@code namespace.id}).
 */
public final class IaItemPath {

	private IaItemPath() {}

	/**
	 * @return {@code namespace.id} for {@code ia.namespace:id}, or null
	 */
	public static String mythicIaTag(String itemPath) {
		if (itemPath == null) {
			return null;
		}
		String path = itemPath.trim();
		if (path.length() < 5 || !path.regionMatches(true, 0, "ia.", 0, 3)) {
			return null;
		}
		String rest = path.substring(3);
		int colon = rest.indexOf(':');
		if (colon <= 0 || colon >= rest.length() - 1) {
			return null;
		}
		String namespace = rest.substring(0, colon).trim();
		String id = rest.substring(colon + 1).trim();
		if (namespace.isEmpty() || id.isEmpty() || id.indexOf(':') >= 0) {
			return null;
		}
		return namespace + "." + id;
	}

	public static boolean tagMatches(String itemPath, String iaTag) {
		String expected = mythicIaTag(itemPath);
		if (expected == null || iaTag == null || iaTag.isBlank()) {
			return false;
		}
		return expected.equalsIgnoreCase(iaTag.trim());
	}
}
