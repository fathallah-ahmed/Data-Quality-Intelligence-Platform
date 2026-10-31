import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { ApiService, apiErrorMessage } from '../../core/api.service';
import { TableDiff, VersionDetails, VersionDiff, VersionSummary } from '../../core/models';
import { EmptyState, ScoreRing, Skeleton } from '../../shared/components';
import { Delta, DeltaPolarity, deltaInfo, formatNumber, formatPercent, formatScore, qualityGrade, relativeTime } from '../../shared/format';
import { Icon } from '../../shared/icon';
import { DQ_PIPES } from '../../shared/pipes';

interface Metric {
  label: string;
  from: string;
  to: string;
  delta: Delta;
}

interface TableRow {
  diff: TableDiff;
  metrics: Metric[];
  hasDetail: boolean;
  valuesOnly: boolean;
}

function metric(label: string, from: number | null | undefined, to: number | null | undefined, polarity: DeltaPolarity, format: (value: number) => string, digits = 0): Metric {
  return {
    label,
    from: from === null || from === undefined ? '–' : format(from),
    to: to === null || to === undefined ? '–' : format(to),
    delta: deltaInfo(from, to, polarity, digits)
  };
}

/** Compare this version with an older one: score, rows and schema drift per table. */
@Component({
  selector: 'app-compare-tab',
  imports: [EmptyState, ScoreRing, Skeleton, Icon, ...DQ_PIPES],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './compare-tab.html',
  styleUrl: './compare-tab.css'
})
export class CompareTab {
  private readonly api = inject(ApiService);
  readonly datasetId = input.required<number>();
  readonly details = input.required<VersionDetails>();
  readonly versions = input<VersionSummary[]>([]);
  /** The `?against=` version id from the URL, when chosen. */
  readonly against = input<number | null>(null);
  readonly againstChange = output<number>();

  readonly diff = signal<VersionDiff | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly firstVersion = computed(() => this.details().version.versionNumber <= 1);

  /** Older versions of the dataset, newest first: what this version can be compared with. */
  readonly olderVersions = computed(() => this.versions().filter((version) => version.versionNumber < this.details().version.versionNumber));
  readonly selectedAgainst = computed(() => this.against() ?? this.diff()?.from.id ?? this.olderVersions()[0]?.id ?? null);

  private readonly requestKey = computed(() => `${this.details().version.id}:${this.details().version.status}:${this.against() ?? ''}`);

  readonly scoreDelta = computed(() => deltaInfo(this.diff()?.scoreFrom, this.diff()?.scoreTo, 'higher-is-better', 1));
  readonly gradeFrom = computed(() => qualityGrade(this.diff()?.scoreFrom).grade);
  readonly gradeTo = computed(() => qualityGrade(this.diff()?.scoreTo).grade);
  readonly rowsDelta = computed(() => deltaInfo(this.diff()?.rowsFrom, this.diff()?.rowsTo, 'neutral'));

  readonly changed = computed<TableRow[]>(() => (this.diff()?.tables ?? []).filter((table) => table.change !== 'UNCHANGED').map(toRow));
  readonly unchanged = computed(() => (this.diff()?.tables ?? []).filter((table) => table.change === 'UNCHANGED').map((table) => table.tableName));

  readonly headline = computed(() => {
    const diff = this.diff();
    if (!diff) {
      return '';
    }
    const parts = [
      diff.tablesChanged && `${diff.tablesChanged} changed`,
      diff.tablesAdded && `${diff.tablesAdded} added`,
      diff.tablesRemoved && `${diff.tablesRemoved} removed`,
      diff.tablesUnchanged && `${diff.tablesUnchanged} unchanged`
    ].filter(Boolean);
    return parts.join(' · ') || 'No tables';
  });

  constructor() {
    effect(() => {
      this.requestKey();
      untracked(() => this.load());
    });
  }

  load(): void {
    if (this.firstVersion()) {
      this.diff.set(null);
      this.error.set(null);
      return;
    }
    this.loading.set(true);
    this.api.diff(this.datasetId(), this.details().version.id, this.against()).subscribe({
      next: (diff) => {
        this.diff.set(diff);
        this.error.set(null);
        this.loading.set(false);
      },
      error: (error) => {
        this.diff.set(null);
        this.error.set(apiErrorMessage(error, 'Could not compare these versions.'));
        this.loading.set(false);
      }
    });
  }

  select(value: string): void {
    this.againstChange.emit(Number(value));
  }

  when(version: VersionSummary): string {
    return relativeTime(version.uploadedAt);
  }

  percent(value: number | null): string {
    return value === null ? '' : `${value > 0 ? '+' : '−'}${formatPercent(Math.abs(value), 0)}`;
  }
}

function toRow(diff: TableDiff): TableRow {
  const metrics = [
    metric('Rows', diff.rowsFrom, diff.rowsTo, 'neutral', (value) => formatNumber(value)),
    metric('Columns', diff.columnsFrom, diff.columnsTo, 'neutral', (value) => formatNumber(value)),
    metric('Score', diff.scoreFrom, diff.scoreTo, 'higher-is-better', (value) => formatScore(value), 1),
    metric('Missing cells', diff.missingFrom, diff.missingTo, 'lower-is-better', (value) => formatNumber(value)),
    metric('Duplicate rows', diff.duplicatesFrom, diff.duplicatesTo, 'lower-is-better', (value) => formatNumber(value))
  ];
  const hasDetail = diff.columnsAdded.length + diff.columnsRemoved.length + diff.typeChanges.length + diff.missingShifts.length > 0;
  // Same counts and schema, yet the content hash differs: values were edited.
  const valuesOnly = diff.change === 'CHANGED' && diff.contentChanged && metrics.every((item) => item.delta.direction === 'flat') && !hasDetail;
  return { diff, metrics, hasDetail, valuesOnly };
}
