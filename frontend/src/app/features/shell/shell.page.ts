import { ChangeDetectionStrategy, Component, DestroyRef, HostListener, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink, RouterOutlet } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { LayoutService } from '../../core/layout.service';
import { LiveEventsService } from '../../core/live-events.service';
import { LiveEvent } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { ThemeService } from '../../core/theme.service';
import { WorkspaceStore } from '../../core/workspace.store';
import { Icon } from '../../shared/icon';
import { ignoreShortcut } from '../../shared/keyboard';
import { DatasetSidebar } from '../datasets/dataset-sidebar';
import { UploadDialog } from '../upload/upload-dialog';
import { CommandPalette } from './command-palette';

/** Signed-in layout: brand sidebar (rail / drawer), top bar and the routed page. */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, DatasetSidebar, UploadDialog, CommandPalette, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './shell.page.html',
  styleUrl: './shell.page.css'
})
export class ShellPage implements OnInit {
  readonly auth = inject(AuthService);
  readonly layout = inject(LayoutService);
  readonly live = inject(LiveEventsService);
  readonly theme = inject(ThemeService);
  private readonly store = inject(WorkspaceStore);
  private readonly toasts = inject(ToastService);
  private readonly destroyRef = inject(DestroyRef);
  readonly userMenuOpen = signal(false);
  readonly now = signal(Date.now());
  readonly isMac = /Mac|iPhone|iPad/.test(globalThis.navigator?.platform ?? '');

  readonly liveLabel = computed(() => {
    switch (this.live.transport()) {
      case 'sse':
        return { text: 'Live', hint: 'Receiving live updates from the backend.' };
      case 'polling':
        return { text: 'Polling', hint: 'Live stream unavailable; refreshing every 5 seconds.' };
      case 'offline':
        return { text: 'Offline', hint: 'The backend cannot be reached. Retrying…' };
      default:
        return { text: 'Connecting', hint: 'Connecting to the live update stream…' };
    }
  });

  ngOnInit(): void {
    this.store.loadDatasets();
    this.live.start();
    this.live.events.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((event) => this.onLiveEvent(event));
    const timer = setInterval(() => this.now.set(Date.now()), 30000);
    this.destroyRef.onDestroy(() => {
      // Leaving the shell means signing out (or an expired session): drop the old user's data.
      clearInterval(timer);
      this.live.stop();
      this.store.reset();
    });
  }

  /** Quality rules that fail when a run finishes raise an alert, wherever the user is. */
  private onLiveEvent(event: LiveEvent): void {
    if (event.type !== 'RULES_FAILED') {
      return;
    }
    const name = this.store.datasets().find((dataset) => dataset.id === event.datasetId)?.name;
    const where = name ? `${name}${event.versionNumber ? ` v${event.versionNumber}` : ''}: ` : '';
    this.toasts.warning(`${where}${event.message ?? 'Quality rules failed.'}`);
  }

  /** Global shortcuts: Ctrl/⌘ K or / opens the palette, N starts a new analysis. */
  @HostListener('document:keydown', ['$event'])
  onKeydown(event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
      event.preventDefault();
      this.layout.paletteOpen.update((open) => !open);
      return;
    }
    if (ignoreShortcut(event)) {
      return;
    }
    if (event.key === '/') {
      event.preventDefault();
      this.layout.paletteOpen.set(true);
    } else if (event.key.toLowerCase() === 'n') {
      event.preventDefault();
      this.layout.openUpload();
    }
  }

  logout(): void {
    this.userMenuOpen.set(false);
    this.live.stop();
    this.store.reset();
    this.auth.logout();
  }
}
