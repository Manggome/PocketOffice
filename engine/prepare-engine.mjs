#!/usr/bin/env node
// ranuts/document 의 빌드 결과(dist/)에서 앱에 필요한 것만 골라 낸다. CI 가 이것을 engine.zip 으로
// 묶어 별도 릴리스(engine-<id>)에 올리고, 앱은 처음 한 번 받아 둔다 (core/EngineStore.kt).
//
//   node engine/prepare-engine.mjs <document/dist> engine/build/engine
//
// 웹 사이트용 빌드라 랜딩 페이지·서비스 워커·도움말·45개 언어 팩이 함께 들어 있다.
// APK 에서는 쓸 일이 없고 크기만 차지하므로 여기서 걸러 낸다.
// 글꼴 카탈로그(fonts/)는 손대지 않는다. 파일 하나만 빠져도 글자가 밀려 그려지는 문제가
// 있어서(document/docs/fonts.md) 크기보다 정확성을 택했다.

import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

const [src, out] = process.argv.slice(2);
if (!src || !out) {
  console.error('usage: prepare-engine.mjs <dist> <out>');
  process.exit(2);
}

// 최상위에서 남길 것. 나머지(랜딩 페이지, 언어별 소개 페이지, sw.js 등)는 버린다.
const KEEP_TOP = new Set([
  'editor.html',
  'assets',
  'sdkjs',
  'web-apps',
  'fonts',
  'ran-fonts',
  'img',
  'libs',
  'themes.json',
  'plugins.json',
]);

// 편집기 UI 언어. 한국어가 기본이고 빠진 문구는 영어로 채워진다.
const KEEP_LOCALES = new Set(['en', 'ko']);

const DROP_DIRS = [
  // 쓰지 않는 편집기: Visio, 모바일 전용 UI(오프라인 패치가 데스크톱 UI 에만 있다), 읽기 전용 embed UI
  /^web-apps\/apps\/visioeditor$/,
  /^sdkjs\/visio$/,
  /^web-apps\/apps\/[^/]+\/mobile$/,
  /^web-apps\/apps\/[^/]+\/embed$/,
  // 구형 IE 용 스타일과 오프라인 도움말
  /^web-apps\/apps\/[^/]+\/main\/ie$/,
  /^web-apps\/apps\/[^/]+\/main\/resources\/help$/,
];

let kept = 0;
let dropped = 0;
let keptBytes = 0;

function isDroppedLocale(rel) {
  // web-apps/apps/<editor>/main/locale/<lang>.json
  const m = rel.match(/\/locale\/([^/]+)\.json$/);
  if (!m) return false;
  return !KEEP_LOCALES.has(m[1].toLowerCase());
}

function copy(rel) {
  const from = path.join(src, rel);
  const stat = fs.statSync(from);
  if (stat.isDirectory()) {
    if (DROP_DIRS.some((re) => re.test(rel))) {
      dropped++;
      return;
    }
    for (const name of fs.readdirSync(from)) copy(rel ? `${rel}/${name}` : name);
    return;
  }
  if (rel.endsWith('.map') || isDroppedLocale(rel)) {
    dropped++;
    return;
  }

  const to = path.join(out, rel);
  fs.mkdirSync(path.dirname(to), { recursive: true });

  // x2t.wasm.br 는 웹 서버가 Content-Encoding: br 로 보내 브라우저가 풀어 주는 전제다.
  // WebView 의 가로채기 응답에는 그 단계가 없으므로 미리 풀어 둔다. 이름은 그대로 둔다
  // (로더가 이 이름으로 요청한다). APK 압축이 다시 줄여 주므로 손해가 크지 않다.
  if (rel.endsWith('x2t.wasm.br')) {
    const raw = zlib.brotliDecompressSync(fs.readFileSync(from));
    if (raw.readUInt32BE(0) !== 0x0061736d) throw new Error('x2t.wasm.br 를 풀었는데 wasm 이 아닙니다');
    fs.writeFileSync(to, raw);
    keptBytes += raw.length;
  } else {
    fs.copyFileSync(from, to);
    keptBytes += stat.size;
  }
  kept++;
}

fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(out, { recursive: true });

for (const name of fs.readdirSync(src)) {
  if (KEEP_TOP.has(name) || /^ran-tokens\.[0-9a-f]+\.css$/.test(name)) copy(name);
  else dropped++;
}

if (!fs.existsSync(path.join(out, 'editor.html'))) throw new Error('editor.html 이 없습니다 — dist 경로를 확인하세요');

// 엔진 버전을 앱이 읽을 수 있게 남긴다 (설정 > 정보)
const ref = process.env.ENGINE_REF || 'local';
fs.writeFileSync(path.join(out, 'ENGINE_VERSION'), ref + '\n');

console.log(`engine: kept ${kept} files (${(keptBytes / 1e6).toFixed(1)} MB), dropped ${dropped} entries -> ${out}`);
