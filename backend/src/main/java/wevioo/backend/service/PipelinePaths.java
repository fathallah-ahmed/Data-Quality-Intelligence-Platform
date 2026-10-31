package wevioo.backend.service;

import java.nio.file.Path;

/** Maps folders written by the backend to the paths the Airflow containers see them under. */
public final class PipelinePaths {
    private static final String MARKER = "/Data_Quality_Wevioo/";

    private PipelinePaths() {}

    /**
     * {@code <anything>/Data_Quality_Wevioo/data/uploads/...} becomes {@code /opt/airflow/data/uploads/...},
     * because the worker project folder is mounted as {@code /opt/airflow}. Other paths are kept.
     */
    public static String toContainerPath(Path hostPath) {
        String normalized = hostPath.toAbsolutePath().normalize().toString().replace('\\', '/');
        int markerIndex = normalized.indexOf(MARKER);
        if (markerIndex >= 0) {
            return "/opt/airflow/" + normalized.substring(markerIndex + MARKER.length());
        }
        return normalized;
    }
}
