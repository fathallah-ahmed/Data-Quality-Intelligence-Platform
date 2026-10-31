import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { TableAnalysisView, VersionDetails } from '../../core/models';
import { EmptyState, LlmBadge } from '../../shared/components';
import { Icon, IconName } from '../../shared/icon';
import { insightList, insightRecords, insightText, recordText } from '../../shared/insight';

interface Card {
  title: string;
  tag?: string;
  text?: string;
  notes?: { label: string; text: string }[];
}

interface Group {
  id: string;
  icon: IconName;
  title: string;
  description: string;
  cards: Card[];
}

@Component({
  selector: 'app-insights-tab',
  imports: [EmptyState, LlmBadge, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './insights-tab.html',
  styleUrl: './insights-tab.css'
})
export class InsightsTab {
  readonly details = input.required<VersionDetails>();
  readonly selected = input<TableAnalysisView | null>(null);
  readonly selectTable = output<string>();

  readonly tables = computed(() => this.details().tables.filter((table) => table.changeStatus !== 'REMOVED' && table.rowCount !== null && table.rowCount !== undefined));
  readonly semantic = computed(() => this.selected()?.llmInterpretation?.['semantic_analysis']);
  readonly executiveSummary = computed(() => insightText(this.semantic(), 'executive_summary'));

  readonly groups = computed<Group[]>(() => {
    const semantic = this.semantic();
    const groups: Group[] = [
      {
        id: 'roles',
        icon: 'columns',
        title: 'Column roles',
        description: 'What each column means for the business and how to use it.',
        cards: insightRecords(semantic, 'column_roles').map((role) => ({
          title: recordText(role, 'column') || 'Column',
          tag: recordText(role, 'role'),
          text: recordText(role, 'description'),
          notes: notes(role, [['business_meaning', 'Business'], ['usage_notes', 'Usage']])
        }))
      },
      {
        id: 'contract',
        icon: 'checkCircle',
        title: 'Data contract',
        description: 'Rules the source should guarantee.',
        cards: insightRecords(semantic, 'data_contract_rules').map((rule) => ({
          title: recordText(rule, 'field') || 'Table',
          tag: recordText(rule, 'severity'),
          text: recordText(rule, 'rule')
        }))
      },
      {
        id: 'monitoring',
        icon: 'activity',
        title: 'Monitoring',
        description: 'Checks worth automating on every new version.',
        cards: insightRecords(semantic, 'monitoring_rules').map((rule) => ({
          title: recordText(rule, 'check') || 'Check',
          tag: recordText(rule, 'field'),
          text: recordText(rule, 'threshold'),
          notes: notes(rule, [['cadence', 'Cadence']])
        }))
      },
      {
        id: 'causes',
        icon: 'search',
        title: 'Root causes',
        description: 'Likely reasons behind the issues found.',
        cards: insightRecords(semantic, 'root_cause_hypotheses').map((cause) => ({
          title: recordText(cause, 'field') || 'Table',
          tag: recordText(cause, 'priority') ? `P${recordText(cause, 'priority')}` : '',
          text: recordText(cause, 'hypothesis'),
          notes: notes(cause, [['evidence', 'Evidence']])
        }))
      },
      {
        id: 'impacts',
        icon: 'alert',
        title: 'Downstream impacts',
        description: 'What breaks or degrades if the issues stay.',
        cards: insightRecords(semantic, 'downstream_impacts').map((impact) => ({
          title: recordText(impact, 'area') || 'Area',
          tag: recordText(impact, 'severity'),
          text: recordText(impact, 'impact')
        }))
      },
      {
        id: 'readiness',
        icon: 'gauge',
        title: 'Use-case readiness',
        description: 'Whether the table can be used as-is for common purposes.',
        cards: insightRecords(semantic, 'use_case_readiness').map((item) => ({
          title: recordText(item, 'use_case') || 'Use case',
          tag: recordText(item, 'status'),
          text: recordText(item, 'rationale')
        }))
      },
      {
        id: 'relations',
        icon: 'link',
        title: 'Relationship hypotheses',
        description: 'Possible links to other tables, to confirm with the data owners.',
        cards: insightRecords(semantic, 'relationship_hypotheses').map((relation) => ({
          title: `${recordText(relation, 'source_column') || 'column'} → ${recordText(relation, 'target_table') || 'table'}.${recordText(relation, 'target_column') || 'column'}`,
          tag: recordText(relation, 'confidence'),
          text: recordText(relation, 'reason')
        }))
      },
      {
        id: 'questions',
        icon: 'info',
        title: 'Questions for the data owner',
        description: 'Open points that the metrics alone cannot answer.',
        cards: insightList(semantic, 'data_owner_questions').map((question) => ({ title: question }))
      }
    ];
    return groups.filter((group) => group.cards.length > 0);
  });

  tone(tag: string | undefined): string | null {
    return tagTone(tag);
  }

  readonly portfolio = computed(() => {
    const global = this.details().globalAnalysis?.llmInterpretation;
    return [
      { id: 'patterns', title: 'Key patterns', items: insightList(global, 'portfolio_summary', 'key_portfolio_patterns') },
      { id: 'risks', title: 'Critical risks', items: insightList(global, 'portfolio_summary', 'critical_risks') },
      { id: 'findings', title: 'Cross-table findings', items: insightList(global, 'cross_dataset_findings') },
      { id: 'actions', title: 'Action plan', items: insightList(global, 'portfolio_action_plan') },
      { id: 'short', title: 'Short term', items: insightList(global, 'recommendations_by_time_horizon', 'short_term') },
      { id: 'medium', title: 'Medium term', items: insightList(global, 'recommendations_by_time_horizon', 'medium_term') },
      { id: 'long', title: 'Long term', items: insightList(global, 'recommendations_by_time_horizon', 'long_term') },
      { id: 'stakeholders', title: 'Questions for stakeholders', items: insightList(global, 'questions_for_stakeholders') }
    ].filter((group) => group.items.length > 0);
  });
}

/** Colour for severity / status tags returned by the LLM (high, blocked, ready…). */
export function tagTone(tag: string | undefined): string | null {
  const value = (tag ?? '').toLowerCase();
  if (/\b(critical|high|blocked|not[ _-]ready|fail(ed)?|p1)\b/.test(value)) {
    return 'danger';
  }
  if (/\b(medium|partial|caution|review|p2|conditional)\b/.test(value)) {
    return 'warning';
  }
  if (/\b(low|ready|ok|pass(ed)?|good|p3)\b/.test(value)) {
    return 'success';
  }
  return null;
}

function notes(record: Record<string, unknown>, keys: [string, string][]): { label: string; text: string }[] {
  return keys.map(([key, label]) => ({ label, text: recordText(record, key) })).filter((note) => note.text);
}
