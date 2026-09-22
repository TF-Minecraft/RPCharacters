package net.tfminecraft.rpcharacters.holder;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import net.tfminecraft.rpcharacters.creation.CharacterCreation;
import net.tfminecraft.rpcharacters.creation.Stage;
import net.tfminecraft.rpcharacters.enums.CreationGuiContext;

public class RPCHolder implements InventoryHolder {
    private final Player owner;
    private Stage stage;
    private boolean overridden = false;
    private CharacterCreation creation;
    private CreationGuiContext context = CreationGuiContext.NONE;

    public RPCHolder(Player p) {
        this.owner = p;
        this.stage = null;
    }

    public RPCHolder(Player p, Stage stage) {
        this.owner = p;
        this.stage = stage;
    }

    public RPCHolder(Player p, CharacterCreation creation, CreationGuiContext context) {
        this.owner = p;
        this.creation = creation;
        this.context = context;
    }

    public RPCHolder(Player p, Stage stage, CharacterCreation creation, CreationGuiContext context) {
        this.owner = p;
        this.stage = stage;
        this.creation = creation;
        this.context = context;
    }

    public Player getOwner() {
        return owner;
    }

    public Stage getStage() {
        return stage;
    }

    public CharacterCreation getCreation() {
        return creation;
    }

    public CreationGuiContext getContext() {
        return context;
    }

    public void override() {
        overridden = true;
    }

    public boolean isOverridden() {
        return overridden;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}
