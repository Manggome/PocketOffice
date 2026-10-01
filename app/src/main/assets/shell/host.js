// 앱(Kotlin)과 편집기(iframe) 사이를 잇는 다리.
//
// 편집기는 ranuts/document 의 embed 모드로 띄운다. 부모 창이 postMessage 로
// 열기/저장을 지시하고, 결과를 다시 postMessage 로 받는 구조라 그 "부모" 역할을 여기서 한다.
//
//   Kotlin  --evaluateJavascript-->  Pocket.open / Pocket.save / Pocket.setReadonly
//   Kotlin  <--PocketNative.*-------  준비됨, 열림, 고쳐짐 여부, 저장 바이트, 오류
//
// 저장한 파일은 base64 조각으로 나눠 넘긴다. JavascriptInterface 는 문자열만 받고,
// 한 번에 수십 MB 문자열을 넘기면 메모리가 두 배로 튀기 때문이다.
(function () {
  'use strict';

  var CHUNK = 3 * 1024 * 1024; // 원본 바이트 기준. base64 로 4MB 쯤
  var frame = document.getElementById('editor');
  var params = new URLSearchParams(location.search);

  // 개발 서버(tools/dev-server.mjs)에서는 Kotlin 이 없으니 같은 모양의 흉내를 낸다
  var native = window.PocketNative || createDevNative();

  var ready = false;
  var pending = []; // 편집기가 준비되기 전에 들어온 명령
  var saves = {}; // id -> { ext }
  var inflight = 0; // 이쪽에서 시킨 저장이 진행 중인 수
  var saveExt = ''; // 지금 문서를 저장할 형식 (docx, xlsx ...)
  var exportSeq = 0;

  function log(msg) {
    try {
      native.log(String(msg));
    } catch (e) {}
  }

  function emit(type, payload) {
    try {
      native.onEvent(type, JSON.stringify(payload || {}));
    } catch (e) {
      log('emit 실패: ' + e);
    }
  }

  function post(type, payload, id) {
    var msg = { id: id || type + '-' + Date.now(), type: type, payload: payload || {} };
    if (!ready) {
      pending.push(msg);
      return msg.id;
    }
    frame.contentWindow.postMessage(msg, location.origin);
    return msg.id;
  }

  window.addEventListener('message', function (event) {
    if (event.source !== frame.contentWindow) return;
    var data = event.data || {};
    if (typeof data.type !== 'string' || data.type.indexOf('document:') !== 0) return;
    var payload = data.payload || {};

    switch (data.type) {
      case 'document:ready':
        ready = true;
        emit('ready');
        pending.splice(0).forEach(function (msg) {
          frame.contentWindow.postMessage(msg, location.origin);
        });
        break;

      case 'document:opened':
        emit('opened', { readonly: !!payload.readonly });
        break;

      case 'document:dirty-changed':
        emit('dirty', { dirty: !!payload.dirty });
        break;

      case 'document:readonly-changed':
        emit('readonly', { readonly: !!payload.readonly });
        break;

      case 'document:saved':
        if (saves[data.id]) {
          delete saves[data.id];
          inflight = Math.max(0, inflight - 1);
          deliver(data.id, payload);
        }
        break;

      case 'document:error':
        if (saves[data.id]) {
          delete saves[data.id];
          inflight = Math.max(0, inflight - 1);
          native.saveFailed(data.id, payload.message || '저장하지 못했습니다');
        } else {
          emit('error', { message: payload.message || '알 수 없는 오류' });
        }
        break;
    }
  });

  // 저장된 File 을 조각내 Kotlin 으로 보낸다
  function deliver(id, payload) {
    var file = payload.file;
    if (!(file instanceof Blob)) {
      native.saveFailed(id, '편집기가 파일을 돌려주지 않았습니다');
      return;
    }
    native.saveBegin(id, payload.fileName || file.name || '', file.type || '', file.size);

    var offset = 0;
    function next() {
      if (offset >= file.size) {
        native.saveEnd(id, !!payload.dirty);
        return;
      }
      var slice = file.slice(offset, offset + CHUNK);
      offset += CHUNK;
      var reader = new FileReader();
      reader.onload = function () {
        var url = String(reader.result);
        native.saveChunk(id, url.slice(url.indexOf(',') + 1));
        // 조각 사이에 숨을 돌려 화면이 굳지 않게 한다
        setTimeout(next, 0);
      };
      reader.onerror = function () {
        native.saveFailed(id, '저장한 파일을 읽지 못했습니다');
      };
      reader.readAsDataURL(slice);
    }
    next();
  }

  // 편집기 자체의 저장 버튼 / Ctrl+S / "다른 형식으로 다운로드".
  //
  // embed 모드에서는 편집기가 만든 파일을 버리고 "부모가 document:save 로 시키라" 고만 한다.
  // 편집기 창(같은 출처)에 오는 file-stream 메시지를 엿들어 앱의 저장으로 돌려 준다.
  //  - 같은 형식: 앱의 "저장" 을 그대로 부른다 (덮어쓰기, 백업, 고쳐짐 표시를 한 곳에서 처리)
  //  - 다른 형식(PDF 로 다운로드 등): 받은 바이트를 그대로 넘겨 "다른 이름으로 저장" 하게 한다
  function watchEditorSaves() {
    var win = frame.contentWindow;
    if (!win || win.__pocketWatching) return;
    win.__pocketWatching = true;
    win.addEventListener('message', function (event) {
      var data = event.data || {};
      if (data.type !== 'onlyoffice-file-stream' || inflight > 0) return;
      var name = String(data.fileName || '');
      var ext = (name.split('.').pop() || String(data.fileType || '')).toLowerCase();
      if (!ext || ext === saveExt) {
        emit('requestSave');
        return;
      }
      if (!data.buffer) return;
      var id = 'export-' + Date.now() + '-' + exportSeq++;
      deliver(id, { file: new Blob([data.buffer]), fileName: name, dirty: true });
    });
  }
  frame.addEventListener('load', watchEditorSaves);

  window.Pocket = {
    /** @param {{url: string, fileName: string, saveExt?: string, readonly?: boolean}} opts */
    open: function (opts) {
      saveExt = String(opts.saveExt || opts.fileName.split('.').pop() || '').toLowerCase();
      post('document:open-url', {
        url: new URL(opts.url, location.href).href,
        fileName: opts.fileName,
        readonly: !!opts.readonly,
      });
    },

    /** 편집 중인 문서를 targetExt(DOCX, XLSX, PPTX, PDF, CSV ...) 로 내보낸다 */
    save: function (id, targetExt) {
      saves[id] = { ext: targetExt };
      inflight++;
      var payload = {};
      if (targetExt) payload.targetExt = targetExt;
      post('document:save', payload, id);
    },

    setReadonly: function (readonly) {
      post('document:set-readonly', { readonly: !!readonly });
    },
  };

  var locale = params.get('locale') || 'ko';
  frame.src = '/editor.html?embed=1&locale=' + encodeURIComponent(locale);

  function createDevNative() {
    function send(path, body) {
      return fetch(path, { method: 'POST', body: body }).catch(function () {});
    }
    var parts = {};
    var meta = {};
    var dev = {
      log: function (m) {
        console.log('[native]', m);
      },
      onEvent: function (type, json) {
        console.log('[native] event', type, json);
        send('/__dev/event?type=' + encodeURIComponent(type), json);
        // 개발 서버는 ?doc= 로 열 파일을 받는다
        if (type === 'ready' && params.get('doc')) {
          window.Pocket.open({ url: '/__dev/doc/' + params.get('doc'), fileName: params.get('doc') });
        }
      },
      saveBegin: function (id, name, mime, size) {
        parts[id] = [];
        meta[id] = name;
        console.log('[native] saveBegin', id, name, mime, size);
      },
      saveChunk: function (id, b64) {
        parts[id].push(b64);
      },
      saveEnd: function (id, dirty) {
        console.log('[native] saveEnd', id, 'dirty=', dirty);
        var blob = new Blob(
          parts[id].map(function (b) {
            var s = atob(b);
            var a = new Uint8Array(s.length);
            for (var i = 0; i < s.length; i++) a[i] = s.charCodeAt(i);
            return a;
          }),
        );
        send('/__dev/save?name=' + encodeURIComponent(meta[id]), blob);
        delete parts[id];
      },
      saveFailed: function (id, message) {
        console.warn('[native] saveFailed', id, message);
      },
    };
    window.__pocketDev = dev;
    return dev;
  }
})();
