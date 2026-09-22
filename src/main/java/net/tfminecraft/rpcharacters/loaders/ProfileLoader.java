package net.tfminecraft.rpcharacters.loaders;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.rpcharacters.Cache;

public class ProfileLoader implements LoaderInterface {

	private static final int HARD_MAX_CHARACTER_SLOTS = 10;
	private static final List<Integer> DEFAULT_ROW1 = List.of(10, 11, 12, 13, 14);
	private static final List<Integer> DEFAULT_ROW2 = List.of(19, 20, 21, 22, 23);
	private static final int DEFAULT_DEAD_SLOT = 16;

	@Override
	public void load(File configFile) {
		FileConfiguration config = new YamlConfiguration();
		try {
			config.load(configFile);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		int maxSlots = config.getInt("max-character-slots", HARD_MAX_CHARACTER_SLOTS);
		maxSlots = Math.max(1, Math.min(HARD_MAX_CHARACTER_SLOTS, maxSlots));
		Cache.maxCharacterSlots = maxSlots;

		int deadSlot = config.getInt("dead-slot", DEFAULT_DEAD_SLOT);
		Cache.deadSlot = deadSlot;

		List<Integer> row1 = new ArrayList<>(config.getIntegerList("character-slots"));
		if (row1.isEmpty()) {
			row1.addAll(DEFAULT_ROW1);
		}

		List<Integer> slots = new ArrayList<>();
		for (int slot : row1) {
			if (slot == deadSlot || slots.contains(slot)) {
				continue;
			}
			slots.add(slot);
			if (slots.size() >= maxSlots) {
				break;
			}
		}

		if (slots.size() < maxSlots) {
			Cache.baseCharacterSlotCount = slots.size();
			List<Integer> row2 = config.contains("extended-character-slots")
					? config.getIntegerList("extended-character-slots")
					: DEFAULT_ROW2;
			for (int slot : row2) {
				if (slots.size() >= maxSlots) {
					break;
				}
				if (slot == deadSlot || slots.contains(slot)) {
					continue;
				}
				slots.add(slot);
			}
		} else {
			Cache.baseCharacterSlotCount = slots.size();
		}

		if (slots.size() > maxSlots) {
			slots = new ArrayList<>(slots.subList(0, maxSlots));
		}

		if (slots.size() < maxSlots && maxSlots > DEFAULT_ROW1.size()) {
			Bukkit.getLogger().warning(
					"[RPCharacters] profile.yml: only " + slots.size() + " character slot positions configured "
							+ "but max-character-slots is " + maxSlots
							+ ". Add extended-character-slots or more character-slots entries.");
		}

		Cache.characterSlots = slots;
	}
}
