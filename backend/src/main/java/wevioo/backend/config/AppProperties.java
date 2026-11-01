package wevioo.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private String storageRoot;
    private String airflowBaseUrl;
    private String airflowUsername;
    private String airflowPassword;
    private String airflowDagId;
    private boolean airflowDebug = false;
    private int airflowConnectTimeoutMs = 5000;
    private int airflowReadTimeoutMs = 15000;
    private String pipelineBackendUrl;
    private String corsAllowedOrigins;
    private String adminUsername;
    private String adminPassword;
    private String pipelineApiToken;
    private String jwtSecret;
    private long jwtTtlMinutes = 480;
    private boolean strictSecrets = false;
    private long watchdogIntervalMs = 60000;
    private long watchdogStaleAfterMinutes = 10;
    private long watchdogMaxRunMinutes = 180;
    private String ollamaUrl = "http://localhost:11434";
    private String ollamaModel = "";
    private int ollamaTimeoutMs = 120000;

    public String getStorageRoot() { return storageRoot; }
    public void setStorageRoot(String storageRoot) { this.storageRoot = storageRoot; }
    public String getAirflowBaseUrl() { return airflowBaseUrl; }
    public void setAirflowBaseUrl(String airflowBaseUrl) { this.airflowBaseUrl = airflowBaseUrl; }
    public String getAirflowUsername() { return airflowUsername; }
    public void setAirflowUsername(String airflowUsername) { this.airflowUsername = airflowUsername; }
    public String getAirflowPassword() { return airflowPassword; }
    public void setAirflowPassword(String airflowPassword) { this.airflowPassword = airflowPassword; }
    public String getAirflowDagId() { return airflowDagId; }
    public void setAirflowDagId(String airflowDagId) { this.airflowDagId = airflowDagId; }
    public boolean isAirflowDebug() { return airflowDebug; }
    public void setAirflowDebug(boolean airflowDebug) { this.airflowDebug = airflowDebug; }
    public int getAirflowConnectTimeoutMs() { return airflowConnectTimeoutMs; }
    public void setAirflowConnectTimeoutMs(int airflowConnectTimeoutMs) { this.airflowConnectTimeoutMs = airflowConnectTimeoutMs; }
    public int getAirflowReadTimeoutMs() { return airflowReadTimeoutMs; }
    public void setAirflowReadTimeoutMs(int airflowReadTimeoutMs) { this.airflowReadTimeoutMs = airflowReadTimeoutMs; }
    public String getPipelineBackendUrl() { return pipelineBackendUrl; }
    public void setPipelineBackendUrl(String pipelineBackendUrl) { this.pipelineBackendUrl = pipelineBackendUrl; }
    public String getCorsAllowedOrigins() { return corsAllowedOrigins; }
    public void setCorsAllowedOrigins(String corsAllowedOrigins) { this.corsAllowedOrigins = corsAllowedOrigins; }
    public String getAdminUsername() { return adminUsername; }
    public void setAdminUsername(String adminUsername) { this.adminUsername = adminUsername; }
    public String getAdminPassword() { return adminPassword; }
    public void setAdminPassword(String adminPassword) { this.adminPassword = adminPassword; }
    public String getPipelineApiToken() { return pipelineApiToken; }
    public void setPipelineApiToken(String pipelineApiToken) { this.pipelineApiToken = pipelineApiToken; }
    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
    public long getJwtTtlMinutes() { return jwtTtlMinutes; }
    public void setJwtTtlMinutes(long jwtTtlMinutes) { this.jwtTtlMinutes = jwtTtlMinutes; }
    public boolean isStrictSecrets() { return strictSecrets; }
    public void setStrictSecrets(boolean strictSecrets) { this.strictSecrets = strictSecrets; }
    public long getWatchdogIntervalMs() { return watchdogIntervalMs; }
    public void setWatchdogIntervalMs(long watchdogIntervalMs) { this.watchdogIntervalMs = watchdogIntervalMs; }
    public long getWatchdogStaleAfterMinutes() { return watchdogStaleAfterMinutes; }
    public void setWatchdogStaleAfterMinutes(long watchdogStaleAfterMinutes) { this.watchdogStaleAfterMinutes = watchdogStaleAfterMinutes; }
    public long getWatchdogMaxRunMinutes() { return watchdogMaxRunMinutes; }
    public void setWatchdogMaxRunMinutes(long watchdogMaxRunMinutes) { this.watchdogMaxRunMinutes = watchdogMaxRunMinutes; }
    public String getOllamaUrl() { return ollamaUrl; }
    public void setOllamaUrl(String ollamaUrl) { this.ollamaUrl = ollamaUrl; }
    public String getOllamaModel() { return ollamaModel; }
    public void setOllamaModel(String ollamaModel) { this.ollamaModel = ollamaModel; }
    public int getOllamaTimeoutMs() { return ollamaTimeoutMs; }
    public void setOllamaTimeoutMs(int ollamaTimeoutMs) { this.ollamaTimeoutMs = ollamaTimeoutMs; }
}
