package net.tfminecraft.rpcharacters.body;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.junit.jupiter.api.Test;

class CharacterBodyStartupGuardTest {
    @Test void bothLoginBoundariesStayClosedAfterStorageConfigurationFails() {
        CharacterBodyStartupGuard guard = new CharacterBodyStartupGuard();
        AsyncPlayerPreLoginEvent preLogin = mock(AsyncPlayerPreLoginEvent.class);
        PlayerLoginEvent login = mock(PlayerLoginEvent.class);
        guard.preLogin(preLogin); guard.login(login);
        verify(preLogin).disallow(eq(AsyncPlayerPreLoginEvent.Result.KICK_OTHER), contains("Character storage is unavailable"));
        verify(login).disallow(eq(PlayerLoginEvent.Result.KICK_OTHER), contains("Character storage is unavailable"));
    }
}
