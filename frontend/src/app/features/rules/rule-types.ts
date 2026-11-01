import { RuleFormat, RuleRequest, RuleType, RuleView, VersionDetails } from '../../core/models';

export interface RuleTypeInfo {
  type: RuleType;
  label: string;
  needsColumn: boolean;
  needsThreshold: boolean;
  /** The rule names a format (email, phone...). */
  needsFormat?: boolean;
  /** The rule compares its column with another column. */
  needsSecondColumn?: boolean;
  /** Shown next to the threshold field. */
  unit: string;
  defaultThreshold: number | null;
  help: string;
}

export const RULE_FORMATS: { value: RuleFormat; label: string }[] = [
  { value: 'EMAIL', label: 'an email address' },
  { value: 'PHONE', label: 'a phone number' },
  { value: 'URL', label: 'a web address' },
  { value: 'DATE', label: 'a date' },
  { value: 'NUMBER', label: 'a number' }
];

export function formatLabel(format: RuleFormat | string | null | undefined): string {
  return RULE_FORMATS.find((item) => item.value === format)?.label ?? String(format ?? '');
}

export const RULE_TYPES: RuleTypeInfo[] = [
  {
    type: 'NOT_NULL', label: 'Column has no missing values', needsColumn: true, needsThreshold: false, unit: '', defaultThreshold: null,
    help: 'Fails when any value of the column is empty.'
  },
  {
    type: 'MAX_MISSING_PERCENT', label: 'Column has at most … % missing', needsColumn: true, needsThreshold: true, unit: '%', defaultThreshold: 5,
    help: 'Fails when more than this share of the column is empty.'
  },
  {
    type: 'UNIQUE', label: 'Column values are unique', needsColumn: true, needsThreshold: false, unit: '', defaultThreshold: null,
    help: 'Fails when a value appears more than once (empty values are ignored). Use it for keys.'
  },
  {
    type: 'MIN_FORMAT_VALID_PERCENT', label: 'Column values follow a format', needsColumn: true, needsThreshold: true, needsFormat: true, unit: '% valid', defaultThreshold: 95,
    help: 'Fails when fewer than this share of the filled values look like an email, a phone number, a date... Empty values are ignored.'
  },
  {
    type: 'DATE_ORDER', label: 'One date is not after another', needsColumn: true, needsThreshold: true, needsSecondColumn: true, unit: '% of rows', defaultThreshold: 0,
    help: 'On rows where both dates are filled, fails when the first comes after the second (for example a closing date before the opening date).'
  },
  {
    type: 'MIN_ROWS', label: 'Table has at least … rows', needsColumn: false, needsThreshold: true, unit: 'rows', defaultThreshold: 1,
    help: 'Fails when the table is smaller than expected, e.g. after a truncated export.'
  },
  {
    type: 'MAX_DUPLICATE_PERCENT', label: 'Table has at most … % duplicate rows', needsColumn: false, needsThreshold: true, unit: '%', defaultThreshold: 1,
    help: 'Fails when too many rows are exact copies of another row.'
  },
  {
    type: 'MIN_QUALITY_SCORE', label: 'Table scores at least …', needsColumn: false, needsThreshold: true, unit: '/ 100', defaultThreshold: 90,
    help: 'Fails when the table quality score drops below the limit.'
  },
  {
    type: 'MAX_ROW_DROP_PERCENT', label: 'Table loses at most … % of its rows', needsColumn: false, needsThreshold: true, unit: '%', defaultThreshold: 10,
    help: 'Compares with the previous version and fails when the table shrinks too much.'
  }
];

export function ruleTypeInfo(type: RuleType | string): RuleTypeInfo {
  return RULE_TYPES.find((item) => item.type === type) ?? RULE_TYPES[0];
}

function num(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(1);
}

/** The same sentence the backend writes for a rule. */
export function describeRule(rule: Pick<RuleRequest, 'type' | 'tableName' | 'columnName' | 'threshold'> & Partial<Pick<RuleRequest, 'format' | 'secondColumn'>>): string {
  const target = rule.tableName + (rule.columnName ? `.${rule.columnName}` : '');
  const limit = rule.threshold === null || rule.threshold === undefined ? '' : num(rule.threshold);
  switch (rule.type) {
    case 'MIN_FORMAT_VALID_PERCENT':
      return `${target} has at least ${limit}% of its values as ${formatLabel(rule.format)}`;
    case 'DATE_ORDER':
      return `${target} is not after ${rule.tableName}.${rule.secondColumn ?? 'column'}${rule.threshold ? ` (up to ${limit}% of rows may break this)` : ''}`;
    case 'NOT_NULL':
      return `${target} has no missing values`;
    case 'MAX_MISSING_PERCENT':
      return `${target} has at most ${limit}% missing values`;
    case 'UNIQUE':
      return `${target} is unique`;
    case 'MIN_ROWS':
      return `${rule.tableName} has at least ${limit} rows`;
    case 'MAX_DUPLICATE_PERCENT':
      return `${rule.tableName} has at most ${limit}% duplicate rows`;
    case 'MIN_QUALITY_SCORE':
      return `${rule.tableName} scores at least ${limit}`;
    case 'MAX_ROW_DROP_PERCENT':
      return `${rule.tableName} loses at most ${limit}% of its rows between versions`;
  }
}

const MAX_SUGGESTIONS = 12;

/**
 * Rules worth adding, derived from how the data looks today: keys must stay unique and filled,
 * columns that are (almost) complete should stay that way, and tables should not shrink or lose
 * quality. Rules that already exist are left out.
 */
export function suggestRules(details: VersionDetails, existing: Pick<RuleView, 'type' | 'tableName' | 'columnName' | 'format' | 'secondColumn'>[]): RuleRequest[] {
  const taken = new Set(existing.map((rule) => ruleKey(rule)));
  const tables = details.tables.filter((table) => table.changeStatus !== 'REMOVED' && (table.rowCount ?? 0) > 0);
  const keys: RuleRequest[] = [];
  const identifiers: RuleRequest[] = [];
  const quality: RuleRequest[] = [];
  const size: RuleRequest[] = [];
  const completeness: RuleRequest[] = [];

  for (const table of tables) {
    const rows = table.rowCount ?? 0;
    for (const column of table.columns) {
      const missing = column.missingCount ?? 0;
      const isKey = missing === 0 && column.uniqueCount === rows && rows > 1;
      if (isKey) {
        keys.push({ tableName: table.tableName, columnName: column.columnName, type: 'UNIQUE', severity: 'CRITICAL' });
        keys.push({ tableName: table.tableName, columnName: column.columnName, type: 'NOT_NULL', severity: 'CRITICAL' });
      } else if (missing === 0 && /(^|_)id$/i.test(column.columnName)) {
        identifiers.push({ tableName: table.tableName, columnName: column.columnName, type: 'NOT_NULL', severity: 'WARNING' });
      } else if ((column.missingPercentage ?? 0) > 0 && (column.missingPercentage ?? 0) < 50) {
        const limit = Math.min(100, Math.ceil(column.missingPercentage ?? 0) + 5);
        completeness.push({ tableName: table.tableName, columnName: column.columnName, type: 'MAX_MISSING_PERCENT', threshold: limit, severity: 'WARNING' });
      }
    }
    if (table.qualityScore !== null && table.qualityScore !== undefined) {
      const floor = Math.max(0, Math.floor((table.qualityScore - 5) / 5) * 5);
      quality.push({ tableName: table.tableName, type: 'MIN_QUALITY_SCORE', threshold: floor, severity: 'WARNING' });
    }
    size.push({ tableName: table.tableName, type: 'MIN_ROWS', threshold: Math.max(1, Math.floor(rows / 2)), severity: 'WARNING' });
    if (table.changeStatus !== 'NEW') {
      size.push({ tableName: table.tableName, type: 'MAX_ROW_DROP_PERCENT', threshold: 20, severity: 'CRITICAL' });
    }
  }

  return [...keys, ...identifiers, ...quality, ...size, ...completeness]
    .filter((rule) => !taken.has(ruleKey(rule)))
    .slice(0, MAX_SUGGESTIONS);
}

export function ruleKey(rule: { type: string; tableName: string; columnName?: string | null; format?: string | null; secondColumn?: string | null }): string {
  return `${rule.type}|${rule.tableName}|${rule.columnName ?? ''}|${rule.format ?? ''}|${rule.secondColumn ?? ''}`;
}
