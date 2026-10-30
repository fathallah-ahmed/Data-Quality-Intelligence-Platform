package wevioo.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private String storageRoot;
    private String airflowBaseUrl;
    private String airflowUsername;
    private String airflowPassword;
    private String airflowDagId;
    private String pipelineBackendUrl;
    private String corsAllowedOrigins;
    private String adminUsername;
    private String adminPassword;
    private String pipelineApiToken;

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
}
