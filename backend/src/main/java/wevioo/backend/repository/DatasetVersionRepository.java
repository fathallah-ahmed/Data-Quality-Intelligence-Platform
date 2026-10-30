package wevioo.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import wevioo.backend.domain.DatasetVersion;

import java.util.List;
import java.util.Optional;

public interface DatasetVersionRepository extends JpaRepository<DatasetVersion, Long> {
    List<DatasetVersion> findByDatasetIdOrderByVersionNumberDesc(Long datasetId);
    Optional<DatasetVersion> findTopByDatasetIdOrderByVersionNumberDesc(Long datasetId);
    Optional<DatasetVersion> findTopByDatasetIdAndVersionNumberLessThanOrderByVersionNumberDesc(Long datasetId, Integer versionNumber);
}
