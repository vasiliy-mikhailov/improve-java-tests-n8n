package tech.mikhailov.ijt.orchestrator.ws;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.mikhailov.ijt.State;

import java.nio.charset.StandardCharsets;

/// `GET /api/state` — the snapshot the stream resumes from.
///
/// It lives beside the WebSocket transport rather than with the rest of the API because that is
/// what it is FOR now: a subscription is useless until the client knows what it is subscribing
/// to, so a fresh page load reads this once, remembers its `seq`, and takes everything after
/// that off `/topic/events`. It is also what every diagnostic in this repo reads, and the two
/// uses want the same bytes.
///
/// The route survives the move from the Node sidecar to the Java backend to here, unchanged.
/// Field names ARE the contract — the n8n workflow read them, the dashboard reads them, and
/// `state.json` on disk has the same shape.
@RestController
public class StateController {

    /// The store, serialised by the store.
    ///
    /// It returns an ALREADY-SERIALISED body, and that is the whole design of this method.
    /// Returning the `Store` object would hand the wire format to Spring's ObjectMapper, which is
    /// configured by Boot and by `spring.jackson.*` — a naming strategy, a default inclusion or a
    /// date format set for some other endpoint would silently rename or drop keys here.
    /// `State.snapshotJson()` is the same call that writes state.json and the same one the old
    /// backend answered this route with (its `RawJson`), so the body is byte for byte what it has
    /// always been, and it is taken under the store's monitor rather than mid-mutation.
    ///
    /// Bytes rather than a String, for the reason `Server.json` records next to its
    /// Content-Length: the two differ the moment an event message carries a `→` or a
    /// `«redacted»`. A String body is written by StringHttpMessageConverter using the charset in
    /// the Content-Type, and `application/json` carries none — so the encoding would come from a
    /// framework default rather than from this decision. Encoding here, once, keeps the response
    /// identical to the one the Node sidecar and the Java backend served: UTF-8 bytes, an exact
    /// byte count, and `application/json` with no charset parameter bolted on.
    ///
    /// `no-store` because there is a reverse proxy in front of this in every deployment. A
    /// cached snapshot is not a stale page — it is a stale `seq`, and the client would subscribe
    /// from a position it never actually reached and silently drop every event in between.
    @GetMapping(path = "/api/state", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> state() {
        byte[] body = State.snapshotJson().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(body.length)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }
}
