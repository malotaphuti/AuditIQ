import { Pipe, PipeTransform } from '@angular/core';
import { RoleName } from '../core/models';

const LABELS: Record<RoleName, string> = {
  ADMIN: 'Administrator',
  AUDITOR: 'Auditor',
  AUDIT_MANAGER: 'Audit Manager',
};

@Pipe({ name: 'roleLabel' })
export class RoleLabelPipe implements PipeTransform {
  transform(role: RoleName | string | null | undefined): string {
    return role ? (LABELS[role as RoleName] ?? role) : '';
  }
}
