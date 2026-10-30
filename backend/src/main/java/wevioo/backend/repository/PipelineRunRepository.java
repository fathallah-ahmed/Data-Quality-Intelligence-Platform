package wevioo.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import wevioo.backend.domain.PipelineRun;

import java.util.Optional;

public interface PipelineRunRepository extends JpaRepository<PipelineRun, Long> {
    Optional<PipelineRun> findByExternalRunId(String externalRunId);
}
