package protonest.co.scancallnewbackend.controllers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import protonest.co.scancallnewbackend.config.ScancallProperties;
import protonest.co.scancallnewbackend.model.CallSession;
import protonest.co.scancallnewbackend.model.MissedCallEntry;
import protonest.co.scancallnewbackend.service.CallSessionService;
import protonest.co.scancallnewbackend.service.LivekitService;
import protonest.co.scancallnewbackend.service.PresenceService;
import protonest.co.scancallnewbackend.state.InMemoryStateStore;

@RestController
@RequestMapping
@CrossOrigin(origins = "*")
public class ApiController {

    private final PresenceService presence;
    private final CallSessionService callSession;
    private final InMemoryStateStore store;
    private final LivekitService livekit;
    private final ScancallProperties properties;

    public ApiController(
            PresenceService presence,
            CallSessionService callSession,
            InMemoryStateStore store,
            LivekitService livekit,
            ScancallProperties properties
    ) {
        this.presence = presence;
        this.callSession = callSession;
        this.store = store;
        this.livekit = livekit;
        this.properties = properties;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("livekitConfigured", livekit.isConfigured());
        response.put("pushConfigured", isFcmConfigured());
        response.put("now", Instant.now().toString());
        return response;
    }

    @GetMapping("/api/presence")
    public Map<String, Object> allPresence() {
        return Map.of("peers", store.getAllPresence());
    }

    @GetMapping("/api/users")
    public Map<String, Object> users() {
        return Map.of("users", presence.getUsersView());
    }

    @GetMapping("/api/calls/{sessionId}")
    public ResponseEntity<Map<String, Object>> getSession(@PathVariable String sessionId) {
        CallSession session = store.getSession(sessionId);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Unknown session"));
        }
        return ResponseEntity.ok(Map.of("session", callSession.serializeSession(session)));
    }

    @PostMapping("/api/calls/{sessionId}/token")
    public ResponseEntity<Map<String, Object>> mintToken(@PathVariable String sessionId, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        CallSession session = store.getSession(sessionId);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Unknown session"));
        }

        if (!livekit.isConfigured()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "LiveKit environment is not configured"));
        }

        if (!("connecting".equals(session.getState()) || "active".equals(session.getState()))) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "Session is not ready for media", "state", session.getState()));
        }

        String userId = String.valueOf(payload.getOrDefault("userId", ""));
        if (userId.isBlank() || (!userId.equals(session.getCallerId()) && !userId.equals(session.getCalleeId()))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "User is not part of this call"));
        }

        String displayName = String.valueOf(payload.getOrDefault("displayName", "")).trim();
        if (displayName.isBlank()) {
            if (store.getPresence(userId) != null && store.getPresence(userId).getDisplayName() != null) {
                displayName = store.getPresence(userId).getDisplayName();
            } else if (userId.equals(session.getCallerId())) {
                displayName = session.getCallerDisplayName();
            } else {
                displayName = session.getCalleeDisplayName();
            }
        }
        if (displayName == null || displayName.isBlank()) {
            displayName = userId;
        }

        String token = livekit.mintToken(userId, displayName, session.getRoomName(), session.getSessionId(), session.getMode());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("serverUrl", livekit.getServerUrl());
        response.put("roomName", session.getRoomName());
        response.put("token", token);
        response.put("session", callSession.serializeSession(session));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/missed-calls/{userId}")
    public Map<String, Object> missedCalls(@PathVariable String userId) {
        return Map.of("missedCalls", store.getMissedCalls(userId));
    }

    @PostMapping("/api/missed-calls/{userId}/read")
    public Map<String, Object> markRead(@PathVariable String userId, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        String callId = String.valueOf(payload.getOrDefault("callId", ""));
        List<MissedCallEntry> calls = store.getMissedCalls(userId);

        if ("all".equals(callId)) {
            calls.forEach(c -> c.setRead(true));
        } else {
            calls.stream().filter(c -> callId.equals(c.getId())).findFirst().ifPresent(c -> c.setRead(true));
        }

        return Map.of("status", "ok");
    }

    @PostMapping("/api/missed-calls/{userId}/clear")
    public Map<String, Object> clearMissedCalls(@PathVariable String userId) {
        store.clearMissedCalls(userId);
        return Map.of("status", "ok");
    }

    @PostMapping("/api/calls/{sessionId}/accept")
    public ResponseEntity<Map<String, Object>> accept(@PathVariable String sessionId, @RequestBody(required = false) Map<String, Object> body) {
        try {
            Map<String, Object> payload = new HashMap<>(body == null ? Map.of() : body);
            payload.put("sessionId", sessionId);
            callSession.accept(payload);
            CallSession session = store.getSession(sessionId);
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "session", session == null ? null : callSession.serializeSession(session)
            ));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage() == null ? "Accept failed" : ex.getMessage()));
        }
    }

    @PostMapping("/api/calls/{sessionId}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String sessionId, @RequestBody(required = false) Map<String, Object> body) {
        return callAction(sessionId, body, "cancel");
    }

    @PostMapping("/api/calls/{sessionId}/end")
    public ResponseEntity<Map<String, Object>> end(@PathVariable String sessionId, @RequestBody(required = false) Map<String, Object> body) {
        return callAction(sessionId, body, "end");
    }

    @PostMapping("/api/calls/{sessionId}/ringing")
    public ResponseEntity<Map<String, Object>> ringing(@PathVariable String sessionId, @RequestBody(required = false) Map<String, Object> body) {
        return callAction(sessionId, body, "ringing");
    }

    @PostMapping("/api/calls/{sessionId}/reject")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable String sessionId, @RequestBody(required = false) Map<String, Object> body) {
        return callAction(sessionId, body, "reject");
    }

    private ResponseEntity<Map<String, Object>> callAction(String sessionId, Map<String, Object> body, String action) {
        try {
            Map<String, Object> payload = new HashMap<>(body == null ? Map.of() : body);
            payload.put("sessionId", sessionId);
            switch (action) {
                case "cancel" -> callSession.cancel(payload);
                case "end" -> callSession.end(payload);
                case "ringing" -> callSession.ringing(payload);
                case "reject" -> callSession.reject(payload);
                default -> throw new IllegalArgumentException("Unsupported action");
            }
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (Exception ex) {
            String fallback = switch (action) {
                case "cancel" -> "Cancel failed";
                case "end" -> "End failed";
                case "ringing" -> "Ringing failed";
                case "reject" -> "Reject failed";
                default -> "Request failed";
            };
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage() == null ? fallback : ex.getMessage()));
        }
    }

    private boolean isFcmConfigured() {
        String serviceAccountPath = properties.getServiceAccountPath();
        return serviceAccountPath != null && !serviceAccountPath.isBlank() && Files.exists(Path.of(serviceAccountPath));
    }
}
