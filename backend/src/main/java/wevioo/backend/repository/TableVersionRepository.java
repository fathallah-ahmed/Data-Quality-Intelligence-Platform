package wevioo.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import wevioo.backend.domain.TableVersion;

import java.util.List;
import java.util.Optional;

public interface TableVersionRepository extends JpaRepository<TableVersion, Long> {
    List<TableVersion> findByDatasetVersionIdOrderByTableNameAsc(Long datasetVersionId);

    List<TableVersion> findByDatasetVersionDatasetId(Long datasetId);

    List<TableVersion> findByPreviousTableVersionIn(List<TableVersion> previousTableVersions);

    Optional<TableVersion> findByDatasetVersionIdAndTableName(Long datasetVersionId, String tableName);

    /** The same table in the closest earlier version of the dataset. */
    Optional<TableVersion> findTopByDatasetVersionDatasetIdAndTableNameAndDatasetVersionVersionNumberLessThanOrderByDatasetVersionVersionNumberDesc(
        Long datasetId,
        String tableName,
        Integer versionNumber
    );
}
