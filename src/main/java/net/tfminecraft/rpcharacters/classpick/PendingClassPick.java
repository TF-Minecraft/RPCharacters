package net.tfminecraft.rpcharacters.classpick;

import net.tfminecraft.rpcharacters.paidchange.Payment;

/**
 * A paid class pick between its debit and the class being applied, saved with the character.
 * {@code refundRequired} is set once applying the class failed, so the payment is refunded even if
 * the character later reaches the same class some other way.
 */
public record PendingClassPick(String targetClass, Payment payment, boolean refundRequired) {}
