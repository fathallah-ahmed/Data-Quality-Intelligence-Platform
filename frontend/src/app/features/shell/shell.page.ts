import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { LayoutService } from '../../core/layout.service';
import { LiveEventsService } from '../../core/live-events.service';
import { ThemeService } from '../../core/theme.service';
import { WorkspaceStore } from '../../core/workspace.store';
import { DatasetSidebar } from '../datasets/dataset-sidebar';
import { UploadDialog } from '../upload/upload-dialog';

/** Signed-in layout: top bar, dataset sidebar (a drawer on small screens) and the routed page. */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, DatasetSidebar, UploadDialog],
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
  private readonly destroyRef = inject(DestroyRef);
  readonly userMenuOpen = signal(false);
  readonly now = signal(Date.now());

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
    const timer = setInterval(() => this.now.set(Date.now()), 30000);
    this.destroyRef.onDestroy(() => {
      // Leaving the shell means signing out (or an expired session): drop the old user's data.
      clearInterval(timer);
      this.live.stop();
      this.store.reset();
    });
  }

  logout(): void {
    this.userMenuOpen.set(false);
    this.live.stop();
    this.store.reset();
    this.auth.logout();
  }
}
