package net.tfminecraft.rpcharacters.managers;

import java.time.Duration;
import java.util.function.BiFunction;

import org.bukkit.Sound;
import org.bukkit.entity.Player;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.ingest.RosterSyncService;
import net.tfminecraft.rpcharacters.lifecycle.CharacterTransitions;
import net.tfminecraft.rpcharacters.lifecycle.CharacterTransitions.ReviveResult;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.persona.CharacterSlotService;
import net.tfminecraft.rpcharacters.utils.RPTexts;

/**
 * The staff revive confirmed on the Character Info screen. It reads the estate when the confirm is
 * clicked; a decided estate that has not finished moving offers a one-use "Force revive" link instead.
 */
public final class StaffRevive {
    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder().uses(1)
            .lifetime(Duration.ofMinutes(10)).build();
    static BiFunction<ClickCallback<Audience>, ClickCallback.Options, ClickEvent> clickEvents = ClickEvent::callback;

    private StaffRevive() {}

    static void run(PlayerManager manager, Player staff, RPCharacter character, boolean force) {
        Player owner = character.getOwner();
        PlayerData data = PlayerManager.get(owner);
        if (data == null) {
            RPTexts.send(staff, RPTexts.ERROR + "Could not find that player's data.");
            return;
        }
        ReviveResult result = CharacterTransitions.revive(data, character, force, staff.getName());
        switch (result.status()) {
            case NOT_DEAD -> RPTexts.send(staff, RPTexts.ERROR + "That character is not dead.");
            case OWNER_OFFLINE -> RPTexts.send(staff, RPTexts.ERROR + "The owner must be online to revive a character.");
            case NOT_SAVED -> RPTexts.send(staff, RPTexts.ERROR + "The revive could not be saved; the character is still dead.");
            case NO_SLOT -> {
                int alive = data.getCharacters(Status.ALIVE).size();
                int max = CharacterSlotService.getMaxAliveCharacters(owner);
                RPTexts.send(staff, RPTexts.ERROR + owner.getName() + " has no free character slots ("
                        + RPTexts.WARN + alive + RPTexts.ERROR + "/" + RPTexts.WARN + max + RPTexts.ERROR + ").");
                staff.playSound(staff.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            }
            case ESTATE_BLOCKED -> {
                RPTexts.send(staff, RPTexts.ERROR + "The estate of " + character.getName() + " has passed and has not finished moving:");
                for (String blocker : result.blockers()) RPTexts.send(staff, RPTexts.WARN + "- " + blocker);
                staff.sendMessage(Component.text("[Force revive]", NamedTextColor.RED)
                        .append(Component.text(" Parts not yet moved stay with the character.", NamedTextColor.GRAY))
                        .clickEvent(clickEvents.apply(audience -> {
                            if (audience == staff) run(manager, staff, character, true);
                        }, ONCE)));
            }
            case REVIVED -> revived(manager, staff, owner, data, character, !result.blockers().isEmpty());
        }
        new InventoryManager().characterView(staff, character);
    }

    private static void revived(PlayerManager manager, Player staff, Player owner, PlayerData data, RPCharacter character,
            boolean forced) {
        if (!data.hasActiveCharacter()) {
            data.setActiveCharacter(character);
            net.tfminecraft.rpcharacters.clues.discovery.ClueDiscoveryVisualManager.get().refreshViewer(owner);
            net.tfminecraft.rpcharacters.wardrobe.WardrobeService.refreshActiveAsync(owner);
        }
        manager.savePlayer(owner);
        manager.reevaluateFreeze(owner);
        RosterSyncService.pushRosterForPlayer(owner);
        RPTexts.send(staff, RPTexts.SUCCESS + "Revived character " + RPTexts.MUTED + character.getName()
                + RPTexts.SUCCESS + " for " + RPTexts.MUTED + owner.getName() + RPTexts.SUCCESS
                + (forced ? ". Forced: parts not yet moved stay with the character." : "."));
        RPTexts.send(owner, RPTexts.SUCCESS + "Your character " + RPTexts.MUTED + character.getName()
                + " " + RPTexts.SUCCESS + "was revived by staff.");
    }
}
