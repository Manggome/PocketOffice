#!/usr/bin/env node
// 앱의 WebView 가 보는 것과 같은 주소 구조로 엔진과 다리 페이지를 띄우는 개발 서버.
// 폰 없이 브라우저에서 열기/저장 흐름을 확인할 때 쓴다.
//
//   node tools/dev-server.mjs [port]
//   http://localhost:8790/__pocket/host.html?doc=test.docx
//
//   /__pocket/*      app/src/main/assets/shell/*   (다리 페이지)
//   /__dev/doc/<n>   tools/samples/<n>             (앱에서는 /__pocket/doc/<토큰>)
//   POST /__dev/save tools/out/<name> 에 저장
//   그 외            engine/build/engine/*         (편집기)

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const engine = path.join(root, 'engine/build/engine');
const shell = path.join(root, 'app/src/main/assets/shell');
const samples = path.join(root, 'tools/samples');
const outDir = path.join(root, 'tools/out');
const port = Number(process.argv[2] || 8790);

// MimeTypes.kt 와 맞춰 둔다
const MIME = {
  html: 'text/html; charset=utf-8',
  js: 'text/javascript; charset=utf-8',
  mjs: 'text/javascript; charset=utf-8',
  css: 'text/css; charset=utf-8',
  json: 'application/json; charset=utf-8',
  svg: 'image/svg+xml',
  png: 'image/png',
  jpg: 'image/jpeg',
  gif: 'image/gif',
  ico: 'image/x-icon',
  woff: 'font/woff',
  woff2: 'font/woff2',
  ttf: 'font/ttf',
  wasm: 'application/wasm',
  br: 'application/wasm', // x2t.wasm.br 는 prepare-engine 이 미리 풀어 둔 wasm
  bin: 'application/octet-stream',
  txt: 'text/plain; charset=utf-8',
};

function serveFile(res, file) {
  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) {
      res.writeHead(404).end('not found');
      return;
    }
    const ext = path.extname(file).slice(1).toLowerCase();
    const type = MIME[ext] || (file.includes(`${path.sep}fonts${path.sep}`) ? 'font/ttf' : 'application/octet-stream');
    res.writeHead(200, { 'Content-Type': type, 'Content-Length': st.size, 'Cache-Control': 'no-cache' });
    fs.createReadStream(file).pipe(res);
  });
}

function readBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks)));
  });
}

http
  .createServer(async (req, res) => {
    const url = new URL(req.url, `http://localhost:${port}`);
    const p = decodeURIComponent(url.pathname);

    if (req.method === 'POST' && p === '/__dev/save') {
      const body = await readBody(req);
      fs.mkdirSync(outDir, { recursive: true });
      const name = path.basename(url.searchParams.get('name') || 'saved.bin');
      fs.writeFileSync(path.join(outDir, name), body);
      console.log(`saved ${name} (${body.length} bytes)`);
      res.writeHead(204).end();
      return;
    }
    if (req.method === 'POST' && p === '/__dev/event') {
      const body = await readBody(req);
      console.log(`event ${url.searchParams.get('type')} ${body.toString()}`);
      res.writeHead(204).end();
      return;
    }

    const safe = (base, rel) => {
      const full = path.join(base, rel);
      return full.startsWith(base) ? full : null;
    };

    let file = null;
    if (p.startsWith('/__pocket/')) file = safe(shell, p.slice('/__pocket/'.length));
    else if (p.startsWith('/__dev/doc/')) file = safe(samples, p.slice('/__dev/doc/'.length));
    else file = safe(engine, p === '/' ? 'editor.html' : p.slice(1));

    if (!file) res.writeHead(403).end();
    else serveFile(res, file);
  })
  .listen(port, () => console.log(`PocketOffice dev server: http://localhost:${port}/__pocket/host.html?doc=test.docx`));
