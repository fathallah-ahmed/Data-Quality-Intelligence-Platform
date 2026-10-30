import { formatBytes } from '../../shared/format';

export interface FileProblem {
  name: string;
  reason: string;
}

/**
 * Merge a new selection into the current one: files are added (not replaced), a file with the
 * same name replaces the previous one, and invalid files are reported instead of added.
 */
export function mergeSelection(current: File[], incoming: File[], maxBytes: number): { files: File[]; problems: FileProblem[] } {
  const byName = new Map(current.map((file) => [file.name.toLowerCase(), file]));
  const problems: FileProblem[] = [];
  for (const file of incoming) {
    const reason = fileProblem(file, maxBytes);
    if (reason) {
      problems.push({ name: file.name, reason });
      continue;
    }
    byName.set(file.name.toLowerCase(), file);
  }
  return { files: [...byName.values()], problems };
}

export function fileProblem(file: File, maxBytes: number): string | null {
  if (!file.name.toLowerCase().endsWith('.csv')) {
    return 'only .csv files are supported';
  }
  if (file.name.toLowerCase() === 'dataset_config.csv') {
    return 'this file name is reserved, rename the table';
  }
  if (file.size === 0) {
    return 'the file is empty';
  }
  if (file.size > maxBytes) {
    return `larger than the ${formatBytes(maxBytes)} limit`;
  }
  return null;
}

export function tableNameFromFile(file: File): string {
  return file.name.replace(/\.[^/.]+$/, '');
}
