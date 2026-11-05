import { Routes } from '@angular/router';
import { authGuard, guestGuard, roleGuard } from './core/auth/auth.guard';
import { NAV_ITEMS } from './core/navigation';

const IMPLEMENTED: Record<string, Routes[number]['loadComponent']> = {
  overview: () => import('./features/overview/overview.component').then((m) => m.OverviewComponent),
  pipeline: () => import('./features/pipeline/pipeline.component').then((m) => m.PipelineComponent),
  transactions: () => import('./features/transactions/transactions.component').then((m) => m.TransactionsComponent),
  exceptions: () => import('./features/exceptions/exceptions.component').then((m) => m.ExceptionsComponent),
  investigations: () =>
    import('./features/investigations/investigations.component').then((m) => m.InvestigationsComponent),
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

const byPath = (path: string) => NAV_ITEMS.find((i) => i.path === path)!;

/** Detail pages inherit the roles of their parent module. */
const detailRoutes: Routes = [
  {
    path: 'exceptions/:id',
    title: 'Exception · AuditIQ',
    canActivate: [roleGuard],
    data: { roles: byPath('exceptions').roles, nav: byPath('exceptions') },
    loadComponent: () =>
      import('./features/exceptions/exception-detail.component').then((m) => m.ExceptionDetailComponent),
  },
  {
    path: 'investigations/:id',
    title: 'Investigation · AuditIQ',
    canActivate: [roleGuard],
    data: { roles: byPath('investigations').roles, nav: byPath('investigations') },
    loadComponent: () =>
      import('./features/investigations/investigation-detail.component').then((m) => m.InvestigationDetailComponent),
  },
];

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
      ...detailRoutes,
      {
        path: 'forbidden',
        title: 'Access denied · AuditIQ',
        loadComponent: () => import('./features/forbidden/forbidden.component').then((m) => m.ForbiddenComponent),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
