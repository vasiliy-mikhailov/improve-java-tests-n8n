package com.example.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * Deliberately weak: it EXECUTES most of Validator (so line coverage looks fine) but
 * asserts nothing that distinguishes correct behaviour from a mutation. This is the
 * ground truth for "coverage overstates quality".
 */
class ValidatorTest {

    @Test
    void exercisesUsername() {
        Validator.isValidUsername("alice");
        Validator.isValidUsername("ab");
        Validator.isValidUsername("9lives");
        Validator.isValidUsername(null);
        Validator.isValidUsername("with space!");
    }

    @Test
    void exercisesEmail() {
        Validator.isValidEmail("a@b.com");
        Validator.isValidEmail("");
        Validator.isValidEmail("no-at-sign");
        Validator.isValidEmail("two@@at.com");
    }

    @Test
    void exercisesPhone() {
        assertNotNull(Validator.normalisePhone("+44 (0)20 7946 0000"));
        assertNotNull(Validator.normalisePhone(null));
    }

    @Test
    void exercisesClamp() {
        Validator.clampPercent(-5);
        Validator.clampPercent(150);
        Validator.clampPercent(50);
    }
}
