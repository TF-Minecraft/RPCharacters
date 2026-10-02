package net.tfminecraft.rpcharacters.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.entity.Player;

import net.Indyuce.mmocore.api.player.PlayerData;
import net.Indyuce.mmocore.api.player.attribute.PlayerAttributes;
import net.Indyuce.mmocore.api.player.attribute.PlayerAttributes.AttributeInstance;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeModifier;

public class Integrator {
	public void integrate(Player p, RPCharacter c) {
		if (c == null) return;
		PlayerAttributes attributes = readyAttributes(p);
		if (attributes == null) return;
		for(AttributeModifier m : c.getAttributeData().getModifiers()) {
			AttributeInstance attribute = attributes.getInstance(m.getType());
			if(attribute == null) continue;
			attribute.setBase(attribute.getBase()+m.getAmount());
		}
	}
	public Map<String, Integer> get(Player p, RPCharacter c) {
		Map<String, Integer> map = new HashMap<>();
		PlayerAttributes attributes = readyAttributes(p);
		if (attributes == null) return map;
		for(AttributeInstance a : attributes.getInstances()) {
			map.put(a.getId(), a.getBase());
		}
		return map;
	}

	public void stripCreationLayer(Player p, RPCharacter c) {
		if (c == null) return;
		PlayerAttributes attributes = readyAttributes(p);
		if (attributes == null) return;
		for (AttributeModifier m : c.getAttributeData().getModifiers()) {
			AttributeInstance attribute = attributes.getInstance(m.getType());
			if (attribute == null) {
				continue;
			}
			int next = attribute.getBase() - m.getAmount();
			attribute.setBase(Math.max(0, next));
		}
	}

 	public void remove(Player p, RPCharacter c, boolean reset) {
		stripCreationLayer(p, c);
	}
	public void remove(Player p, String s) {
		PlayerAttributes attributes = readyAttributes(p);
		if (attributes == null) return;
		remove(attributes, s);
	}

	private void remove(PlayerAttributes attributes, String s) {
		if (s == null) return;
		int separator = s.lastIndexOf('.');
		if (separator <= 0 || separator == s.length() - 1) return;
		String type = s.substring(0, separator);
		int amount;
		try {
			amount = Integer.parseInt(s.substring(separator + 1));
		} catch (NumberFormatException invalid) {
			return;
		}
		AttributeInstance attribute = attributes.getInstance(type);
		if(attribute == null) return;
		attribute.setBase(attribute.getBase()-amount);
	}

	public void applyPendingRemoves(Player p, List<String> pending) {
		if (p == null || pending == null || pending.isEmpty()) {
			return;
		}
		tryApplyPendingRemoves(p, pending);
	}

	/** False means no removals were applied: the caller must keep its persisted queue. */
	public boolean tryApplyPendingRemoves(Player p, List<String> pending) {
		PlayerAttributes attributes = readyAttributes(p);
		if (attributes == null) return false;
		for (String removal : pending) {
			remove(attributes, removal);
		}
		return true;
	}
	public List<String> getRemoveList(Player p, RPCharacter c) {
		List<String> remove = new ArrayList<>();
		if (c == null) return remove;
		for(AttributeModifier m : c.getAttributeData().getModifiers()) {
			remove.add(m.getType()+"."+m.getAmount());
		}
		return remove;
	}

	private static PlayerAttributes readyAttributes(Player player) {
		if (player == null) return null;
		try {
			PlayerData data = PlayerData.get(player);
			return data != null && data.isSynchronized() ? data.getAttributes() : null;
		} catch (RuntimeException | LinkageError unavailable) {
			return null;
		}
	}
}
