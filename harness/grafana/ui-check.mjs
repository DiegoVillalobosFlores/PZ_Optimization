// One dashboard page in a running headless Chrome, through the DevTools protocol (Node's built-in WebSocket): wait for
// the panels, then print JSON facts per panel (text, no-data / error markers, drawn canvases, clipped text, overlaps,
// legend) plus the hover tooltips of one chart, and save a full-page screenshot. Driven by ui-check.py.
//
//   node ui-check.mjs <devtools port> <url> <width> <height> <screenshot.png> [hover panel title prefix]
import { writeFileSync } from "node:fs";

const [, , port, url, width, height, shot, hoverTitle, screenH] = process.argv;
const SCREEN_H = +(screenH || height);
const W = +width, H = +height;
const target = await (await fetch(`http://127.0.0.1:${port}/json/new?about:blank`, { method: "PUT" })).json();
const ws = new WebSocket(target.webSocketDebuggerUrl);
let seq = 0;
const pending = new Map();
ws.onmessage = (e) => {
  const m = JSON.parse(e.data);
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
};
await new Promise((r) => (ws.onopen = r));
const send = (method, params = {}) => new Promise((r) => { const id = ++seq; pending.set(id, r); ws.send(JSON.stringify({ id, method, params })); });
const evaluate = async (fn, arg) => {
  const r = await send("Runtime.evaluate", { expression: `(${fn})(${JSON.stringify(arg ?? null)})`, returnByValue: true, awaitPromise: true });
  if (r.result?.exceptionDetails) throw new Error(JSON.stringify(r.result.exceptionDetails).slice(0, 500));
  return r.result?.result?.value;
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

await send("Emulation.setDeviceMetricsOverride", { width: W, height: H, deviceScaleFactor: 1, mobile: W < 800 });
await send("Page.enable");
await send("Page.navigate", { url });

// every panel in the (tall) viewport renders; wait until none is loading and the panel set stops changing
const state = () => evaluate(() => ({
  panels: document.querySelectorAll("[data-viz-panel-key]").length,
  loading: document.querySelectorAll('[aria-label="Panel loading bar"], [data-testid="data-testid Panel loading bar"], .panel-loading').length,
}));
let last = "", stable = 0;
for (let i = 0; i < 120 && stable < 4; i++) {
  await sleep(500);
  const s = JSON.stringify(await state());
  stable = s === last && !JSON.parse(s).loading ? stable + 1 : 0;
  last = s;
}
await sleep(1500);

const facts = await evaluate(() => {
  const rect = (el) => { const r = el.getBoundingClientRect(); return { x: Math.round(r.x), y: Math.round(r.y + scrollY), w: Math.round(r.width), h: Math.round(r.height) }; };
  const clean = (s) => (s || "").replace(/\s+/g, " ").trim();
  const panels = [...document.querySelectorAll("[data-viz-panel-key]")];
  const boxes = panels.map(rect);
  const out = panels.map((p, i) => {
    const title = clean(p.querySelector("h2")?.textContent);
    const text = clean(p.innerText);
    const canvases = [...p.querySelectorAll("canvas")].map((c) => {
      let drawn = null;
      try {
        const ctx = c.getContext("2d");
        if (ctx && c.width && c.height) {
          const d = ctx.getImageData(0, 0, c.width, c.height).data;
          let n = 0; for (let k = 3; k < d.length; k += 16) if (d[k]) n++;
          drawn = n;
        }
      } catch (e) { drawn = "unreadable"; }
      return { w: c.width, h: c.height, drawn_pixels: drawn };
    });
    // text that the browser clips: an element narrower than its content, or an ellipsis
    const clipped = [...p.querySelectorAll("div, span, td, th, a, p")].filter((el) =>
      el.children.length === 0 && clean(el.textContent) && (el.scrollWidth > el.clientWidth + 2 || el.scrollHeight > el.clientHeight + 4)
      && getComputedStyle(el).overflow !== "visible").map((el) => clean(el.textContent).slice(0, 80)).slice(0, 12);
    const b = boxes[i];
    const overlaps = boxes.map((o, j) => j !== i && o.w && b.w && o.x < b.x + b.w - 2 && b.x < o.x + o.w - 2 && o.y < b.y + b.h - 2 && b.y < o.y + o.h - 2 ? j : -1).filter((j) => j >= 0)
      .map((j) => clean(panels[j].querySelector("h2")?.textContent) || `panel ${j}`);
    return {
      index: i, title, box: b, off_page: b.x < 0 || b.x + b.w > innerWidth + 1,
      no_data: /\bNo data\b/.test(text), error: !!p.querySelector('[data-testid*="Panel status error"], [aria-label*="error" i]'),
      error_text: clean(p.querySelector('[data-testid*="Panel status error"]')?.getAttribute("aria-label")),
      text: text.slice(0, 700), canvases, clipped, overlaps,
      legend: [...p.querySelectorAll('[class*="LegendItem"], [data-testid*="legend"] button, [aria-label*="series" i]')].map((e) => clean(e.textContent)).filter(Boolean).slice(0, 30),
    };
  });
  return {
    title: document.title, width: innerWidth, page_width: document.documentElement.scrollWidth,
    horizontal_scroll: document.documentElement.scrollWidth > innerWidth + 1,
    variables: [...document.querySelectorAll('[data-testid*="template variable"], [data-testid*="Dashboard template variables"] label, label')].map((e) => clean(e.textContent)).filter(Boolean).slice(0, 12),
    panels: out,
  };
});

// hover: pointer across the chosen chart; the tooltip text at each position
facts.hover = [];
if (hoverTitle) {
  const plot = await evaluate((t) => {
    const p = [...document.querySelectorAll("[data-viz-panel-key]")].find((x) => (x.querySelector("h2")?.textContent || "").trim().startsWith(t));
    const o = p?.querySelector(".u-over");
    if (!o) return null;
    o.scrollIntoView({ block: "center" });
    const r = o.getBoundingClientRect();
    return { x: r.x, y: r.y, w: r.width, h: r.height };
  }, hoverTitle);
  if (plot) {
    for (let f = 0.02; f <= 0.99; f += 0.06) {
      const x = plot.x + plot.w * f, y = plot.y + plot.h * 0.5;
      await send("Input.dispatchMouseEvent", { type: "mouseMoved", x, y });
      await sleep(250);
      const tip = await evaluate((screenH) => {
        const w = document.querySelector('[data-testid="data-testid viz-tooltip-wrapper"]');
        if (!w) return { text: "" };
        const r = w.getBoundingClientRect();
        const clipped = [...w.querySelectorAll("div, span")].filter((el) => el.children.length === 0 && el.textContent.trim()
          && el.scrollWidth > el.clientWidth + 2).map((el) => el.textContent.trim().slice(0, 80)).slice(0, 8);
        // the headless window is tall (every panel renders); the visitor's screen is screenH high
        return { text: w.innerText.replace(/\n+/g, " | ").slice(0, 1500), box: { x: Math.round(r.x), w: Math.round(r.width), h: Math.round(r.height) },
                 screen: { w: innerWidth, h: screenH }, fits_width: r.x >= 0 && r.x + r.width <= innerWidth + 1,
                 fits_screen_height: r.height <= screenH, clipped_rows: clipped };
      }, SCREEN_H);
      if (tip.text && !facts.hover.some((h) => h.text === tip.text)) facts.hover.push({ at: Math.round(f * 100), ...tip });
    }
  } else facts.hover_missing = `no chart titled "${hoverTitle}..."`;
}

const png = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: true });
if (png.result?.data) writeFileSync(shot, Buffer.from(png.result.data, "base64"));
ws.close();
await fetch(`http://127.0.0.1:${port}/json/close/${target.id}`).catch(() => {});
// exit only after the pipe took everything: process.exit() right after a large write drops the tail (~8 KB kept)
process.stdout.write(JSON.stringify(facts) + "\n", () => process.exit(0));
