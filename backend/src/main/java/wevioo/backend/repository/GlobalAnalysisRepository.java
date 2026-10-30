package wevioo.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import wevioo.backend.domain.GlobalAnalysis;

import java.util.Optional;

public interface GlobalAnalysisRepository extends JpaRepository<GlobalAnalysis, Long> {
    Optional<GlobalAnalysis> findByDatasetVersionId(Long datasetVersionId);
}
