import { expect, test, type Page } from '@playwright/test';
import { spawn, spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import axe from 'axe-core';

/**
 * Evidence for section 6 of the brief, against the running stack:
 *  - no request to another host (every such request is blocked and listed)
 *  - main-thread long tasks while 3,000 assets move at 5x replay speed and the map is panned/zoomed
 *  - after a 30 s network loss the console becomes correct again without a reload
 *  - WCAG 2.1 AA scan (axe-core) of sign-in, console, and the resolve dialog; keyboard-only claim
 *
 * Needs: the stack on BASE_URL with an EMPTY database (so the replay produces live updates),
 * USERS_FILE pointing at the users file, and python on PATH for replay.py.
 */
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const USERS = JSON.parse(readFileSync(process.env.USERS_FILE ?? path.join(ROOT, 'deploy/secrets/users.json'), 'utf8')) as
  { username: string; role: string; password: string }[];
const pw = (u: string) => USERS.find((x) => x.username === u)!.password;
const OUT = path.join(ROOT, 'docs', 'evidence');
const BASE = process.env.BASE_URL ?? 'http://localhost:8080';

const foreign: string[] = [];

async function guard(page: Page) {
  const own = new URL(BASE).host;
  await page.route('**/*', (route) => {
    const u = new URL(route.request().url());
    if (u.host !== own && u.protocol !== 'data:') {
      foreign.push(route.request().url());
      return route.abort();
    }
    return route.continue();
  });
}

async function signIn(page: Page, user: string) {
  await page.goto('/');
  await page.getByLabel('Username').fill(user);
  await page.getByLabel('Password').fill(pw(user));
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByText(`Signed in as`)).toBeVisible();
  await expect(page.locator('.link-live')).toBeVisible({ timeout: 30_000 });
}

async function runAxe(page: Page, label: string) {
  // Evaluated through DevTools: the page's CSP (rightly) refuses injected script tags.
  await page.evaluate(axe.source);
  const result = await page.evaluate(async () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any -- axe is injected as a global
    const r = await (window as any).axe.run(document, { runOnly: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] });
    return { violations: r.violations.map((v: { id: string; impact: string; nodes: unknown[] }) => ({ id: v.id, impact: v.impact, nodes: v.nodes.length })), passes: r.passes.length };
  });
  console.log(`axe ${label}: ${result.passes} rules passed, ${result.violations.length} violations`, result.violations);
  return { label, ...result };
}

test('live map under 5x load, reconnect after 30 s network loss, accessibility', async ({ page }) => {
  mkdirSync(OUT, { recursive: true });
  await guard(page);
  const axeResults = [await (async () => { await page.goto('/'); return runAxe(page, 'sign-in'); })()];

  await signIn(page, 'op01');

  // Long tasks are recorded by the browser itself.
  await page.evaluate(() => {
    (window as unknown as { __long: number[] }).__long = [];
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) (window as unknown as { __long: number[] }).__long.push(e.duration);
    }).observe({ type: 'longtask', buffered: false });
  });

  // 5x real time = about 1,500 position updates per second.
  const replay = spawn('python', ['-I', path.join(ROOT, 'replay.py'), '--base-url', BASE, '--speed', '5',
    '--connections', '4', '--max-lines', '150000'], { env: { ...process.env, FEED_PASSWORD: pw('feed') }, stdio: 'ignore' });
  const replayDone = new Promise<number>((r) => replay.on('exit', (code) => r(code ?? -1)));

  // Phase 1 (40 s): pan and zoom continuously while updates stream in.
  const map = page.locator('canvas.map');
  const box = (await map.boundingBox())!;
  const cx = box.x + box.width / 2;
  const cy = box.y + box.height / 2;
  const frameTimes: number[] = [];
  const t0 = Date.now();
  while (Date.now() - t0 < 40_000) {
    const s = Date.now();
    await page.mouse.move(cx, cy);
    await page.mouse.down();
    await page.mouse.move(cx + 120, cy + 40, { steps: 8 });
    await page.mouse.up();
    await page.mouse.wheel(0, -300);
    await page.mouse.move(cx + 120, cy + 40);
    await page.mouse.down();
    await page.mouse.move(cx, cy, { steps: 8 });
    await page.mouse.up();
    await page.mouse.wheel(0, 300);
    frameTimes.push(Date.now() - s);
  }
  const longTasks = await page.evaluate(() => (window as unknown as { __long: number[] }).__long);
  const assetsShown = await page.locator('.link').innerText();

  // Phase 2: 30 s network loss. Freezing the load balancer stalls every open connection, as a cut
  // link does (browser offline emulation does not interrupt an already-open stream). The feed's
  // sender retries meanwhile, so redelivery is exercised too.
  const lb = process.env.LB_CONTAINER ?? 'geoalert-lb-1';
  spawnSync('docker', ['pause', lb]);
  const lossStarted = Date.now();
  await expect(page.locator('.link-reconnecting')).toBeVisible({ timeout: 30_000 });
  await page.waitForTimeout(Math.max(0, 30_000 - (Date.now() - lossStarted)));
  spawnSync('docker', ['unpause', lb]);
  await expect(page.locator('.link-live')).toBeVisible({ timeout: 30_000 });

  // Correct again without a reload: once the feed has finished, the panel counts equal the server's.
  expect(await replayDone).toBe(0);
  const stats = await (await page.request.get('/api/v1/stats', {
    headers: { Authorization: `Bearer ${await page.evaluate(() => JSON.parse(sessionStorage.getItem('console.session')!).accessToken as string)}` },
  })).json() as { alerts: { open: number; claimed: number } };
  await expect(page.locator('.panel-head .muted')).toHaveText(`${stats.alerts.open} open · ${stats.alerts.claimed} claimed`, { timeout: 15_000 });

  // Keyboard-only claim of the first open alert.
  const claim = page.getByRole('button', { name: /^Claim alert/ }).first();
  await claim.focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('.notice-info')).toContainText('You claimed');
  axeResults.push(await runAxe(page, 'console with alert panel'));
  await page.getByRole('button', { name: /^Resolve alert/ }).first().focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('dialog')).toBeVisible();
  axeResults.push(await runAxe(page, 'resolve dialog'));
  await page.keyboard.type('Checked with the keyboard only');
  await page.keyboard.press('Tab');
  await page.keyboard.press('Tab');
  await page.keyboard.press('Enter');
  await expect(page.locator('.notice-info')).toContainText('You resolved');
  await page.getByRole('button', { name: 'Zones' }).click();
  axeResults.push(await runAxe(page, 'zones (non-map view)'));

  const sorted = [...longTasks].sort((a, b) => b - a);
  const evidence = {
    measuredAt: new Date().toISOString(),
    scenario: 'replay.py --speed 5 (about 1,500 updates/s), 3,000 assets, continuous pan + zoom for 40 s, headless Chromium 1600x900',
    longTasks: { count: longTasks.length, maxMs: Math.round(sorted[0] ?? 0), top5Ms: sorted.slice(0, 5).map(Math.round) },
    interactionLoopMs: { median: frameTimes.sort((a, b) => a - b)[Math.floor(frameTimes.length / 2)], iterations: frameTimes.length },
    headerDuringLoad: assetsShown,
    reconnect: 'load balancer frozen 30 s while the feed retried; header showed the interruption; back to Live without reload; alert counts equal /stats',
    requestsToOtherHosts: foreign,
    axe: axeResults,
  };
  writeFileSync(path.join(OUT, 'frontend-evidence.json'), JSON.stringify(evidence, null, 2));
  console.log(JSON.stringify(evidence, null, 2));

  expect(foreign).toEqual([]);
  expect(sorted[0] ?? 0).toBeLessThan(200);
  for (const r of axeResults) expect(r.violations, r.label).toEqual([]);
});
