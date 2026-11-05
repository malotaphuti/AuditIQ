import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ApiService } from '../../core/api.service';
import { errorMessage } from '../../core/error-message';
import { AuditRule, Severity, ThresholdSpec, ThresholdValue } from '../../core/models';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { HumanizePipe } from '../../shared/humanize.pipe';

interface Draft {
  thresholds: Record<string, ThresholdValue>;
  enabled: boolean;
  severity: Severity;
}

/** Administrator view: change rule thresholds, severity and enabled flag (validated server-side; audited). */
@Component({
  selector: 'app-audit-rules-admin',
  imports: [DatePipe, FormsModule, MatExpansionModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatSlideToggleModule, MatButtonModule, MatSnackBarModule, EmptyStateComponent, HumanizePipe],
  template: `
    <p class="muted small intro">
      Changes apply to the next rule execution. Each save increments the rule version; findings keep the thresholds
      that produced them, and every change is written to the audit trail.
    </p>
    @if (error()) {
      <app-empty-state icon="error_outline" title="Rules unavailable" [message]="error()!">
        <button mat-stroked-button (click)="load()">Retry</button>
      </app-empty-state>
    }
    <mat-accordion multi>
      @for (rule of rules(); track rule.ruleId) {
        <mat-expansion-panel>
          <mat-expansion-panel-header>
            <mat-panel-title><span class="rule-id">{{ rule.ruleId }}</span> {{ rule.ruleName }}</mat-panel-title>
            <mat-panel-description>
              v{{ rule.version }} · {{ rule.enabled ? 'enabled' : 'disabled' }}
              @if (rule.updatedBy) { · changed by {{ rule.updatedBy }} {{ rule.updatedAt | date: 'd MMM, HH:mm' }} }
            </mat-panel-description>
          </mat-expansion-panel-header>
          @if (drafts[rule.ruleId]; as draft) {
            <form class="rule-form" (ngSubmit)="save(rule)" [attr.aria-label]="'Configure ' + rule.ruleName">
              <div class="row">
                <mat-slide-toggle [name]="rule.ruleId + '-enabled'" [(ngModel)]="draft.enabled">Enabled</mat-slide-toggle>
                <mat-form-field subscriptSizing="dynamic">
                  <mat-label>Severity</mat-label>
                  <mat-select [name]="rule.ruleId + '-severity'" [(ngModel)]="draft.severity">
                    @for (s of severities; track s) { <mat-option [value]="s">{{ s | humanize }}</mat-option> }
                  </mat-select>
                </mat-form-field>
              </div>
              <div class="fields">
                @for (entry of schemaEntries(rule); track entry.key) {
                  @switch (entry.spec.type) {
                    @case ('boolean') {
                      <mat-slide-toggle [name]="rule.ruleId + entry.key" [(ngModel)]="draft.thresholds[entry.key]">
                        {{ entry.spec.label ?? entry.key }}
                      </mat-slide-toggle>
                    }
                    @case ('list') {
                      <mat-form-field subscriptSizing="dynamic">
                        <mat-label>{{ entry.spec.label ?? entry.key }}</mat-label>
                        <mat-select multiple [name]="rule.ruleId + entry.key" [(ngModel)]="draft.thresholds[entry.key]">
                          @for (a of entry.spec.allowed ?? []; track a) { <mat-option [value]="a">{{ a | humanize }}</mat-option> }
                        </mat-select>
                      </mat-form-field>
                    }
                    @default {
                      <mat-form-field subscriptSizing="dynamic">
                        <mat-label>{{ entry.spec.label ?? entry.key }}</mat-label>
                        <input matInput type="number" [name]="rule.ruleId + entry.key" [(ngModel)]="draft.thresholds[entry.key]"
                          [min]="entry.spec.min ?? null" [max]="entry.spec.max ?? null"
                          [step]="entry.spec.type === 'integer' ? 1 : 'any'" required />
                        @if (entry.spec.min !== undefined || entry.spec.max !== undefined) {
                          <mat-hint>{{ entry.spec.min }} – {{ entry.spec.max }}</mat-hint>
                        }
                      </mat-form-field>
                    }
                  }
                }
              </div>
              <div class="actions">
                <button mat-flat-button type="submit" [disabled]="saving() === rule.ruleId">Save version {{ rule.version + 1 }}</button>
                <button mat-button type="button" (click)="reset(rule)">Discard changes</button>
              </div>
            </form>
          }
        </mat-expansion-panel>
      }
    </mat-accordion>
  `,
  styles: `
    :host { display: block; padding: 16px; }
    .intro { margin: 0 0 12px; }
    .rule-id { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 0.8rem; color: var(--aiq-text-muted);
      margin-right: 8px; }
    .rule-form { display: flex; flex-direction: column; gap: 16px; }
    .row { display: flex; gap: 24px; align-items: center; flex-wrap: wrap; }
    .fields { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 16px; align-items: center; }
    .actions { display: flex; gap: 8px; }
  `,
})
export class AuditRulesAdminComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly snack = inject(MatSnackBar);

  readonly severities: Severity[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
  readonly rules = signal<AuditRule[]>([]);
  readonly error = signal<string | null>(null);
  readonly saving = signal<string | null>(null);
  drafts: Record<string, Draft> = {};

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.error.set(null);
    this.api.auditRules().subscribe({
      next: (rules) => {
        this.rules.set(rules);
        rules.forEach((r) => this.reset(r));
      },
      error: (err) => this.error.set(errorMessage(err, 'Audit rules could not be loaded.')),
    });
  }

  schemaEntries(rule: AuditRule): { key: string; spec: ThresholdSpec }[] {
    return Object.entries(rule.thresholdSchema).map(([key, spec]) => ({ key, spec }));
  }

  reset(rule: AuditRule): void {
    this.drafts[rule.ruleId] = {
      thresholds: structuredClone(rule.thresholds),
      enabled: rule.enabled,
      severity: rule.severity,
    };
  }

  save(rule: AuditRule): void {
    const draft = this.drafts[rule.ruleId];
    this.saving.set(rule.ruleId);
    this.api.updateAuditRule(rule.ruleId, draft).subscribe({
      next: (updated) => {
        this.saving.set(null);
        this.rules.update((list) => list.map((r) => (r.ruleId === updated.ruleId ? updated : r)));
        this.reset(updated);
        this.snack.open(`${updated.ruleId} saved as version ${updated.version}`, 'OK', { duration: 3000 });
      },
      error: (err) => {
        this.saving.set(null);
        this.snack.open(errorMessage(err, 'The rule could not be saved.'), 'Dismiss', { duration: 6000 });
      },
    });
  }
}
