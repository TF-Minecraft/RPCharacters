package net.tfminecraft.rpcharacters.database;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import net.tfminecraft.rpcharacters.classpick.PendingClassPick;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.paidchange.Payment;

/**
 * Class picker state in the character file, under "class-picks": whether the free subclass pick
 * is used ("subclass-picked"), how many picks were paid for ("paid") and a paid pick saved before
 * its debit ("pending"). Hidden classes the character has unlocked are listed under
 * "unlocked-classes", and classes with their own skill-point pool under "class-skill-points".
 */
public final class CharacterClassPickFields {
	static final String UNLOCKED_CLASSES = "unlocked-classes";
	static final String CLASS_SKILL_POINTS = "class-skill-points";

	private CharacterClassPickFields() {}

	public static void load(RPCharacter character, JSONObject characterJson) {
		if (character == null || characterJson == null) {
			return;
		}
		if (characterJson.get(UNLOCKED_CLASSES) instanceof List<?> unlocked) {
			for (Object classId : unlocked) {
				if (classId instanceof String id && !id.isBlank()) {
					character.addUnlockedClass(id.trim());
				}
			}
		}
		if (characterJson.get(CLASS_SKILL_POINTS) instanceof Map<?, ?> pools) {
			for (Map.Entry<?, ?> pool : pools.entrySet()) {
				if (pool.getKey() instanceof String id && !id.isBlank() && pool.getValue() instanceof Number total) {
					character.setClassSkillPoints(id.trim(), total.intValue());
				}
			}
		}
		if (!(characterJson.get("class-picks") instanceof Map<?, ?> picks)) {
			return;
		}
		character.setSubclassPicked(Boolean.TRUE.equals(picks.get("subclass-picked")));
		if (picks.get("paid") instanceof Number paid) {
			character.setPaidClassPicks(paid.intValue());
		}
		if (picks.get("pending") instanceof Map<?, ?> pending) {
			try {
				character.setPendingClassPick(new PendingClassPick((String) pending.get("target"),
						Payment.fromJson((Map<?, ?>) pending.get("payment")),
						Boolean.TRUE.equals(pending.get("refund-required"))));
			} catch (RuntimeException e) {
				Logger.getLogger("RPCharacters").warning("[RPCharacters] Unreadable pending class pick " + pending
						+ "; refund it by hand if denars were taken.");
			}
		}
	}

	@SuppressWarnings("unchecked")
	public static void save(Map<String, Object> defaults, RPCharacter character) {
		if (!character.getUnlockedClasses().isEmpty()) {
			JSONArray unlocked = new JSONArray();
			unlocked.addAll(character.getUnlockedClasses());
			defaults.put(UNLOCKED_CLASSES, unlocked);
		}
		if (!character.getClassSkillPoints().isEmpty()) {
			JSONObject pools = new JSONObject();
			pools.putAll(character.getClassSkillPoints());
			defaults.put(CLASS_SKILL_POINTS, pools);
		}
		PendingClassPick pending = character.getPendingClassPick();
		if (!character.hasPickedSubclass() && character.getPaidClassPicks() == 0 && pending == null) {
			return;
		}
		JSONObject picks = new JSONObject();
		picks.put("subclass-picked", character.hasPickedSubclass());
		picks.put("paid", character.getPaidClassPicks());
		if (pending != null) {
			JSONObject held = new JSONObject();
			held.put("target", pending.targetClass());
			held.put("payment", pending.payment().toJson());
			held.put("refund-required", pending.refundRequired());
			picks.put("pending", held);
		}
		defaults.put("class-picks", picks);
	}
}
