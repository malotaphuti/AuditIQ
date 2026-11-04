import { BreakpointObserver } from '@angular/cdk/layout';
import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatMenuModule } from '@angular/material/menu';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { catchError, map, of, switchMap, timer } from 'rxjs';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth/auth.service';
import { SystemStatus } from '../core/models';
import { NAV_ITEMS } from '../core/navigation';
import { RoleLabelPipe } from '../shared/role-label.pipe';

@Component({
  selector: 'app-shell',
  imports: [
    RouterOutlet, RouterLink, RouterLinkActive, MatSidenavModule, MatToolbarModule, MatListModule,
    MatIconModule, MatButtonModule, MatMenuModule, MatTooltipModule, RoleLabelPipe,
  ],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss',
})
export class ShellComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);

  readonly user = this.auth.user;
  readonly navItems = computed(() => NAV_ITEMS.filter((item) => this.auth.hasAnyRole(item.roles)));
  readonly status = signal<SystemStatus | null>(null);

  readonly isHandset = toSignal(
    inject(BreakpointObserver).observe('(max-width: 959px)').pipe(map((r) => r.matches)),
    { initialValue: false },
  );

  readonly initials = computed(() =>
    (this.user()?.fullName ?? '?')
      .split(/\s+/)
      .map((p) => p[0])
      .slice(0, 2)
      .join('')
      .toUpperCase(),
  );

  ngOnInit(): void {
    // Poll dependency status once a minute for the header indicator.
    timer(0, 60_000)
      .pipe(
        switchMap(() => this.api.systemStatus().pipe(catchError(() => of(null)))),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((s) => this.status.set(s));
  }

  logout(): void {
    this.auth.logout();
  }
}
