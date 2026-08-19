package com.splitexpense.expense.exception;

/**
 * The caller is not a member of the named group, or it does not exist — group-service
 * answered {@code GET /api/v1/groups/{id}} with 404.
 *
 * <p>group-service deliberately does not distinguish "no such group" from "not your group";
 * see its {@code GroupController} javadoc. This service passes that ambiguity straight
 * through rather than trying to narrow it, for the identical reason: confirming a group's
 * existence to somebody outside it is already more than they should learn.
 *
 * <p>A perfectly healthy service giving a correct answer, exactly like the wallet platform's
 * {@code NoWalletException}: listed in {@code resilience4j.*}'s ignore-exceptions so it is
 * neither retried nor counted against the {@code groupService} breaker.
 */
public class NoSuchGroupException extends RuntimeException {

    public NoSuchGroupException(String message) {
        super(message);
    }
}
