import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, finalize, map, shareReplay, tap } from 'rxjs';
import { AuthResponse, RoleName, UserSummary } from '../models';
import { StoredSession, TokenStorage } from './token-storage';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly storage = inject(TokenStorage);

  private readonly session = signal<StoredSession | null>(this.storage.load());
  private refreshInFlight: Observable<string> | null = null;

  readonly user = computed<UserSummary | null>(() => this.session()?.user ?? null);
  readonly isAuthenticated = computed(() => this.session() !== null);

  accessToken(): string | null {
    return this.session()?.accessToken ?? null;
  }

  hasAnyRole(roles: readonly RoleName[] | undefined): boolean {
    if (!roles || roles.length === 0) {
      return true;
    }
    const mine = this.user()?.roles ?? [];
    return roles.some((r) => mine.includes(r));
  }

  login(username: string, password: string): Observable<UserSummary> {
    return this.http.post<AuthResponse>('/api/auth/login', { username, password }).pipe(
      tap((res) => this.session.set(this.storage.save(res))),
      map((res) => res.user),
    );
  }

  /** Exchanges the refresh token for a new pair. Concurrent callers share one request. */
  refresh(): Observable<string> {
    const refreshToken = this.session()?.refreshToken;
    if (!refreshToken) {
      throw new Error('No refresh token');
    }
    if (!this.refreshInFlight) {
      this.refreshInFlight = this.http
        .post<AuthResponse>('/api/auth/refresh', { refreshToken })
        .pipe(
          tap((res) => this.session.set(this.storage.save(res))),
          map((res) => res.accessToken),
          finalize(() => (this.refreshInFlight = null)),
          shareReplay(1),
        );
    }
    return this.refreshInFlight;
  }

  canRefresh(): boolean {
    return !!this.session()?.refreshToken;
  }

  logout(redirect = true): void {
    const refreshToken = this.session()?.refreshToken;
    this.clearSession();
    if (refreshToken) {
      this.http.post('/api/auth/logout', { refreshToken }).subscribe({ error: () => undefined });
    }
    if (redirect) {
      this.router.navigate(['/login']);
    }
  }

  /** Drops the local session without calling the server (used when the server already rejected it). */
  clearSession(): void {
    this.storage.clear();
    this.session.set(null);
  }
}
