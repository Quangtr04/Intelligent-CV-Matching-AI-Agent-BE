import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';

// Canonical sources are the Mermaid blocks in docs/sequence-diagrams.md.
// Runtime dependencies remain outside the application dependency tree.
const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repository = path.resolve(scriptDirectory, '../..');
const output = path.join(repository, 'docs/diagrams/sequence');
const markdownPath = path.join(repository, 'docs/sequence-diagrams.md');
const args = process.argv.slice(2);
function option(name) {
  const index = args.indexOf(name);
  return index === -1 ? undefined : args[index + 1];
}
const escapeHtml = value => String(value).replace(/[&<>"']/g, c => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
}[c]));
const document = await fs.readFile(markdownPath, 'utf8');
const sections = [...document.matchAll(/^## (SD-\d{2}) ([^\n]+)\n([\s\S]*?)(?=^## SD-\d{2} |^## Source đối chiếu|$(?![\s\S]))/gm)];
if (sections.length !== 16) throw new Error(`Expected 16 SD sections, got ${sections.length}.`);
const diagrams = [];
for (const [, id, title, body] of sections) {
  const blocks = [...body.matchAll(/```mermaid\r?\n([\s\S]*?)\r?\n```/g)].map(match => match[1]);
  for (const [type, prefix] of [['sequence', 'sequenceDiagram'], ['class', 'classDiagram']]) {
    const matching = blocks.filter(block => block.trimStart().startsWith(prefix));
    if (matching.length !== 1) throw new Error(`${id}: expected exactly one ${prefix}.`);
    const source = matching[0].trim() + '\n';
    diagrams.push({ id, title, type, stem: `${id.toLowerCase()}-${type}`, source,
      sourceSha256: createHash('sha256').update(source).digest('hex') });
  }
}
if (args.includes('--check')) {
  const manifest = JSON.parse(await fs.readFile(path.join(output, 'render-manifest.json'), 'utf8'));
  if (manifest.diagrams.length !== 32) throw new Error('Expected 32 render metadata records.');
  for (const diagram of diagrams) {
    for (const extension of ['mmd', 'svg', 'png']) {
      const file = path.join(output, `${diagram.stem}.${extension}`);
      if ((await fs.stat(file)).size === 0) throw new Error(`Empty diagram: ${file}`);
    }
    const svg = await fs.readFile(path.join(output, diagram.stem + '.svg'), 'utf8');
    if (!svg.includes('<svg') || /Syntax error|Parse error|Error parsing/i.test(svg)) {
      throw new Error(`Invalid rendered SVG: ${diagram.stem}`);
    }
    const source = await fs.readFile(path.join(output, diagram.stem + '.mmd'), 'utf8');
    const metadata = manifest.diagrams.find(item => item.stem === diagram.stem);
    if (source !== diagram.source || metadata?.sourceSha256 !== diagram.sourceSha256) {
      throw new Error(`Stale render: ${diagram.stem}. Run the renderer again.`);
    }
    const png = await fs.readFile(path.join(output, diagram.stem + '.png'));
    if (png.subarray(0,8).toString('hex') !== '89504e470d0a1a0a' || png.readUInt32BE(16) < 100) {
      throw new Error(`Invalid PNG: ${diagram.stem}`);
    }
  }
  console.log('Verified 16 sequence and 16 class diagrams: 32 valid PNG/SVG pairs match their current Mermaid sources.');
  process.exit(0);
}
await fs.mkdir(output, { recursive: true });
for (const diagram of diagrams) await fs.writeFile(path.join(output, diagram.stem + '.mmd'), diagram.source);

const mermaidPath = option('--mermaid-js');
if (!mermaidPath) throw new Error('Provide --mermaid-js <path to mermaid.tiny.js>. See generated README.');
const require = createRequire(import.meta.url);
let playwright;
try { playwright = require('playwright'); }
catch {
  const explicitModule = option('--playwright-module');
  const runtimeModule = path.resolve(path.dirname(process.execPath), '../node_modules/playwright');
  playwright = require(explicitModule || runtimeModule);
}
const browser = await playwright.chromium.launch({
  headless: true,
  ...(option('--browser') ? { executablePath: option('--browser') } : {}),
});
const dimensions = [];
try {
  const page = await browser.newPage({ viewport: { width: 2200, height: 1500 }, deviceScaleFactor: 1.5 });
  await page.setContent('<!doctype html><html lang="vi"><head><meta charset="utf-8"><style>html,body{margin:0;background:white}#canvas{display:inline-block;padding:20px;background:white}svg{display:block;max-width:none!important}</style></head><body><div id="canvas"></div></body></html>');
  await page.addScriptTag({ path: path.resolve(mermaidPath) });
  await page.evaluate(() => {
    window.mermaid.initialize({
      startOnLoad: false,
      securityLevel: 'strict',
      theme: 'base',
      fontFamily: 'Arial, sans-serif',
      fontSize: 16,
      themeVariables: {
        primaryColor: '#eef4f8', primaryTextColor: '#172f43', primaryBorderColor: '#496b85',
        lineColor: '#496b85', secondaryColor: '#f4f7f9', tertiaryColor: '#ffffff',
        actorBkg: '#eef4f8', actorBorder: '#496b85', actorTextColor: '#172f43',
        signalColor: '#334b5e', signalTextColor: '#172f43',
        noteBkgColor: '#fff8de', noteBorderColor: '#c9b86a', noteTextColor: '#3d3721',
        labelBoxBkgColor: '#eef4f8', labelTextColor: '#172f43',
        sequenceNumberColor: '#ffffff',
      },
      sequence: {
        useMaxWidth: false, diagramMarginX: 30, diagramMarginY: 25,
        actorMargin: 65, width: 175, height: 52, boxMargin: 12,
        messageMargin: 32, noteMargin: 15, mirrorActors: false, wrap: true,
      },
      class: { useMaxWidth: false, htmlLabels: false, diagramPadding: 28 },
    });
  });
  const only = option('--only');
  for (const diagram of diagrams.filter(item => !only || item.id.toLowerCase() === only.toLowerCase())) {
    const result = await page.evaluate(async ({ source, stem }) => {
      const canvas = document.getElementById('canvas');
      canvas.innerHTML = '';
      await window.mermaid.parse(source);
      const { svg } = await window.mermaid.render('diagram_' + stem.replaceAll('-', '_'), source);
      canvas.innerHTML = svg;
      await document.fonts.ready;
      const element = canvas.querySelector('svg');
      const box = element.viewBox.baseVal;
      element.setAttribute('width', Math.ceil(box.width));
      element.setAttribute('height', Math.ceil(box.height));
      element.style.maxWidth = 'none';
      element.style.background = '#ffffff';
      const rectangle = element.getBoundingClientRect();
      return { svg: element.outerHTML, width: Math.ceil(rectangle.width), height: Math.ceil(rectangle.height) };
    }, diagram);
    await fs.writeFile(path.join(output, diagram.stem + '.svg'), result.svg);
    await page.locator('#canvas').screenshot({ path: path.join(output, diagram.stem + '.png'), scale: 'device' });
    dimensions.push({ id: diagram.id, title: diagram.title, type: diagram.type, stem: diagram.stem,
      sourceSha256: diagram.sourceSha256, width: result.width, height: result.height });
    console.log(`${diagram.id} ${diagram.type}: ${result.width} x ${result.height}`);
  }
  if (only) process.exitCode = 0;
} finally {
  await browser.close();
}
if (option('--only')) process.exit(0);
await fs.writeFile(path.join(output, 'render-manifest.json'), JSON.stringify({ renderer: '@mermaid-js/tiny 12.2.0 + Chromium', diagrams: dimensions }, null, 2) + '\n');

const cards = sections.map(([, id, title]) => {
  const stem = id.toLowerCase();
  const figures = [['sequence', 'Sequence diagram'], ['class', 'Class diagram']].map(([type, label]) => `
    <article><div class="figure-head"><h3>${label}</h3><span><a href="${stem}-${type}.png" target="_blank" rel="noopener">PNG</a><a href="${stem}-${type}.svg" target="_blank" rel="noopener">SVG phóng to</a><a href="${stem}-${type}.mmd">Mermaid</a></span></div>
    <a class="diagram" href="${stem}-${type}.svg" target="_blank" rel="noopener" title="Mở hình SVG để phóng to"><img src="${stem}-${type}.svg" alt="${escapeHtml(id + ' ' + label + ' ' + title)}" loading="lazy"></a></article>`).join('');
  return `<section id="${stem}" data-search="${escapeHtml(id + ' ' + title)}"><h2>${escapeHtml(id + ' ' + title)}</h2><div class="pair">${figures}</div></section>`;
}).join('\n');
const navigation = sections.map(([, id]) => `<a href="#${id.toLowerCase()}">${id}</a>`).join('');
await fs.writeFile(path.join(output, 'index.html'), `<!doctype html>
<html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>FA26SE119 Sequence và Class diagrams</title>
<style>*{box-sizing:border-box}body{margin:0;font-family:Arial,sans-serif;color:#172f43;background:#f4f7f9;line-height:1.5}header,main{max-width:1560px;margin:auto;padding:28px}header h1{margin:0 0 10px;font-size:30px}header p{max-width:1000px;margin:8px 0}a{color:#225f8c}nav{display:flex;flex-wrap:wrap;gap:8px;margin:18px 0}nav a{padding:6px 11px;background:white;border:1px solid #cdd8e0;border-radius:5px;text-decoration:none}input{font:inherit;width:min(700px,100%);padding:11px;border:1px solid #aabecb;border-radius:5px;background:white}section{scroll-margin-top:20px;margin-bottom:44px}h2{font-size:21px;margin:0 0 14px}.pair{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:20px}article{background:white;border:1px solid #d5dfe6;border-radius:7px;overflow:hidden}.figure-head{padding:14px 18px;border-bottom:1px solid #d5dfe6;display:flex;gap:10px;flex-wrap:wrap;justify-content:space-between;align-items:center}h3{font-size:17px;margin:0}.figure-head a{margin-left:14px;font-size:13px}.diagram{display:block;overflow:auto;max-height:780px;padding:16px;background:white}.diagram img{display:block;width:100%;height:auto}.empty{display:none}footer{max-width:1560px;margin:0 auto;padding:0 28px 30px;color:#4d6575;font-size:14px}@media(max-width:950px){.pair{grid-template-columns:1fr}header,main{padding:20px}.diagram{max-height:650px}}@media print{input,nav{display:none}.pair{display:block}article{break-inside:avoid;margin-bottom:20px}.diagram{max-height:none;overflow:visible}}</style></head>
<body><header><h1>Sequence và Class diagrams</h1><p><strong>FA26SE119 · 16 luồng nghiệp vụ · 32 sơ đồ</strong></p><p>Mỗi luồng gồm sequence diagram và class diagram từ mã nguồn. Chọn hình hoặc “SVG phóng to” để xem riêng; dùng PNG khi chèn vào báo cáo. Trang sử dụng file cục bộ và xem được khi không có mạng.</p><input id="filter" type="search" aria-label="Tìm sơ đồ" placeholder="Tìm theo SD hoặc tên luồng"><nav>${navigation}</nav></header><main>${cards}<p class="empty" id="empty">Không có sơ đồ phù hợp.</p></main><footer>Mã nguồn và chú thích chi tiết: docs/sequence-diagrams.md. Class diagram chọn lớp/phương thức liên quan, không mô tả toàn bộ thuộc tính hay ERD.</footer>
<script>document.getElementById('filter').addEventListener('input',function(){const normalize=s=>s.toLocaleLowerCase('vi').normalize('NFD').replace(/[\\u0300-\\u036f]/g,'').replace(/đ/g,'d');const q=normalize(this.value);let count=0;document.querySelectorAll('section[data-search]').forEach(s=>{s.hidden=!normalize(s.dataset.search).includes(q);if(!s.hidden)count++;});document.getElementById('empty').style.display=count?'none':'block';});</script></body></html>\n`);
const rows = sections.map(([,id,title]) => {
  const stem=id.toLowerCase();
  return `| ${id} ${title} | [PNG](${stem}-sequence.png) · [SVG](${stem}-sequence.svg) · [Mermaid](${stem}-sequence.mmd) | [PNG](${stem}-class.png) · [SVG](${stem}-class.svg) · [Mermaid](${stem}-class.mmd) |`;
}).join('\n');
await fs.writeFile(path.join(output, 'README.md'), `# Sequence và Class diagrams FA26SE119\n\nCó **16 sequence diagram và 16 class diagram**, xuất sẵn thành PNG/SVG.\n\n- [Mở trang xem toàn bộ](index.html) bằng trình duyệt. Trang hoạt động offline; bấm hình để mở SVG phóng to.\n- [Tài liệu và mã Mermaid gốc](../../sequence-diagrams.md). Markdown Preview hiển thị ảnh PNG mà không cần hỗ trợ Mermaid.\n- PNG có độ phân giải 1,5 lần kích thước bố cục; SVG giữ nét khi phóng to.\n- Các file .mmd được xuất từ Markdown, không chỉnh hai nơi độc lập.\n\n| Luồng | Sequence diagram | Class diagram |\n| --- | --- | --- |\n${rows}\n\n## Tạo lại hình\n\nNguồn chính là các khối Mermaid trong docs/sequence-diagrams.md. Công cụ dùng Mermaid Tiny 12.2.0 và Playwright/Chromium, không thay đổi dependency ứng dụng Java/React. Chuẩn bị mermaid.tiny.js từ https://cdn.jsdelivr.net/npm/@mermaid-js/tiny@12.2.0/dist/mermaid.tiny.js, cài Playwright ở môi trường công cụ riêng hoặc dùng runtime có sẵn. Chạy từ thư mục backend:\n\n\`\`\`powershell\nnode scripts/dev/RenderSequenceDiagrams.mjs --mermaid-js <path-to-mermaid.tiny.js> --playwright-module <path-to-playwright> --browser <path-to-chrome.exe>\nnode scripts/dev/RenderSequenceDiagrams.mjs --check\n\`\`\`\n\nBỏ --browser khi Playwright đã có Chromium. Nếu dùng Node trong Codex runtime, công cụ tự tìm Playwright cùng runtime. Dùng --only SD-01 để render thử một cặp. Công cụ lưu source .mmd, SVG, PNG, metadata kích thước và trang HTML; --check xác nhận đủ 32 bộ ảnh/nguồn.\n`);
console.log('Generated offline gallery and README for all 32 diagrams.');
