package protonest.co.scancallnewbackend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import protonest.co.scancallnewbackend.model.KnownUser;
import protonest.co.scancallnewbackend.model.PeerPresence;
import protonest.co.scancallnewbackend.state.InMemoryStateStore;
import protonest.co.scancallnewbackend.util.MessageFactory;

@Service
public class PresenceService {

    private final InMemoryStateStore store;
    private final ObjectMapper objectMapper;

    public PresenceService(InMemoryStateStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> register(WebSocketSession ws, Map<String, Object> rawMessage) {
        String userId = String.valueOf(rawMessage.getOrDefault("userId", "")).trim();
        String displayName = String.valueOf(rawMessage.getOrDefault("displayName", "")).trim();
        String platform = MessageFactory.normalizePlatform(String.valueOf(rawMessage.getOrDefault("platform", "")));
        String appState = MessageFactory.normalizeAppState(String.valueOf(rawMessage.getOrDefault("appState", "")));

        if (userId.isBlank()) {
            throw new IllegalArgumentException("presence.register requires userId");
        }

        PeerPresence peer = new PeerPresence();
        peer.setUserId(userId);
        peer.setDisplayName(displayName.isBlank() ? userId : displayName);
        peer.setPlatform(platform);
        peer.setAppState(appState);
        peer.setStatus("online");
        peer.setLastSeen(Instant.now().toString());

        boolean hadActiveSocket = store.hasConnectedSockets(userId);
        store.addSocket(userId, ws);
        store.setPresence(userId, peer);
        store.upsertKnownUser(userId, peer.getDisplayName(), platform, peer.getLastSeen());

        sendToUser(userId, MessageFactory.build("presence.snapshot", Map.of("peers", store.getAllPresence())));
        if (!hadActiveSocket) {
            broadcast(MessageFactory.build("presence.joined", Map.of("peer", peer)), userId);
        }

        return Map.of("userId", userId, "hadActiveSocket", hadActiveSocket);
    }

    public void update(WebSocketSession ws, Map<String, Object> rawMessage) {
        String registeredUserId = store.getSocketUser(ws);
        String requestedUserId = String.valueOf(rawMessage.getOrDefault("userId", ""));
        String userId = registeredUserId != null ? registeredUserId : requestedUserId;
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("presence.update requires userId");
        }
        if (!store.isRegisteredSocket(userId, ws)) {
            return;
        }
        PeerPresence peer = store.getPresence(userId);
        if (peer == null) {
            return;
        }
        peer.setAppState(MessageFactory.normalizeAppState(String.valueOf(rawMessage.getOrDefault("appState", ""))));
        peer.setLastSeen(Instant.now().toString());
        store.upsertKnownUser(userId, peer.getDisplayName(), peer.getPlatform(), peer.getLastSeen());
    }

    public void remove(String userId) {
        PeerPresence peer = store.getPresence(userId);
        if (peer == null) {
            return;
        }

        store.removePresence(userId);
        store.upsertKnownUser(userId, peer.getDisplayName(), peer.getPlatform(), Instant.now().toString());
        broadcast(MessageFactory.build("presence.left", Map.of("peer", peer)), userId);
    }

    public void sendToUser(String userId, Map<String, Object> message) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        for (WebSocketSession socket : store.getUserSockets(userId)) {
            sendMessage(socket, message);
        }
    }

    public void sendMessage(WebSocketSession ws, Map<String, Object> message) {
        if (ws == null || !ws.isOpen()) {
            return;
        }
        try {
            ws.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        } catch (IOException ignored) {
        }
    }

    public void broadcast(Map<String, Object> message, String excludeUserId) {
        for (PeerPresence peer : store.getAllPresence()) {
            if (Objects.equals(peer.getUserId(), excludeUserId)) {
                continue;
            }
            sendToUser(peer.getUserId(), message);
        }
    }

    public Map<String, Object> serializeKnownUser(String userId) {
        KnownUser knownUser = store.getKnownUser(userId);
        PeerPresence onlinePeer = store.getPresence(userId);
        boolean hasPushToken = store.hasPushToken(userId);
        boolean online = onlinePeer != null;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("displayName", online ? onlinePeer.getDisplayName() : knownUser != null ? knownUser.getDisplayName() : userId);
        payload.put("platform", online ? onlinePeer.getPlatform() : knownUser != null ? knownUser.getPlatform() : "unknown");
        payload.put("status", online ? "online" : "offline");
        payload.put("online", online);
        payload.put("hasPushToken", hasPushToken);
        payload.put("callable", online || hasPushToken);
        payload.put("lastSeen", online ? onlinePeer.getLastSeen() : knownUser != null ? knownUser.getLastSeen() : null);
        return payload;
    }

    public List<Map<String, Object>> getUsersView() {
        return store.getAllKnownUsers().stream()
                .map(KnownUser::getUserId)
                .map(this::serializeKnownUser)
                .sorted(Comparator
                        .comparing((Map<String, Object> m) -> !Boolean.TRUE.equals(m.get("online")))
                        .thenComparing(m -> String.valueOf(m.get("displayName"))))
                .toList();
    }
}
