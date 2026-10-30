/** Production settings (used by `npm run build`). Adjust the URLs to where the services are deployed. */
export const environment = {
  production: true,
  apiBase: 'http://localhost:8081/api',
  airflowUiUrl: 'http://localhost:8080',
  maxUploadBytes: 500 * 1024 * 1024
};
