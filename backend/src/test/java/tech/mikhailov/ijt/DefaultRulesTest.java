package tech.mikhailov.ijt;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// The environment holds DEFAULTS for a repo's rules, and says so in the name.
///
/// `RULES_WRITE_TEST` read as though the deployment owned a repo's test-writing rules. It does
/// not. Those are prompts — "no mocks", "prefer real objects over stubs" — and prompts are
/// per-repo, edited by the team that owns the repo, and the thing a feedback loop rewrites. A
/// value baked into one deployment's .env can be none of those. `DEFAULT_` states what the
/// environment can honestly provide: the seed used when the repo has said nothing.
///
/// The old spelling is still honoured, and that is not tidiness. All six RULES_* keys are set in
/// the live deployment's .env right now. A rename that only read the new name would silently
/// empty every stage's rules, and nothing would report it — the run would simply stop following
/// instructions it had been following an hour earlier.
class DefaultRulesTest {

    private static State.Config config(Map<String, String> env) {
        return State.envConfig(env::get);
    }

    @Test
    void theNewSpellingIsRead() {
        State.Rules rules = config(Map.of("DEFAULT_RULES_WRITE_TEST", "no mocks")).rules();
        assertEquals("no mocks", rules.writeTest());
    }

    @Test
    void theOldSpellingStillWorks() {
        // set in production today; renaming must not empty it
        State.Rules rules = config(Map.of("RULES_WRITE_TEST", "no reflection")).rules();
        assertEquals("no reflection", rules.writeTest());
    }

    @Test
    void theNewSpellingWinsWhenBothAreSet() {
        State.Rules rules = config(Map.of(
                "DEFAULT_RULES_WRITE_TEST", "the new one",
                "RULES_WRITE_TEST", "the old one")).rules();
        assertEquals("the new one", rules.writeTest());
    }

    @Test
    void anEmptyNewSpellingFallsBackRatherThanBlanking() {
        // `DEFAULT_RULES_WRITE_TEST=` in a .env is indistinguishable from unset, and must not be
        // read as "the team deliberately wants no rules" while the old key still carries text
        State.Rules rules = config(Map.of(
                "DEFAULT_RULES_WRITE_TEST", "",
                "RULES_WRITE_TEST", "no reflection")).rules();
        assertEquals("no reflection", rules.writeTest());
    }

    @Test
    void everyStageIsCovered() {
        // all six, so a stage cannot be missed in the rename — that is exactly the kind of
        // omission that shows up as one stage quietly ignoring its rules
        State.Rules r = config(Map.of(
                "DEFAULT_RULES_POST_CLONE", "a",
                "DEFAULT_RULES_PRE_PICK", "b",
                "DEFAULT_RULES_PICK_FILE", "c",
                "DEFAULT_RULES_WRITE_TEST", "d",
                "DEFAULT_RULES_CHECK_CHANGES", "e",
                "DEFAULT_RULES_MAKE_PR", "f")).rules();
        assertEquals("a", r.postClone());
        assertEquals("b", r.prePick());
        assertEquals("c", r.pickFile());
        assertEquals("d", r.writeTest());
        assertEquals("e", r.checkChanges());
        assertEquals("f", r.makePr());
    }

    @Test
    void nothingSetIsEmpty() {
        assertEquals("", config(Map.of()).rules().writeTest());
    }
}
