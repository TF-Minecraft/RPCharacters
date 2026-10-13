package net.tfminecraft.rpcharacters.will;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.UUID;

import org.json.simple.JSONObject;
import org.junit.jupiter.api.Test;

import net.tfminecraft.rpcharacters.enums.Status;
import net.tfminecraft.rpcharacters.estate.EstateHooks;
import net.tfminecraft.rpcharacters.estate.EstateLink;
import net.tfminecraft.rpcharacters.identity.CharacterDirectory;
import net.tfminecraft.rpcharacters.objects.RPCharacter;

class WillRosterTest {
    @Test
    void rowsCarryTheDeathTheWillAndTheEstate() {
        RPCharacter dead = new RPCharacter(WillQueueTest.owner());
        dead.setStatus(Status.DEAD);
        UUID heir = UUID.randomUUID();
        dead.setWill(new CharacterWill(heir, 2, 5L, null));
        EstateHooks hooks = mock(EstateHooks.class);
        try (var link = mockStatic(EstateLink.class); var service = mockStatic(WillService.class)) {
            link.when(EstateLink::current).thenReturn(hooks);
            service.when(() -> WillService.heirName(dead.getWill())).thenReturn("Kira");
            JSONObject legacy = new JSONObject();
            WillRoster.append(legacy, dead);
            UUID legacyId = CharacterDirectory.legacyDeathId(UUID.fromString(dead.getId()));
            assertEquals(legacyId.toString(), legacy.get("death_id"));
            assertNull(legacy.get("died_at"));
            assertEquals(heir.toString(), legacy.get("will_heir_id"));
            assertEquals("Kira", legacy.get("will_heir_name"));
            assertEquals(2L, legacy.get("will_revision"));
            verify(hooks).appendRoster(legacy, legacyId);
            UUID death = UUID.randomUUID();
            dead.setDeathRecord(death, Instant.ofEpochMilli(77));
            JSONObject recorded = new JSONObject();
            WillRoster.append(recorded, dead);
            assertEquals(77L, recorded.get("died_at"));
            verify(hooks).appendRoster(recorded, death);
            RPCharacter alive = new RPCharacter(WillQueueTest.owner());
            JSONObject living = new JSONObject();
            WillRoster.append(living, alive);
            assertNull(living.get("death_id"));
            verify(hooks, never()).appendRoster(eq(living), any());
            link.when(EstateLink::current).thenReturn(null);
            WillRoster.append(new JSONObject(), dead);
        }
    }
}
