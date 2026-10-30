package wevioo.backend.config;

import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

/**
 * Small schema fix-ups that {@code ddl-auto=update} cannot do on databases created by older versions.
 *
 * <p>Hibernate stores enums as H2 CHECK constraints or MySQL ENUM columns, so adding a value
 * (e.g. {@code RunStatus.PARTIAL}) breaks inserts on existing databases. This drops the stale
 * H2 checks and widens MySQL columns to VARCHAR. Runs after Hibernate has updated the schema.
 */
@Component
public class SchemaMaintenance implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(SchemaMaintenance.class);
    private static final List<String> ENUM_COLUMNS = List.of(
        "dataset_version.status",
        "pipeline_run.status",
        "table_version.change_status"
    );

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public SchemaMaintenance(DataSource dataSource, EntityManagerFactory entityManagerFactory) {
        // The EntityManagerFactory dependency only makes sure Hibernate's schema update ran first.
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void afterPropertiesSet() {
        String product;
        try (Connection connection = dataSource.getConnection()) {
            product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        } catch (SQLException e) {
            log.warn("Could not inspect database for schema maintenance: {}", e.getMessage());
            return;
        }
        try {
            if (product.contains("h2")) {
                dropStaleH2EnumChecks();
            } else if (product.contains("mysql") || product.contains("mariadb")) {
                widenMysqlEnumColumns();
            }
        } catch (RuntimeException e) {
            log.warn("Schema maintenance skipped: {}", e.getMessage());
        }
    }

    private void dropStaleH2EnumChecks() {
        List<String[]> checks = jdbc.query(
            "SELECT tc.TABLE_NAME, tc.CONSTRAINT_NAME, cc.CHECK_CLAUSE "
                + "FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc "
                + "JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME "
                + "AND cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA "
                + "WHERE tc.CONSTRAINT_TYPE = 'CHECK' AND tc.TABLE_SCHEMA = SCHEMA()",
            (rs, rowNum) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}
        );
        for (String[] check : checks) {
            String clause = check[2] == null ? "" : check[2].toUpperCase(Locale.ROOT);
            if (clause.contains("'COMPLETED'") && !clause.contains("'PARTIAL'")) {
                jdbc.execute("ALTER TABLE " + check[0] + " DROP CONSTRAINT \"" + check[1] + "\"");
                log.info("Dropped stale enum check {} on {}", check[1], check[0]);
            }
        }
    }

    private void widenMysqlEnumColumns() {
        for (String column : ENUM_COLUMNS) {
            String[] parts = column.split("\\.");
            String type = jdbc.query(
                "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                parts[0],
                parts[1]
            );
            if ("enum".equalsIgnoreCase(type)) {
                jdbc.execute("ALTER TABLE " + parts[0] + " MODIFY " + parts[1] + " VARCHAR(32) NOT NULL");
                log.info("Converted {} from ENUM to VARCHAR(32)", column);
            }
        }
    }
}
