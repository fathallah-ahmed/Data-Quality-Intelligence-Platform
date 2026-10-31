package wevioo.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import wevioo.backend.domain.QualityRule;

import java.util.List;
import java.util.Optional;

public interface QualityRuleRepository extends JpaRepository<QualityRule, Long> {
    List<QualityRule> findByDatasetIdOrderByIdAsc(Long datasetId);

    Optional<QualityRule> findByIdAndDatasetId(Long id, Long datasetId);
}
