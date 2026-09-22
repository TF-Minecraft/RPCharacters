package net.tfminecraft.rpcharacters.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.entity.Player;

import net.Indyuce.mmocore.api.player.PlayerData;
import net.Indyuce.mmocore.api.player.attribute.PlayerAttributes.AttributeInstance;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.objects.attributes.AttributeModifier;

public class Integrator {
	public void integrate(Player p, RPCharacter c) {
		PlayerData pd = PlayerData.get(p);
		for(AttributeModifier m : c.getAttributeData().getModifiers()) {
			AttributeInstance attribute = pd.getAttributes().getInstance(m.getType());
			if(attribute == null) continue;
			attribute.setBase(attribute.getBase()+m.getAmount());
		}
	}
	public Map<String, Integer> get(Player p, RPCharacter c) {
		Map<String, Integer> map = new HashMap<>();
		PlayerData pd = PlayerData.get(p);
		for(AttributeInstance a : pd.getAttributes().getInstances()) {
			map.put(a.getId(), a.getBase());
		}
		return map;
	}

	public void stripCreationLayer(Player p, RPCharacter c) {
		PlayerData pd = PlayerData.get(p);
		for (AttributeModifier m : c.getAttributeData().getModifiers()) {
			AttributeInstance attribute = pd.getAttributes().getInstance(m.getType());
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
		String type = s.split("\\.")[0];
		int amount = Integer.parseInt(s.split("\\.")[1]);
		PlayerData pd = PlayerData.get(p);
		AttributeInstance attribute = pd.getAttributes().getInstance(type);
		if(attribute == null) return;
		attribute.setBase(attribute.getBase()-amount);
	}

	public void applyPendingRemoves(Player p, List<String> pending) {
		if (p == null || pending == null || pending.isEmpty()) {
			return;
		}
		for (String a : pending) {
			remove(p, a);
		}
	}
	public List<String> getRemoveList(Player p, RPCharacter c) {
		List<String> remove = new ArrayList<>();
		for(AttributeModifier m : c.getAttributeData().getModifiers()) {
			remove.add(m.getType()+"."+m.getAmount());
		}
		return remove;
	}
}
