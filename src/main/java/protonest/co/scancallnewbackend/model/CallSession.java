package protonest.co.scancallnewbackend.model;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CallSession {
    private String sessionId;
    private String roomName;
    private String callerId;
    private String calleeId;
    private String callerDisplayName;
    private String calleeDisplayName;
    private String state;
    private String mode;
    private String timeoutAt;
    private String createdAt;
    private String updatedAt;
    private final Set<String> connectedParticipants = ConcurrentHashMap.newKeySet();

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getRoomName() {
        return roomName;
    }

    public void setRoomName(String roomName) {
        this.roomName = roomName;
    }

    public String getCallerId() {
        return callerId;
    }

    public void setCallerId(String callerId) {
        this.callerId = callerId;
    }

    public String getCalleeId() {
        return calleeId;
    }

    public void setCalleeId(String calleeId) {
        this.calleeId = calleeId;
    }

    public String getCallerDisplayName() {
        return callerDisplayName;
    }

    public void setCallerDisplayName(String callerDisplayName) {
        this.callerDisplayName = callerDisplayName;
    }

    public String getCalleeDisplayName() {
        return calleeDisplayName;
    }

    public void setCalleeDisplayName(String calleeDisplayName) {
        this.calleeDisplayName = calleeDisplayName;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getTimeoutAt() {
        return timeoutAt;
    }

    public void setTimeoutAt(String timeoutAt) {
        this.timeoutAt = timeoutAt;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Set<String> getConnectedParticipants() {
        return connectedParticipants;
    }

    public void touch() {
        this.updatedAt = Instant.now().toString();
    }
}
