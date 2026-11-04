import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/error-message';
import { UserAccount } from '../../core/models';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { RoleLabelPipe } from '../../shared/role-label.pipe';
import { CreateUserDialogComponent } from './create-user-dialog.component';

@Component({
  selector: 'app-user-management',
  imports: [
    DatePipe, FormsModule, MatTableModule, MatPaginatorModule, MatButtonModule, MatIconModule, MatFormFieldModule,
    MatInputModule, MatSlideToggleModule, MatChipsModule, MatDialogModule, MatSnackBarModule, MatTooltipModule,
    MatProgressBarModule, RoleLabelPipe, EmptyStateComponent,
  ],
  templateUrl: './user-management.component.html',
})
export class UserManagementComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly dialog = inject(MatDialog);
  private readonly snack = inject(MatSnackBar);
  readonly currentUserId = inject(AuthService).user()?.id;

  readonly columns = ['user', 'roles', 'status', 'lastLogin', 'actions'];
  readonly rows = signal<UserAccount[]>([]);
  readonly total = signal(0);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  search = '';
  page = 0;
  size = 10;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.users(this.search, this.page, this.size).subscribe({
      next: (res) => {
        this.rows.set(res.content);
        this.total.set(res.totalElements);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(errorMessage(err));
        this.loading.set(false);
      },
    });
  }

  applySearch(): void {
    this.page = 0;
    this.load();
  }

  onPage(e: PageEvent): void {
    this.page = e.pageIndex;
    this.size = e.pageSize;
    this.load();
  }

  openCreate(): void {
    this.dialog
      .open(CreateUserDialogComponent, { width: '520px', autoFocus: 'first-tabbable' })
      .afterClosed()
      .subscribe((created?: UserAccount) => {
        if (created) {
          this.snack.open(`User ${created.username} created`, 'OK', { duration: 3000 });
          this.load();
        }
      });
  }

  toggleEnabled(user: UserAccount, enabled: boolean): void {
    this.api.setUserEnabled(user.id, enabled).subscribe({
      next: (updated) => this.replace(updated, `${updated.username} ${enabled ? 'enabled' : 'disabled'}`),
      error: (err) => {
        this.snack.open(errorMessage(err), 'Dismiss', { duration: 5000 });
        this.load();
      },
    });
  }

  unlock(user: UserAccount): void {
    this.api.unlockUser(user.id).subscribe({
      next: (updated) => this.replace(updated, `${updated.username} unlocked`),
      error: (err) => this.snack.open(errorMessage(err), 'Dismiss', { duration: 5000 }),
    });
  }

  private replace(updated: UserAccount, message: string): void {
    this.rows.update((rows) => rows.map((r) => (r.id === updated.id ? updated : r)));
    this.snack.open(message, 'OK', { duration: 3000 });
  }
}
