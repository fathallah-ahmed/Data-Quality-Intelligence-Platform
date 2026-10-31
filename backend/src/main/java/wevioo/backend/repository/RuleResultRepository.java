package wevioo.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import wevioo.backend.domain.RuleResult;
import wevioo.backend.domain.RuleStatus;

import java.util.List;

public interface RuleResultRepository extends JpaRepository<RuleResult, Long> {
    List<RuleResult> findByDatasetVersionIdOrderByIdAsc(Long datasetVersionId);

    long countByDatasetVersionIdAndStatus(Long datasetVersionId, RuleStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RuleResult r where r.datasetVersion.id = :versionId")
    void deleteByVersion(@Param("versionId") Long versionId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RuleResult r where r.ruleId = :ruleId")
    void deleteByRule(@Param("ruleId") Long ruleId);
}
