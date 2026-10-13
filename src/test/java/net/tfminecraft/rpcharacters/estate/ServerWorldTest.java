package net.tfminecraft.rpcharacters.estate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.tfminecraft.rpcharacters.identity.CharacterDirectory;
import net.tfminecraft.rpcharacters.identity.CharacterIdentityService;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterStatus;
import net.tfminecraft.tlibs.character.estate.DeathGuild;
import net.tfminecraft.tlibs.character.estate.EstateParticipant;
import net.tfminecraft.tlibs.character.estate.Estates;
import net.tfminecraft.tlibs.character.estate.GuildLookup;

class ServerWorldTest {
    @TempDir Path root;

    private CharacterInfo dead(String name) {
        return new CharacterInfo(new CharacterRef(UUID.randomUUID(), UUID.randomUUID()), name, CharacterStatus.DEAD, "elf",
                Set.of(), 0, null);
    }

    private void file(CharacterInfo info, String json) throws Exception {
        Path folder = Files.createDirectories(root.resolve(info.ref().ownerId().toString()));
        Files.writeString(folder.resolve(info.ref().characterId() + ".json"), json);
    }

    @Test
    void deadCharactersCarryTheirSavedDeathTimeAndWill() throws Exception {
        CharacterIdentityService identity = mock(CharacterIdentityService.class);
        ServerWorld world = new ServerWorld(() -> identity, root);
        CharacterInfo recorded = dead("Aria");
        CharacterInfo legacy = dead("Ilvaran");
        CharacterInfo missing = dead("Lost");
        CharacterInfo badWill = dead("Odd");
        UUID heir = UUID.randomUUID();
        file(recorded, "{\"death-id\":\"" + UUID.randomUUID() + "\",\"died-at-ms\":1760356800000,\"will-heir\":\"" + heir
                + "\",\"will-revision\":3}");
        file(legacy, "{\"died-at-ms\":5}");
        file(badWill, "{\"will-heir\":\"not-a-uuid\",\"will-revision\":2}");
        UUID d1 = UUID.randomUUID(), d2 = UUID.randomUUID(), d3 = UUID.randomUUID(), d4 = UUID.randomUUID();
        when(identity.deaths()).thenReturn(List.of(new CharacterDirectory.Death(recorded, d1),
                new CharacterDirectory.Death(legacy, d2), new CharacterDirectory.Death(missing, d3),
                new CharacterDirectory.Death(badWill, d4)));
        List<EstateWorld.Dead> found = world.dead();
        assertEquals(new EstateWorld.Dead(recorded, d1, Instant.ofEpochMilli(1760356800000L), heir, 3), found.get(0));
        assertEquals(new EstateWorld.Dead(legacy, d2, null, null, 0), found.get(1));
        assertEquals(new EstateWorld.Dead(missing, d3, null, null, 0), found.get(2));
        assertEquals(new EstateWorld.Dead(badWill, d4, null, null, 2), found.get(3));
        file(missing, "[]");
        assertNull(world.dead().get(2).diedAt());
    }

    @Test
    void lookupsGoToTheIdentityServiceAndTLibs() {
        CharacterIdentityService identity = mock(CharacterIdentityService.class);
        ServerWorld world = new ServerWorld(() -> identity, root);
        CharacterInfo info = dead("Aria");
        UUID id = info.ref().characterId();
        when(identity.info(id)).thenReturn(Optional.of(info));
        when(identity.unreadable(id)).thenReturn(true);
        when(identity.findByName("Aria")).thenReturn(List.of(info));
        when(identity.deathId(id)).thenReturn(Optional.of(id));
        assertEquals(Optional.of(info), world.info(id));
        assertTrue(world.unreadable(id));
        assertEquals(List.of(info), world.findByName("Aria"));
        assertEquals(Optional.of(id), world.deathId(id));
        try (var estates = mockStatic(Estates.class)) {
            EstateParticipant participant = mock(EstateParticipant.class);
            estates.when(() -> Estates.deathGuild(id, id)).thenReturn(DeathGuild.NONE);
            estates.when(() -> Estates.guild(id)).thenReturn(GuildLookup.GONE);
            estates.when(() -> Estates.guildByName("Iron")).thenReturn(GuildLookup.UNAVAILABLE);
            estates.when(Estates::participants).thenReturn(List.of(participant));
            assertEquals(DeathGuild.NONE, world.deathGuild(id, id));
            assertEquals(GuildLookup.GONE, world.guild(id));
            assertEquals(GuildLookup.UNAVAILABLE, world.guildByName("Iron"));
            assertEquals(List.of(participant), world.participants());
        }
    }
}
