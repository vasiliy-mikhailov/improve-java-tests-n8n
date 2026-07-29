package tech.mikhailov.ijt.orchestrator.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// JSON columns, and the one mapper behind them.
///
/// Three shapes in this store have no faithful Java type and are deliberately kept as maps and
/// lists — the per-unit file record, the ledger entries, and `runner`. `State` says why (see
/// its class comment): every caller patches them with whatever it has just learned
/// (`startSha`, `consecutiveMisses`, `roundTestPaths`, `everReached`, …), and pinning that to
/// a record would either drop keys the dashboard renders or force a schema change on every new
/// field. A column per key would force a MIGRATION on every new field, which is worse.
///
/// The conversions below are deliberately null-preserving in both directions. `null` and `[]`
/// are different answers — `lastSurvived` is null when nothing has ever been measured and empty
/// when a measurement found no survivors, and `Select.exhausted` gives opposite verdicts for
/// the two. A converter that normalised one into the other would settle units that still had
/// work left in them.
/// Public only because Hibernate instantiates the converters below by reflection and a
/// package-private enclosing class is one of the ways that goes wrong at CONTEXT STARTUP
/// rather than at compile time.
public final class Json {

    private Json() {}

    /// Its own mapper rather than `State.MAPPER`, which is package-private to the backend.
    /// Configured the same way for the same reason: a value written by another version of this
    /// pipeline carries keys this build does not know, and unknown keys must survive a
    /// round-trip rather than fail the load.
    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Object>> LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    /// Serialise, or `null` for a null attribute. Insertion order is preserved on the way out
    /// and on the way back: the PR payload file's key order is the Node sidecar's insertion
    /// order and is part of its on-disk contract, and a measurement stamped by
    /// `Measure.stamp` serialises in the order it was written.
    private static String write(Object value) {
        if (value == null) return null;
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            // A store that silently drops what it cannot serialise is how a run's record stops
            // matching the run. Fail where the bad value entered, not three restarts later.
            throw new IllegalArgumentException("value is not serialisable for a JSON column: " + e.getMessage(), e);
        }
    }

    private static <T> T read(String json, TypeReference<T> type) {
        if (json == null || json.isEmpty()) return null;
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            // Reading is the asymmetric half: the row was written by SOME version of this
            // pipeline and one unreadable ledger entry must not cost the run every other entry
            // in the table — the same reasoning as State.load applying keys one at a time.
            System.err.println("store: unreadable JSON column, treated as absent: " + e.getMessage());
            return null;
        }
    }

    /// `Map<String, Object>` columns: unit extras, ledger payloads, config, runner, decisions.
    @Converter
    public static final class MapConverter implements AttributeConverter<Map<String, Object>, String> {
        @Override public String convertToDatabaseColumn(Map<String, Object> attribute) { return write(attribute); }

        @Override public Map<String, Object> convertToEntityAttribute(String dbData) {
            return read(dbData, MAP_TYPE);
        }
    }

    /// `List<Object>` columns: `lastSurvived` (mutant maps) and the LLM dialog.
    @Converter
    public static final class ListConverter implements AttributeConverter<List<Object>, String> {
        @Override public String convertToDatabaseColumn(List<Object> attribute) { return write(attribute); }

        @Override public List<Object> convertToEntityAttribute(String dbData) {
            return read(dbData, LIST_TYPE);
        }
    }

    /// `List<String>` columns: PR labels.
    @Converter
    public static final class StringListConverter implements AttributeConverter<List<String>, String> {
        @Override public String convertToDatabaseColumn(List<String> attribute) { return write(attribute); }

        @Override public List<String> convertToEntityAttribute(String dbData) {
            return read(dbData, STRING_LIST_TYPE);
        }
    }
}
