import { ActivatedRouteSnapshot, ViewTransitionInfo } from '@angular/router';

/** The path of the deepest active route, e.g. `datasets/3/versions/4/graph` (query parameters are not part of it). */
export function routePath(snapshot: ActivatedRouteSnapshot | null): string {
  const parts: string[] = [];
  for (let current = snapshot; current; current = current.firstChild) {
    parts.push(...current.url.map((segment) => segment.path));
  }
  return parts.join('/');
}

/**
 * Pages cross-fade when the router changes the route, but not when only a query parameter changes (picking a
 * table, choosing a version to compare): that is the same page updating, and animating it would feel sluggish.
 */
export function skipSamePage({ transition, from, to }: ViewTransitionInfo): void {
  if (routePath(from) === routePath(to)) {
    transition.skipTransition();
  }
}
