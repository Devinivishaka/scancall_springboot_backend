package protonest.co.scancallnewbackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "scancall")
public class ScancallProperties {

    private String wsPath = "/ws";
    private long callRingTimeoutMs = 30_000;
    private long activeCallDisconnectGraceMs = 30_000;
    private long wsPingIntervalMs = 25_000;
    private long wsPongTimeoutMs = 10_000;
    private int maxMissedCallsPerUser = 100;
    private String serviceAccountPath;
    private String pushTokenStorePath;

    private final Livekit livekit = new Livekit();
    private final Apns apns = new Apns();

    public String getWsPath() {
        return wsPath;
    }

    public void setWsPath(String wsPath) {
        this.wsPath = wsPath;
    }

    public long getCallRingTimeoutMs() {
        return callRingTimeoutMs;
    }

    public void setCallRingTimeoutMs(long callRingTimeoutMs) {
        this.callRingTimeoutMs = callRingTimeoutMs;
    }

    public long getActiveCallDisconnectGraceMs() {
        return activeCallDisconnectGraceMs;
    }

    public void setActiveCallDisconnectGraceMs(long activeCallDisconnectGraceMs) {
        this.activeCallDisconnectGraceMs = activeCallDisconnectGraceMs;
    }

    public long getWsPingIntervalMs() {
        return wsPingIntervalMs;
    }

    public void setWsPingIntervalMs(long wsPingIntervalMs) {
        this.wsPingIntervalMs = wsPingIntervalMs;
    }

    public long getWsPongTimeoutMs() {
        return wsPongTimeoutMs;
    }

    public void setWsPongTimeoutMs(long wsPongTimeoutMs) {
        this.wsPongTimeoutMs = wsPongTimeoutMs;
    }

    public int getMaxMissedCallsPerUser() {
        return maxMissedCallsPerUser;
    }

    public void setMaxMissedCallsPerUser(int maxMissedCallsPerUser) {
        this.maxMissedCallsPerUser = maxMissedCallsPerUser;
    }

    public String getServiceAccountPath() {
        return serviceAccountPath;
    }

    public void setServiceAccountPath(String serviceAccountPath) {
        this.serviceAccountPath = serviceAccountPath;
    }

    public String getPushTokenStorePath() {
        return pushTokenStorePath;
    }

    public void setPushTokenStorePath(String pushTokenStorePath) {
        this.pushTokenStorePath = pushTokenStorePath;
    }

    public Livekit getLivekit() {
        return livekit;
    }

    public Apns getApns() {
        return apns;
    }

    public static class Livekit {
        private String url = "";
        private String apiKey = "";
        private String apiSecret = "";
        private long tokenExpirySeconds = 3600;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getApiSecret() {
            return apiSecret;
        }

        public void setApiSecret(String apiSecret) {
            this.apiSecret = apiSecret;
        }

        public long getTokenExpirySeconds() {
            return tokenExpirySeconds;
        }

        public void setTokenExpirySeconds(long tokenExpirySeconds) {
            this.tokenExpirySeconds = tokenExpirySeconds;
        }

        public boolean isConfigured() {
            return !url.isBlank() && !apiKey.isBlank() && !apiSecret.isBlank();
        }
    }

    public static class Apns {
        private String authKey = "";
        private String teamId = "";
        private String keyId = "";
        private String bundleId = "";
        private String host = "https://api.development.push.apple.com";
        private int ipFamily = 0;

        public String getAuthKey() {
            return authKey;
        }

        public void setAuthKey(String authKey) {
            this.authKey = authKey;
        }

        public String getTeamId() {
            return teamId;
        }

        public void setTeamId(String teamId) {
            this.teamId = teamId;
        }

        public String getKeyId() {
            return keyId;
        }

        public void setKeyId(String keyId) {
            this.keyId = keyId;
        }

        public String getBundleId() {
            return bundleId;
        }

        public void setBundleId(String bundleId) {
            this.bundleId = bundleId;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getIpFamily() {
            return ipFamily;
        }

        public void setIpFamily(int ipFamily) {
            this.ipFamily = ipFamily;
        }

        public boolean isConfigured() {
            return !authKey.isBlank() && !teamId.isBlank() && !keyId.isBlank() && !bundleId.isBlank();
        }
    }
}
