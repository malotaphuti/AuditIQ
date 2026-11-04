import { Routes } from '@angular/router';
import { authGuard, guestGuard, roleGuard } from './core/auth/auth.guard';
import { NAV_ITEMS } from './core/navigation';

const IMPLEMENTED: Record<string, Routes[number]['loadComponent']> = {
  overview: () => import('./features/overview/overview.component').then((m) => m.OverviewComponent),
  admin: () => import('./features/admin/admin.component').then((m) => m.AdminComponent),
};

const moduleRoutes: Routes = NAV_ITEMS.map((item) => ({
  path: item.path,
  title: `${item.label} · AuditIQ`,
  canActivate: [roleGuard],
  data: { roles: item.roles, nav: item },
  loadComponent:
    IMPLEMENTED[item.path] ??
    (() => import('./features/module-pending/module-pending.component').then((m) => m.ModulePendingComponent)),
}));

export const routes: Routes = [
  {
    path: 'login',
    title: 'Sign in · AuditIQ',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/login/login.component').then((m) => m.LoginComponent),
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./layout/shell.component').then((m) => m.ShellComponent),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'overview' },
      ...moduleRoutes,
      {
        path: 'forbidden',
        title: 'Access denied · AuditIQ',
        loadComponent: () => import('./features/forbidden/forbidden.component').then((m) => m.ForbiddenComponent),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
