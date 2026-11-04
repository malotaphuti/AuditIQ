import { HumanizePipe } from './humanize.pipe';
import { RoleLabelPipe } from './role-label.pipe';
import { errorMessage } from '../core/error-message';
import { HttpErrorResponse } from '@angular/common/http';

describe('shared helpers', () => {
  it('humanizes enum-style values', () => {
    const pipe = new HumanizePipe();
    expect(pipe.transform('LOGIN_SUCCESS')).toBe('Login success');
    expect(pipe.transform('')).toBe('');
    expect(pipe.transform(null)).toBe('');
  });

  it('labels roles', () => {
    expect(new RoleLabelPipe().transform('AUDIT_MANAGER')).toBe('Audit Manager');
  });

  it('extracts API error messages and field errors', () => {
    expect(errorMessage(new HttpErrorResponse({ status: 0 }))).toContain('unreachable');
    expect(errorMessage(new HttpErrorResponse({ status: 409, error: { message: 'Username is already in use' } })))
      .toBe('Username is already in use');
    expect(errorMessage(new HttpErrorResponse({ status: 400, error: { fieldErrors: { email: 'must be valid' } } })))
      .toBe('email: must be valid');
    expect(errorMessage(new Error('x'), 'fallback')).toBe('fallback');
  });
});
