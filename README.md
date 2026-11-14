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
ALLOW_DEMO_SEED=1 DEMO_PASSWORD='choose-a-demo-password' ../.venv/bin/python -m app.manage seed-demo
../.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

브라우저에서 `http://127.0.0.1:8000`을 엽니다. API 문서는 `/docs`입니다. 기본 SQLite DB와 비공개 파일은 `.data/`에 영속 저장됩니다. 파일 경로는 정적 서비스에 노출하지 않습니다. 로컬 파일 저장은 MVP의 대체 저장소이며 비공개 클라우드 객체 저장소는 연결하지 않았습니다.

시연 계정은 `requester@demo.local`, `witness@demo.local`, `other@demo.local`이며 비밀번호는 위에서 지정한 값입니다. 최초 생성 후 재실행은 기존 비밀번호를 바꾸지 않습니다. `DEMO-PARK/B2/B`의 최근 가상 세션을 생성하고, `other`는 B3에 배치합니다. **가상 차량 확인 상태는 실제 소유권 인증이 아닙니다.** 영상은 자동 생성·수집하지 않습니다.

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
| pleosDebug | Vehicle SDK 2.3.2의 읽기 전용 기어 조회 + CarUxRestrictions 연동 코드. 실제 신호 검증 전. |
| demoRelease / pleosRelease | 서명되지 않은 release APK. HTTPS만 허용. 제출용 서명 키와 lineage는 별도 준비 필요. |

APK는 `app/build/outputs/apk/`에 생성됩니다. 차량 앱은 인증·주차 참여·참여 철회·수동 출차 확인·요청 현황을 제공합니다. 상세 사건 접수·영상 공유는 모바일 웹에서 진행합니다. 에뮬레이터에서 서버 주소는 `http://10.0.2.2:8000`입니다. 로그인 비밀번호는 서버 로그인 후 지우며 토큰은 메모리에만 보관합니다. 재시작 후 다시 로그인합니다. 시설·층·구역·서버 주소 초안은 기기에 저장됩니다.

Pleos 빌드는 미확인·제한·비주차·연결 종료·오래된 차량 상태에서 상세 입력을 잠급니다. 화면 복귀 시 재확인하고, 백그라운드 전환 시 상태를 무효화합니다. 기어값을 위치·층·출차 시각으로 바꾸지 않으며 모든 위치 등록은 **수동 입력**입니다. 실제 Pleos AVD와 실차 검증 결과는 [검증 기록](docs/VALIDATION.md)을 확인하세요.

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
- 알림은 모바일/차량 앱의 요청함 새로고침 방식입니다. 이메일 발송·푸시·차량 전원 종료 후 즉시 수신은 구현하지 않았습니다.

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

PostgreSQL 16과 API 컨테이너를 구성합니다. 외부 DB를 직접 쓸 때는 `DATABASE_URL=postgresql+psycopg://...`를 지정합니다. 시작 시 SQLAlchemy가 초기 스키마를 생성합니다. 스키마 변경 마이그레이션, 운영자 UI, 객체 저장소, 외부 인증 공급자, 메일/푸시 전송, 프로덕션 악용 탐지·로그인 속도 제한·업로드 악성 파일 검사·프록시 요청 크기 제한은 후속 작업입니다. 인터넷 공개 운영 준비를 마친 제품으로 간주하지 않습니다. Docker/PostgreSQL의 실제 실행 여부는 검증 기록에 구분합니다.

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

[기획 대응 범위](docs/SCOPE.md) · [검증 기록](docs/VALIDATION.md) · [시연 순서](docs/DEMO.md)

## 화면 디자인

차량 앱과 모바일 웹은 딥 그린·세이지·크림 팔레트, 공통 W 브랜드, 주차 일러스트를 사용합니다. 차량 앱은 넓은 화면에서 소개와 입력을 두 열로 나누고, 모바일 웹은 로그인 후 활동 요약과 사건·요청·주차 탭을 제공합니다. 서버 연결 설정은 펼쳐서 확인합니다.

고객 화면에서는 SDK 검증 상태와 개발용 설명을 제거했습니다. 구현 범위·미검증 연동 사항은 이 README와 검증 문서에서 관리합니다. 파일 직접 등록, 개인정보 확인, 개별 승인·철회 안내는 해당 작업 흐름에 유지하며 실제 모의 데이터 항목의 표시는 유지합니다. 일반 등록 화면에 가상 주차장 값을 미리 채우지 않습니다.

[차량 앱 화면](docs/android-design-login.png) · [모바일 첫 화면](docs/web-design-login.png) · [활동 요약](docs/web-design-dashboard.png)
