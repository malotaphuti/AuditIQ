import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthResponse } from '../models';
import { AuthService } from './auth.service';

export const FAKE_AUTH: AuthResponse = {
  accessToken: 'access-1',
  refreshToken: 'refresh-1',
  tokenType: 'Bearer',
  expiresIn: 900,
  user: { id: 7, username: 'auditor', fullName: 'Thandi Auditor', email: 'a@x.io', roles: ['AUDITOR'] },
};

describe('AuthService', () => {
  let service: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    service = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('starts unauthenticated', () => {
    expect(service.isAuthenticated()).toBeFalse();
    expect(service.accessToken()).toBeNull();
  });

  it('stores the session after login', () => {
    service.login('auditor', 'pw').subscribe();
    const req = http.expectOne('/api/auth/login');
    expect(req.request.body).toEqual({ username: 'auditor', password: 'pw' });
    req.flush(FAKE_AUTH);

    expect(service.isAuthenticated()).toBeTrue();
    expect(service.user()?.username).toBe('auditor');
    expect(service.accessToken()).toBe('access-1');
    expect(sessionStorage.getItem('auditiq.session')).toContain('refresh-1');
  });

  it('checks roles', () => {
    service.login('auditor', 'pw').subscribe();
    http.expectOne('/api/auth/login').flush(FAKE_AUTH);
    expect(service.hasAnyRole(['AUDITOR'])).toBeTrue();
    expect(service.hasAnyRole(['ADMIN'])).toBeFalse();
    expect(service.hasAnyRole(undefined)).toBeTrue();
  });

  it('shares a single refresh request between concurrent callers', () => {
    service.login('auditor', 'pw').subscribe();
    http.expectOne('/api/auth/login').flush(FAKE_AUTH);

    const tokens: string[] = [];
    service.refresh().subscribe((t) => tokens.push(t));
    service.refresh().subscribe((t) => tokens.push(t));
    const req = http.expectOne('/api/auth/refresh');
    expect(req.request.body).toEqual({ refreshToken: 'refresh-1' });
    req.flush({ ...FAKE_AUTH, accessToken: 'access-2', refreshToken: 'refresh-2' });

    expect(tokens).toEqual(['access-2', 'access-2']);
    expect(service.accessToken()).toBe('access-2');
  });

  it('revokes the refresh token and clears state on logout', () => {
    service.login('auditor', 'pw').subscribe();
    http.expectOne('/api/auth/login').flush(FAKE_AUTH);

    service.logout(false);
    const req = http.expectOne('/api/auth/logout');
    expect(req.request.body).toEqual({ refreshToken: 'refresh-1' });
    req.flush(null);

    expect(service.isAuthenticated()).toBeFalse();
    expect(sessionStorage.getItem('auditiq.session')).toBeNull();
  });
});
