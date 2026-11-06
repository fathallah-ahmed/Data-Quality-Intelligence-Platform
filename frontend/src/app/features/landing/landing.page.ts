import { ChangeDetectionStrategy, Component, DestroyRef, afterNextRender, computed, inject, signal } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { ThemeService } from '../../core/theme.service';
import { ScoreRing } from '../../shared/components';
import { Icon, IconName } from '../../shared/icon';
import { Reveal, prefersReducedMotion, watchSections } from '../../shared/motion';

export interface Feature {
  icon: IconName;
  title: string;
  text: string;
}

export interface Step {
  title: string;
  text: string;
  icon: IconName;
}

export type PreviewTab = 'health' | 'fixes' | 'ask';

/** What the product does, in the words of the people who use it. No jargon. */
export const BENEFITS: Feature[] = [
  { icon: 'gauge', title: 'See how healthy your data is', text: 'One score and one grade for every table, with the figures behind them, so nobody has to guess.' },
  { icon: 'alert', title: 'Know exactly what to fix', text: 'Missing values, repeated rows, badly written dates or emails: each problem is listed with real examples.' },
  { icon: 'layers', title: 'Stay confident over time', text: 'Every new file is compared with the previous one, so a quiet drop in quality never goes unnoticed.' }
];

export const FEATURES: Feature[] = [
  { icon: 'gauge', title: 'Health score', text: 'A clear grade from A to D for each table and for the whole set of files.' },
  { icon: 'search', title: 'Ask in plain words', text: 'Type a question such as “Which customers have two different emails?” and get an answer you can check.' },
  { icon: 'shield', title: 'Rules that watch for you', text: 'Set simple rules, or accept the ones suggested from your goal. You are alerted as soon as one is broken.' },
  { icon: 'checkCircle', title: 'Safe corrections', text: 'Describe a fix in a sentence, preview what would change, then apply it to a copy. Your original is never touched.' },
  { icon: 'swap', title: 'Compare versions', text: 'See what changed between two uploads: rows, columns, missing values and quality.' },
  { icon: 'network', title: 'See how it connects', text: 'A map shows how your tables relate to each other, and highlights what does not match.' },
  { icon: 'bell', title: 'Alerts', text: 'Get a notice the moment something fails, wherever you are in the application.' },
  { icon: 'printer', title: 'Reports to share', text: 'Print a clean report or save it as a document to send to your team.' }
];

export const STEPS: Step[] = [
  { icon: 'upload', title: 'Add your files', text: 'Drop your spreadsheet files. Tell us, if you like, what the data is about and what you want to do with it.' },
  { icon: 'activity', title: 'We check everything', text: 'Every table is read and measured. Results appear one by one while the analysis is still running.' },
  { icon: 'sparkles', title: 'Review, fix and share', text: 'Read the findings, correct what is wrong, ask questions, and share a report.' }
];

export const PREVIEW_TABS: { id: PreviewTab; label: string; icon: IconName }[] = [
  { id: 'health', label: 'Health at a glance', icon: 'gauge' },
  { id: 'fixes', label: 'Fix with confidence', icon: 'checkCircle' },
  { id: 'ask', label: 'Ask questions', icon: 'search' }
];

/** Public front page: explains the service and leads to the sign-in page. */
@Component({
  selector: 'app-landing-page',
  imports: [RouterLink, Icon, ScoreRing, Reveal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './landing.page.html',
  styleUrl: './landing.page.css'
})
export class LandingPage {
  private readonly auth = inject(AuthService);
  readonly theme = inject(ThemeService);

  readonly benefits = BENEFITS;
  readonly features = FEATURES;
  readonly steps = STEPS;
  readonly previewTabs = PREVIEW_TABS;
  readonly preview = signal<PreviewTab>('health');
  readonly year = new Date().getFullYear();
  /** True once the page has scrolled: the top bar then gets its border and blur. */
  readonly scrolled = signal(false);
  /** The section being read, highlighted in the navigation. */
  readonly active = signal('');

  /** Someone already signed in goes straight back to their work. */
  readonly signedIn = computed(() => this.auth.isAuthenticated());

  constructor() {
    inject(Title).setTitle('Data Quality – Know if your data can be trusted');
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      const stopSections = watchSections(['benefits', 'features', 'how', 'preview'], (id) => this.active.set(id));
      // An observer on a marker at the very top tells when the page has scrolled, with no scroll listener to run.
      const marker = globalThis.document?.getElementById('top-marker');
      const observer = marker && typeof IntersectionObserver !== 'undefined'
        ? new IntersectionObserver(([entry]) => this.scrolled.set(!entry.isIntersecting))
        : null;
      if (marker && observer) {
        observer.observe(marker);
      }
      destroyRef.onDestroy(() => {
        stopSections();
        observer?.disconnect();
      });
    });
  }

  show(tab: PreviewTab): void {
    this.preview.set(tab);
  }

  /** Smooth scroll to a section, without animation for people who asked for less motion. */
  scrollTo(id: string): void {
    globalThis.document?.getElementById(id)?.scrollIntoView({ behavior: prefersReducedMotion() ? 'auto' : 'smooth', block: 'start' });
  }
}
