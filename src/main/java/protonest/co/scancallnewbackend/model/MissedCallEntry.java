package protonest.co.scancallnewbackend.model;

public class MissedCallEntry {
    private String id;
    private String sessionId;
    private String callerUserId;
    private String callerDisplayName;
    private String calleeUserId;
    private String calleeDisplayName;
    private String mode;
    private String reason;
    private boolean read;
    private String timestamp;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getCallerUserId() {
        return callerUserId;
    }

    public void setCallerUserId(String callerUserId) {
        this.callerUserId = callerUserId;
    }

    public String getCallerDisplayName() {
        return callerDisplayName;
    }

    public void setCallerDisplayName(String callerDisplayName) {
        this.callerDisplayName = callerDisplayName;
    }

    public String getCalleeUserId() {
        return calleeUserId;
    }

    public void setCalleeUserId(String calleeUserId) {
        this.calleeUserId = calleeUserId;
    }

    public String getCalleeDisplayName() {
        return calleeDisplayName;
    }

    public void setCalleeDisplayName(String calleeDisplayName) {
        this.calleeDisplayName = calleeDisplayName;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public boolean isRead() {
        return read;
    }

    public void setRead(boolean read) {
        this.read = read;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
}
