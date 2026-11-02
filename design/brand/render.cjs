// Local, deterministic SVG rasterization. No network or generative rendering.
const fs = require('node:fs');
const { chromium } = require('playwright');

(async () => {
  const { executable, jobs } = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
  const browser = await chromium.launch({
    executablePath: executable,
    headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--force-color-profile=srgb'],
  });
  try {
    const page = await browser.newPage({ deviceScaleFactor: 1 });
    for (const job of jobs) {
      await page.setViewportSize({ width: job.width, height: job.height });
      const svgSource = fs.readFileSync(job.input, 'utf8').replace(/<\?xml[^>]*\?>/, '');
      await page.setContent('<!doctype html><html><head><style>' +
        'html,body{margin:0;width:100%;height:100%;background:transparent}' +
        'body>svg{display:block;width:100%;height:100%}' +
        '</style></head><body>' + svgSource + '</body></html>', { waitUntil: 'load' });
      await page.evaluate(({ width, height }) => {
        const svg = document.querySelector('body > svg');
        svg.setAttribute('width', width);
        svg.setAttribute('height', height);
      }, job);
      await page.screenshot({ path: job.output, omitBackground: true });
    }
    process.stdout.write(JSON.stringify({ chromium: browser.version() }));
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
