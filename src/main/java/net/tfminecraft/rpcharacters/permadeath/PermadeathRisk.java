package net.tfminecraft.rpcharacters.permadeath;

import java.util.ArrayList;
import java.util.List;

import net.tfminecraft.rpcharacters.utils.RPTexts;

public final class PermadeathRisk {

	private final int injuryCount;
	private final int chancePercent;
	private final int chancePerInjury;

	public PermadeathRisk(int injuryCount, int chancePercent, int chancePerInjury) {
		this.injuryCount = injuryCount;
		this.chancePercent = chancePercent;
		this.chancePerInjury = chancePerInjury;
	}

	public int getInjuryCount() {
		return injuryCount;
	}

	public int getChancePercent() {
		return chancePercent;
	}

	public int getChancePerInjury() {
		return chancePerInjury;
	}

	public List<String> toLoreLines() {
		List<String> lore = new ArrayList<>();
		lore.add(RPTexts.formatGui(RPTexts.MUTED + "------------------------"));
		lore.add(RPTexts.formatGui(RPTexts.GUI_WARN + "Permadeath Risk:"));
		lore.add(RPTexts.formatGui(RPTexts.MUTED + "On death in a permadeath zone:"));
		lore.add(RPTexts.formatGui(RPTexts.MUTED + "Injury count: " + RPTexts.MUTED + injuryCount));

		if (chancePercent > 0) {
			lore.add(RPTexts.formatGui(RPTexts.MUTED + "Permakill chance: " + RPTexts.MUTED + chancePercent + "%"));
			if (chancePerInjury > 0) {
				lore.add(RPTexts.formatGui(
						RPTexts.MUTED + "Adds " + RPTexts.MUTED + "+" + chancePerInjury + "% per injury"));
			}
		} else {
			lore.add(RPTexts.formatGui(RPTexts.MUTED + "Permakill chance: " + RPTexts.GUI_SUCCESS + "0%"));
		}

		lore.add(RPTexts.formatGui(RPTexts.MUTED + "Healing injuries may become permanent on death."));
		lore.add(RPTexts.formatGui(RPTexts.MUTED + "Surgery treats healing injuries only."));
		lore.add(RPTexts.formatGui(RPTexts.MUTED + "Prosthetics replace some permanent injuries."));

		return lore;
	}
}
