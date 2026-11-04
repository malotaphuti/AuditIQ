import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { RoleName } from '../models';
import { authGuard, guestGuard, roleGuard } from './auth.guard';
import { AuthService } from './auth.service';

describe('route guards', () => {
  let authenticated = false;
  let roles: RoleName[] = [];

  const fakeAuth = {
    isAuthenticated: () => authenticated,
    hasAnyRole: (required?: RoleName[]) => !required || required.some((r) => roles.includes(r)),
  };

  const state = { url: '/admin' } as RouterStateSnapshot;
  const routeWithRoles = (r: RoleName[]) => ({ data: { roles: r } }) as unknown as ActivatedRouteSnapshot;

  beforeEach(() => {
    authenticated = false;
    roles = [];
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AuthService, useValue: fakeAuth }],
    });
  });

  const run = <T>(fn: () => T) => TestBed.runInInjectionContext(fn);
  const serialize = (tree: unknown) => TestBed.inject(Router).serializeUrl(tree as UrlTree);

  it('authGuard redirects anonymous users to login with returnUrl', () => {
    const result = run(() => authGuard({} as ActivatedRouteSnapshot, state));
    expect(serialize(result)).toBe('/login?returnUrl=%2Fadmin');
  });

  it('authGuard allows authenticated users', () => {
    authenticated = true;
    expect(run(() => authGuard({} as ActivatedRouteSnapshot, state))).toBeTrue();
  });

  it('roleGuard blocks users without a required role', () => {
    authenticated = true;
    roles = ['AUDITOR'];
    const result = run(() => roleGuard(routeWithRoles(['ADMIN']), state));
    expect(serialize(result)).toBe('/forbidden');
  });

  it('roleGuard allows users holding any required role', () => {
    authenticated = true;
    roles = ['AUDIT_MANAGER'];
    expect(run(() => roleGuard(routeWithRoles(['ADMIN', 'AUDIT_MANAGER']), state))).toBeTrue();
  });

  it('guestGuard sends signed-in users to the overview', () => {
    authenticated = true;
    const result = run(() => guestGuard({} as ActivatedRouteSnapshot, state));
    expect(serialize(result)).toBe('/overview');
  });
});
