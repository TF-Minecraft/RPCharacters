package net.tfminecraft.rpcharacters.professions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ProfessionItemType {
	private final String id;
	private final List<String> mmoItemTypes;

	public ProfessionItemType(String id, List<String> mmoItemTypes) {
		this.id = id;
		this.mmoItemTypes = mmoItemTypes != null ? mmoItemTypes : new ArrayList<>();
	}

	public String getId() {
		return id;
	}

	public List<String> getMmoItemTypes() {
		return Collections.unmodifiableList(mmoItemTypes);
	}
}
