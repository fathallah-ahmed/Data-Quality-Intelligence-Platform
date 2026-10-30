import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { VersionDetails } from '../../core/models';
import { EmptyState, InfoTip, LlmBadge, StatusBadge } from '../../shared/components';
import { scoreTone, statusTone } from '../../shared/format';
import { DQ_PIPES } from '../../shared/pipes';
import { PipelineProgress } from './pipeline-progress';
import {
  businessView,
  changeCounts,
  expectedTables,
  finalAssessment,
  qualityPosture,
  readyTables,
  totalColumns,
  totalRows
} from './version-metrics';

@Component({
  selector: 'app-overview-tab',
  imports: [PipelineProgress, StatusBadge, LlmBadge, InfoTip, EmptyState, RouterLink, ...DQ_PIPES],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './overview-tab.html',
  styleUrl: './overview-tab.css'
})
export class OverviewTab {
  readonly details = input.required<VersionDetails>();
  readonly now = input(Date.now());
  readonly retrying = input(false);
  readonly retry = output<void>();

  readonly ready = computed(() => readyTables(this.details()));
  readonly expected = computed(() => Math.max(expectedTables(this.details()), this.ready().length));
  readonly rows = computed(() => totalRows(this.details()));
  readonly columns = computed(() => totalColumns(this.details()));
  readonly score = computed(() => this.details().qualityScore ?? null);
  readonly scoreTone = computed(() => scoreTone(this.score()));
  readonly maxRows = computed(() => Math.max(1, ...this.ready().map((table) => table.rowCount ?? 0)));
  readonly maxColumns = computed(() => Math.max(1, ...this.ready().map((table) => table.columnCount ?? 0)));
  readonly changes = computed(() => changeCounts(this.details()).map((item) => ({ ...item, tone: statusTone(item.status) })));
  readonly changeTotal = computed(() => Math.max(1, this.details().tables.length));
  readonly hasPrevious = computed(() => this.details().version.versionNumber > 1);
  readonly business = computed(() => businessView(this.details()));
  readonly posture = computed(() => qualityPosture(this.details()));
  readonly assessment = computed(() => finalAssessment(this.details()));

  readonly scoreHelp = 'Each table scores 100 minus its average missing-value % across columns minus its duplicate-row %. '
    + 'The version score is the average of the table scores weighted by row count. 90+ is good, 70–90 needs review, below 70 is poor.';
}
