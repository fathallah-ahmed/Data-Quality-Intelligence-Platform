import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import {
  AuthMe,
  CorrectionInterpretation,
  CorrectionLog,
  CorrectionOp,
  CorrectionPreview,
  CorrectionSuggestions,
  DatasetSummary,
  GraphBuild,
  GraphSchema,
  GraphStatus,
  QuestionAnswer,
  LoginResponse,
  RuleRequest,
  RuleResults,
  RuleView,
  TableAnalysisView,
  UploadRequest,
  UploadResponse,
  VersionDetails,
  VersionDiff,
  VersionSummary
} from './models';

/** All HTTP calls to the Spring Boot API. The auth header is added by the interceptor. */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  readonly base = environment.apiBase;

  login(username: string, password: string): Observable<LoginResponse> {
    return this.http.post<LoginResponse>(`${this.base}/auth/login`, { username, password });
  }

  me(): Observable<AuthMe> {
    return this.http.get<AuthMe>(`${this.base}/auth/me`);
  }

  datasets(): Observable<DatasetSummary[]> {
    return this.http.get<DatasetSummary[]>(`${this.base}/datasets`);
  }

  versions(datasetId: number): Observable<VersionSummary[]> {
    return this.http.get<VersionSummary[]>(`${this.base}/datasets/${datasetId}/versions`);
  }

  versionDetails(datasetId: number, versionId: number): Observable<VersionDetails> {
    return this.http.get<VersionDetails>(`${this.base}/datasets/${datasetId}/versions/${versionId}`);
  }

  /** One table with its raw metrics and analysis (top values, quartiles, associations). */
  tableDetails(datasetId: number, versionId: number, tableVersionId: number): Observable<TableAnalysisView> {
    return this.http.get<TableAnalysisView>(`${this.base}/datasets/${datasetId}/versions/${versionId}/tables/${tableVersionId}`);
  }

  upload(request: UploadRequest, rerunExisting = false): Observable<UploadResponse> {
    const form = new FormData();
    for (const file of request.files) {
      form.append('files', file);
    }
    if (request.name?.trim()) {
      form.append('name', request.name.trim());
    }
    form.append('contextScope', request.contextScope);
    if (request.globalDescription?.trim()) {
      form.append('globalDescription', request.globalDescription.trim());
    }
    if (request.globalQuestion?.trim()) {
      form.append('globalQuestion', request.globalQuestion.trim());
    }
    if (request.tableDescriptions && Object.keys(request.tableDescriptions).length > 0) {
      form.append('tableDescriptionsJson', JSON.stringify(request.tableDescriptions));
    }
    for (const [key, value] of [['domain', request.domain], ['objective', request.objective], ['rowGrain', request.rowGrain]] as const) {
      if (value?.trim()) {
        form.append(key, value.trim());
      }
    }
    return this.http.post<UploadResponse>(`${this.base}/datasets/upload`, form, {
      params: { rerunExisting: String(rerunExisting) }
    });
  }

  /** Compare a version with an older one (the previous version unless `against` is given). */
  diff(datasetId: number, versionId: number, against?: number | null): Observable<VersionDiff> {
    return this.http.get<VersionDiff>(`${this.base}/datasets/${datasetId}/versions/${versionId}/diff`, {
      params: against ? { against: String(against) } : {}
    });
  }

  rules(datasetId: number): Observable<RuleView[]> {
    return this.http.get<RuleView[]>(`${this.base}/datasets/${datasetId}/rules`);
  }

  createRule(datasetId: number, rule: RuleRequest): Observable<RuleView> {
    return this.http.post<RuleView>(`${this.base}/datasets/${datasetId}/rules`, rule);
  }

  updateRule(datasetId: number, ruleId: number, rule: RuleRequest): Observable<RuleView> {
    return this.http.put<RuleView>(`${this.base}/datasets/${datasetId}/rules/${ruleId}`, rule);
  }

  /** Switch on a rule the analysis suggested. */
  approveRule(datasetId: number, ruleId: number): Observable<RuleView> {
    return this.http.post<RuleView>(`${this.base}/datasets/${datasetId}/rules/${ruleId}/approve`, null);
  }

  /** Turn down a suggested rule; it is not suggested again. */
  dismissRule(datasetId: number, ruleId: number): Observable<{ status: string }> {
    return this.http.post<{ status: string }>(`${this.base}/datasets/${datasetId}/rules/${ruleId}/dismiss`, null);
  }

  deleteRule(datasetId: number, ruleId: number): Observable<{ status: string }> {
    return this.http.delete<{ status: string }>(`${this.base}/datasets/${datasetId}/rules/${ruleId}`);
  }

  ruleResults(datasetId: number, versionId: number): Observable<RuleResults> {
    return this.http.get<RuleResults>(`${this.base}/datasets/${datasetId}/versions/${versionId}/rule-results`);
  }

  /** Check the rules against a version (after adding or editing a rule). */
  evaluateRules(datasetId: number, versionId: number): Observable<RuleResults> {
    return this.http.post<RuleResults>(`${this.base}/datasets/${datasetId}/versions/${versionId}/rules/evaluate`, null);
  }

  graphStatus(datasetId: number, versionId: number): Observable<GraphStatus> {
    return this.http.get<GraphStatus>(`${this.base}/datasets/${datasetId}/versions/${versionId}/graph/status`);
  }

  /** How the rows would become a graph; `refine` asks the local model to name the parts. */
  graphSchema(datasetId: number, versionId: number, promote: string[], refine: boolean): Observable<GraphSchema> {
    return this.http.get<GraphSchema>(`${this.base}/datasets/${datasetId}/versions/${versionId}/graph/schema`, {
      params: { promote: promote.join(','), refine: String(refine) }
    });
  }

  /** Builds the graph in Neo4j, replacing the one built before for this version. */
  buildGraph(datasetId: number, versionId: number, promote: string[], renames: Record<string, string>): Observable<GraphBuild> {
    return this.http.post<GraphBuild>(`${this.base}/datasets/${datasetId}/versions/${versionId}/graph/build`, { promote, renames });
  }

  /** Removes the graph of a version from Neo4j. */
  deleteGraph(datasetId: number, versionId: number): Observable<{ deleted: boolean }> {
    return this.http.delete<{ deleted: boolean }>(`${this.base}/datasets/${datasetId}/versions/${versionId}/graph`);
  }

  /** The last build of a version; an empty object when there is none. */
  lastGraph(datasetId: number, versionId: number): Observable<Partial<GraphBuild>> {
    return this.http.get<Partial<GraphBuild>>(`${this.base}/datasets/${datasetId}/versions/${versionId}/graph`);
  }

  /** Asks a question in plain language about the graph of a version (it must have been built). */
  askQuestion(datasetId: number, versionId: number, question: string): Observable<QuestionAnswer> {
    return this.http.post<QuestionAnswer>(`${this.base}/datasets/${datasetId}/versions/${versionId}/ask`, { question });
  }

  /** Corrections found by reading a table: spaces, placeholders, date formats, spellings, repeated rows. */
  correctionSuggestions(datasetId: number, versionId: number, table: string): Observable<CorrectionSuggestions> {
    return this.http.get<CorrectionSuggestions>(`${this.base}/datasets/${datasetId}/versions/${versionId}/corrections/suggestions`, { params: { table } });
  }

  /** A request written in plain language, read as corrections. Nothing is changed. */
  interpretCorrection(datasetId: number, versionId: number, table: string, instruction: string): Observable<CorrectionInterpretation> {
    return this.http.post<CorrectionInterpretation>(`${this.base}/datasets/${datasetId}/versions/${versionId}/corrections/interpret`, { table, instruction });
  }

  /** What the corrections would change, with counts and examples. Nothing is written. */
  previewCorrections(datasetId: number, versionId: number, table: string, operations: CorrectionOp[]): Observable<CorrectionPreview> {
    return this.http.post<CorrectionPreview>(`${this.base}/datasets/${datasetId}/versions/${versionId}/corrections/preview`, { table, operations });
  }

  /** Applies the corrections to a copy: the result is the next version of the dataset. */
  applyCorrections(datasetId: number, versionId: number, table: string, operations: CorrectionOp[]): Observable<UploadResponse> {
    return this.http.post<UploadResponse>(`${this.base}/datasets/${datasetId}/versions/${versionId}/corrections/apply`, { table, operations });
  }

  correctionLog(datasetId: number, versionId: number): Observable<CorrectionLog> {
    return this.http.get<CorrectionLog>(`${this.base}/datasets/${datasetId}/versions/${versionId}/corrections`);
  }

  rerun(datasetId: number, versionId: number): Observable<UploadResponse> {
    return this.http.post<UploadResponse>(`${this.base}/datasets/${datasetId}/versions/${versionId}/rerun`, null);
  }

  deleteDataset(datasetId: number): Observable<{ status: string }> {
    return this.http.delete<{ status: string }>(`${this.base}/datasets/${datasetId}`);
  }

  eventStreamUrl(): string {
    return `${this.base}/events/stream`;
  }
}

/** A readable message for a failed API call: the backend's `{error}` text when present. */
export function apiErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) {
      return `The backend is not reachable at ${environment.apiBase.replace(/\/api$/, '')}. Is it running?`;
    }
    const body = error.error as { error?: unknown } | string | null;
    if (body && typeof body === 'object' && typeof body.error === 'string' && body.error.trim()) {
      return body.error;
    }
    if (error.status === 413) {
      return 'The upload is larger than the server limit.';
    }
  }
  return fallback;
}
