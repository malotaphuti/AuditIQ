import { HttpErrorResponse } from '@angular/common/http';
import { ApiError } from './models';

/** Converts any HTTP failure into a message suitable for display. */
export function errorMessage(err: unknown, fallback = 'Something went wrong. Please try again.'): string {
  if (err instanceof HttpErrorResponse) {
    if (err.status === 0) {
      return 'The AuditIQ server is unreachable. Check that the backend is running.';
    }
    const body = err.error as Partial<ApiError> | null;
    if (body?.fieldErrors && Object.keys(body.fieldErrors).length > 0) {
      return Object.entries(body.fieldErrors)
        .map(([field, msg]) => `${field}: ${msg}`)
        .join('; ');
    }
    if (body?.message) {
      return body.message;
    }
  }
  return fallback;
}
