package wevioo.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import wevioo.backend.domain.TableVersion;

import java.util.List;
import java.util.Optional;

public interface TableVersionRepository extends JpaRepository<TableVersion, Long> {
    List<TableVersion> findByDatasetVersionIdOrderByTableNameAsc(Long datasetVersionId);

    List<TableVersion> findByDatasetVersionDatasetId(Long datasetId);

    Optional<TableVersion> findByDatasetVersionIdAndTableName(Long datasetVersionId, String tableName);

    Optional<TableVersion> findTopByDatasetVersionDatasetIdAndTableNameAndDatasetVersionIdNotOrderByDatasetVersionVersionNumberDesc(
        Long datasetId,
        String tableName,
        Long excludedDatasetVersionId
    );
}
