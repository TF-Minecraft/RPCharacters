package net.tfminecraft.rpcharacters.wardrobe;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Holder for the in-game wardrobe skin picker GUI.
 */
public final class WardrobeGuiHolder implements InventoryHolder {

	private final Player owner;
	private Inventory inventory;

	public WardrobeGuiHolder(Player owner) {
		this.owner = owner;
	}

	public Player getOwner() {
		return owner;
	}

	void setInventory(Inventory inventory) {
		this.inventory = inventory;
	}

	@Override
	public Inventory getInventory() {
		return inventory;
	}
}
