package protonest.co.scancallnewbackend.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import protonest.co.scancallnewbackend.model.CallSession;
import protonest.co.scancallnewbackend.model.PeerPresence;
import protonest.co.scancallnewbackend.state.InMemoryStateStore;

@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    private final InMemoryStateStore store;

    public PushService(InMemoryStateStore store) {
        this.store = store;
    }

    public void sendPushInvite(CallSession session) {
        PeerPresence calleePresence = store.getPresence(session.getCalleeId());
        if (calleePresence != null
                && "foreground".equals(calleePresence.getAppState())
                && store.hasConnectedSockets(session.getCalleeId())) {
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "call.incoming");
        payload.put("sessionId", session.getSessionId());
        payload.put("roomName", session.getRoomName());
        payload.put("fromUserId", session.getCallerId());
        payload.put("toUserId", session.getCalleeId());
        payload.put("displayName", session.getCallerDisplayName());
        payload.put("mode", session.getMode());
        payload.put("timeoutAt", session.getTimeoutAt());
        payload.put("timestamp", Instant.now().toString());

        sendPushEventToUsers(List.of(session.getCalleeId()), payload);
    }

    public void queuePushEventToUsers(List<String> userIds, Map<String, Object> payload) {
        sendPushEventToUsers(userIds, payload);
    }

    public void sendPushEventToUsers(List<String> userIds, Map<String, Object> payload) {
        Set<String> targets = Set.copyOf(userIds.stream().filter(id -> id != null && !id.isBlank()).toList());
        for (String userId : targets) {
            sendPushEventToUser(userId, payload);
        }
    }

    public void sendPushEventToUser(String userId, Map<String, Object> payload) {
        String androidToken = store.getAndroidToken(userId);
        String iosToken = store.getIosToken(userId);
        if (androidToken == null && iosToken == null) {
            return;
        }

        // Keep the architecture seam in place. The concrete FCM/APNS transport can be plugged here.
        log.info("Queued push event for user {} type={}", userId, payload.get("type"));
    }
}
