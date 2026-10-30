import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { environment } from '../../../environments/environment';
import { VersionDetails } from '../../core/models';
import { isActiveStatus } from '../../shared/format';
import { DQ_PIPES } from '../../shared/pipes';
import { StatusBadge } from '../../shared/components';
import { expectedTables, readyTables } from './version-metrics';

type StepState = 'done' | 'active' | 'pending' | 'failed' | 'warning';

interface Step {
  label: string;
  detail?: string;
  state: StepState;
}

const PENDING_HINT_AFTER_MS = 2 * 60 * 1000;

/** Upload → Airflow → Tables x/y → Global → Done, with errors, failed tables and Retry. */
@Component({
  selector: 'app-pipeline-progress',
  imports: [StatusBadge, ...DQ_PIPES],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './pipeline-progress.html',
  styleUrl: './pipeline-progress.css'
})
export class PipelineProgress {
  readonly details = input.required<VersionDetails>();
  /** Current time, ticking while the run is active so the elapsed time updates. */
  readonly now = input(Date.now());
  readonly retrying = input(false);
  readonly retry = output<void>();
  readonly airflowUiUrl = environment.airflowUiUrl;

  readonly status = computed(() => this.details().version.status.toUpperCase());
  readonly active = computed(() => isActiveStatus(this.status()));
  readonly ready = computed(() => readyTables(this.details()).length);
  readonly expected = computed(() => Math.max(expectedTables(this.details()), this.ready()));
  readonly failedTables = computed(() => this.details().failedTables ?? []);
  readonly canRetry = computed(() => this.status() === 'FAILED' || this.status() === 'PARTIAL');

  readonly startedAt = computed(() => {
    const version = this.details().version;
    return Date.parse(version.startedAt ?? version.uploadedAt);
  });

  readonly elapsedMs = computed(() => {
    const finished = this.details().version.finishedAt;
    const end = this.active() || !finished ? this.now() : Date.parse(finished);
    return Math.max(0, end - this.startedAt());
  });

  readonly showPendingHint = computed(() =>
    this.status() === 'PENDING' && this.now() - Date.parse(this.details().version.uploadedAt) > PENDING_HINT_AFTER_MS
  );

  readonly steps = computed<Step[]>(() => {
    const status = this.status();
    const details = this.details();
    const ready = this.ready();
    const expected = this.expected();
    const failed = status === 'FAILED';
    const finished = status === 'COMPLETED' || status === 'PARTIAL';
    const tablesDone = finished || (expected > 0 && ready >= expected);
    const hasGlobal = !!details.globalAnalysis;
    return [
      { label: 'Upload', state: 'done' },
      {
        label: 'Airflow',
        state: status === 'PENDING' ? 'active' : failed && ready === 0 ? 'failed' : 'done'
      },
      {
        label: 'Tables',
        detail: `${ready}/${expected}`,
        state: this.failedTables().length > 0 && (finished || failed) ? 'warning'
          : tablesDone ? 'done'
          : failed ? 'failed'
          : status === 'RUNNING' ? 'active'
          : 'pending'
      },
      {
        label: 'Global',
        state: hasGlobal ? 'done' : failed ? 'failed' : tablesDone && status === 'RUNNING' ? 'active' : 'pending'
      },
      {
        label: 'Done',
        state: status === 'COMPLETED' ? 'done' : status === 'PARTIAL' ? 'warning' : failed ? 'failed' : 'pending'
      }
    ];
  });

  readonly percent = computed(() => {
    const status = this.status();
    if (!this.active()) {
      return 100;
    }
    const expected = this.expected();
    const tableShare = expected > 0 ? (this.ready() / expected) * 80 : 0;
    return Math.round(status === 'PENDING' ? 5 : 12 + tableShare);
  });
}
