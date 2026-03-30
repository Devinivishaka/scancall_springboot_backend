package protonest.co.scancallnewbackend.service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import protonest.co.scancallnewbackend.config.ScancallProperties;

@Service
public class LivekitService {

    private final ScancallProperties properties;
    private final ObjectMapper objectMapper;

    public LivekitService(ScancallProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return properties.getLivekit().isConfigured();
    }

    public String mintToken(String userId, String displayName, String roomName, String sessionId, String mode) {
        Instant now = Instant.now();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("displayName", displayName);
        metadata.put("sessionId", sessionId);
        metadata.put("mode", mode);
        String metadataJson;
        try {
            metadataJson = objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            metadataJson = "{}";
        }

        Map<String, Object> video = new HashMap<>();
        video.put("roomJoin", true);
        video.put("room", roomName);
        video.put("canPublish", true);
        video.put("canSubscribe", true);
        video.put("canPublishData", true);

        Algorithm algorithm = Algorithm.HMAC256(properties.getLivekit().getApiSecret());

        return JWT.create()
                .withIssuer(properties.getLivekit().getApiKey())
                .withSubject(userId)
                .withClaim("name", displayName)
                .withClaim("metadata", metadataJson)
                .withNotBefore(now.minusSeconds(10))
                .withExpiresAt(now.plusSeconds(properties.getLivekit().getTokenExpirySeconds()))
                .withClaim("video", video)
                .sign(algorithm);
    }

    public String getServerUrl() {
        return properties.getLivekit().getUrl();
    }
}
