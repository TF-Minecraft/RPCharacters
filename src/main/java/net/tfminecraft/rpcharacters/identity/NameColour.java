package net.tfminecraft.rpcharacters.identity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

public final class NameColour {

	private final List<String> colours;

	private NameColour(List<String> colours) {
		this.colours = colours;
	}

	public static NameColour of(List<String> hexCodes) {
		if (hexCodes == null || hexCodes.isEmpty()) {
			return null;
		}
		List<String> copy = new ArrayList<>();
		for (String code : hexCodes) {
			if (code != null && !code.isBlank()) {
				copy.add(code.trim());
			}
		}
		if (copy.isEmpty()) {
			return null;
		}
		return new NameColour(Collections.unmodifiableList(copy));
	}

	public static NameColour solid(String hex) {
		return of(List.of(hex));
	}

	public static NameColour gradient(String a, String b) {
		return of(List.of(a, b));
	}

	public List<String> getHexCodes() {
		return colours;
	}

	@SuppressWarnings("unchecked")
	public static NameColour fromJson(JSONObject json) {
		if (json == null || !json.containsKey("colours")) {
			return null;
		}
		Object raw = json.get("colours");
		if (!(raw instanceof JSONArray array)) {
			return null;
		}
		List<String> codes = new ArrayList<>();
		for (Object entry : array) {
			if (entry != null) {
				codes.add(entry.toString());
			}
		}
		return of(codes);
	}

	@SuppressWarnings("unchecked")
	public JSONObject toJsonObject() {
		JSONObject json = new JSONObject();
		JSONArray array = new JSONArray();
		for (String colour : colours) {
			array.add(colour);
		}
		json.put("colours", array);
		return json;
	}

	public HashMap<String, Object> toJsonMap() {
		HashMap<String, Object> map = new HashMap<>();
		map.put("colours", new ArrayList<>(colours));
		return map;
	}
}
