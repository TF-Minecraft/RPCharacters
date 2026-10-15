package net.tfminecraft.rpcharacters.will;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.tlibs.character.CharacterSessionStartEvent;

/** Fetches the heir's bust when a character starts playing, so /will usually opens with it. */
public final class WillPortraitListener implements Listener {
    @EventHandler
    public void onSessionStart(CharacterSessionStartEvent event) {
        PlayerData data = PlayerManager.get(event.getPlayer());
        RPCharacter character = data == null ? null
                : data.getCharacterById(event.getSession().ref().characterId().toString());
        if (character != null) WillMenu.heir(character).ifPresent(WillMenu::prefetch);
    }
}
