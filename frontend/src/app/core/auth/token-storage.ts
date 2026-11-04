import { Injectable } from '@angular/core';
import { AuthResponse, UserSummary } from '../models';

const KEY = 'auditiq.session';

export interface StoredSession {
  accessToken: string;
  refreshToken: string;
  user: UserSummary;
}

/**
 * Session persistence. sessionStorage is used so tokens are scoped to the tab and cleared when it closes;
 * access tokens are short-lived (15 min) and refresh tokens are rotated and revocable server-side.
 */
@Injectable({ providedIn: 'root' })
export class TokenStorage {
  load(): StoredSession | null {
    try {
      const raw = sessionStorage.getItem(KEY);
      return raw ? (JSON.parse(raw) as StoredSession) : null;
    } catch {
      return null;
    }
  }

  save(response: AuthResponse): StoredSession {
    const session: StoredSession = {
      accessToken: response.accessToken,
      refreshToken: response.refreshToken,
      user: response.user,
    };
    try {
      sessionStorage.setItem(KEY, JSON.stringify(session));
    } catch {
      /* storage unavailable: session lives in memory only */
    }
    return session;
  }

  clear(): void {
    try {
      sessionStorage.removeItem(KEY);
    } catch {
      /* ignore */
    }
  }
}
