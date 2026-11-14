# MVP 검증 기록

검증일: 2026-11-12, Asia/Seoul. 기존 Park on Wheels의 검증 결과를 재사용하지 않고 새 프로젝트를 빌드·시험했습니다.

## 확인 결과

| 항목 | 결과 |
| --- | --- |
| GitHub 조직 권한 | HTTPS 인증으로 Atelier-PJ active/admin 확인. 비공개 저장소 생성 HTTP 201, main push 성공 |
| SQLite HTTP 통합 테스트 | 33 passed. [로그](sqlite-tests.log) |
| PostgreSQL 16.14 HTTP 통합 테스트 | 별도 일회성 컨테이너 DB에서 33 passed. [로그](postgres-tests.log) |
| Kotlin 핵심 제한 규칙 테스트 | 1 passed, failures=0, errors=0. [JUnit 결과](kotlin-test.xml) |
| 웹 JavaScript | node --check web/app.js 통과 |
| 브라우저 실사용 | 앱 내 브라우저에서 계정 로그인·사건 접수·익명 1명 매칭·목격자 요청함·모의 PNG 업로드·미리보기·수신자/목적 확인·개별 승인 확인 |
| 모바일 화면 | 약 580px 폭에서 요청함/승인 화면 확인. [승인 화면](mobile-approved.png) |
| Android debug | demoDebug / pleosDebug APK 생성 성공 |
| Android release | demoRelease / pleosRelease APK 생성 성공. 둘 다 **unsigned**, 제출용 서명 미적용 |
| 로컬 전체 Android 빌드 | JDK 17.0.21, Gradle 8.11.1, AGP 8.9.2, Kotlin 2.0.21, SDK 35, Build Tools 36.0.0. BUILD SUCCESSFUL, 174 tasks. [로그](android-build.log) |
| GitHub Actions | [서버·PostgreSQL·Android 작업 모두 성공](https://github.com/Atelier-PJ/park_witness/actions/runs/43218079313). APK 아티팩트 업로드 성공 |
| Pleos 실제 차량 신호 | **미검증**. SDK 2.3.2의 typed Gear 조회·CarUxRestrictions 코드는 컴파일 완료. 실차 값/권한/변경 알림 검증과 구분 |
| Pleos 에뮬레이터의 demo 앱 실행 | Android 14/API 34에서 demo APK 직접 설치 Success. MainActivity COLD 실행 Status: ok, TotalTime 1646ms, 정상 프로세스 확인. [실행 화면](android-demo-runtime.png). 모의 P/UX 상태이며 실제 SDK 신호 확인과 구분 |
| 실제 영상 SDK | **미연동**. 블랙박스·다른 차량 영상 조회·V2V 없음 |
| 메일·푸시·객체 저장소 | 미연동. 요청함 새로고침·비공개 로컬 파일 저장으로 대체 |

통합 테스트에는 다른 시설/층/시간 제외, 명시적 인접 구역 확대, 후보 익명화, 동시 중복 매칭, 차량 권한 미확인 차단, 다른 사용자 접근 차단, 승인 전 열람 차단, 요청/파일/링크 만료, 거절 후 상태 변경 차단, 철회·사건 종료 후 이전 링크 차단, 피해 사진 비공개, 재접속과 보관 삭제가 포함됩니다.

## 빌드 중 확인한 환경 문제와 수정

- Android Studio의 현재 Java 25는 이 프로젝트의 Kotlin/Gradle 조합과 맞지 않아 공식 Temurin JDK 17을 임시 폴더에서 사용했습니다.
- 문서 폴더의 클라우드 동기화로 SDK JAR가 dataless 자리표시자가 되어 EOFException이 발생했습니다. 소스·SDK를 `/private/tmp`의 별도 빌드 디렉터리로 복사해 빌드했습니다. 사용자 전역 SDK 설정은 바꾸지 않았습니다.
- Kotlin daemon의 기존 권한 문제를 피하기 위해 로컬 검증 시 `--no-daemon -Pkotlin.compiler.execution.strategy=in-process`를 사용했습니다.
- 첫 CI는 SDK setup action의 기본 `tools` 패키지가 더 이상 제공되지 않아 실패했습니다. 설치 패키지를 `platform-tools`로 명시한 재실행은 모든 작업이 통과했습니다.
- SDK Gear 조회 메서드가 deprecated라는 경고가 있습니다. 컴파일 성공을 실제 지원 확인으로 간주하지 않습니다. 향후 Google GEAR_SELECTION 속성 전환 검토가 필요합니다.
- Chrome CLI 실행은 macOS 권한 제한으로 실패해 앱 내 브라우저로 검증했습니다. 완전한 다중 기기/해상도 브라우저 자동화, 실제 영상 재생 코덱 시험은 수행하지 않았습니다.

## 결과물

`artifacts/`에 로컬 APK 4개와 SHA256SUMS가 있습니다. APK는 Git에 넣지 않으며 GitHub Actions의 `park-witness-apks` 아티팩트에서도 받을 수 있습니다. debug APK는 개발 서명으로 설치용이며 HTTP 개발 서버 연결을 허용합니다. release APK는 서명 전 패키지이며 HTTPS만 허용합니다. 실제 공모전 제출용 서명 키·lineage·플랫폼 심사·실차 검증은 완료되지 않았습니다.

공개 운영 배포는 하지 않았습니다. DB 스키마 마이그레이션, 운영자 인증 UI, 파일 악성 검사, 서버 로그인 속도 제한, 푸시/메일 전송, 실제 시설 배치, 실제 사용자·차량 확인과 보관 정책 운영은 별도 작업입니다.

## Android Studio 재실행 확인

ParkWitness 프로젝트를 Android Studio에서 다시 열고 Pleos AVD를 시작했습니다. IDE의 기존 SDK 경로 설정 파일 수정은 자동 승인 검토에서 허용되지 않아 적용하지 않았습니다. 이미 검증된 APK를 로컬 디버그 연결로 직접 설치해 실행을 확인했습니다. IDE의 Run 버튼으로 재빌드·설치하는 전체 흐름과 실제 차량 신호 검증은 완료 항목에 포함하지 않습니다.

## 디자인 및 사용자 문구 개선 (2026-11-14)

- 작업 전 origin/main을 가져와 비교했으며 로컬과 동일했습니다.
- 차량 앱: 공통 Compose 테마, 반응형 두 열 구성, 로그인·참여 현황 분리, 로그아웃, 필수값 검증, 백그라운드 전환 후 오래된 응답 무시.
- 웹: 브랜드·일러스트·입력·요청 상태·빈 화면 개선, 실제 API 응답 기반 요약 수치, 탭 선택 접근성 상태. SDK·검증·데모 설명과 해시를 일반 화면에서 제거하고 사용자 행동 중심으로 문구를 수정했습니다.
- SQLite HTTP 테스트 33개 통과, core 테스트 통과(변경 없음으로 UP-TO-DATE), JavaScript 구문 및 diff 공백 검사 통과.
- demoDebug, pleosDebug 빌드 성공. 최신 demo APK를 Pleos API 34 에뮬레이터에 재설치하여 실행(Status: ok)하고 새 화면을 확인했습니다. pleos flavor의 차량 신호 런타임 검증은 추가로 수행하지 않았습니다.
- 로컬 웹에서 테스트 계정 가입·로그인, 요약 0건, 요청함·주차 탭 전환, 로그아웃을 확인했습니다. 별도 테스트 계정으로 진행했으며 실제 사용자 자료를 업로드하지 않았습니다.
- 스크린샷: android-design-login.png, web-design-login.png, web-design-dashboard.png. APK는 artifacts/park-witness-demo-debug.apk 및 artifacts/park-witness-pleos-debug.apk에 복사했습니다(버전 관리 제외).

## 진단 후 안정화 및 E2E 회귀 시험

2026-11-14. 새 API 회귀 시험 49개를 SQLite에서 통과했습니다. 첫 GitHub 검증에서는 backend·postgres·android 작업이 모두 성공했습니다. S3 호환 HTTP 시험 서버(Moto)와 로컬 SMTP 수신 서버로 저장·익명 접근 차단·삭제와 이메일 주소 확인·동의·알림 수신·해제를 시험했습니다. 운영 AWS/S3나 외부 이메일 수신함 전달과 구분합니다.

차량 앱의 차량·방향 선택 추가 후 core:test와 demo/pleos debug/release APK 4종 빌드에 성공했습니다. release는 unsigned이며 실제 Pleos 신호 런타임 검증을 완료했다는 의미는 아닙니다.

앱 내 브라우저에서 피해 사진 실패→동일 사건에서 사진만 재시도→1건 유지, 사진 조회, 익명 매칭, 다른 계정에서 빈 입력 폼, 공유 자료 등록을 확인했습니다. macOS Chromium은 MachPort 권한 오류로 실행하지 못했습니다. GitHub의 첫 E2E 환경은 ubuntu-latest가 Ubuntu 26.04로 변경돼 Playwright 1.56.1 설치에 실패했습니다. E2E 작업만 지원되는 Ubuntu 24.04로 고정해 다시 검증합니다.

기존 로컬 DB 전체 백업은 자동 승인 검토에서 사적 데이터 접근 위험으로 거절됐으며 해당 데이터를 복사·변경하지 않았습니다. 시험에는 별도 일회성 합성 데이터만 사용했습니다.

### 최종 GitHub 검증 통과

코드 커밋 `91b6a478eefeef33c395aea09b6efef0f47f56a9`의 [전체 검증 실행](https://github.com/Atelier-PJ/park_witness/actions/runs/43555277285)에서 backend, postgres, e2e, android가 모두 success입니다. SQLite와 PostgreSQL 모두 49개 시험을 통과했고 Android APK 4종을 업로드했습니다.

실제 Chromium 자동 시험은 390px 화면에서 사진 실패 후 재시도·사진 조회·후보 매칭·계정별 입력 및 사건 분리·파일 업로드·디코딩 전 승인 차단·두 자료 개별 승인·열람 기한 선택·다운로드 바이트 일치·다른 계정의 사건 미노출·사건 종료 후 자료 버튼 제거·가로 넘침 없음을 확인했습니다. GitHub 로그에 E2E PASS를 기록하고 browser-e2e-results에 결과 화면을 저장했습니다.

[APK 다운로드](https://github.com/Atelier-PJ/park_witness/actions/runs/43555277285/artifacts/14097534696) · [브라우저 시험 화면](https://github.com/Atelier-PJ/park_witness/actions/runs/43555277285/artifacts/14097419531)

운영용 SMTP/S3 자격증명·실제 시설 배치·실제 차량 권한 검토·실차 신호/주행 제한 런타임·제출용 서명은 외부 환경에서 별도 확인해야 합니다. 이 검증은 기획의 전체 출시·실차·파일럿 검증 완료를 뜻하지 않습니다.
