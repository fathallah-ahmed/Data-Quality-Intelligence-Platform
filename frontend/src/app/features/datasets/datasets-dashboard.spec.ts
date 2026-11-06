import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { DatasetSummary } from '../../core/models';
import { WorkspaceStore } from '../../core/workspace.store';
import { avatarHue, initialsOf } from '../../shared/format';
import { animateNumber } from '../../shared/motion';
import {
  DatasetsDashboardPage,
  isHealthy,
  needsAttention,
  visibleDatasets,
  workspaceStats
} from './datasets-dashboard.page';

function dataset(id: number, name: string, extra: Partial<DatasetSummary> & { status?: string } = {}): DatasetSummary {
  const { status = 'COMPLETED', ...rest } = extra;
  return {
    id,
    name,
    sourceType: 'CSV',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: `2026-01-0${id}T00:00:00Z`,
    versionCount: 1,
    failedRules: 0,
    latestScore: 90,
    tableCount: 2,
    latestVersion: { id: id * 10, versionNumber: 1, status, filePath: '', uploadedAt: '2026-01-01T00:00:00Z' },
    ...rest
  } as DatasetSummary;
}

const SALES = dataset(1, 'Sales', { latestScore: 95 });
const HR = dataset(2, 'HR', { latestScore: 72, failedRules: 2 });
const LOGS = dataset(3, 'Logs', { latestScore: 40 });
const NEW = dataset(4, 'Fresh upload', { latestScore: null, tableCount: 0, status: 'RUNNING' });
const BROKEN = dataset(5, 'Broken', { latestScore: null, status: 'FAILED' });
const ALL = [SALES, HR, LOGS, NEW, BROKEN];

describe('dataset dashboard helpers', () => {
  it('says what needs attention: a failed or partial run, a failing rule, or a low score', () => {
    expect(needsAttention(SALES)).toBe(false);
    expect(needsAttention(HR)).toBe(true);
    expect(needsAttention(LOGS)).toBe(true);
    expect(needsAttention(BROKEN)).toBe(true);
    expect(needsAttention(dataset(6, 'Partial', { status: 'PARTIAL' }))).toBe(true);
    expect(needsAttention(NEW)).toBe(false);
  });

  it('calls healthy only a finished dataset with a good score and nothing failing', () => {
    expect(isHealthy(SALES)).toBe(true);
    expect(isHealthy(HR)).toBe(false);
    expect(isHealthy(NEW)).toBe(false);
  });

  it('filters by name and by state without touching the list', () => {
    const copy = [...ALL];
    expect(visibleDatasets(copy, 'sal', 'all', 'name').map((item) => item.name)).toEqual(['Sales']);
    expect(visibleDatasets(copy, '', 'attention', 'name').map((item) => item.name)).toEqual(['Broken', 'HR', 'Logs']);
    expect(visibleDatasets(copy, '', 'healthy', 'name').map((item) => item.name)).toEqual(['Sales']);
    expect(visibleDatasets(copy, '', 'active', 'name').map((item) => item.name)).toEqual(['Fresh upload']);
    expect(copy).toEqual(ALL);
  });

  it('sorts by update date, name or quality, putting datasets without a score last', () => {
    expect(visibleDatasets(ALL, '', 'all', 'recent').map((item) => item.id)).toEqual([5, 4, 3, 2, 1]);
    expect(visibleDatasets(ALL, '', 'all', 'name').map((item) => item.name)).toEqual(['Broken', 'Fresh upload', 'HR', 'Logs', 'Sales']);
    expect(visibleDatasets(ALL, '', 'all', 'score-low').map((item) => item.name).slice(0, 3)).toEqual(['Logs', 'HR', 'Sales']);
    expect(visibleDatasets(ALL, '', 'all', 'score-high').map((item) => item.name).slice(0, 3)).toEqual(['Sales', 'HR', 'Logs']);
  });

  it('computes the figures of the top strip from the datasets that have a score', () => {
    expect(workspaceStats(ALL)).toEqual({
      datasets: 5,
      tables: 2 + 2 + 2 + 0 + 2,
      averageScore: (95 + 72 + 40) / 3,
      attention: 3,
      running: 1
    });
    expect(workspaceStats([]).averageScore).toBeNull();
  });

  it('gives a dataset the same initials and colour every time', () => {
    expect(initialsOf('Sales Orders')).toBe('SO');
    expect(initialsOf('customers')).toBe('CU');
    expect(avatarHue('Sales')).toBe(avatarHue('Sales'));
  });
});

describe('animateNumber', () => {
  it('jumps to the end when there is nothing to animate', () => {
    const seen: number[] = [];
    animateNumber(5, 5, 500, (value) => seen.push(value));
    expect(seen).toEqual([5]);
  });

  it('reaches the target and can be stopped', async () => {
    const seen: number[] = [];
    animateNumber(0, 10, 30, (value) => seen.push(value));
    await new Promise((resolve) => setTimeout(resolve, 120));
    expect(seen[seen.length - 1]).toBe(10);
    expect(seen.every((value) => value >= 0 && value <= 10)).toBe(true);

    const stopped: number[] = [];
    const stop = animateNumber(0, 10, 500, (value) => stopped.push(value));
    stop();
    await new Promise((resolve) => setTimeout(resolve, 40));
    expect(stopped.length).toBe(0);
  });
});

describe('dataset dashboard page', () => {
  function render(datasets: DatasetSummary[], loaded = true): HTMLElement {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: WorkspaceStore, useValue: { datasets: signal(datasets), datasetsLoaded: signal(loaded), datasetsError: signal(null), loadDatasets: () => undefined } }
      ]
    });
    const fixture = TestBed.createComponent(DatasetsDashboardPage);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows one card per dataset, each opening its latest version', () => {
    const page = render(ALL);
    const cards = Array.from(page.querySelectorAll<HTMLAnchorElement>('a.ds'));
    expect(cards.length).toBe(5);
    expect(page.querySelector('a.ds[aria-label="Open Sales"]')?.getAttribute('href')).toBe('/datasets/1/versions/10/overview');
    expect(page.textContent).toContain('3 of 5 need your attention');
    expect(page.textContent).toContain('2 quality rules failing');
  });

  it('shows the welcome screen when there is no dataset yet', () => {
    const page = render([]);
    expect(page.querySelector('.welcome')).not.toBeNull();
    expect(page.querySelectorAll('a.ds').length).toBe(0);
  });

  it('shows placeholders while the datasets load', () => {
    const page = render([], false);
    expect(page.querySelector('[aria-busy="true"]')).not.toBeNull();
  });

  it('narrows the cards with a state filter and with the search box', () => {
    const page = render(ALL);
    const chips = Array.from(page.querySelectorAll<HTMLButtonElement>('.chip'));
    chips.find((chip) => chip.textContent?.includes('Healthy'))?.click();
    TestBed.tick();
    expect(page.querySelectorAll('a.ds').length).toBe(1);

    chips.find((chip) => chip.textContent?.includes('All'))?.click();
    const search = page.querySelector<HTMLInputElement>('.search input') as HTMLInputElement;
    search.value = 'zzz';
    search.dispatchEvent(new Event('input'));
    TestBed.tick();
    expect(page.querySelectorAll('a.ds').length).toBe(0);
    expect(page.textContent).toContain('No dataset matches');
  });
});
