import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Title } from '@angular/platform-browser';
import { Router } from '@angular/router';
import { apiErrorMessage } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { ThemeService } from '../../core/theme.service';

@Component({
  selector: 'app-login-page',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './login.page.html',
  styleUrl: './login.page.css'
})
export class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  readonly theme = inject(ThemeService);
  readonly returnUrl = input<string | undefined>(undefined);

  readonly username = signal('admin');
  readonly password = signal('');
  readonly submitting = signal(false);
  readonly error = signal<string | null>(null);

  constructor() {
    inject(Title).setTitle('Sign in – Data Quality');
  }

  submit(): void {
    if (!this.username().trim() || !this.password()) {
      this.error.set('Enter your username and password.');
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    this.auth.login(this.username().trim(), this.password()).subscribe({
      next: () => {
        this.submitting.set(false);
        this.password.set('');
        const target = this.returnUrl();
        this.router.navigateByUrl(target && target.startsWith('/') && !target.startsWith('//') ? target : '/datasets');
      },
      error: (error: unknown) => {
        this.submitting.set(false);
        this.error.set(error instanceof Error && !('status' in error)
          ? error.message
          : apiErrorMessage(error, 'Sign-in failed.'));
      }
    });
  }
}
