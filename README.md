# 포켓오피스 (PocketOffice)

폰에서 워드·엑셀·파워포인트·PDF 를 **보고, 고치고, 저장하는** 안드로이드 앱.

**[📥 최신 APK 받기](https://github.com/Manggome/PocketOffice/releases/latest/download/PocketOffice.apk)**: 폰에서 이 링크를 누르면 바로 설치됩니다.

- 광고 없음, 로그인 없음. 문서는 **기기 밖으로 나가지 않습니다** (편집기가 폰 안에서만 돕니다)
- 처음 한 번만 직접 설치하면 이후 업데이트는 앱 안에서 이어집니다
- 최소 안드로이드 8.0 (API 26). 갤럭시 Z 폴드처럼 접고 펴는 화면을 고려했습니다

---

## 열고 고칠 수 있는 것

| 종류 | 고쳐서 그대로 저장 | 열기만 (저장은 새 형식으로) |
|---|---|---|
| 문서 | `.docx` `.odt` `.rtf` | `.doc` → `.docx` 로 저장 |
| 스프레드시트 | `.xlsx` `.ods` `.csv` | `.xls` → `.xlsx` 로 저장 |
| 프레젠테이션 | `.pptx` `.odp` | `.ppt` → `.pptx` 로 저장 |
| PDF | 메모·주석·양식 채우기·텍스트 편집 | |

- 무엇이든 **PDF 로 내보내기**, **공유**, **인쇄** 가 됩니다
- 옛 형식(doc/xls/ppt)은 엔진이 다시 쓸 수 없어서, 저장하면 원본 옆에 새 형식(docx/xlsx/pptx) 파일을 만듭니다. 원본은 그대로 남습니다

## 보기 모드 / 편집 모드

첫 화면 맨 위에서 고릅니다. 문서를 누르면 고른 모드로 열리고, 다른 앱(파일 관리자, 카카오톡)에서 문서를 눌러도 같습니다. 각 문서의 ⋮ 메뉴에서 반대 모드로 열 수도 있습니다.

| | 보기 모드 | 편집 모드 |
|---|---|---|
| 워드·엑셀·파워포인트 | 툴바·메뉴·눈금자 없이 문서만. 위쪽에 쪽 수, 프레젠테이션은 아래에 ◀ 1 / 10 ▶ | OnlyOffice 편집기 그대로 |
| PDF | **앱 자체 뷰어** (안드로이드 기본 렌더러) — 이어 넘기기, 두 손가락 확대, 두 번 탭 확대, 오른쪽 손잡이로 빨리 넘기기, 쪽 번호 눌러 이동, 어두운 페이지 | 주석·양식·텍스트 편집 |
| 바꾸기 | 위쪽 **편집** | ⋮ > **보기 모드로** (저장한 뒤) |

PDF 뷰어는 편집 엔진을 띄우지 않아서 엔진을 받기 전에도, 큰 PDF 도 가볍게 열립니다.

## 첫 화면

- **새로 만들기**: 문서 / 스프레드시트 / 프레젠테이션
- **최근**: 연 문서 목록. ☆ 로 즐겨찾기하면 위에 고정됩니다
- **내 폰**: 다운로드·카카오톡 받은 파일·문서 폴더의 오피스 파일과 PDF 를 모두 모아 보여 줍니다. `모든 파일 접근` 을 허용해야 하며, 허용하지 않아도 `파일 열기` 로 하나씩 열 수 있습니다
- 종류별 거르기(문서/스프레드시트/프레젠테이션/PDF), 이름 검색, 최근 수정순/이름순
- 파일 관리자, 카카오톡, 메일에서 문서를 누르거나 `공유 > 포켓오피스` 로 넘겨도 바로 열립니다

## 편집 화면

- 위쪽 막대: **저장**, 더 보기(다른 이름으로 저장 · PDF 로 내보내기 · 공유 · 인쇄 · 읽기/편집 모드)
- 편집기 안의 저장 버튼과 키보드 `Ctrl+S` 도 같은 저장으로 이어집니다
- 저장하지 않은 내용이 있으면 제목 아래에 `● 저장 안 됨` 이 뜨고, 나갈 때 물어봅니다
- 접었다 펴거나 화면을 돌려도 편집 중인 내용이 그대로 남습니다
- 문서마다 최근 앱 목록에 따로 떠서 여러 문서를 오가기 쉽습니다

### 날아가지 않게

- **원본 백업**: 파일을 처음 덮어쓰기 직전에 원본을 앱 안에 한 부 남깁니다 (최근 15개)
- **복구 사본**: 저장하지 않고 다른 앱으로 넘어가면 지금 내용을 앱 안에 떠 둡니다. 시스템이 앱을 내려 버려도 같은 문서를 다시 열거나 첫 화면의 `저장하지 않고 닫은 문서` 에서 이어서 편집할 수 있습니다
- 다른 앱이 "읽기만" 허락한 파일(예: 메신저 미리보기)은 덮어쓸 수 없어서 새 파일로 저장하게 안내합니다

---

## 설치 / 업데이트

1. 위의 **최신 APK 받기** 링크로 처음 한 번 설치합니다
2. 처음 켜면 **편집 엔진(약 180MB)** 을 한 번 받습니다. Wi-Fi 면 자동으로, 데이터 망이면 버튼을 눌러 받습니다. 그다음부터는 인터넷이 없어도 됩니다
3. 이후에는 앱을 켤 때 새 버전이 있는지 조용히 확인하고, 있으면 `업데이트` 를 눌러 앱 안에서 받아 설치합니다 (`설정 > 앱 업데이트` 에서 직접 확인도 가능)
   - 처음 업데이트할 때 안드로이드가 "이 출처 허용" 을 한 번 묻습니다
   - 서명 키가 고정돼 있어 지우지 않고 덮어쓰기로 설치되고, 최근 목록·설정이 남습니다

**엔진을 APK 와 따로 받는 이유**: 엔진과 글꼴을 APK 에 넣으면 215MB 가 되고, 앱을 고칠 때마다 업데이트가 그만큼 커집니다. 따로 두면 APK 는 약 11MB 이고, 엔진은 엔진이 바뀔 때만 다시 받습니다.

---

## 빌드 (GitHub Actions)

push 하면 `.github/workflows/build.yml` 이 돕니다.

1. **engine**: `engine/ENGINE_REF`(ranuts/document 커밋)와 `engine/prepare-engine.mjs` 의 해시로 엔진 id 를 정합니다. `engine-<id>` 릴리스가 없을 때만 ranuts/document 를 빌드하고 → 쓰지 않는 파일을 걸러 → `engine.zip` + `engine.zip.sha256` 을 프리릴리스로 올립니다
2. **build**: APK 를 빌드해 `latest` 릴리스에 `PocketOffice.apk` 와 `latest.json` 을 올립니다. 앱은 `latest.json` 의 versionCode 를 자기 것과 비교해 업데이트합니다

- 버전은 CI 실행번호로 매깁니다 (`1.0.<run_number>`)
- 서명 키는 `keystore/debug.jks` 로 저장소에 고정돼 있습니다
- 빌드가 실패하면 원인이 실행 요약과 `ci-logs` 브랜치(`last-failure.txt`)에 올라갑니다
- 엔진을 올리려면 `engine/ENGINE_REF` 의 커밋만 바꾸면 됩니다. 새 id 로 엔진이 만들어지고, 새 APK 가 처음 켜질 때 새 엔진을 받습니다

## 로컬에서 고치기

```bash
# 1) 엔진 만들기 (한 번)
git clone https://github.com/ranuts/document /tmp/document
cd /tmp/document && git checkout $(cat <포켓오피스>/engine/ENGINE_REF) && corepack pnpm install && corepack pnpm run build
cd <포켓오피스> && node engine/prepare-engine.mjs /tmp/document/dist engine/build/engine

# 2) 브라우저에서 앱과 같은 구조로 띄우기 (tools/samples/ 에 시험 파일을 넣어 두고)
node tools/dev-server.mjs
# http://localhost:8790/__pocket/host.html?doc=test.docx
#   콘솔에서 Pocket.save('s1', 'DOCX') → tools/out/ 에 저장된다
```

폴더 이름에 한글이 있으면 윈도우에서 Gradle 이 멈추므로 로컬 빌드는 `-Pandroid.overridePathCheck=true` 를 붙입니다.

---

## 프로젝트 구조

```
app/src/main/
├── assets/
│   ├── shell/host.html, host.js   앱 ↔ 편집기 다리 (embed postMessage API 를 부린다)
│   └── templates/blank.*          새 문서용 빈 파일 (편집기가 직접 만든 것)
└── java/kr/neptune/pocketoffice/
    ├── PocketOfficeApp.kt          전역 저장소
    ├── MainActivity.kt             첫 화면 / 설정, 파일 고르기, 권한
    ├── core/
    │   ├── DocTypes.kt             형식 ↔ 확장자 ↔ MIME, 저장 형식
    │   ├── DocIo.kt                content:// · file:// 읽기/덮어쓰기(잘라 쓰기), 권한, 원본 백업
    │   ├── EngineStore.kt          엔진 zip 받기(이어 받기·해시 확인)와 읽기
    │   ├── RecentStore.kt          최근 문서
    │   ├── RecoveryStore.kt        저장 안 된 내용의 복구 사본
    │   ├── DeviceScanner.kt        MediaStore 로 폰 안의 문서 찾기
    │   ├── Prefs.kt                설정
    │   └── AppUpdater.kt           릴리스 확인 → APK 받기 → 설치
    ├── editor/
    │   ├── EditorActivity.kt       편집 화면 (회전·접기에 다시 만들어지지 않음)
    │   ├── EditorController.kt     열기·저장·내보내기·복구·나가기의 모든 흐름
    │   ├── EditorScreen.kt         위쪽 막대, 대화상자
    │   ├── EngineServer.kt         WebView 요청 가로채기 → 엔진 zip / assets / 문서 바이트
    │   ├── EditorBridge.kt         window.PocketNative (저장 바이트를 조각으로 받음)
    │   ├── DocRequest.kt           무엇을 열지 (파일 / 새 문서 / 복구)
    │   └── Outputs.kt              위치 고르기, 공유, 인쇄
    └── ui/                         첫 화면, 설정, 업데이트·엔진 카드, 테마
engine/
├── ENGINE_REF                      ranuts/document 커밋
└── prepare-engine.mjs              dist 에서 앱에 필요한 것만 추린다
tools/dev-server.mjs                앱의 WebView 와 같은 주소 구조로 띄우는 개발 서버
```

### 알아두면 도움되는 설계 결정

**편집기는 OnlyOffice 를 서버 없이 돌린 [ranuts/document](https://github.com/ranuts/document) 입니다.** 문서 변환(x2t)까지 WebAssembly 로 폰 안에서 돌아서 서버가 필요 없습니다. 직접 만든 편집기로는 docx/pptx 를 이만큼 정확하게 그리고 저장할 수 없습니다.

**WebView 가 `https://appassets.androidplatform.net` 을 여는 것처럼 보이지만 실제로는 모든 요청을 앱이 가로채 답합니다.** 편집기·글꼴은 받아 둔 zip 에서, 다리 페이지는 assets 에서, 문서는 SAF 스트림에서 꺼냅니다. 다른 주소로 나가는 요청은 막습니다.

**모바일 전용 UI 대신 데스크톱 UI 를 좁은 화면에 맞춘 것을 씁니다.** OnlyOffice 의 모바일 UI 에는 서버 없이 도는 패치가 들어 있지 않습니다. 폴드를 펴면 데스크톱 UI 가 넉넉하게 들어갑니다.

**저장은 편집기 → base64 조각 → 앱 → 파일입니다.** JavascriptInterface 는 문자열만 받습니다. 한 번에 넘기면 큰 파일에서 메모리가 튀어서 3MB 씩 끊어 넘깁니다.

**덮어쓰기는 `"wt"`(잘라 쓰기)로 합니다.** 그냥 `"w"` 는 일부 저장소에서 옛 길이를 남겨, 새 파일이 짧으면 끝에 쓰레기가 붙어 docx(zip)가 깨집니다. `"wt"` 가 안 되는 곳은 `"rw"` 로 열어 직접 자릅니다.

**글꼴 파일은 하나도 빼지 않았습니다.** 엔진은 글꼴을 번호로 찾습니다. 파일을 빼거나 바꾸면 글자가 엉뚱한 모양으로 그려지는 문제가 있어서(ranuts/document `docs/fonts.md`) 크기보다 정확성을 택했습니다.

---

## 라이선스

AGPL-3.0 ([LICENSE](LICENSE), [NOTICE](NOTICE)). 편집 엔진이 AGPL-3.0 인 ONLYOFFICE 를 담고 있어서 이 앱도 같은 조건으로 공개합니다. 편집기 안의 ONLYOFFICE 로고는 그 조건에 따라 그대로 둡니다.
