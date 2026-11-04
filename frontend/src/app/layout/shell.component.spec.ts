import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed, discardPeriodicTasks, fakeAsync, tick } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../core/auth/auth.service';
import { RoleName, UserSummary } from '../core/models';
import { ShellComponent } from './shell.component';

function setup(roles: RoleName[]) {
  const user = signal<UserSummary>({ id: 1, username: 'u', fullName: 'Una User', email: 'u@x', roles });
  TestBed.configureTestingModule({
    imports: [ShellComponent],
    providers: [
      provideRouter([]),
      provideHttpClient(),
      provideHttpClientTesting(),
      {
        provide: AuthService,
        useValue: {
          user,
          hasAnyRole: (req?: RoleName[]) => !req || req.some((r) => roles.includes(r)),
          logout: jasmine.createSpy('logout'),
        },
      },
    ],
  });
  const fixture = TestBed.createComponent(ShellComponent);
  fixture.detectChanges();
  tick();
  TestBed.inject(HttpTestingController).expectOne('/api/system/status').flush({
    checkedAt: '', database: 'UP', analyticsService: 'UP', version: '0.1.0',
  });
  fixture.detectChanges();
  discardPeriodicTasks();
  return fixture;
}

describe('ShellComponent', () => {
  it('shows only navigation permitted for an auditor', fakeAsync(() => {
    const labels = setup(['AUDITOR']).componentInstance.navItems().map((n) => n.label);
    expect(labels).toContain('AI Investigation Agent');
    expect(labels).not.toContain('Administration');
  }));

  it('shows administration to administrators', fakeAsync(() => {
    const labels = setup(['ADMIN']).componentInstance.navItems().map((n) => n.label);
    expect(labels).toContain('Administration');
    expect(labels).not.toContain('AI Investigation Agent');
  }));

  it('renders user initials and system status', fakeAsync(() => {
    const fixture = setup(['AUDIT_MANAGER']);
    expect(fixture.componentInstance.initials()).toBe('UU');
    expect(fixture.nativeElement.textContent).toContain('All systems operational');
  }));
});
