import { Component, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-empty-state',
  imports: [MatIconModule],
  template: `
    <div class="empty" role="status">
      <mat-icon aria-hidden="true">{{ icon() }}</mat-icon>
      <div class="title">{{ title() }}</div>
      @if (message()) {
        <p>{{ message() }}</p>
      }
      <ng-content />
    </div>
  `,
  styles: `
    .empty {
      display: flex;
      flex-direction: column;
      align-items: center;
      text-align: center;
      padding: 28px 16px;
      color: var(--aiq-text-muted);
    }
    mat-icon {
      font-size: 36px;
      width: 36px;
      height: 36px;
      color: #9fb0c8;
    }
    .title {
      margin-top: 8px;
      font-weight: 600;
      color: var(--aiq-text);
    }
    p {
      margin: 6px 0 12px;
      max-width: 420px;
      font-size: 0.9rem;
    }
  `,
})
export class EmptyStateComponent {
  readonly icon = input('inbox');
  readonly title = input.required<string>();
  readonly message = input<string>();
}
