package wevioo.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Warns loudly when development defaults are used for secrets, and refuses to start
 * with them when {@code app.strict-secrets=true}.
 */
@Component
public class SecretsGuard implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(SecretsGuard.class);

    private final AppProperties properties;

    public SecretsGuard(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> defaults = defaultSecretsInUse(properties);
        if (defaults.isEmpty()) {
            return;
        }
        String message = "Insecure default secrets in use: " + String.join(", ", defaults)
            + ". Set them through environment variables before sharing this instance.";
        if (properties.isStrictSecrets()) {
            throw new IllegalStateException(message + " (refusing to start because app.strict-secrets=true)");
        }
        log.warn("************************************************************");
        log.warn(message);
        log.warn("************************************************************");
    }

    static List<String> defaultSecretsInUse(AppProperties properties) {
        List<String> defaults = new ArrayList<>();
        if ("admin123".equals(properties.getAdminPassword()) || isPlaceholder(properties.getAdminPassword())) {
            defaults.add("ADMIN_PASSWORD");
        }
        if ("dev-pipeline-token".equals(properties.getPipelineApiToken()) || isPlaceholder(properties.getPipelineApiToken())) {
            defaults.add("PIPELINE_API_TOKEN");
        }
        String jwtSecret = properties.getJwtSecret();
        if (jwtSecret == null || jwtSecret.startsWith("dev-jwt-secret") || isPlaceholder(jwtSecret)) {
            defaults.add("JWT_SECRET");
        }
        if ("admin".equals(properties.getAirflowPassword()) || isPlaceholder(properties.getAirflowPassword())) {
            defaults.add("AIRFLOW_PASSWORD");
        }
        return defaults;
    }

    private static boolean isPlaceholder(String value) {
        return value == null || value.isBlank() || value.startsWith("change-this");
    }
}
