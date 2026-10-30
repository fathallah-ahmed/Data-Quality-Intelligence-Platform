import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { environment } from '../../environments/environment';
import { ApiService, apiErrorMessage } from './api.service';
import { AuthService } from './auth.service';
import { authInterceptor } from './auth.interceptor';
import { parseSseChunk } from './live-events.service';

describe('ApiService', () => {
  let api: ApiService;
  let http: HttpTestingController;
  const base = environment.apiBase;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting()]
    });
    api = TestBed.inject(ApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('logs in without an Authorization header and stores the session', () => {
    const auth = TestBed.inject(AuthService);
    let done = false;
    auth.login('admin', 'secret').subscribe(() => (done = true));

    const request = http.expectOne(`${base}/auth/login`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ username: 'admin', password: 'secret' });
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({ token: 'jwt-token', expiresAt: new Date(Date.now() + 3600_000).toISOString(), username: 'admin', roles: ['ADMIN'] });

    expect(done).toBe(true);
    expect(auth.isAuthenticated()).toBe(true);
    expect(sessionStorage.getItem('dq_session')).toContain('jwt-token');
    expect(localStorage.getItem('dq_auth_header')).toBeNull();
  });

  it('adds the bearer token to API calls', () => {
    sessionStorage.setItem('dq_session', JSON.stringify({
      token: 'abc', expiresAt: new Date(Date.now() + 3600_000).toISOString(), username: 'admin', roles: ['ADMIN']
    }));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(withInterceptors([authInterceptor])), provideHttpClientTesting()]
    });
    const service = TestBed.inject(ApiService);
    const controller = TestBed.inject(HttpTestingController);

    service.datasets().subscribe();
    const request = controller.expectOne(`${base}/datasets`);
    expect(request.request.headers.get('Authorization')).toBe('Bearer abc');
    request.flush([]);
    controller.verify();
  });

  it('builds the upload form and the selected-table / rerun URLs', () => {
    const file = new File(['a,b\n1,2\n'], 'orders.csv', { type: 'text/csv' });
    api.upload({ files: [file], name: ' Sales ', contextScope: 'per_table', tableDescriptions: { orders: 'One row per order' } }, true).subscribe();
    const upload = http.expectOne((request) => request.url === `${base}/datasets/upload`);
    expect(upload.request.params.get('rerunExisting')).toBe('true');
    const form = upload.request.body as FormData;
    expect(form.getAll('files')).toHaveLength(1);
    expect(form.get('name')).toBe('Sales');
    expect(form.get('tableDescriptionsJson')).toBe('{"orders":"One row per order"}');
    upload.flush({});

    api.tableDetails(1, 2, 3).subscribe();
    http.expectOne(`${base}/datasets/1/versions/2/tables/3`).flush({});

    api.rerun(1, 2).subscribe();
    const rerun = http.expectOne(`${base}/datasets/1/versions/2/rerun`);
    expect(rerun.request.method).toBe('POST');
    rerun.flush({});
  });

  it('extracts readable error messages', () => {
    expect(apiErrorMessage(new HttpErrorResponse({ status: 400, error: { error: 'Only .csv files are supported: a.txt' } }), 'x'))
      .toBe('Only .csv files are supported: a.txt');
    expect(apiErrorMessage(new HttpErrorResponse({ status: 0 }), 'x')).toContain('not reachable');
    expect(apiErrorMessage(new HttpErrorResponse({ status: 413 }), 'x')).toContain('larger');
    expect(apiErrorMessage(new Error('boom'), 'fallback')).toBe('fallback');
  });
});

describe('parseSseChunk', () => {
  it('parses event and data lines', () => {
    const event = parseSseChunk('event:TABLE_READY\nid:1\ndata:{"type":"TABLE_READY","datasetId":4,"tableName":"orders"}');
    expect(event).toEqual({ type: 'TABLE_READY', datasetId: 4, tableName: 'orders' });
  });

  it('ignores comments and malformed chunks', () => {
    expect(parseSseChunk(':keep-alive')).toBeNull();
    expect(parseSseChunk('data:{not json')).toBeNull();
  });
});
