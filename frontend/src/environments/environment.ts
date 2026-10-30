/** Development settings (used by `npm start`). */
export const environment = {
  production: false,
  /** Spring Boot API base URL. */
  apiBase: 'http://localhost:8081/api',
  /** Airflow web UI, linked from the pipeline status when a run looks stuck. */
  airflowUiUrl: 'http://localhost:8080',
  /** Client-side upload limit; keep in line with spring.servlet.multipart.max-file-size. */
  maxUploadBytes: 500 * 1024 * 1024
};
