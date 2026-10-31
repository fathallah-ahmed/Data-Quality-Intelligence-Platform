import { ChangeDetectionStrategy, Component, ElementRef, computed, effect, inject, output, signal, viewChild } from '@angular/core';
import { Router } from '@angular/router';
import { LayoutService } from '../../core/layout.service';
import { ThemeService } from '../../core/theme.service';
import { WorkspaceStore } from '../../core/workspace.store';
import { statusLabel } from '../../shared/format';
import { Icon, IconName } from '../../shared/icon';

interface Command {
  id: string;
  group: 'Datasets' | 'Tables' | 'Go to' | 'Actions';
  label: string;
  hint?: string;
  icon: IconName;
  shortcut?: string;
  run: () => void;
}

/** Ctrl/⌘ K: jump to any dataset, table or section, or run an action, from the keyboard. */
@Component({
  selector: 'app-command-palette',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './command-palette.html',
  styleUrl: './command-palette.css'
})
export class CommandPalette {
  readonly layout = inject(LayoutService);
  private readonly store = inject(WorkspaceStore);
  private readonly theme = inject(ThemeService);
  private readonly router = inject(Router);
  readonly signOut = output<void>();

  readonly query = signal('');
  readonly activeIndex = signal(0);
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
  private readonly input = viewChild.required<ElementRef<HTMLInputElement>>('input');

  private readonly commands = computed<Command[]>(() => {
    const commands: Command[] = [];
    const { datasetId, versionId } = this.store.selection();
    for (const dataset of this.store.sortedDatasets()) {
      commands.push({
        id: `dataset-${dataset.id}`,
        group: 'Datasets',
        label: dataset.name,
        hint: `v${dataset.latestVersion?.versionNumber ?? 1} · ${statusLabel(dataset.latestVersion?.status)}`,
        icon: 'database',
        run: () => this.router.navigate(dataset.latestVersion
          ? ['/datasets', dataset.id, 'versions', dataset.latestVersion.id, 'overview']
          : ['/datasets', dataset.id])
      });
    }
    if (datasetId !== null && versionId !== null) {
      for (const table of this.store.details()?.tables ?? []) {
        commands.push({
          id: `table-${table.tableVersionId}`,
          group: 'Tables',
          label: table.tableName,
          hint: table.rowCount !== null && table.rowCount !== undefined ? `${table.rowCount.toLocaleString('en-US')} rows` : statusLabel(table.changeStatus),
          icon: 'table',
          run: () => this.router.navigate(['/datasets', datasetId, 'versions', versionId, 'tables'], { queryParams: { table: table.tableName } })
        });
      }
      const base = ['/datasets', datasetId, 'versions', versionId];
      const tabs: [string, string, IconName, string][] = [
        ['overview', 'Overview', 'overview', '1'],
        ['tables', 'Tables', 'table', '2'],
        ['model', 'Data model', 'network', '3'],
        ['insights', 'Insights', 'lightbulb', '4'],
        ['compare', 'Compare versions', 'swap', '5'],
        ['rules', 'Quality rules', 'shield', '6']
      ];
      for (const [id, label, icon, shortcut] of tabs) {
        commands.push({ id: `tab-${id}`, group: 'Go to', label, icon, shortcut, run: () => this.router.navigate([...base, id]) });
      }
    }
    commands.push(
      { id: 'new', group: 'Actions', label: 'New analysis', hint: 'Upload CSV files', icon: 'upload', shortcut: 'N', run: () => this.layout.openUpload() },
      { id: 'theme', group: 'Actions', label: this.theme.isDark() ? 'Switch to light theme' : 'Switch to dark theme', icon: this.theme.isDark() ? 'sun' : 'moon', run: () => this.theme.toggle() },
      { id: 'sidebar', group: 'Actions', label: this.layout.sidebarCollapsed() ? 'Expand sidebar' : 'Collapse sidebar', icon: 'sidebar', run: () => this.layout.toggleCollapsed() },
      { id: 'signout', group: 'Actions', label: 'Sign out', icon: 'logout', run: () => this.signOut.emit() }
    );
    return commands;
  });

  readonly results = computed(() => {
    const tokens = this.query().trim().toLowerCase().split(/\s+/).filter(Boolean);
    const matches = this.commands().filter((command) => {
      const haystack = `${command.label} ${command.hint ?? ''} ${command.group}`.toLowerCase();
      return tokens.every((token) => haystack.includes(token));
    });
    if (tokens.length > 0) {
      return matches.slice(0, 40);
    }
    // Without a query: recent datasets, the current version's sections and the actions.
    const recent = matches.filter((command) => command.group === 'Datasets').slice(0, 6);
    return [...recent, ...matches.filter((command) => command.group === 'Go to' || command.group === 'Actions')];
  });

  readonly groups = computed(() => {
    const groups = new Map<string, { command: Command; index: number }[]>();
    this.results().forEach((command, index) => groups.set(command.group, [...(groups.get(command.group) ?? []), { command, index }]));
    return [...groups.entries()].map(([name, items]) => ({ name, items }));
  });

  constructor() {
    effect(() => {
      const element = this.dialog().nativeElement;
      if (this.layout.paletteOpen() && !element.open) {
        this.query.set('');
        this.activeIndex.set(0);
        element.showModal?.();
        setTimeout(() => this.input().nativeElement.focus());
      } else if (!this.layout.paletteOpen() && element.open) {
        element.close();
      }
    });
  }

  setQuery(value: string): void {
    this.query.set(value);
    this.activeIndex.set(0);
  }

  onKeydown(event: KeyboardEvent): void {
    const count = this.results().length;
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      const step = event.key === 'ArrowDown' ? 1 : -1;
      this.activeIndex.update((index) => (count === 0 ? 0 : (index + step + count) % count));
      this.dialog().nativeElement.querySelector(`[data-index="${this.activeIndex()}"]`)?.scrollIntoView({ block: 'nearest' });
    } else if (event.key === 'Enter') {
      event.preventDefault();
      const command = this.results()[this.activeIndex()];
      if (command) {
        this.execute(command);
      }
    }
  }

  execute(command: Command): void {
    this.close();
    command.run();
  }

  close(): void {
    this.layout.paletteOpen.set(false);
  }

  onBackdrop(event: MouseEvent): void {
    if (event.target === this.dialog().nativeElement) {
      this.close();
    }
  }
}
