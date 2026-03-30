package protonest.co.scancallnewbackend.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import protonest.co.scancallnewbackend.config.ScancallProperties;
import protonest.co.scancallnewbackend.model.CallSession;
import protonest.co.scancallnewbackend.model.MissedCallEntry;
import protonest.co.scancallnewbackend.state.InMemoryStateStore;
import protonest.co.scancallnewbackend.util.MessageFactory;

@Service
public class CallSessionService {

    private final InMemoryStateStore store;
    private final PresenceService presence;
    private final PushService push;
    private final TaskScheduler scheduler;
    private final ScancallProperties properties;

    public CallSessionService(
            InMemoryStateStore store,
            PresenceService presence,
            PushService push,
            TaskScheduler scheduler,
            ScancallProperties properties
    ) {
        this.store = store;
        this.presence = presence;
        this.push = push;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    public Map<String, Object> serializeSession(CallSession session) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", session.getSessionId());
        payload.put("roomName", session.getRoomName());
        payload.put("callerId", session.getCallerId());
        payload.put("calleeId", session.getCalleeId());
        payload.put("callerDisplayName", session.getCallerDisplayName());
        payload.put("calleeDisplayName", session.getCalleeDisplayName());
        payload.put("state", session.getState());
        payload.put("mode", session.getMode());
        payload.put("timeoutAt", session.getTimeoutAt());
        payload.put("createdAt", session.getCreatedAt());
        payload.put("updatedAt", session.getUpdatedAt());
        return payload;
    }

    public synchronized void invite(Map<String, Object> rawMessage) {
        String callerId = String.valueOf(rawMessage.getOrDefault("fromUserId", "")).trim();
        String calleeId = String.valueOf(rawMessage.getOrDefault("toUserId", "")).trim();
        String sessionId = String.valueOf(rawMessage.getOrDefault("sessionId", UUID.randomUUID().toString()));
        String roomName = String.valueOf(rawMessage.getOrDefault("roomName", "room-" + UUID.randomUUID()));
        String mode = MessageFactory.normalizeMode(String.valueOf(rawMessage.getOrDefault("mode", "audio")));

        String displayFromMessage = String.valueOf(rawMessage.getOrDefault("displayName", "")).trim();
        String callerDisplayName = !displayFromMessage.isBlank()
                ? displayFromMessage
                : store.getPresence(callerId) != null ? store.getPresence(callerId).getDisplayName() : callerId;

        if (callerId.isBlank() || calleeId.isBlank()) {
            throw new IllegalArgumentException("call.invite requires fromUserId and toUserId");
        }
        if (callerId.equals(calleeId)) {
            throw new IllegalArgumentException("Cannot call the same user");
        }

        CallSession existing = store.getSession(sessionId);
        if (existing != null) {
            if (existing.getCallerId().equals(callerId) && existing.getCalleeId().equals(calleeId)) {
                return;
            }
            throw new IllegalArgumentException("Session already exists");
        }

        if (store.isUserBusy(callerId) || store.isUserBusy(calleeId)) {
            presence.sendToUser(callerId, MessageFactory.build("call.busy", Map.of(
                    "sessionId", sessionId,
                    "fromUserId", callerId,
                    "toUserId", calleeId,
                    "displayName", callerDisplayName,
                    "mode", mode,
                    "reason", "BUSY"
            )));
            return;
        }

        CallSession session = new CallSession();
        session.setSessionId(sessionId);
        session.setRoomName(roomName);
        session.setCallerId(callerId);
        session.setCalleeId(calleeId);
        session.setCallerDisplayName(callerDisplayName);
        session.setCalleeDisplayName(store.getPresence(calleeId) != null ? store.getPresence(calleeId).getDisplayName() : calleeId);
        session.setState("calling");
        session.setMode(mode);
        session.setCreatedAt(Instant.now().toString());
        session.setUpdatedAt(Instant.now().toString());
        session.setTimeoutAt(Instant.now().plusMillis(properties.getCallRingTimeoutMs()).toString());

        store.setSession(session);
        store.setUserSession(callerId, sessionId);
        store.setUserSession(calleeId, sessionId);
        scheduleRingTimeout(sessionId);

        Map<String, Object> base = serializeSession(session);
        presence.sendToUser(calleeId, MessageFactory.build("call.incoming", merge(base, Map.of(
                "fromUserId", callerId,
                "toUserId", calleeId,
                "displayName", callerDisplayName
        ))));

        presence.sendToUser(callerId, MessageFactory.build("call.inviting", merge(base, Map.of(
                "fromUserId", callerId,
                "toUserId", calleeId,
                "displayName", callerDisplayName
        ))));

        push.sendPushInvite(session);
    }

    public synchronized void ringing(Map<String, Object> rawMessage) {
        CallSession session = requireSession(String.valueOf(rawMessage.getOrDefault("sessionId", "")));
        String userId = String.valueOf(rawMessage.getOrDefault("fromUserId", ""));

        if (!"calling".equals(session.getState()) && !"ringing".equals(session.getState())) {
            return;
        }
        if (!userId.equals(session.getCalleeId())) {
            throw new IllegalArgumentException("Only the callee can indicate ringing");
        }

        session.setState("ringing");
        session.touch();

        presence.sendToUser(session.getCallerId(), MessageFactory.build("call.ringing", merge(serializeSession(session), Map.of(
                "fromUserId", session.getCalleeId(),
                "toUserId", session.getCallerId(),
                "displayName", session.getCalleeDisplayName()
        ))));
    }

    public synchronized void accept(Map<String, Object> rawMessage) {
        CallSession session = requireSession(String.valueOf(rawMessage.getOrDefault("sessionId", "")));
        String userId = String.valueOf(rawMessage.getOrDefault("fromUserId", ""));

        boolean answered = "connecting".equals(session.getState()) || "active".equals(session.getState());
        if ((answered || "calling".equals(session.getState()) || "ringing".equals(session.getState()))
                && !userId.equals(session.getCalleeId())) {
            throw new IllegalArgumentException("Only the callee can accept the call");
        }

        if (answered) {
            return;
        }
        if (!"calling".equals(session.getState()) && !"ringing".equals(session.getState())) {
            return;
        }

        store.clearSessionTimeout(session.getSessionId());
        session.setState("connecting");
        session.touch();
        session.getConnectedParticipants().clear();
        scheduleConnectingTimeout(session.getSessionId());

        Map<String, Object> event = MessageFactory.build("call.accepted", merge(serializeSession(session), Map.of(
                "fromUserId", session.getCalleeId(),
                "toUserId", session.getCallerId(),
                "displayName", session.getCalleeDisplayName()
        )));

        presence.sendToUser(session.getCallerId(), event);
        presence.sendToUser(session.getCalleeId(), event);
    }

    public synchronized void reject(Map<String, Object> rawMessage) {
        CallSession session = requireSession(String.valueOf(rawMessage.getOrDefault("sessionId", "")));
        String userId = String.valueOf(rawMessage.getOrDefault("fromUserId", ""));

        if (!"calling".equals(session.getState()) && !"ringing".equals(session.getState())) {
            return;
        }
        if (!userId.equals(session.getCalleeId())) {
            throw new IllegalArgumentException("Only the callee can reject the call");
        }

        finishSession(session, "rejected", MessageFactory.build("call.rejected", merge(serializeSession(session), Map.of(
                "fromUserId", session.getCalleeId(),
                "toUserId", session.getCallerId(),
                "displayName", session.getCalleeDisplayName(),
                "reason", "REJECTED"
        ))));
    }

    public synchronized void cancel(Map<String, Object> rawMessage) {
        CallSession session = requireSession(String.valueOf(rawMessage.getOrDefault("sessionId", "")));
        String userId = String.valueOf(rawMessage.getOrDefault("fromUserId", ""));

        if (!List.of("calling", "ringing", "connecting").contains(session.getState())) {
            return;
        }
        if (!userId.equals(session.getCallerId())) {
            throw new IllegalArgumentException("Only the caller can cancel the call");
        }

        recordMissedCall(session, "CANCELLED");
        finishSession(session, "cancelled", MessageFactory.build("call.cancelled", merge(serializeSession(session), Map.of(
                "fromUserId", session.getCallerId(),
                "toUserId", session.getCalleeId(),
                "displayName", session.getCallerDisplayName(),
                "reason", "CANCELLED"
        ))));
    }

    public synchronized void end(Map<String, Object> rawMessage) {
        String sessionId = String.valueOf(rawMessage.getOrDefault("sessionId", ""));
        CallSession session = store.getSession(sessionId);
        if (session == null) {
            return;
        }

        String userId = String.valueOf(rawMessage.getOrDefault("fromUserId", ""));
        if (!userId.equals(session.getCallerId()) && !userId.equals(session.getCalleeId())) {
            throw new IllegalArgumentException("Only participants can end the call");
        }
        if (!"connecting".equals(session.getState()) && !"active".equals(session.getState())) {
            return;
        }

        finishSession(session, "ended", MessageFactory.build("call.ended", merge(serializeSession(session), Map.of(
                "fromUserId", userId,
                "toUserId", userId.equals(session.getCallerId()) ? session.getCalleeId() : session.getCallerId(),
                "displayName", userId.equals(session.getCallerId()) ? session.getCallerDisplayName() : session.getCalleeDisplayName(),
                "reason", "HANGUP"
        ))));
    }

    public synchronized void connectionStatus(Map<String, Object> rawMessage) {
        String sessionId = String.valueOf(rawMessage.getOrDefault("sessionId", ""));
        CallSession session = store.getSession(sessionId);
        String userId = String.valueOf(rawMessage.getOrDefault("fromUserId", ""));
        String status = String.valueOf(rawMessage.getOrDefault("status", ""));
        if (session == null || status.isBlank()) {
            return;
        }
        if (!userId.equals(session.getCallerId()) && !userId.equals(session.getCalleeId())) {
            return;
        }

        if ("connected".equals(status) || "reconnected".equals(status)) {
            store.clearGraceTimer(session.getSessionId());
            session.getConnectedParticipants().add(userId);
            if ("connecting".equals(session.getState()) && session.getConnectedParticipants().size() >= 2) {
                store.clearConnectingTimeout(session.getSessionId());
                session.setState("active");
            }
            session.touch();
        } else if ("disconnected".equals(status)) {
            session.getConnectedParticipants().remove(userId);
            session.touch();
            if ("connecting".equals(session.getState()) || "active".equals(session.getState())) {
                scheduleMediaDisconnectGrace(session, userId);
            }
        }

        String otherUserId = userId.equals(session.getCallerId()) ? session.getCalleeId() : session.getCallerId();
        String displayName = userId.equals(session.getCallerId()) ? session.getCallerDisplayName() : session.getCalleeDisplayName();

        presence.sendToUser(otherUserId, MessageFactory.build("call.connection.status", merge(serializeSession(session), Map.of(
                "fromUserId", userId,
                "toUserId", otherUserId,
                "displayName", displayName,
                "status", status
        ))));
    }

    public synchronized void switchRequest(Map<String, Object> rawMessage) {
        CallSession session = requireSession(String.valueOf(rawMessage.getOrDefault("sessionId", "")));
        String userId = String.valueOf(rawMessage.getOrDefault("fromUserId", ""));
        String requestedMode = MessageFactory.normalizeMode(String.valueOf(rawMessage.getOrDefault("mode", "audio")));

        if (!"active".equals(session.getState())) {
            return;
        }
        ensureParticipant(session, userId);

        String targetUserId = userId.equals(session.getCallerId()) ? session.getCalleeId() : session.getCallerId();
        String displayName = userId.equals(session.getCallerId()) ? session.getCallerDisplayName() : session.getCalleeDisplayName();

        presence.sendToUser(targetUserId, MessageFactory.build("call.switch.requested", merge(serializeSession(session), Map.of(
                "fromUserId", userId,
                "toUserId", targetUserId,
                "displayName", displayName,
                "mode", requestedMode
        ))));
    }

    public synchronized void switchAccept(Map<String, Object> rawMessage) {
        CallSession session = requireSession(String.valueOf(rawMessage.getOrDefault("sessionId", "")));
        String userId = String.valueOf(rawMessage.getOrDefault("fromUserId", ""));
        String acceptedMode = MessageFactory.normalizeMode(String.valueOf(rawMessage.getOrDefault("mode", "audio")));

        if (!"active".equals(session.getState())) {
            return;
        }
        ensureParticipant(session, userId);

        session.setMode(acceptedMode);
        session.touch();

        String otherUserId = userId.equals(session.getCallerId()) ? session.getCalleeId() : session.getCallerId();
        String displayName = userId.equals(session.getCallerId()) ? session.getCallerDisplayName() : session.getCalleeDisplayName();

        Map<String, Object> event = MessageFactory.build("call.switch.accepted", merge(serializeSession(session), Map.of(
                "fromUserId", userId,
                "toUserId", otherUserId,
                "displayName", displayName,
                "mode", acceptedMode
        )));

        presence.sendToUser(session.getCallerId(), event);
        presence.sendToUser(session.getCalleeId(), event);
    }

    public synchronized void switchReject(Map<String, Object> rawMessage) {
        CallSession session = requireSession(String.valueOf(rawMessage.getOrDefault("sessionId", "")));
        String userId = String.valueOf(rawMessage.getOrDefault("fromUserId", ""));
        String rejectedMode = MessageFactory.normalizeMode(String.valueOf(rawMessage.getOrDefault("mode", "audio")));

        if (!"active".equals(session.getState())) {
            return;
        }
        ensureParticipant(session, userId);

        String otherUserId = userId.equals(session.getCallerId()) ? session.getCalleeId() : session.getCallerId();
        String displayName = userId.equals(session.getCallerId()) ? session.getCallerDisplayName() : session.getCalleeDisplayName();

        presence.sendToUser(otherUserId, MessageFactory.build("call.switch.rejected", merge(serializeSession(session), Map.of(
                "fromUserId", userId,
                "toUserId", otherUserId,
                "displayName", displayName,
                "mode", rejectedMode
        ))));
    }

    public synchronized void handleDisconnect(String userId) {
        String sessionId = store.getUserSession(userId);
        if (sessionId == null) {
            return;
        }

        CallSession session = store.getSession(sessionId);
        if (session == null) {
            store.deleteUserSession(userId);
            return;
        }

        if ("calling".equals(session.getState()) || "ringing".equals(session.getState())) {
            if (userId.equals(session.getCallerId())) {
                recordMissedCall(session, "CALLER_DISCONNECTED");
                finishSession(session, "cancelled", MessageFactory.build("call.cancelled", merge(serializeSession(session), Map.of(
                        "fromUserId", userId,
                        "toUserId", session.getCalleeId(),
                        "displayName", session.getCallerDisplayName(),
                        "reason", "CALLER_DISCONNECTED"
                ))));
                return;
            }

            if (!store.hasPushToken(userId)) {
                recordMissedCall(session, "CALLEE_DISCONNECTED");
                finishSession(session, "cancelled", MessageFactory.build("call.cancelled", merge(serializeSession(session), Map.of(
                        "fromUserId", userId,
                        "toUserId", session.getCallerId(),
                        "displayName", session.getCalleeDisplayName(),
                        "reason", "CALLEE_DISCONNECTED"
                ))));
                return;
            }

            store.setGraceTimer(session.getSessionId(), scheduler.schedule(() -> {
                store.clearGraceTimer(session.getSessionId());
                CallSession current = store.getSession(session.getSessionId());
                if (current == null) {
                    return;
                }
                if (!"calling".equals(current.getState()) && !"ringing".equals(current.getState())) {
                    return;
                }
                recordMissedCall(current, "CALLEE_DISCONNECTED");
                finishSession(current, "cancelled", MessageFactory.build("call.cancelled", merge(serializeSession(current), Map.of(
                        "fromUserId", userId,
                        "toUserId", current.getCallerId(),
                        "displayName", current.getCalleeDisplayName(),
                        "reason", "CALLEE_DISCONNECTED"
                ))));
            }, Instant.now().plusMillis(properties.getCallRingTimeoutMs())));

            return;
        }

        if ("connecting".equals(session.getState()) || "active".equals(session.getState())) {
            String peerId = userId.equals(session.getCallerId()) ? session.getCalleeId() : session.getCallerId();
            presence.sendToUser(peerId, MessageFactory.build("call.connection.status", Map.of(
                    "sessionId", session.getSessionId(),
                    "fromUserId", userId,
                    "toUserId", peerId,
                    "status", "disconnected"
            )));

            store.setGraceTimer(session.getSessionId(), scheduler.schedule(() -> {
                store.clearGraceTimer(session.getSessionId());
                CallSession current = store.getSession(session.getSessionId());
                if (current == null) {
                    return;
                }
                if (!("connecting".equals(current.getState()) || "active".equals(current.getState()))) {
                    return;
                }
                finishSession(current, "ended", MessageFactory.build("call.ended", merge(serializeSession(current), Map.of(
                        "fromUserId", userId,
                        "toUserId", peerId,
                        "displayName", userId.equals(current.getCallerId()) ? current.getCallerDisplayName() : current.getCalleeDisplayName(),
                        "reason", "DISCONNECTED"
                ))));
            }, Instant.now().plusMillis(properties.getActiveCallDisconnectGraceMs())));
        }
    }

    private void ensureParticipant(CallSession session, String userId) {
        if (!userId.equals(session.getCallerId()) && !userId.equals(session.getCalleeId())) {
            throw new IllegalArgumentException("Only participants can change call mode");
        }
    }

    private CallSession requireSession(String sessionId) {
        CallSession session = store.getSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Unknown session");
        }
        return session;
    }

    private void scheduleRingTimeout(String sessionId) {
        store.setSessionTimeout(sessionId, scheduler.schedule(() -> {
            CallSession session = store.getSession(sessionId);
            if (session == null || !("calling".equals(session.getState()) || "ringing".equals(session.getState()))) {
                return;
            }
            recordMissedCall(session, "TIMEOUT");
            finishSession(session, "timeout", MessageFactory.build("call.timeout", merge(serializeSession(session), Map.of(
                    "state", "timeout",
                    "fromUserId", session.getCallerId(),
                    "toUserId", session.getCalleeId(),
                    "displayName", session.getCallerDisplayName(),
                    "reason", "TIMEOUT"
            ))));
        }, Instant.now().plusMillis(properties.getCallRingTimeoutMs())));
    }

    private void scheduleConnectingTimeout(String sessionId) {
        store.setConnectingTimeout(sessionId, scheduler.schedule(() -> {
            CallSession session = store.getSession(sessionId);
            if (session == null || !"connecting".equals(session.getState())) {
                return;
            }
            if (session.getConnectedParticipants().size() >= 2) {
                return;
            }
            finishSession(session, "ended", MessageFactory.build("call.ended", merge(serializeSession(session), Map.of(
                    "state", "ended",
                    "fromUserId", session.getCalleeId(),
                    "toUserId", session.getCallerId(),
                    "displayName", session.getCalleeDisplayName(),
                    "reason", "MEDIA_CONNECT_TIMEOUT"
            ))));
        }, Instant.now().plusMillis(properties.getActiveCallDisconnectGraceMs())));
    }

    private void scheduleMediaDisconnectGrace(CallSession session, String disconnectedUserId) {
        String peerId = disconnectedUserId.equals(session.getCallerId()) ? session.getCalleeId() : session.getCallerId();

        store.setGraceTimer(session.getSessionId(), scheduler.schedule(() -> {
            store.clearGraceTimer(session.getSessionId());
            CallSession current = store.getSession(session.getSessionId());
            if (current == null) {
                return;
            }
            if (!("connecting".equals(current.getState()) || "active".equals(current.getState()))) {
                return;
            }
            if (current.getConnectedParticipants().size() >= 2) {
                return;
            }
            finishSession(current, "ended", MessageFactory.build("call.ended", merge(serializeSession(current), Map.of(
                    "state", "ended",
                    "fromUserId", disconnectedUserId,
                    "toUserId", peerId,
                    "displayName", disconnectedUserId.equals(current.getCallerId()) ? current.getCallerDisplayName() : current.getCalleeDisplayName(),
                    "reason", "DISCONNECTED"
            ))));
        }, Instant.now().plusMillis(properties.getActiveCallDisconnectGraceMs())));
    }

    private void finishSession(CallSession session, String finalState, Map<String, Object> event) {
        store.clearSessionTimeout(session.getSessionId());
        store.clearConnectingTimeout(session.getSessionId());
        store.clearGraceTimer(session.getSessionId());

        session.setState(finalState);
        session.touch();

        presence.sendToUser(session.getCallerId(), event);
        presence.sendToUser(session.getCalleeId(), event);
        push.queuePushEventToUsers(List.of(session.getCallerId(), session.getCalleeId()), event);

        store.deleteSession(session.getSessionId());
        store.deleteUserSession(session.getCallerId());
        store.deleteUserSession(session.getCalleeId());
    }

    private void recordMissedCall(CallSession session, String reason) {
        MissedCallEntry entry = new MissedCallEntry();
        entry.setId(UUID.randomUUID().toString());
        entry.setSessionId(session.getSessionId());
        entry.setCallerUserId(session.getCallerId());
        entry.setCallerDisplayName(session.getCallerDisplayName());
        entry.setCalleeUserId(session.getCalleeId());
        entry.setCalleeDisplayName(session.getCalleeDisplayName());
        entry.setMode(session.getMode());
        entry.setReason(reason);
        entry.setRead(false);
        entry.setTimestamp(Instant.now().toString());

        store.addMissedCall(session.getCalleeId(), entry);
        presence.sendToUser(session.getCalleeId(), MessageFactory.build("missed-call.new", toMap(entry)));
    }

    public CallSession getSession(String sessionId) {
        return store.getSession(sessionId);
    }

    public List<MissedCallEntry> getMissedCalls(String userId) {
        return store.getMissedCalls(userId);
    }

    private Map<String, Object> merge(Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> payload = new LinkedHashMap<>(left);
        payload.putAll(right);
        return payload;
    }

    private Map<String, Object> toMap(MissedCallEntry entry) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", entry.getId());
        m.put("sessionId", entry.getSessionId());
        m.put("callerUserId", entry.getCallerUserId());
        m.put("callerDisplayName", entry.getCallerDisplayName());
        m.put("calleeUserId", entry.getCalleeUserId());
        m.put("calleeDisplayName", entry.getCalleeDisplayName());
        m.put("mode", entry.getMode());
        m.put("reason", entry.getReason());
        m.put("read", entry.isRead());
        m.put("timestamp", entry.getTimestamp());
        return m;
    }
}
