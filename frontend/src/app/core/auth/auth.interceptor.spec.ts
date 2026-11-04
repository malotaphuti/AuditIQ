import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';
import { FAKE_AUTH } from './auth.service.spec';

describe('authInterceptor', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  let auth: AuthService;

  function signIn(): void {
    auth.login('auditor', 'pw').subscribe();
    http.expectOne('/api/auth/login').flush(FAKE_AUTH);
  }

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => http.verify());

  it('attaches the bearer token to API calls', () => {
    signIn();
    client.get('/api/overview').subscribe();
    expect(http.expectOne('/api/overview').request.headers.get('Authorization')).toBe('Bearer access-1');
  });

  it('does not attach tokens to non-API URLs', () => {
    signIn();
    client.get('/assets/x.json').subscribe();
    expect(http.expectOne('/assets/x.json').request.headers.has('Authorization')).toBeFalse();
  });

  it('refreshes once on 401 and retries with the new token', () => {
    signIn();
    let result: unknown;
    client.get('/api/overview').subscribe((r) => (result = r));

    http.expectOne('/api/overview').flush({}, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/auth/refresh').flush({ ...FAKE_AUTH, accessToken: 'access-2' });
    const retry = http.expectOne('/api/overview');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer access-2');
    retry.flush({ ok: true });

    expect(result).toEqual({ ok: true });
  });

  it('clears the session and redirects when refresh fails', () => {
    signIn();
    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    let failed = false;
    client.get('/api/overview').subscribe({ error: () => (failed = true) });

    http.expectOne('/api/overview').flush({}, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });

    expect(failed).toBeTrue();
    expect(auth.isAuthenticated()).toBeFalse();
    expect(navigate).toHaveBeenCalledWith(['/login'], { queryParams: { expired: 1 } });
  });

  it('passes through non-401 errors untouched', () => {
    signIn();
    let status = 0;
    client.get('/api/admin/users').subscribe({ error: (e) => (status = e.status) });
    http.expectOne('/api/admin/users').flush({}, { status: 403, statusText: 'Forbidden' });
    expect(status).toBe(403);
  });
});
