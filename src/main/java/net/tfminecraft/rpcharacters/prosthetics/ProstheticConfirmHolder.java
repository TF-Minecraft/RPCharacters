package net.tfminecraft.rpcharacters.prosthetics;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class ProstheticConfirmHolder implements InventoryHolder {

	private final Player owner;

	public ProstheticConfirmHolder(Player owner) {
		this.owner = owner;
	}

	public Player getOwner() {
		return owner;
	}

	@Override
	public Inventory getInventory() {
		return null;
	}
}
