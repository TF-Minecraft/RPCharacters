package net.tfminecraft.rpcharacters.classpick;

import net.tfminecraft.rpcharacters.paidchange.Payment;

/** A paid class pick between its debit and the class being applied, saved with the character. */
public record PendingClassPick(String targetClass, Payment payment) {}
