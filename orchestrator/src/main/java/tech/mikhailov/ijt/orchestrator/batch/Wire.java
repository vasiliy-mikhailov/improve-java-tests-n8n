package tech.mikhailov.ijt.orchestrator.batch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// How a route's answer is READ — which is half of every branch below.
///
/// The n8n IF nodes tested JavaScript expressions: `$json.skip ? 0 : 1`, `$json.passed ? 1 : 0`,
/// `$json.result && $json.result.file`. Their subject is JSON that came off an HTTP body, so the
/// test is JS truthiness: absent is false, `0` is false, `""` is false, and a non-empty string is
/// true whatever it spells. Reading the same fields as Java booleans would change three branches
/// on the spot — `skip` is absent rather than `false` when a prompt IS to be made, and
/// `Boolean.TRUE.equals(null)` and `!truthy(null)` do not agree about `Wrote Any?`.
///
/// {@link tech.mikhailov.ijt.State} has this function and it is unit-tested there, but it is
/// package-private to the domain layer. This is a deliberate copy of six lines rather than a
/// widening of the backend's API for the benefit of a caller; if the two ever need to move
/// together, widen `State.jsTruthy` and delete this.
final class Wire {

    private Wire() {}

    /// JS truthiness over a value that arrived as JSON.
    static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0 && !Double.isNaN(n.doubleValue());
        if (v instanceof String s) return !s.isEmpty();
        return true;
    }

    /// A nested object, or null when the key is absent or holds something else.
    ///
    /// `$json.result && $json.result.approved` never threw in n8n because JS shortcuts; the null
    /// this returns is what the `&&` becomes here.
    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    /// The same, but readable without a null check. Reads only — never handed back to a route.
    static Map<String, Object> orEmpty(Map<String, Object> m) {
        return m == null ? Map.of() : m;
    }

    /// `String(v)`, with null staying null: a route body carrying `"null"` is not the same as one
    /// carrying no value, and `/api/test/run` tests its `stage` for truthiness.
    static String text(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /// `$('Parse Tests').first().json.count` for the `larger than 0` comparison. A missing count
    /// compares as NaN in n8n — false — and as 0 here, which takes the same branch.
    static long count(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    /// `(x.paths || [])`, for the two lists `Delete Broken Tests` concatenates.
    static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                if (o != null) out.add(String.valueOf(o));
            }
        }
        return out;
    }
}
