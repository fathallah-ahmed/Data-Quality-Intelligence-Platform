package wevioo.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import wevioo.backend.domain.TableAnalysis;

import java.util.Optional;

public interface TableAnalysisRepository extends JpaRepository<TableAnalysis, Long> {
    Optional<TableAnalysis> findByTableVersionId(Long tableVersionId);
}
