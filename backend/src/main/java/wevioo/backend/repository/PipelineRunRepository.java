package wevioo.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import wevioo.backend.domain.PipelineRun;
import wevioo.backend.domain.RunStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PipelineRunRepository extends JpaRepository<PipelineRun, Long> {
    Optional<PipelineRun> findByExternalRunId(String externalRunId);

    List<PipelineRun> findByStatusIn(Collection<RunStatus> statuses);
}
