import { Pipe, PipeTransform } from '@angular/core';
import {
  absoluteTime,
  formatDuration,
  formatNumber,
  formatPercent,
  formatScore,
  formatValue,
  relativeTime,
  statusLabel,
  typeLabel
} from './format';

@Pipe({ name: 'dqPercent' })
export class DqPercentPipe implements PipeTransform {
  transform(value: number | null | undefined, digits = 1): string {
    return formatPercent(value, digits);
  }
}

@Pipe({ name: 'dqNumber' })
export class DqNumberPipe implements PipeTransform {
  transform(value: number | null | undefined, maxDecimals = 2): string {
    return formatNumber(value, maxDecimals);
  }
}

@Pipe({ name: 'dqValue' })
export class DqValuePipe implements PipeTransform {
  transform(value: unknown): string {
    return formatValue(value);
  }
}

@Pipe({ name: 'dqScore' })
export class DqScorePipe implements PipeTransform {
  transform(value: number | null | undefined): string {
    return formatScore(value);
  }
}

@Pipe({ name: 'relativeTime' })
export class RelativeTimePipe implements PipeTransform {
  /** `now` lets a caller re-evaluate the pipe on a timer (pure pipes only re-run on input change). */
  transform(value: string | Date | null | undefined, now?: number): string {
    return relativeTime(value, now);
  }
}

@Pipe({ name: 'absoluteTime' })
export class AbsoluteTimePipe implements PipeTransform {
  transform(value: string | Date | null | undefined): string {
    return absoluteTime(value);
  }
}

@Pipe({ name: 'duration' })
export class DurationPipe implements PipeTransform {
  transform(milliseconds: number | null | undefined): string {
    return formatDuration(milliseconds);
  }
}

@Pipe({ name: 'statusLabel' })
export class StatusLabelPipe implements PipeTransform {
  transform(status: string | null | undefined): string {
    return statusLabel(status);
  }
}

@Pipe({ name: 'typeLabel' })
export class TypeLabelPipe implements PipeTransform {
  transform(dtype: string | null | undefined): string {
    return typeLabel(dtype);
  }
}

export const DQ_PIPES = [
  DqPercentPipe,
  DqNumberPipe,
  DqValuePipe,
  DqScorePipe,
  RelativeTimePipe,
  AbsoluteTimePipe,
  DurationPipe,
  StatusLabelPipe,
  TypeLabelPipe
] as const;
