package net.tfminecraft.rpcharacters.paidchange;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.tlibs.character.CharacterRef;

/** Test access to the {@link Payments} seams from other packages. Restore with RuntimeTestState. */
public final class PaymentsFixture {
    private PaymentsFixture() {}

    public static void saves(BiPredicate<UUID, RPCharacter> persist) {
        Payments.persist = persist;
    }

    public static void heirs(BooleanSupplier ready, Function<UUID, Optional<CharacterRef>> heirs) {
        Payments.heirsReady = ready;
        Payments.heirs = heirs;
    }
}
