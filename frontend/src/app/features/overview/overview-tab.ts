import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { VersionDetails } from '../../core/models';
import { EmptyState, InfoTip, LlmBadge, ScoreRing, StatusBadge } from '../../shared/components';
import { isActiveStatus, qualityGrade, scoreTone, statusLabel, statusTone } from '../../shared/format';
import { Icon } from '../../shared/icon';
import { DQ_PIPES } from '../../shared/pipes';
import { PipelineProgress } from './pipeline-progress';
import {
  aiCoverage,
  businessView,
  changeCounts,
  columnsToReview,
  expectedTables,
  finalAssessment,
  qualityDimensions,
  qualityPosture,
  readyTables,
  SCORE_DEFINITION,
  tableMissingPercent,
  totalColumns,
  totalRows
} from './version-metrics';

@Component({
  selector: 'app-overview-tab',
  imports: [PipelineProgress, StatusBadge, LlmBadge, InfoTip, EmptyState, ScoreRing, RouterLink, Icon, ...DQ_PIPES],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './overview-tab.html',
  styleUrl: './overview-tab.css'
})
export class OverviewTab {
  readonly details = input.required<VersionDetails>();
  readonly now = input(Date.now());
  readonly retrying = input(false);
  readonly retry = output<void>();

  readonly active = computed(() => isActiveStatus(this.details().version.status));
  readonly rules = computed(() => this.details().ruleSummary ?? null);
  readonly ready = computed(() => readyTables(this.details()));
  readonly expected = computed(() => Math.max(expectedTables(this.details()), this.ready().length));
  readonly rows = computed(() => totalRows(this.details()));
  readonly columns = computed(() => totalColumns(this.details()));
  readonly missingCells = computed(() => this.ready().reduce((sum, table) => sum + (table.missingCount ?? 0), 0));
  readonly duplicateRows = computed(() => this.ready().reduce((sum, table) => sum + (table.duplicateCount ?? 0), 0));
  readonly score = computed(() => this.details().qualityScore ?? null);
  readonly grade = computed(() => qualityGrade(this.score()));
  readonly scoreTone = computed(() => scoreTone(this.score()));

  readonly dimensions = computed(() => [
    ...qualityDimensions(this.details()),
    {
      label: 'AI coverage',
      value: aiCoverage(this.details()),
      detail: '',
      hint: 'Share of tables whose insights come from a validated LLM answer.'
    }
  ].map((dimension) => ({ ...dimension, tone: scoreTone(dimension.value) })));

  readonly health = computed(() => this.details().tables.map((table) => ({
    table,
    missing: tableMissingPercent(table),
    tone: scoreTone(table.qualityScore),
    ready: table.rowCount !== null && table.rowCount !== undefined
  })));

  readonly changes = computed(() => changeCounts(this.details()).map((item) => ({ ...item, tone: statusTone(item.status), label: statusLabel(item.status) })));
  readonly changeTotal = computed(() => Math.max(1, this.details().tables.length));
  readonly hasPrevious = computed(() => this.details().version.versionNumber > 1);
  readonly review = computed(() => columnsToReview(this.details()));
  readonly business = computed(() => businessView(this.details()));
  readonly posture = computed(() => qualityPosture(this.details()));
  readonly assessment = computed(() => finalAssessment(this.details()));

  readonly scoreHelp = SCORE_DEFINITION;
}
