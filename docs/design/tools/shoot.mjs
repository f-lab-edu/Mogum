// html/ 의 화면을 브라우저로 열어 screenshots/ 에 모바일 · 데스크톱으로 찍는다. 사용: npm --prefix tools run shoot (design/ 에서)
// 페이지 높이만큼 뷰포트를 늘려 찍는다 — 전체 페이지 캡처는 고정 위 바 · 탭 바를 첫 화면 높이에 찍는다.
import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const design = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const htmlDir = path.join(design, 'html');
const outDir = path.join(design, 'screenshots');
const VIEWPORTS = {
  mobile: { width: 390, height: 844, deviceScaleFactor: 2 },
  desktop: { width: 1280, height: 800, deviceScaleFactor: 1 },
};
const DESKTOP_ONLY = new Set(['flow']);
const MAX_HEIGHT = 12000;

// 문서 높이 + 안에서 스크롤되는 영역이 가린 높이
const fullHeight = () => {
  let extra = 0;
  for (const el of document.querySelectorAll('*')) {
    const oy = getComputedStyle(el).overflowY;
    if ((oy === 'auto' || oy === 'scroll') && el !== document.documentElement && el !== document.body) {
      extra = Math.max(extra, el.scrollHeight - el.clientHeight);
    }
  }
  return Math.max(document.documentElement.scrollHeight, document.body.scrollHeight) + extra;
};

fs.mkdirSync(outDir, { recursive: true });
for (const f of fs.readdirSync(outDir).filter((f) => f.endsWith('.png'))) fs.unlinkSync(path.join(outDir, f));

const browser = await chromium.launch();
for (const file of fs.readdirSync(htmlDir).filter((f) => f.endsWith('.html')).sort()) {
  const name = file.replace(/\.html$/, '');
  for (const [kind, vp] of Object.entries(VIEWPORTS)) {
    if (kind === 'mobile' && DESKTOP_ONLY.has(name)) continue;
    const ctx = await browser.newContext({ viewport: { width: vp.width, height: vp.height }, deviceScaleFactor: vp.deviceScaleFactor });
    const page = await ctx.newPage();
    await page.goto('file://' + path.join(htmlDir, file), { waitUntil: 'networkidle' });
    await page.evaluate(() => document.fonts.ready);
    await page.waitForTimeout(800);
    let h = vp.height;
    for (let i = 0; i < 5; i++) {
      const next = Math.min(MAX_HEIGHT, Math.max(vp.height, await page.evaluate(fullHeight)));
      if (next === h) break;
      h = next;
      await page.setViewportSize({ width: vp.width, height: h });
      await page.waitForTimeout(400);
    }
    await page.screenshot({ path: path.join(outDir, `${name}-${kind}.png`), animations: 'disabled' });
    console.log(`screenshots/${name}-${kind}.png  ${vp.width}x${h}`);
    await ctx.close();
  }
}
await browser.close();
