import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { map } from 'rxjs';
import { NavItem } from '../../core/navigation';
import { EmptyStateComponent } from '../../shared/empty-state.component';

/**
 * Honest placeholder for modules scheduled in later phases: states what the module will do and when, and offers no
 * interactive controls that would not work yet.
 */
@Component({
  selector: 'app-module-pending',
  imports: [EmptyStateComponent],
  template: `
    @if (item(); as i) {
      <header class="page-header">
        <div>
          <h1>{{ i.label }}</h1>
          <p class="subtitle">{{ i.description }}</p>
        </div>
      </header>
      <section class="panel">
        <app-empty-state
          icon="construction"
          [title]="'Scheduled for Phase ' + i.plannedPhase"
          message="This module is part of the AuditIQ delivery plan and is not active in this build. No data is shown until it is implemented against the live database."
        />
      </section>
    }
  `,
})
export class ModulePendingComponent {
  private readonly data = toSignal(inject(ActivatedRoute).data.pipe(map((d) => d['nav'] as NavItem)));
  readonly item = computed(() => this.data());
}
