package net.tfminecraft.rpcharacters.speechbubble.fake;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/** Stand-ins with the same shape as the 1.21.2+ NMS teleport records. */
class TeleportPacketFactoryTest {
    public record Vec(double x, double y, double z) {}
    public record Change(Vec position, Vec deltaMovement, float yRot, float xRot) {}
    public record Teleport(int id, Change change, Set<?> relatives, boolean onGround) {}
    public record Exploding(int id, Change change, Set<?> relatives, boolean onGround) {
        public Exploding { if (id < 0) throw new IllegalStateException("boom"); }
    }

    public record TwoParts(int id, Change change) {}
    public record LongId(long id, Change change, Set<?> relatives, boolean onGround) {}
    public record ListFlags(int id, Change change, List<?> relatives, boolean onGround) {}
    public record IntGround(int id, Change change, Set<?> relatives, int onGround) {}
    public record PlainChange(int id, String change, Set<?> relatives, boolean onGround) {}
    public record ThreePartChange(Vec position, Vec deltaMovement, float yRot) {}
    public record WithThreePartChange(int id, ThreePartChange change, Set<?> relatives, boolean onGround) {}
    public record MixedChange(Vec position, Object deltaMovement, float yRot, float xRot) {}
    public record WithMixedChange(int id, MixedChange change, Set<?> relatives, boolean onGround) {}
    public record DoubleYawChange(Vec position, Vec deltaMovement, double yRot, float xRot) {}
    public record WithDoubleYaw(int id, DoubleYawChange change, Set<?> relatives, boolean onGround) {}
    public record DoublePitchChange(Vec position, Vec deltaMovement, float yRot, double xRot) {}
    public record WithDoublePitch(int id, DoublePitchChange change, Set<?> relatives, boolean onGround) {}
    public record IntVec(int x, int y, int z) {}
    public record IntVecChange(IntVec position, IntVec deltaMovement, float yRot, float xRot) {}
    public record WithIntVec(int id, IntVecChange change, Set<?> relatives, boolean onGround) {}
    private record Hidden(int id, Change change, Set<?> relatives, boolean onGround) {}

    @Test void builtPacketCarriesAbsolutePositionZeroVelocityAndRotation() {
        var factory = TeleportPacketFactory.forPacketClass(Teleport.class);
        assertNotNull(factory);
        var first = (Teleport) factory.create(-7, 1.25, 64.5, -3.75, 90f, -45f);
        assertEquals(new Teleport(-7, new Change(new Vec(1.25, 64.5, -3.75), new Vec(0, 0, 0), 90f, -45f), Set.of(), false), first);
        var second = (Teleport) factory.create(-8, 2, 3, 4, 0f, 0f);
        assertNotSame(first, second);
        assertEquals(new Vec(2, 3, 4), second.change().position());
        assertSame(first.change().deltaMovement(), second.change().deltaMovement(), "the zero velocity is built once");
    }

    @Test void unexpectedRecordShapesAreRejectedSoProtocolLibStaysInCharge() {
        for (Class<?> shape : List.of(String.class, TwoParts.class, LongId.class, ListFlags.class, IntGround.class,
                PlainChange.class, WithThreePartChange.class, WithMixedChange.class, WithDoubleYaw.class,
                WithDoublePitch.class, WithIntVec.class, Hidden.class)) {
            assertNull(TeleportPacketFactory.forPacketClass(shape), shape.getSimpleName());
        }
    }

    @Test void constructorFailureIsReportedAsAnIllegalState() {
        var factory = TeleportPacketFactory.forPacketClass(Exploding.class);
        assertNotNull(factory);
        var failure = assertThrows(IllegalStateException.class, () -> factory.create(-1, 0, 0, 0, 0f, 0f));
        assertEquals("boom", failure.getCause().getMessage());
    }

    @Test void detectionWithoutTheServerPacketClassFallsBack() {
        assertNull(TeleportPacketFactory.detect(), "no server running");
        MockBukkit.mock();
        try {
            assertNull(TeleportPacketFactory.detect(), "MockBukkit has no NMS packet classes");
        } finally {
            MockBukkit.unmock();
        }
        var loader = getClass().getClassLoader();
        assertNotNull(TeleportPacketFactory.detect(loader, Teleport.class.getName()));
        assertNull(TeleportPacketFactory.detect(loader, TwoParts.class.getName()));
    }
}
