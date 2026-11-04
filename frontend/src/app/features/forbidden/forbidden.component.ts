import { Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { EmptyStateComponent } from '../../shared/empty-state.component';

@Component({
  selector: 'app-forbidden',
  imports: [EmptyStateComponent, MatButtonModule, RouterLink],
  template: `
    <section class="panel">
      <app-empty-state icon="lock" title="Access denied"
        message="Your role does not permit access to this page. Contact an administrator if you need access.">
        <a mat-flat-button routerLink="/overview">Back to overview</a>
      </app-empty-state>
    </section>
  `,
})
export class ForbiddenComponent {}
