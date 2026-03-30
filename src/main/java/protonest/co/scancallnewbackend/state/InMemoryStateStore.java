package protonest.co.scancallnewbackend.state;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import protonest.co.scancallnewbackend.config.ScancallProperties;
import protonest.co.scancallnewbackend.model.CallSession;
import protonest.co.scancallnewbackend.model.KnownUser;
import protonest.co.scancallnewbackend.model.MissedCallEntry;
import protonest.co.scancallnewbackend.model.PeerPresence;

@Component
public class InMemoryStateStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryStateStore.class);

    private final ScancallProperties properties;
    private final ObjectMapper objectMapper;

    private final Map<String, Set<WebSocketSession>> clients = new ConcurrentHashMap<>();
    private final Map<String, String> socketUsers = new ConcurrentHashMap<>();

    private final Map<String, PeerPresence> presence = new ConcurrentHashMap<>();
    private final Map<String, KnownUser> knownUsers = new ConcurrentHashMap<>();

    private final Map<String, String> androidPushTokens = new ConcurrentHashMap<>();
    private final Map<String, String> iosVoipTokens = new ConcurrentHashMap<>();

    private final Map<String, CallSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> userSessions = new ConcurrentHashMap<>();

    private final Map<String, ScheduledFuture<?>> sessionTimeouts = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> connectingSessionTimeouts = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> disconnectGraceTimers = new ConcurrentHashMap<>();

    private final Map<String, List<MissedCallEntry>> missedCalls = new ConcurrentHashMap<>();

    public InMemoryStateStore(ScancallProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        loadPushTokens();
    }

    public void addSocket(String userId, WebSocketSession session) {
        clients.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet()).add(session);
        socketUsers.put(session.getId(), userId);
    }

    public String removeSocket(WebSocketSession session) {
        String userId = socketUsers.remove(session.getId());
        if (userId == null) {
            return null;
        }
        Set<WebSocketSession> sockets = clients.get(userId);
        if (sockets != null) {
            sockets.remove(session);
            if (sockets.isEmpty()) {
                clients.remove(userId);
            }
        }
        return userId;
    }

    public Set<WebSocketSession> getUserSockets(String userId) {
        return clients.getOrDefault(userId, Set.of());
    }

    public String getSocketUser(WebSocketSession session) {
        return socketUsers.get(session.getId());
    }

    public boolean hasConnectedSockets(String userId) {
        return Optional.ofNullable(clients.get(userId)).map(s -> !s.isEmpty()).orElse(false);
    }

    public boolean isRegisteredSocket(String userId, WebSocketSession session) {
        Set<WebSocketSession> sockets = clients.get(userId);
        return sockets != null && sockets.contains(session);
    }

    public void setPresence(String userId, PeerPresence peer) {
        presence.put(userId, peer);
    }

    public PeerPresence getPresence(String userId) {
        return presence.get(userId);
    }

    public void removePresence(String userId) {
        presence.remove(userId);
    }

    public List<PeerPresence> getAllPresence() {
        return new ArrayList<>(presence.values());
    }

    public void upsertKnownUser(String userId, String displayName, String platform, String lastSeen) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        KnownUser existing = knownUsers.get(userId);
        KnownUser user = new KnownUser();
        user.setUserId(userId);
        user.setDisplayName(displayName == null || displayName.isBlank()
                ? existing != null && existing.getDisplayName() != null ? existing.getDisplayName() : userId
                : displayName);
        user.setPlatform(platform == null || platform.isBlank() || "unknown".equals(platform)
                ? existing != null && existing.getPlatform() != null ? existing.getPlatform() : "unknown"
                : platform);
        user.setLastSeen(lastSeen != null ? lastSeen : existing != null ? existing.getLastSeen() : Instant.now().toString());
        knownUsers.put(userId, user);
    }

    public KnownUser getKnownUser(String userId) {
        return knownUsers.get(userId);
    }

    public List<KnownUser> getAllKnownUsers() {
        return new ArrayList<>(knownUsers.values());
    }

    public void setPushToken(String userId, String token, String platform) {
        if ("ios".equals(platform)) {
            iosVoipTokens.put(userId, token);
        } else {
            androidPushTokens.put(userId, token);
        }
    }

    public String getAndroidToken(String userId) {
        return androidPushTokens.get(userId);
    }

    public String getIosToken(String userId) {
        return iosVoipTokens.get(userId);
    }

    public boolean hasPushToken(String userId) {
        return androidPushTokens.containsKey(userId) || iosVoipTokens.containsKey(userId);
    }

    public synchronized void persistPushTokens() {
        String tokenStorePath = properties.getPushTokenStorePath();
        if (tokenStorePath == null || tokenStorePath.isBlank()) {
            return;
        }

        Path path = Path.of(tokenStorePath);
        Map<String, Object> payload = new HashMap<>();
        payload.put("android", new HashMap<>(androidPushTokens));
        payload.put("ios", new HashMap<>(iosVoipTokens));

        try {
            Files.createDirectories(path.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), payload);
        } catch (IOException ex) {
            log.error("Failed to persist push token store", ex);
        }
    }

    @SuppressWarnings("unchecked")
    public synchronized void loadPushTokens() {
        String tokenStorePath = properties.getPushTokenStorePath();
        if (tokenStorePath == null || tokenStorePath.isBlank()) {
            return;
        }

        Path path = Path.of(tokenStorePath);
        if (!Files.exists(path)) {
            return;
        }

        try {
            Map<String, Object> payload = objectMapper.readValue(path.toFile(), new TypeReference<>() {
            });
            Map<String, String> android = (Map<String, String>) payload.getOrDefault("android", Map.of());
            Map<String, String> ios = (Map<String, String>) payload.getOrDefault("ios", Map.of());

            android.forEach((k, v) -> {
                if (k != null && v != null) {
                    androidPushTokens.put(k, String.valueOf(v));
                }
            });
            ios.forEach((k, v) -> {
                if (k != null && v != null) {
                    iosVoipTokens.put(k, String.valueOf(v));
                }
            });
        } catch (Exception ex) {
            log.error("Failed to load push token store", ex);
        }
    }

    public void setSession(CallSession session) {
        sessions.put(session.getSessionId(), session);
    }

    public CallSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public void deleteSession(String sessionId) {
        sessions.remove(sessionId);
    }

    public void setUserSession(String userId, String sessionId) {
        userSessions.put(userId, sessionId);
    }

    public String getUserSession(String userId) {
        return userSessions.get(userId);
    }

    public void deleteUserSession(String userId) {
        userSessions.remove(userId);
    }

    public boolean isUserBusy(String userId) {
        return userSessions.containsKey(userId);
    }

    public void setSessionTimeout(String sessionId, ScheduledFuture<?> future) {
        clearSessionTimeout(sessionId);
        sessionTimeouts.put(sessionId, future);
    }

    public void clearSessionTimeout(String sessionId) {
        ScheduledFuture<?> future = sessionTimeouts.remove(sessionId);
        if (future != null) {
            future.cancel(false);
        }
    }

    public void setConnectingTimeout(String sessionId, ScheduledFuture<?> future) {
        clearConnectingTimeout(sessionId);
        connectingSessionTimeouts.put(sessionId, future);
    }

    public void clearConnectingTimeout(String sessionId) {
        ScheduledFuture<?> future = connectingSessionTimeouts.remove(sessionId);
        if (future != null) {
            future.cancel(false);
        }
    }

    public void setGraceTimer(String sessionId, ScheduledFuture<?> future) {
        clearGraceTimer(sessionId);
        disconnectGraceTimers.put(sessionId, future);
    }

    public void clearGraceTimer(String sessionId) {
        ScheduledFuture<?> future = disconnectGraceTimers.remove(sessionId);
        if (future != null) {
            future.cancel(false);
        }
    }

    public ScheduledFuture<?> getGraceTimer(String sessionId) {
        return disconnectGraceTimers.get(sessionId);
    }

    public void addMissedCall(String calleeId, MissedCallEntry entry) {
        List<MissedCallEntry> list = missedCalls.computeIfAbsent(calleeId, key -> new ArrayList<>());
        synchronized (list) {
            list.add(0, entry);
            if (list.size() > properties.getMaxMissedCallsPerUser()) {
                list.subList(properties.getMaxMissedCallsPerUser(), list.size()).clear();
            }
        }
    }

    public List<MissedCallEntry> getMissedCalls(String userId) {
        return missedCalls.getOrDefault(userId, new ArrayList<>());
    }

    public void clearMissedCalls(String userId) {
        missedCalls.remove(userId);
    }

    public Set<String> getConnectedUserIds() {
        return new HashSet<>(clients.keySet());
    }
}
