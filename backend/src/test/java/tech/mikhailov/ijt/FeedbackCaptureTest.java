package tech.mikhailov.ijt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The pipeline's half of the record, asserted through the ROUTE it actually calls.
///
/// `Feedback` is storage and `FeedbackTest` proves the storage. Neither says a single record is
/// ever written by a run — and a store nothing writes to holds only whatever a human types, which
/// is not a corpus. This drives `POST /api/test/write-many`, the route `Phase` calls for every
/// generated test of every round, and asserts a record comes out the other side.
///
/// That distinction has cost this project eight components: correct, tested, called by nothing.
class FeedbackCaptureTest {

    private static final String REPO_URL = "https://example.invalid/o/r";
    private static final String UNIT = "src/main/java/org/json/XML.java::escape";

    private Path savedData;
    private State.Run savedRun;
    private Path repo;

    @BeforeEach
    void aRunWithOneUnit(@TempDir Path tmp) throws Exception {
        savedData = State.DATA_DIR;
        savedRun = State.STATE.run;
        State.DATA_DIR = tmp;
        State.STATE.run = State.freshRun(State.envConfig(k -> null), Map.of("repoUrl", REPO_URL));
        State.STATE.files = new LinkedHashMap<>();
        State.STATE.currentUnit = UNIT;

        repo = Repo.repoDir();
        Files.createDirectories(repo.resolve(".git").resolve("info"));
        Files.createDirectories(repo.resolve("src/main/java/org/json"));
        Files.writeString(repo.resolve("src/main/java/org/json/XML.java"), """
                package org.json;

                public class XML {
                    public static String escape(String s) {
                        return s;
                    }
                }
                """);

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("status", "candidate");
        f.put("key", UNIT);
        f.put("path", "src/main/java/org/json/XML.java");
        f.put("method", "escape");
        f.put("fqcn", "org.json.XML");
        f.put("methodLine", 5);
        f.put("attempts", 1);
        f.put("rounds", 2);
        State.STATE.files.put(UNIT, f);
    }

    @AfterEach
    void restore() {
        State.DATA_DIR = savedData;
        State.STATE.run = savedRun;
        State.STATE.currentUnit = null;
    }

    private static Object writeMany(List<Map<String, Object>> tests) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tests", tests);
        body.put("stage", "improving_mutation");
        return Server.routes().get("POST /api/test/write-many").handle(Server.Query.EMPTY, body);
    }

    private static Map<String, Object> aTest() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("path", "src/test/java/org/json/XMLEscapeMacMutTest.java");
        t.put("content", "package org.json;\nclass XMLEscapeMacMutTest { }\n");
        return t;
    }

    @Test
    void writingATestRecordsIt() throws Exception {
        writeMany(List.of(aTest()));

        List<Map<String, Object>> records = Feedback.join(repo);
        assertEquals(1, records.size(), "the run wrote a test and recorded nothing");
        Map<String, Object> r = records.get(0);
        assertEquals(UNIT, r.get("unit"));
        assertEquals(1, ((List<?>) r.get("tests")).size());
    }

    @Test
    void theRecordCarriesTheGENERATEDTestContent() throws Exception {
        writeMany(List.of(aTest()));

        @SuppressWarnings("unchecked")
        Map<String, Object> t = (Map<String, Object>) ((List<?>) Feedback.join(repo).get(0).get("tests")).get(0);
        assertTrue(String.valueOf(t.get("content")).contains("XMLEscapeMacMutTest"),
                "the output half of the training example");
    }

    @Test
    void theRecordCarriesTheSOURCEThatWasUnderTest() throws Exception {
        writeMany(List.of(aTest()));

        @SuppressWarnings("unchecked")
        Map<String, Object> code = (Map<String, Object>) Feedback.join(repo).get(0).get("code");
        assertEquals("org.json.XML", code.get("fqcn"));
        assertTrue(String.valueOf(code.get("methodSource")).contains("escape"),
                "the input half: what the model was shown. got: " + code.get("methodSource"));
    }

    @Test
    void theRecordCarriesTheRULEThatAskedForIt() throws Exception {
        // the GEPA candidate. Without it an outcome cannot be attributed to a prompt variant and
        // the whole corpus is unusable for what it was collected for.
        Feedback.feedback(repo, REPO_URL, null, "no mocks; prefer real objects", true, null);
        writeMany(List.of(aTest()));

        @SuppressWarnings("unchecked")
        List<String> rule = (List<String>) Feedback.join(repo).get(0).get("writeTestRule");
        assertTrue(rule.stream().anyMatch(s -> s.contains("no mocks")),
                "the rule in force must be stored with the test it produced. got: " + rule);
    }

    @Test
    void generationAndRepairShareOneRoundId() throws Exception {
        // two writes inside one round — Phase writes, the suite goes red, the repair rewrites.
        // One outcome judges both, so both must carry the same id or the join drops one.
        writeMany(List.of(aTest()));
        writeMany(List.of(aTest()));

        List<Map<String, Object>> records = Feedback.join(repo);
        assertEquals(2, records.size());
        assertEquals(records.get(0).get("roundId"), records.get(1).get("roundId"));
    }

    @Test
    void aDifferentRoundGetsADifferentId() throws Exception {
        writeMany(List.of(aTest()));
        State.upsertFile(UNIT, Map.of("rounds", 3L));
        writeMany(List.of(aTest()));

        List<Map<String, Object>> records = Feedback.join(repo);
        assertTrue(!records.get(0).get("roundId").equals(records.get(1).get("roundId")),
                "an outcome would otherwise close a round it did not judge");
    }

    @Test
    void aFailureToCaptureDoesNotFailTheWrite() throws Exception {
        // the repo directory is gone mid-run. The test still has to be written — capturing a
        // training example is not worth failing a round for.
        Path doomed = repo.resolve("src/test/java/org/json");
        Files.createDirectories(doomed);
        State.DATA_DIR = Path.of("/proc/nonexistent-and-unwritable");

        Object out = writeMany(List.of(aTest()));
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) out;
        assertEquals(Boolean.TRUE, res.get("ok"), "the round must survive a store that cannot be written");
    }
}
