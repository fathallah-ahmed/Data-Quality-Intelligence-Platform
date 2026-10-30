import {
  ClassDiagramEntity,
  ClassDiagramRelation,
  ColumnMetricView,
  DataModelMap,
  DataModelNode,
  TableAnalysisView
} from '../../core/models';
import { isNumericType, typeLabel } from '../../shared/format';

/**
 * Pure functions that turn table results into a class-diagram style data model:
 * relations (pipeline value overlap first, column-name inference only as a fallback),
 * entities with key roles, and a grid layout with routed edges.
 */

export function modelTables(tables: TableAnalysisView[]): TableAnalysisView[] {
  return tables.filter((table) => table.changeStatus !== 'REMOVED');
}

export function buildRelations(tables: TableAnalysisView[]): ClassDiagramRelation[] {
  const deterministic = deterministicRelations(tables);
  return deterministic.length > 0 ? deterministic : inferRelationsFromNames(tables);
}

export function buildEntities(tables: TableAnalysisView[], relations: ClassDiagramRelation[]): ClassDiagramEntity[] {
  const foreignKeys = new Set(relations.map((relation) => `${relation.sourceTable}.${relation.sourceColumn}`));
  return tables.map((table) => ({
    tableName: table.tableName,
    rowCount: table.rowCount ?? 0,
    columns: table.columns.map((column) => ({
      name: column.columnName,
      type: typeLabel(column.dataType),
      role: columnRole(table, column, foreignKeys)
    }))
  }));
}

function deterministicRelations(tables: TableAnalysisView[]): ClassDiagramRelation[] {
  const available = new Set(tables.map((table) => table.tableName));
  const seen = new Set<string>();
  const relations: ClassDiagramRelation[] = [];
  for (const table of tables) {
    for (const raw of table.relationships ?? []) {
      const relation: ClassDiagramRelation = {
        sourceTable: String(raw['source_table'] ?? ''),
        sourceColumn: String(raw['source_column'] ?? ''),
        targetTable: String(raw['target_table'] ?? ''),
        targetColumn: String(raw['target_column'] ?? ''),
        confidence: String(raw['confidence'] ?? '').toLowerCase() === 'high' ? 'high' : 'medium',
        reason: String(raw['reason'] ?? '').trim() || 'Detected from value overlap before LLM enrichment.',
        origin: 'values',
        coverage: numberOrUndefined(raw['coverage']),
        overlapCount: numberOrUndefined(raw['overlap_count']),
        sourceDistinctCount: numberOrUndefined(raw['source_distinct_count']),
        targetDistinctCount: numberOrUndefined(raw['target_distinct_count'])
      };
      const key = relationKey(relation);
      if (!relation.sourceColumn || !relation.targetColumn || !available.has(relation.sourceTable)
        || !available.has(relation.targetTable) || seen.has(key)) {
        continue;
      }
      seen.add(key);
      relations.push(relation);
    }
  }
  return relations.sort(byRelationSource);
}

export function inferRelationsFromNames(tables: TableAnalysisView[]): ClassDiagramRelation[] {
  const relations: ClassDiagramRelation[] = [];
  const seen = new Set<string>();
  const add = (source: TableAnalysisView, sourceColumn: ColumnMetricView, target: TableAnalysisView, targetColumn: ColumnMetricView,
    confidence: 'high' | 'medium', reason: string) => {
    const relation: ClassDiagramRelation = {
      sourceTable: source.tableName,
      sourceColumn: sourceColumn.columnName,
      targetTable: target.tableName,
      targetColumn: targetColumn.columnName,
      confidence,
      reason,
      origin: 'names'
    };
    if (!seen.has(relationKey(relation))) {
      seen.add(relationKey(relation));
      relations.push(relation);
    }
  };

  for (const source of tables) {
    for (const sourceColumn of source.columns) {
      if (!isIdentifierColumn(sourceColumn.columnName) || isOwnIdentifier(source, sourceColumn)) {
        continue;
      }
      const sourceKey = normalizeIdentifier(sourceColumn.columnName);
      const sourceEntity = columnEntityToken(sourceColumn.columnName);
      for (const target of tables) {
        if (target.tableName === source.tableName) {
          continue;
        }
        const exact = target.columns.find((column) => normalizeIdentifier(column.columnName) === sourceKey && isPrimaryKeyCandidate(target, column));
        if (exact) {
          add(source, sourceColumn, target, exact, 'high', 'Same identifier column name as a key of the target table.');
          continue;
        }
        const targetPrimary = primaryColumnCandidate(target);
        if (sourceEntity && targetPrimary && tokensMatch(sourceEntity, entityToken(target.tableName))) {
          add(source, sourceColumn, target, targetPrimary, isPrimaryKeyCandidate(target, targetPrimary) ? 'high' : 'medium',
            'The identifier name matches the target table name.');
        }
      }
    }
  }
  return relations.sort(byRelationSource);
}

/** UML-style multiplicities from distinct counts and missing values. */
export function relationCardinality(tables: TableAnalysisView[], relation: ClassDiagramRelation): { source: string; target: string } {
  const sourceTable = tables.find((table) => table.tableName === relation.sourceTable);
  const targetTable = tables.find((table) => table.tableName === relation.targetTable);
  const sourceColumn = sourceTable?.columns.find((column) => column.columnName === relation.sourceColumn);
  const targetColumn = targetTable?.columns.find((column) => column.columnName === relation.targetColumn);
  const sourceMissing = sourceColumn?.missingCount ?? 0;
  const sourceNonNull = Math.max(0, (sourceTable?.rowCount ?? 0) - sourceMissing);
  const sourceDistinct = relation.sourceDistinctCount || sourceColumn?.uniqueCount || 0;
  const targetDistinct = relation.targetDistinctCount || targetColumn?.uniqueCount || 0;
  const optional = sourceMissing > 0 ? '0..1' : '1..1';
  if (sourceNonNull > 0 && sourceDistinct >= sourceNonNull * 0.98) {
    return { source: optional, target: optional };
  }
  const everyTargetUsed = targetDistinct > 0 && sourceDistinct >= targetDistinct;
  return { source: everyTargetUsed && sourceMissing === 0 ? '1..*' : '0..*', target: optional };
}

// ------------------------------------------------------------------ layout

const NODE_WIDTH = 310;
const HEADER_HEIGHT = 46;
const FIELD_GAP = 17;
const NODE_PADDING_BOTTOM = 18;
const MIN_NODE_HEIGHT = 126;
const GAP_X = 96;
const MARGIN = 88;

export function buildDataModelMap(tables: TableAnalysisView[], entities: ClassDiagramEntity[], relations: ClassDiagramRelation[]): DataModelMap {
  const ordered = orderByDegree(entities, relations);
  const columnCount = Math.max(1, Math.min(ordered.length || 1, Math.ceil(Math.sqrt(Math.max(1, ordered.length)))));
  const rows: ClassDiagramEntity[][] = [];
  ordered.forEach((entity, index) => {
    const row = Math.floor(index / columnCount);
    rows[row] = [...(rows[row] ?? []), entity];
  });
  const positions = new Map<string, { row: number; column: number }>();
  rows.forEach((row, rowIndex) => row.forEach((entity, columnIndex) => positions.set(entity.tableName, { row: rowIndex, column: columnIndex })));

  const laneCounts = new Map<string, number>();
  const laneIndexes = new Map<number, number>();
  relations.forEach((relation, index) => {
    const source = positions.get(relation.sourceTable);
    const target = positions.get(relation.targetTable);
    if (!source || !target) {
      return;
    }
    const key = laneKey(source.row, target.row);
    laneIndexes.set(index, laneCounts.get(key) ?? 0);
    laneCounts.set(key, (laneCounts.get(key) ?? 0) + 1);
  });
  const gapY = Math.max(130, 46 + Math.max(1, ...laneCounts.values()) * 28);
  const rowHeights = rows.map((row) =>
    Math.max(...row.map((entity) => Math.max(MIN_NODE_HEIGHT, HEADER_HEIGHT + 25 + entity.columns.length * FIELD_GAP + NODE_PADDING_BOTTOM)))
  );
  const rowTops: number[] = [];
  rowHeights.forEach((_height, index) => rowTops.push(index === 0 ? MARGIN : rowTops[index - 1] + rowHeights[index - 1] + gapY));

  const nodes: DataModelNode[] = rows.flatMap((row, rowIndex) => row.map((entity, columnIndex) => ({
    id: entity.tableName,
    label: compactText(entity.tableName, 24),
    subtitle: `${entity.rowCount.toLocaleString('en-US')} rows · ${entity.columns.length} fields`,
    x: MARGIN + columnIndex * (NODE_WIDTH + GAP_X),
    y: rowTops[rowIndex],
    width: NODE_WIDTH,
    height: rowHeights[rowIndex],
    row: rowIndex,
    column: columnIndex,
    fields: entity.columns.map((field, fieldIndex) => ({
      name: compactText(field.name, 27),
      type: compactText(field.type, 14),
      role: field.role,
      y: HEADER_HEIGHT + 25 + fieldIndex * FIELD_GAP
    }))
  })));

  const nodeById = new Map(nodes.map((node) => [node.id, node]));
  const bySource = groupBy(relations, (relation) => relation.sourceTable);
  const byTarget = groupBy(relations, (relation) => relation.targetTable);
  const edges = relations.flatMap((relation, index) => {
    const source = nodeById.get(relation.sourceTable);
    const target = nodeById.get(relation.targetTable);
    if (!source || !target) {
      return [];
    }
    const sourceSiblings = bySource.get(relation.sourceTable) ?? [];
    const targetSiblings = byTarget.get(relation.targetTable) ?? [];
    const points = edgePoints(
      source, target,
      Math.max(0, sourceSiblings.indexOf(relation)), Math.max(1, sourceSiblings.length),
      Math.max(0, targetSiblings.indexOf(relation)), Math.max(1, targetSiblings.length),
      rowTops, rowHeights, gapY,
      laneIndexes.get(index) ?? 0, laneCounts.get(laneKey(source.row, target.row)) ?? 1
    );
    const cardinality = relationCardinality(tables, relation);
    return [{
      id: `${relationKey(relation)}-${index}`,
      sourceTable: relation.sourceTable,
      targetTable: relation.targetTable,
      label: compactText(relation.sourceColumn, 18),
      labelWidth: Math.max(58, Math.min(140, compactText(relation.sourceColumn, 18).length * 7 + 18)),
      confidence: relation.confidence,
      reason: relation.reason,
      ...points,
      sourceCardinality: cardinality.source,
      targetCardinality: cardinality.target
    }];
  });

  const lastRow = Math.max(0, rowHeights.length - 1);
  const columnsUsed = Math.max(1, Math.min(columnCount, ordered.length || 1));
  return {
    width: MARGIN * 2 + columnsUsed * NODE_WIDTH + Math.max(0, columnsUsed - 1) * GAP_X,
    height: (rowTops[lastRow] ?? MARGIN) + (rowHeights[lastRow] ?? MIN_NODE_HEIGHT) + MARGIN + gapY,
    nodes,
    edges
  };
}

function edgePoints(
  source: DataModelNode, target: DataModelNode,
  sourceIndex: number, sourceCount: number, targetIndex: number, targetCount: number,
  rowTops: number[], rowHeights: number[], rowGap: number, laneIndex: number, laneCount: number
) {
  const leftToRight = source.column < target.column || (source.column === target.column && sourceIndex % 2 === 0);
  const x1 = leftToRight ? source.x + source.width : source.x;
  const x2 = leftToRight ? target.x : target.x + target.width;
  const y1 = source.y + 58 + ((sourceIndex + 1) / (sourceCount + 1)) * Math.max(24, source.height - 76);
  const y2 = target.y + 58 + ((targetIndex + 1) / (targetCount + 1)) * Math.max(24, target.height - 76);
  const sourceGutter = leftToRight ? x1 + 24 + sourceIndex * 12 + laneIndex * 3 : x1 - 24 - sourceIndex * 12 - laneIndex * 3;
  const targetGutter = leftToRight ? x2 - 24 - targetIndex * 12 - laneIndex * 3 : x2 + 24 + targetIndex * 12 + laneIndex * 3;
  const laneStep = Math.min(34, Math.max(20, (rowGap - 38) / Math.max(1, laneCount)));
  const laneOffset = 22 + laneIndex * laneStep;
  let laneY: number;
  if (source.row === target.row) {
    laneY = rowTops[source.row] + rowHeights[source.row] + Math.min(rowGap - 16, laneOffset);
  } else {
    const upper = Math.min(source.row, target.row);
    const upperBottom = rowTops[upper] + rowHeights[upper];
    const lowerTop = rowTops[Math.max(source.row, target.row)];
    laneY = upperBottom + Math.min(Math.max(18, lowerTop - upperBottom - 18), laneOffset);
  }
  return {
    path: `M ${x1} ${y1} L ${sourceGutter} ${y1} L ${sourceGutter} ${laneY} L ${targetGutter} ${laneY} L ${targetGutter} ${y2} L ${x2} ${y2}`,
    labelX: (sourceGutter + targetGutter) / 2,
    labelY: laneY - 8,
    sourceCardinalityX: leftToRight ? x1 + 20 : x1 - 20,
    sourceCardinalityY: y1 - 7,
    targetCardinalityX: leftToRight ? x2 - 20 : x2 + 20,
    targetCardinalityY: y2 - 7
  };
}

/** Standalone SVG file of the diagram (light colours, for documents). */
export function dataModelSvg(model: DataModelMap): string {
  const edges = model.edges.map((edge) => `
    <path d="${xml(edge.path)}" fill="none" stroke="#2f766b" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" marker-end="url(#arrowHead)"><title>${xml(edge.reason)}</title></path>`).join('');
  const labels = model.edges.map((edge) => `
    <rect x="${edge.labelX - edge.labelWidth / 2}" y="${edge.labelY - 14}" width="${edge.labelWidth}" height="18" rx="9" fill="#f8fbfa" stroke="#c8d8d3"/>
    <text x="${edge.labelX}" y="${edge.labelY}" text-anchor="middle" font-size="11" font-weight="700" fill="#24594f">${xml(edge.label)}</text>
    <text x="${edge.sourceCardinalityX}" y="${edge.sourceCardinalityY}" text-anchor="middle" font-size="11" font-weight="700" fill="#1f5e55">${xml(edge.sourceCardinality)}</text>
    <text x="${edge.targetCardinalityX}" y="${edge.targetCardinalityY}" text-anchor="middle" font-size="11" font-weight="700" fill="#1f5e55">${xml(edge.targetCardinality)}</text>`).join('');
  const nodes = model.nodes.map((node) => `
    <g transform="translate(${node.x}, ${node.y})">
      <rect width="${node.width}" height="${node.height}" rx="8" fill="#ffffff" stroke="#b7c9c3" stroke-width="1.4"/>
      <rect width="${node.width}" height="42" rx="8" fill="#eaf3f0"/>
      <text x="14" y="25" font-size="14" font-weight="700" fill="#203c35">${xml(node.label)}</text>
      <text x="14" y="58" font-size="10" fill="#60736d">${xml(node.subtitle)}</text>
      ${node.fields.map((field) => `<text x="14" y="${field.y + 14}" font-size="11" fill="#3f554e"><tspan font-weight="700">${xml(field.role)}</tspan> ${xml(field.name)} <tspan fill="#71847e">${xml(field.type)}</tspan></text>`).join('')}
    </g>`).join('');
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${model.width}" height="${model.height}" viewBox="0 0 ${model.width} ${model.height}" font-family="Inter, system-ui, sans-serif">
  <rect width="100%" height="100%" fill="#f8fbfa"/>
  <defs><marker id="arrowHead" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="#2f766b"/></marker></defs>
  ${edges}${nodes}${labels}
</svg>`;
}

// ------------------------------------------------------------------ naming heuristics

export function columnRole(table: TableAnalysisView, column: ColumnMetricView, foreignKeys: Set<string>): string {
  const pk = isPrimaryKeyCandidate(table, column);
  const fk = foreignKeys.has(`${table.tableName}.${column.columnName}`);
  if (pk && fk) {
    return 'PK FK';
  }
  if (pk) {
    return 'PK';
  }
  if (fk) {
    return 'FK';
  }
  if (isIdentifierColumn(column.columnName)) {
    return 'ID';
  }
  if (typeLabel(column.dataType) === 'Date') {
    return 'DATE';
  }
  return isNumericType(column.dataType) ? 'NUM' : 'ATTR';
}

function primaryColumnCandidate(table: TableAnalysisView): ColumnMetricView | undefined {
  return table.columns.find((column) => isPrimaryKeyCandidate(table, column))
    ?? table.columns.find((column) => isIdentifierColumn(column.columnName));
}

export function isPrimaryKeyCandidate(table: TableAnalysisView, column: ColumnMetricView): boolean {
  const rows = table.rowCount ?? 0;
  const uniqueRatio = rows > 0 ? (column.uniqueCount ?? 0) / rows : 0;
  const idColumns = table.columns.filter((candidate) => isIdentifierColumn(candidate.columnName));
  return normalizeIdentifier(column.columnName) === 'id'
    || columnEntityToken(column.columnName) === entityToken(table.tableName)
    || (idColumns.length === 1 && isIdentifierColumn(column.columnName) && uniqueRatio >= 0.98 && (column.missingCount ?? 0) === 0);
}

function isOwnIdentifier(table: TableAnalysisView, column: ColumnMetricView): boolean {
  const token = columnEntityToken(column.columnName);
  return !!token && token === entityToken(table.tableName);
}

export function isIdentifierColumn(name: string): boolean {
  const key = normalizeIdentifier(name);
  return key === 'id' || key.endsWith('_id') || key.endsWith('_key') || key.endsWith('_code') || key.includes('identifier');
}

function entityToken(tableName: string): string {
  return singular(normalizeIdentifier(tableName));
}

function columnEntityToken(columnName: string): string {
  return singular(normalizeIdentifier(columnName).replace(/_(identifier|id|key|code)$/, '').replace(/^(identifier|id|key|code)_/, ''));
}

function tokensMatch(left: string, right: string): boolean {
  return left === right || left.endsWith(`_${right}`) || right.endsWith(`_${left}`);
}

export function normalizeIdentifier(value: string): string {
  return value
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '');
}

function singular(identifier: string): string {
  return identifier.split('_').map((part) => {
    if (part.length > 3 && part.endsWith('ies')) {
      return `${part.slice(0, -3)}y`;
    }
    return part.length > 3 && part.endsWith('s') && !part.endsWith('ss') ? part.slice(0, -1) : part;
  }).join('_');
}

// ------------------------------------------------------------------ small helpers

function relationKey(relation: ClassDiagramRelation): string {
  return `${relation.sourceTable}.${relation.sourceColumn}->${relation.targetTable}.${relation.targetColumn}`;
}

function byRelationSource(left: ClassDiagramRelation, right: ClassDiagramRelation): number {
  return `${left.sourceTable}.${left.sourceColumn}`.localeCompare(`${right.sourceTable}.${right.sourceColumn}`);
}

function orderByDegree(entities: ClassDiagramEntity[], relations: ClassDiagramRelation[]): ClassDiagramEntity[] {
  const degree = new Map(entities.map((entity) => [entity.tableName, 0]));
  for (const relation of relations) {
    degree.set(relation.sourceTable, (degree.get(relation.sourceTable) ?? 0) + 1);
    degree.set(relation.targetTable, (degree.get(relation.targetTable) ?? 0) + 1);
  }
  return [...entities].sort((left, right) =>
    (degree.get(right.tableName) ?? 0) - (degree.get(left.tableName) ?? 0) || left.tableName.localeCompare(right.tableName));
}

function groupBy<T>(items: T[], key: (item: T) => string): Map<string, T[]> {
  const groups = new Map<string, T[]>();
  for (const item of items) {
    groups.set(key(item), [...(groups.get(key(item)) ?? []), item]);
  }
  return groups;
}

function laneKey(sourceRow: number, targetRow: number): string {
  return sourceRow === targetRow ? `same-${sourceRow}` : `${Math.min(sourceRow, targetRow)}-${Math.max(sourceRow, targetRow)}`;
}

function compactText(value: string, maxLength: number): string {
  return value.length > maxLength ? `${value.slice(0, Math.max(1, maxLength - 1))}…` : value;
}

function numberOrUndefined(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function xml(value: string): string {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&apos;');
}
