import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { LoginComponent } from './login.component';

describe('LoginComponent', () => {
  let fixture: ComponentFixture<LoginComponent>;
  let auth: jasmine.SpyObj<AuthService>;
  let router: Router;

  beforeEach(async () => {
    auth = jasmine.createSpyObj<AuthService>('AuthService', ['login']);
    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    }).compileComponents();
    fixture = TestBed.createComponent(LoginComponent);
    router = TestBed.inject(Router);
    spyOn(router, 'navigateByUrl').and.resolveTo(true);
    fixture.detectChanges();
  });

  it('does not submit an empty form', () => {
    fixture.componentInstance.submit();
    expect(auth.login).not.toHaveBeenCalled();
  });

  it('navigates to the overview after a successful login', () => {
    auth.login.and.returnValue(of({ id: 1, username: 'a', fullName: 'A', email: 'a@x', roles: ['AUDITOR'] }));
    fixture.componentInstance.form.setValue({ username: 'auditor', password: 'secret' });
    fixture.componentInstance.submit();
    expect(auth.login).toHaveBeenCalledWith('auditor', 'secret');
    expect(router.navigateByUrl).toHaveBeenCalledWith('/overview');
  });

  it('ignores external returnUrls', () => {
    auth.login.and.returnValue(of({ id: 1, username: 'a', fullName: 'A', email: 'a@x', roles: ['AUDITOR'] }));
    fixture.componentRef.setInput('returnUrl', '//evil.example.com');
    fixture.componentInstance.form.setValue({ username: 'a', password: 'b' });
    fixture.componentInstance.submit();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/overview');
  });

  it('honours a same-app returnUrl', () => {
    auth.login.and.returnValue(of({ id: 1, username: 'a', fullName: 'A', email: 'a@x', roles: ['AUDITOR'] }));
    fixture.componentRef.setInput('returnUrl', '/admin');
    fixture.componentInstance.form.setValue({ username: 'a', password: 'b' });
    fixture.componentInstance.submit();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/admin');
  });

  it('shows the server error message on failure', () => {
    auth.login.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 401, error: { message: 'Invalid username or password' } })),
    );
    fixture.componentInstance.form.setValue({ username: 'auditor', password: 'wrong' });
    fixture.componentInstance.submit();
    fixture.detectChanges();

    const alert: HTMLElement = fixture.nativeElement.querySelector('[role="alert"]');
    expect(alert.textContent).toContain('Invalid username or password');
    expect(fixture.componentInstance.submitting()).toBeFalse();
  });
});
