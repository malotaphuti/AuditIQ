import { Component, inject } from '@angular/core';
import { MatTabsModule } from '@angular/material/tabs';
import { AuthService } from '../../core/auth/auth.service';
import { AuditRulesAdminComponent } from './audit-rules-admin.component';
import { AuditTrailComponent } from './audit-trail.component';
import { MlSettingsAdminComponent } from './ml-settings-admin.component';
import { UserManagementComponent } from './user-management.component';

@Component({
  selector: 'app-admin',
  imports: [MatTabsModule, UserManagementComponent, AuditRulesAdminComponent, MlSettingsAdminComponent,
    AuditTrailComponent],
  template: `
    <header class="page-header">
      <div>
        <h1>Administration</h1>
        <p class="subtitle">User access, detection settings and the immutable audit trail.</p>
      </div>
    </header>
    @if (isAdmin) {
      <mat-tab-group animationDuration="0ms" class="panel tabs">
        <mat-tab label="Users"><app-user-management /></mat-tab>
        <mat-tab label="Audit rules"><ng-template matTabContent><app-audit-rules-admin /></ng-template></mat-tab>
        <mat-tab label="Anomaly model"><ng-template matTabContent><app-ml-settings-admin /></ng-template></mat-tab>
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
