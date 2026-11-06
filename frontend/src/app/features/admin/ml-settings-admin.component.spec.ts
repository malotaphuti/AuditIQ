import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { MlSettings } from '../../core/models';
import { MlSettingsAdminComponent } from './ml-settings-admin.component';

const SETTINGS: MlSettings = {
  anomalyThreshold: 0.55, mediumThreshold: 0.6, highThreshold: 0.65, exceptionMinPriority: 'MEDIUM',
  updatedBy: null, updatedAt: null,
};

describe('MlSettingsAdminComponent', () => {
  let fixture: ComponentFixture<MlSettingsAdminComponent>;
  let api: jasmine.SpyObj<ApiService>;

  beforeEach(() => {
    api = jasmine.createSpyObj<ApiService>('ApiService', ['mlSettings', 'updateMlSettings']);
    api.mlSettings.and.returnValue(of(SETTINGS));
    TestBed.configureTestingModule({
      imports: [MlSettingsAdminComponent],
      providers: [{ provide: ApiService, useValue: api }],
    });
    fixture = TestBed.createComponent(MlSettingsAdminComponent);
    fixture.detectChanges();
  });

  it('refuses unordered thresholds before calling the API', () => {
    const c = fixture.componentInstance;
    expect(c.ordered()).toBeTrue();
    c.draft!.anomalyThreshold = 0.7;
    expect(c.ordered()).toBeFalse();
    c.save();
    expect(api.updateMlSettings).not.toHaveBeenCalled();
  });

  it('saves ordered thresholds', () => {
    const c = fixture.componentInstance;
    api.updateMlSettings.and.returnValue(of({ ...SETTINGS, highThreshold: 0.7, updatedBy: 'admin' }));
    c.draft!.highThreshold = 0.7;
    c.save();
    expect(api.updateMlSettings).toHaveBeenCalledWith({
      anomalyThreshold: 0.55, mediumThreshold: 0.6, highThreshold: 0.7, exceptionMinPriority: 'MEDIUM',
    });
    expect(c.current()?.updatedBy).toBe('admin');
  });
});
