import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ThemeService } from './core/theme.service';
import { ToastHost } from './shared/components';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, ToastHost],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <router-outlet />
    <app-toast-host />
  `
})
export class App {
  // Instantiated at startup so the saved theme is applied before the first paint of a page.
  protected readonly theme = inject(ThemeService);
}
