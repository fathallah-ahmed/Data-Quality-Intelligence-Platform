import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { TableAnalysisView } from '../../core/models';
import { InfoTip, LlmBadge, ScoreRing, Skeleton, StatusBadge } from '../../shared/components';
import { qualityGrade, scoreTone, typeIcon } from '../../shared/format';
import { Icon, IconName } from '../../shared/icon';
import { insightList, insightText } from '../../shared/insight';
import { DQ_PIPES } from '../../shared/pipes';
import { associationPairs, columnProfiles, typeMix } from './table-profile';

type Section = 'summary' | 'columns' | 'missing' | 'associations' | 'insights';

@Component({
  selector: 'app-table-detail',
  imports: [StatusBadge, LlmBadge, InfoTip, Skeleton, ScoreRing, Icon, ...DQ_PIPES],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './table-detail.html',
  styleUrl: './table-detail.css'
})
export class TableDetail {
  readonly table = input.required<TableAnalysisView>();
  readonly detail = input<TableAnalysisView | null>(null);
  readonly loading = input(false);
  readonly failure = input<string | null>(null);
  readonly versionNumber = input(1);
  readonly expanded = signal<string | null>(null);

  readonly removed = computed(() => this.table().changeStatus === 'REMOVED');
  readonly pending = computed(() => !this.removed() && (this.table().rowCount === null || this.table().rowCount === undefined));
  readonly empty = computed(() => this.table().rowCount === 0);
  readonly scoreTone = computed(() => scoreTone(this.table().qualityScore));
  readonly grade = computed(() => qualityGrade(this.table().qualityScore));
  readonly types = computed(() => typeMix(this.table()));
  readonly profiles = computed(() => columnProfiles(this.table(), this.detail()));
  readonly missingColumns = computed(() =>
    this.profiles().filter((column) => (column.missingPercent ?? 0) > 0).sort((a, b) => (b.missingPercent ?? 0) - (a.missingPercent ?? 0)).slice(0, 15)
  );
  readonly associations = computed(() => associationPairs(this.detail()));
  readonly rawLoaded = computed(() => !!this.detail());

  readonly sections = computed(() => {
    const sections: { id: Section; label: string; icon: IconName }[] = [
      { id: 'summary', label: 'Summary', icon: 'overview' },
      { id: 'columns', label: 'Columns', icon: 'columns' }
    ];
    if (this.missingColumns().length > 0) {
      sections.push({ id: 'missing', label: 'Missing values', icon: 'alert' });
    }
    if (this.associations().length > 0) {
      sections.push({ id: 'associations', label: 'Associations', icon: 'link' });
    }
    sections.push({ id: 'insights', label: 'Insights', icon: 'sparkles' });
    return sections;
  });

  readonly interpretation = computed(() => this.table().llmInterpretation);
  readonly summaryText = computed(() =>
    insightText(this.interpretation(), 'interpretation', 'summary') || this.table().llmSummary || ''
  );
  readonly completeness = computed(() => insightText(this.interpretation(), 'quality_assessment', 'completeness'));
  readonly consistency = computed(() => insightText(this.interpretation(), 'quality_assessment', 'consistency'));
  readonly issues = computed(() => insightList(this.interpretation(), 'quality_assessment', 'issues'));
  readonly improvements = computed(() => insightList(this.interpretation(), 'improvement_areas'));
  readonly steps = computed(() => insightList(this.interpretation(), 'resolution_steps'));
  readonly customQuestion = computed(() => insightText(this.interpretation(), 'custom_analysis', 'question'));
  readonly customAnswer = computed(() => insightText(this.interpretation(), 'custom_analysis', 'answer'));

  toggle(name: string): void {
    this.expanded.update((current) => (current === name ? null : name));
  }

  scrollTo(id: Section): void {
    document.getElementById(`table-section-${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  typeIcon(label: string): IconName {
    return typeIcon(label);
  }

  barWidth(score: number): number {
    return Math.min(100, Math.abs(score) * 100);
  }
}
