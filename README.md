# Park Witness

주차 사고의 시간·장소가 겹치는 참여자를 익명 목격 후보로 연결하고, 제공자가 검토·승인한 자료만 사건 요청자에게 전달하는 MVP입니다. `Park_Witness_Application.docx`의 작품 소개를 기준으로 구현했습니다. 영상 확보나 사고 해결을 보장하지 않습니다.

**구성:** Kotlin/Compose 차량 앱 + FastAPI + 반응형 모바일 웹. 가상 계정·모의 주차 세션과 실제 서버 처리 흐름을 구분합니다. 블랙박스/다른 차량 영상 조회, 자동 충격 감지, V2V, 자동 마스킹, AI 책임 판정은 구현하지 않았습니다.

## 빠른 실행

Python 3.11과 Node.js(웹 구문 검증용)가 필요합니다.

```bash
python3.11 -m venv .venv
.venv/bin/pip install -r backend/requirements.txt
cd backend
# 선택: 로컬 시연 전용 계정/주차 이력 생성. 실제 서비스 DB에서는 실행하지 마세요.
ALLOW_DEMO_SEED=1 DEMO_PASSWORD='park123456' ../.venv/bin/python -m app.manage seed-demo
../.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

브라우저에서 `http://127.0.0.1:8000`을 엽니다. API 문서는 `/docs`입니다. 기본 SQLite DB와 비공개 파일은 `.data/`에 영속 저장됩니다. 파일 경로는 정적 서비스에 노출하지 않습니다. 기본은 로컬 파일 저장이며 아래 환경 설정으로 비공개 S3 호환 객체 저장소를 선택할 수 있습니다.

시연 계정은 `requester@demo.local`, `witness@demo.local`, `other@demo.local`이며 기본 비밀번호는 `park123456`입니다. `demoDebug`는 에뮬레이터에서 실행하면 requester로 자동 로그인합니다. 로그인 화면이 필요한 경우 **시연 계정 채우기**를 누를 수 있습니다. 자동 로그인은 demo flavor에만 적용하며 pleos flavor에는 적용하지 않습니다. `seed-demo`를 다시 실행하면 기존 시연 계정의 비밀번호도 지정한 값으로 초기화됩니다. `DEMO-PARK/B2/B`의 가상 세션을 생성하고 `other`는 B3에 배치합니다. **가상 차량 확인 상태는 실제 소유권 인증이 아닙니다.** 앱의 기스 사진과 주변 차량 영상 화면은 시연용 예시이며, 실제 사고 사진이나 주변 차량 영상이 아닙니다.

1. requester로 로그인하고 최근 1시간~30분 전의 B2/B 사건을 등록합니다. 피해 사진은 선택 사항이며 요청자만 열람합니다.
2. 사건의 **목격 후보 찾기**를 누릅니다. 같은 시설·층·구역과 겹치는 시간만 검색합니다. 인접 A/C 구역은 별도 확대 버튼을 눌러야 요청합니다.
3. 로그아웃 후 witness로 로그인해 받은 요청을 확인합니다. 영상 없음·확인 예정·공유 검토·거절 중 선택합니다.
4. 사전 동의한 짧은 MP4/PNG/JPEG 공유본을 **모의 자료**로 등록합니다. 직접 편집·마스킹한 실물 자료는 **수동 공유본**으로 구분합니다. 서버는 파일을 편집하지 않습니다.
5. 미리보기를 열고 수신자·사건 목적·24시간 열람에 동의해 개별 승인합니다. 업로드 자체는 공유 승인이 아닙니다.
6. requester로 다시 로그인해 승인 자료를 받습니다. 제공자 철회·열람 만료·사건 종료 후에는 이전 접근 수단도 차단됩니다.

휴대전화에서 개발 서버에 접속하려면 같은 네트워크의 개발 PC 주소를 사용하고 서버를 명시적으로 `--host 0.0.0.0`으로 실행합니다. 실제 파일·계정으로 운영하려면 HTTPS를 구성해야 합니다. 기본 실행은 로컬 접속만 허용합니다.

## Android 빌드

기존 [Atelier-PJ/park_in_wheels](https://github.com/Atelier-PJ/park_in_wheels)의 `app/core`, Compose, `demo/pleos`, Vehicle SDK 어댑터 구조를 참고했습니다. 참조 커밋: `25ea484b76f1004728d48a82c31e0009d00ac110`. 기존 프로젝트의 기능·영상·인증정보를 복사하지 않았습니다.

JDK **17**, Android platform **35**, Build Tools **36.0.0**이 필요합니다. `local.properties`에 로컬 `sdk.dir`을 지정하거나 `ANDROID_HOME`을 설정합니다.

```bash
./gradlew :core:test :app:assembleDemoDebug :app:assemblePleosDebug
./gradlew :app:assembleDemoRelease :app:assemblePleosRelease
```

| 빌드 | 동작 |
| --- | --- |
| demoDebug | 가상 P/UX 허용을 명시한 시연 앱. 로컬 HTTP 허용. |
| demoRelease | 공모전 시연용 release variant. HTTP는 데모 에뮬레이터 주소 `10.0.2.2`에만 앱 코드에서 허용합니다. |
| pleosDebug | Vehicle SDK 2.3.2의 읽기 전용 기어 조회 + CarUxRestrictions 연동 코드. 실제 신호 검증 전. |
| pleosRelease | HTTPS만 허용하는 Pleos용 release variant. Gradle 산출물에는 별도 서명이 필요합니다. |

APK는 `app/build/outputs/apk/`에 생성됩니다. 차량 앱에는 홈 요약, 사고 접수, 받은 요청, 주차 참여 화면이 있습니다. 사고 화면의 기스 사진은 작성 방법을 보여주는 예시이며 사건에 첨부되지 않습니다. 실제 피해 사진과 영상 공유본 등록·미리보기·승인은 모바일 웹에서 진행합니다. `demoDebug` 자동 로그인은 시연용 requester 계정을 사용하며 실제 사용자 인증을 대체하지 않습니다. 에뮬레이터의 서버 주소는 `http://10.0.2.2:8000`입니다. 토큰은 메모리에만 보관하고, 시설·층·구역·서버 주소 초안은 기기에 저장합니다.

공모전 제출용 APK는 `demoRelease` variant를 별도 서명해 [GitHub Release](https://github.com/Atelier-PJ/park_witness/releases/tag/park-witness-submission-2026-11-15)에 제공합니다. 서명 키는 저장소에 포함하지 않습니다. 이 데모 APK는 로컬 에뮬레이터 API 실행을 전제로 하며, 운영용 배포판이 아닙니다.

Pleos 빌드는 미확인·제한·비주차·연결 종료·오래된 차량 상태에서 상세 입력을 잠급니다. 화면 복귀 시 재확인하고, 백그라운드 전환 시 상태를 무효화합니다. 기어값을 위치·층·출차 시각으로 바꾸지 않으며 모든 위치 등록은 **수동 입력**입니다. 시연 확인 환경은 Pleos Connect AVD, Android 14/API 34, 1080×600 px입니다. 실제 차량 신호와 UX 제한의 런타임 검증 결과는 [검증 기록](docs/VALIDATION.md)을 확인하세요.

## 서버 데이터와 권한

- 암호는 salt+scrypt, 로그인 토큰은 해시만 저장하며 8시간 만료됩니다. 로그아웃은 서버 세션을 폐기합니다.
- 신규 계정의 차량은 확인 대기입니다. 운영자가 별도 증빙을 확인한 뒤 `python -m app.manage verify-vehicle VEHICLE_ID --review-reference REVIEW_REFERENCE`로 승인합니다. 증빙 원문·연락처를 코드나 명령 인자에 넣지 않습니다. 이 절차는 외부 본인인증·자동 소유권 검증을 대체하지 않습니다.
- 시설·층·시간이 모두 겹쳐야 매칭합니다. 기본은 같은 구역입니다. 인접 구역 확대는 별도 사용자 동작이며 A–B–C의 시연용 인접표만 제공합니다. 실제 시설 배치는 운영자 설정이 필요합니다. 방향은 동일 구역 내 보조 정렬 기준이며 촬영 여부를 판정하지 않습니다.
- 종료 시각을 모르는 세션은 마지막 관측까지만 매칭하고 자동 출차나 상시 실행을 전제하지 않습니다. 주차 세션은 시작 후 72시간 만료됩니다.
- 후보 목록에는 임의 별칭·요청 상태만 반환하고 제공자 계정 ID·차량 ID·연락처·번호판·전체 주차 이력을 반환하지 않습니다.
- 요청은 사건별 제공자당 1회, 사건별 최대 10명, 제공자 하루 최대 5회, 요청자 하루 최대 5건으로 제한합니다. 거절·영상 없음은 재매칭으로 초기화되지 않습니다. 요청은 48시간 만료됩니다.
- 개별 승인에는 공유본 확인과 수신자·목적 확인이 필요합니다. 승인 자료만 요청자에게 공개하고, 최대 24시간 열람 + 최대 5분 접근 토큰 + 계정 인증을 매 요청마다 함께 검사합니다.
- 서버는 공유본 SHA-256, 자료 종류, 승인·접근·철회·삭제 이력을 기록합니다. 해시는 촬영 사실·가공 이력의 완전성·법적 증거능력을 보증하지 않습니다. 원본 수집과 자동 편집은 없습니다.
- 차단은 향후 매칭을 차단하고 기존 자료 접근도 중단합니다. 신고는 운영자 검토 기록이며 자동 신고 처리·외부 통보를 하지 않습니다.
- 요청함 새로고침과 선택적 이메일 알림을 지원합니다. 이메일은 SMTP 설정·주소 확인·사용자 동의가 필요하며, 푸시와 차량 전원 종료 후 즉시 수신은 구현하지 않았습니다.

보관 기간은 기획서의 **초기 정책 가안**을 적용했습니다. 다음 명령을 운영 환경의 일별 작업으로 실행해야 실제 삭제됩니다. 앱 내 자동 스케줄러나 일별 작업은 등록하지 않았습니다.

```bash
cd backend
../.venv/bin/python -m app.manage cleanup
```

이 명령은 만료 세션·접근 수단·로그인을 삭제하고, 종료 30일 후 사건/공유 자료/피해 사진과 오래된 미승인 업로드를 삭제합니다. 최소 감사 이벤트는 남습니다. 실제 보관 기간·접근 권한·제3자 권리·제공 근거·감사 이력 보관은 출시 전 확정해야 합니다.

## PostgreSQL / Docker

```bash
cp .env.example .env
# .env의 POSTGRES_PASSWORD를 긴 URL-safe 값으로 바꿉니다.
docker compose up --build
```

PostgreSQL 16과 API 컨테이너를 구성합니다. 외부 DB를 직접 쓸 때는 `DATABASE_URL=postgresql+psycopg://...`를 지정합니다. 시작 시 SQLAlchemy가 초기 스키마를 생성합니다. 스키마 변경 마이그레이션, 운영자 UI, 객체 저장소 운영 배치, 외부 인증 공급자, 푸시 전송, 프로덕션 악용 탐지·로그인 속도 제한·업로드 악성 파일 검사·프록시 요청 크기 제한은 후속 작업입니다. 인터넷 공개 운영 준비를 마친 제품으로 간주하지 않습니다. Docker/PostgreSQL의 실제 실행 여부는 검증 기록에 구분합니다.

## 테스트와 프로젝트 구조

```bash
(cd backend && ../.venv/bin/python -m pytest -q)
node --check web/app.js
./gradlew :core:test :app:assembleDemoDebug :app:assemblePleosDebug
```

`backend/`의 HTTP 통합 테스트는 실제 DB와 비공개 파일 저장을 사용합니다. 다른 층/시설/시간, 명시적 인접 확대, 후보 익명화, 동시 중복 요청, 무권한 접근, 만료, 철회, 거절, 수동 자료 구분, 보관 삭제, 재접속 등을 검증합니다. `core/` 테스트는 주차·UX·신호 신선도에 따른 입력 제한을 검증합니다. GitHub Actions는 서버 테스트와 두 flavor APK를 빌드합니다.

```text
app/src/main/      Compose 차량 앱·HTTP API
app/src/demo/      명시적인 모의 차량 상태
app/src/pleos/     실제 SDK 호출 코드·차량 화면 제한
core/             Android에 의존하지 않는 상태 제한 규칙·테스트
backend/app/      인증·매칭·요청·승인·비공개 파일·운영 CLI
backend/tests/    HTTP 통합 테스트
web/              서버와 같은 출처로 제공되는 반응형 모바일 UI
docs/             기획 대응표·검증 증거·API 스냅샷
```

[기획 대응 범위](docs/SCOPE.md) · [검증 기록](docs/VALIDATION.md) · [시연 순서와 제출 자료](docs/DEMO.md)

## 화면 디자인

차량 앱은 어두운 바탕과 절제한 주황색 강조색을 사용하며, 홈에서 사고 접수·요청·주차 상태와 시연용 영상 미리보기를 제공합니다. 사고 접수는 차량 가로 화면에서 시간·장소와 손상 정보를 입력하도록 구성했습니다. 모바일 웹은 로그인 후 사건·요청·주차 및 자료 승인 흐름을 제공합니다.

고객 화면에서는 SDK 검증 상태와 개발용 설명을 제거했습니다. 구현 범위·미검증 연동 사항은 이 README와 검증 문서에서 관리합니다. 파일 직접 등록, 개인정보 확인, 개별 승인·철회 안내는 해당 작업 흐름에 유지하며 실제 모의 데이터 항목의 표시는 유지합니다. 일반 등록 화면에 가상 주차장 값을 미리 채우지 않습니다.

[차량 앱 화면](docs/android-design-login.png) · [모바일 첫 화면](docs/web-design-login.png) · [활동 요약](docs/web-design-dashboard.png)

## E2E 안정화와 추가 기능

진단에서 재현된 계정 전환 입력 노출, 여러 파일 승인, 사진 실패 재접수, 반복 출차, 만료 표시, 파일 유실, 임의 인접 매칭, 손상 파일 등록을 보완했습니다. `backend/tests/test_regressions.py`가 API 회귀 시험을 담당하며 `web/tests/e2e.mjs`는 실제 Chromium에서 사진 실패 후 재시도부터 계정 전환, 두 파일 승인, 다운로드, 사건 종료까지 검증합니다.

```bash
pip install -r backend/requirements-test.txt
(cd backend && python -m pytest -q)
npm ci
npx playwright install --with-deps chromium
E2E_PYTHON=python npm run test:e2e
```

S3 호환 HTTP 서버를 별도로 실행한 경우 `TEST_S3_ENDPOINT_URL`을 설정하면 S3 업로드·다운로드·익명 접근 차단·보관 삭제 시험도 실행합니다. 시험은 합성 PNG·MP4와 일회성 DB를 사용하며 실제 사용자 서버를 변경하지 않습니다. 브라우저 결과 화면은 `output/e2e/`와 GitHub Actions의 `browser-e2e-results`에 저장합니다.

### 시설별 인접 구역

인접표가 없는 주차장에서는 같은 구역만 매칭합니다. 운영자는 실제 배치를 확인한 뒤 `python -m app.manage set-layout FACILITY layout.json`으로 대칭 인접표를 등록합니다. 예시 JSON은 `{"A":["B"],"B":["A","C"],"C":["B"]}`입니다. 촬영 방향은 현재 알려짐/미확인 보조 정렬이며 실제 시야의 교차를 계산하지 않습니다.

### 이메일 알림과 보관 작업

Compose의 별도 worker가 60초마다 정리 작업과 메일 재시도를 실행합니다. SMTP_HOST, SMTP_PORT, SMTP_FROM, SMTP_USER, SMTP_PASSWORD, PUBLIC_WEB_URL을 운영 환경에서 설정하세요. SMTP_STARTTLS의 기본값은 true입니다. 비밀번호는 저장소에 넣지 않습니다. API에도 SMTP_HOST·SMTP_FROM·PUBLIC_WEB_URL을 전달해 알림 제공 여부를 표시합니다.

사용자는 알림을 선택한 후 이메일의 20분 확인 링크를 열고 같은 계정으로 로그인해야 합니다. 확인·동의를 완료한 주소에만 새 요청 알림을 보내며, 수신 동의를 끄면 대기 알림도 취소합니다. 실패한 전송은 지연을 늘려 최대 5회 재시도합니다. SMTP 서버가 메일을 받은 직후 프로세스가 중단되는 경우 중복 알림 가능성이 있으므로 exactly-once 수신은 보장하지 않습니다. 외부 수신함까지의 실제 전달은 별도 운영 검증 대상입니다.

### 비공개 객체 저장소

기본 저장소는 로컬 비공개 디렉터리입니다. STORAGE_BACKEND=s3, S3_BUCKET, S3_ENDPOINT_URL, AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_DEFAULT_REGION으로 S3 호환 비공개 버킷을 사용할 수 있습니다. 버킷은 운영자가 미리 만들고 익명 접근을 차단해야 합니다. 다운로드는 서버의 계정·사건·승인·기한 검사를 거치며 공개 객체 URL을 제공하지 않습니다. 운영 AWS/S3 자격증명과 실제 버킷 배치는 포함하지 않습니다.

미디어는 이미지 디코딩·무결성 및 MP4 디코딩 검사 후 저장합니다. 영상 검사는 앞 60초와 최대 1800프레임, 25초 처리 제한으로 수행하며 전체 코덱·악성 파일의 안전성을 보증하는 검사는 아닙니다. 수동 편집·개인정보 검토가 필요하고 블랙박스/타 차량 영상 SDK는 연결하지 않습니다.

[개선 및 검증 범위](docs/FEATURE_AUDIT.md)
