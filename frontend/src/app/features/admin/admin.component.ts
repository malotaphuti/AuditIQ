import { Component, inject } from '@angular/core';
import { MatTabsModule } from '@angular/material/tabs';
import { AuthService } from '../../core/auth/auth.service';
import { AuditTrailComponent } from './audit-trail.component';
import { UserManagementComponent } from './user-management.component';

@Component({
  selector: 'app-admin',
  imports: [MatTabsModule, UserManagementComponent, AuditTrailComponent],
  template: `
    <header class="page-header">
      <div>
        <h1>Administration</h1>
        <p class="subtitle">User access and the immutable audit trail.</p>
      </div>
    </header>
    @if (isAdmin) {
      <mat-tab-group animationDuration="0ms" class="panel tabs">
        <mat-tab label="Users"><app-user-management /></mat-tab>
        <mat-tab label="Audit trail"><app-audit-trail /></mat-tab>
      </mat-tab-group>
    } @else {
      <section class="panel"><app-audit-trail /></section>
    }
  `,
  styles: `
    .tabs {
      padding: 0;
    }
  `,
})
export class AdminComponent {
  readonly isAdmin = inject(AuthService).hasAnyRole(['ADMIN']);
}
