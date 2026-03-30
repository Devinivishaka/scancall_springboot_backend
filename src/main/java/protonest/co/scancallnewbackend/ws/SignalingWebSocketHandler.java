package protonest.co.scancallnewbackend.ws;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import protonest.co.scancallnewbackend.service.CallSessionService;
import protonest.co.scancallnewbackend.service.PresenceService;
import protonest.co.scancallnewbackend.state.InMemoryStateStore;
import protonest.co.scancallnewbackend.util.MessageFactory;

@Component
public class SignalingWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SignalingWebSocketHandler.class);

    private final ObjectMapper objectMapper;
    private final PresenceService presenceService;
    private final CallSessionService callSessionService;
    private final InMemoryStateStore store;

    public SignalingWebSocketHandler(
            ObjectMapper objectMapper,
            PresenceService presenceService,
            CallSessionService callSessionService,
            InMemoryStateStore store
    ) {
        this.objectMapper = objectMapper;
        this.presenceService = presenceService;
        this.callSessionService = callSessionService;
        this.store = store;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket client connected id={}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Map<String, Object> data;
        try {
            data = objectMapper.readValue(message.getPayload(), new TypeReference<>() {
            });
        } catch (Exception ex) {
            presenceService.sendMessage(session, MessageFactory.build("error", Map.of("message", "Invalid JSON payload")));
            return;
        }

        try {
            handleMessage(session, data);
        } catch (Exception ex) {
            log.error("WebSocket handler error", ex);
            presenceService.sendMessage(session, MessageFactory.build("error", Map.of("message", ex.getMessage())));
        }
    }

    private void handleMessage(WebSocketSession ws, Map<String, Object> rawMessage) {
        String type = String.valueOf(rawMessage.getOrDefault("type", ""));

        switch (type) {
            case "presence.register" -> presenceService.register(ws, rawMessage);
            case "presence.update" -> presenceService.update(ws, rawMessage);
            case "push.register" -> handlePushRegister(rawMessage);
            case "call.invite" -> callSessionService.invite(rawMessage);
            case "call.ringing" -> callSessionService.ringing(rawMessage);
            case "call.accept" -> callSessionService.accept(rawMessage);
            case "call.reject" -> callSessionService.reject(rawMessage);
            case "call.cancel" -> callSessionService.cancel(rawMessage);
            case "call.end" -> callSessionService.end(rawMessage);
            case "call.connection.status" -> callSessionService.connectionStatus(rawMessage);
            case "call.switch.request" -> callSessionService.switchRequest(rawMessage);
            case "call.switch.accept" -> callSessionService.switchAccept(rawMessage);
            case "call.switch.reject" -> callSessionService.switchReject(rawMessage);
            case "ping" -> {
                String userId = String.valueOf(rawMessage.getOrDefault("userId", ""));
                presenceService.sendToUser(userId, MessageFactory.build("pong"));
            }
            default -> throw new IllegalArgumentException("Unsupported message type: " + type);
        }
    }

    private void handlePushRegister(Map<String, Object> rawMessage) {
        String userId = String.valueOf(rawMessage.getOrDefault("userId", "")).trim();
        String token = String.valueOf(rawMessage.getOrDefault("token", "")).trim();
        String platform = MessageFactory.normalizePlatform(String.valueOf(rawMessage.getOrDefault("platform", "")));
        String displayName = String.valueOf(rawMessage.getOrDefault("displayName", "")).trim();

        if (userId.isBlank() || token.isBlank()) {
            throw new IllegalArgumentException("push.register requires userId and token");
        }

        store.setPushToken(userId, token, platform);
        store.persistPushTokens();
        store.upsertKnownUser(userId, displayName, platform, java.time.Instant.now().toString());

        presenceService.sendToUser(userId, MessageFactory.build("push.registered", Map.of(
                "userId", userId,
                "platform", platform
        )));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String userId = store.removeSocket(session);
        if (userId == null) {
            return;
        }
        if (store.hasConnectedSockets(userId)) {
            return;
        }

        presenceService.remove(userId);
        callSessionService.handleDisconnect(userId);
    }
}
