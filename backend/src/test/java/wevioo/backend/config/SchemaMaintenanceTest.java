package wevioo.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import wevioo.backend.service.AirflowClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE")
class SchemaMaintenanceTest {
    @Autowired SchemaMaintenance schemaMaintenance;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AirflowClient airflowClient;

    @Test
    void legacyEnumStatusColumnsAcceptNewValuesAfterMaintenance() {
        // Database created before PARTIAL existed: H2 in MySQL mode stored the status as an ENUM.
        schemaMaintenance.dropStaleH2EnumChecks();
        jdbc.execute("ALTER TABLE pipeline_run ALTER COLUMN status SET DATA TYPE ENUM('PENDING','RUNNING','COMPLETED','FAILED')");
        jdbc.execute("INSERT INTO dataset (name, source_type, created_at, updated_at) VALUES ('legacy', 'CSV', NOW(), NOW())");
        Long datasetId = jdbc.queryForObject("SELECT id FROM dataset WHERE name = 'legacy'", Long.class);
        jdbc.update("INSERT INTO dataset_version (dataset_id, version_number, file_path, uploaded_at, status) VALUES (?, 1, '/x', NOW(), 'PENDING')", datasetId);
        Long versionId = jdbc.queryForObject("SELECT id FROM dataset_version WHERE dataset_id = ?", Long.class, datasetId);
        jdbc.update("INSERT INTO pipeline_run (external_run_id, dataset_version_id, status) VALUES ('run-legacy', ?, 'RUNNING')", versionId);
        assertThatThrownBy(() -> jdbc.update("UPDATE pipeline_run SET status = 'PARTIAL' WHERE external_run_id = 'run-legacy'"))
            .hasMessageContaining("PARTIAL");

        schemaMaintenance.afterPropertiesSet();

        jdbc.update("UPDATE pipeline_run SET status = 'PARTIAL' WHERE external_run_id = 'run-legacy'");
        assertThat(jdbc.queryForObject("SELECT status FROM pipeline_run WHERE external_run_id = 'run-legacy'", String.class))
            .isEqualTo("PARTIAL");
    }

    @Test
    void legacyRuleTypeChecksAcceptTheNewRuleTypesAfterMaintenance() {
        // Database created before format and date-order rules existed: the rule type had a closed list of values.
        jdbc.execute("INSERT INTO dataset (name, source_type, created_at, updated_at) VALUES ('legacy-rules', 'CSV', NOW(), NOW())");
        Long datasetId = jdbc.queryForObject("SELECT id FROM dataset WHERE name = 'legacy-rules'", Long.class);
        schemaMaintenance.dropStaleH2EnumChecks();
        jdbc.execute("ALTER TABLE quality_rule ADD CONSTRAINT legacy_rule_type CHECK (type IN "
            + "('NOT_NULL','MAX_MISSING_PERCENT','UNIQUE','MIN_ROWS','MAX_DUPLICATE_PERCENT','MIN_QUALITY_SCORE','MAX_ROW_DROP_PERCENT'))");
        String insert = "INSERT INTO quality_rule (dataset_id, table_name, column_name, type, severity, enabled, created_at) "
            + "VALUES (?, 't', 'c', 'MIN_FORMAT_VALID_PERCENT', 'WARNING', TRUE, NOW())";
        assertThatThrownBy(() -> jdbc.update(insert, datasetId)).hasMessageContaining("Check constraint violation");

        schemaMaintenance.afterPropertiesSet();

        jdbc.update(insert, datasetId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quality_rule WHERE type = 'MIN_FORMAT_VALID_PERCENT'", Integer.class))
            .isGreaterThanOrEqualTo(1);
    }
}
