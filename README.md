# diet-share-server

식단 사진을 올리면 AI가 영양 균형을 알려주고, 초대 코드로 들어온 친구들과 방에서 공유하는 앱의 백엔드입니다.

- Kotlin 2.1 · Spring Boot 3.5 · Java 21
- PostgreSQL (Flyway) · Redis · S3 호환 스토리지 (Cloudflare R2 / MinIO)
- 작업 큐: PostgreSQL `analysis_jobs` 테이블 + `FOR UPDATE SKIP LOCKED`

## 구조

```
com.dietshare
├── auth       카카오 로그인 → JWT, 인증 인터셉터, @CurrentUser
├── user       회원, 목표 타입, 선택 프로필, 탈퇴
├── meal       업로드 URL, 기록 생성(멱등), 캘린더/날짜 조회, 재시도, 삭제
├── analysis   작업 큐, 워커, AI 분석(stub / Anthropic), 7일 패턴 요약
├── room       방, 초대 코드, 피드(키셋 페이지네이션), 리액션, 신고
├── quota      하루 분석 횟수(Redis Lua), 입장 시도 제한
├── storage    S3 호환 스토리지, 썸네일
└── common     설정, 예외, 시간(KST)
```

## 로컬 실행

```bash
docker compose up -d          # postgres, redis, minio(+ 버킷 생성)
STORAGE_ENDPOINT=http://localhost:9000 \
STORAGE_ACCESS_KEY=minio STORAGE_SECRET_KEY=minio12345 \
DEV_LOGIN_ENABLED=true \
./gradlew bootRun
```

`STORAGE_ENDPOINT`를 비워 두면 메모리 스토리지로 뜹니다(재시작하면 사진이 사라짐).

```bash
./gradlew test                # Testcontainers로 Postgres, Redis를 띄워 통합 테스트 (Docker 필요)
```

## 사진 업로드 흐름

1. `POST /uploads` → `{ key, url }` (5분짜리 업로드 URL)
2. 앱이 긴 변 1080px JPEG로 줄여 `PUT url` (헤더 `Content-Type: image/jpeg`)
3. `POST /meals { requestId, uploadKey, mealType?, takenAt?, roomIds }`
   - 같은 `requestId`로 다시 보내면 처음 만든 기록을 그대로 돌려줌 (네트워크 재시도 안전)
   - 하루 횟수가 남았으면 `PENDING` + 작업 등록, 다 썼으면 `LIMITED` (나만 보기로 보관)
4. 워커가 분석 → `DONE`이 되는 순간 공유한 방에 공개
   - 부적절 → 사진 즉시 삭제, `REJECTED`(이미지 없는 기록만 남음), 횟수 반환
   - 음식 아님 → `NOT_FOOD`(나만 보기), 횟수 반환
   - 같은 사진 재업로드 → 이전 분석 결과 재사용, 횟수 반환
   - 3번 실패 → `FAILED`, 횟수 반환, `POST /meals/{id}/retry`로 다시 시도

## API

모든 요청은 `Authorization: Bearer <token>` (`/auth/**`, `/health` 제외)

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | /auth/kakao | 카카오 액세스 토큰으로 로그인 |
| POST | /auth/dev | 베타용 로그인 (`DEV_LOGIN_ENABLED=true`일 때만) |
| GET/PATCH/DELETE | /me | 내 정보 / 닉네임·목표 타입 수정 / 탈퇴 |
| GET/PUT | /me/profile | 선호 음식, 식단 유형, 키·체중 (선택) |
| POST | /uploads | 업로드 URL 발급 |
| POST | /meals | 기록 생성 |
| GET | /meals/calendar?month=2026-10 | 날짜별 기록 수와 대표 사진 |
| GET | /meals?date=2026-10-15 | 그날의 기록 + 남은 분석 횟수 |
| GET/PATCH/DELETE | /meals/{id} | 상세 / 끼니·공유 방 변경 / 삭제 |
| POST | /meals/{id}/retry | 실패·한도 초과 기록 다시 분석 |
| GET | /quota | 오늘 분석 횟수 |
| POST/GET | /rooms | 방 만들기 / 내 방 목록 |
| GET | /rooms/preview?code= | 입장 전 방 정보 |
| POST | /rooms/join | 초대 코드로 입장 |
| GET/PATCH | /rooms/{id} | 방 상세(멤버, 코드) / 이름 변경(방장) |
| POST | /rooms/{id}/invite-code | 코드 재발급(방장, 기존 코드 즉시 무효) |
| POST | /rooms/{id}/leave | 나가기 (방장이면 가장 먼저 들어온 멤버가 이어받음) |
| DELETE | /rooms/{id}/members/{userId} | 내보내기(방장) |
| GET | /rooms/{id}/feed?cursor=&size= | 방 피드 (사진·끼니·균형 등급·리액션, AI 피드백은 비공개) |
| POST/DELETE | /rooms/{id}/meals/{mealId}/reactions(/{type}) | 리액션 추가/취소 (LIKE, GREAT, CHEER, YUMMY) |
| GET | /rooms/{id}/meals/{mealId}/reactions | 누가 눌렀는지 |
| POST | /rooms/{id}/meals/{mealId}/reports | 신고 (본인에게 즉시 숨김, 2명 이상이면 방 전체 숨김) |

## 동시성 처리

| 상황 | 처리 |
|---|---|
| 동시 업로드로 하루 한도 초과 | Redis Lua로 확인과 증가를 한 번에 |
| 같은 요청 재시도 | `UNIQUE(user_id, request_id)` + 충돌 시 기존 기록 반환, 예약 횟수 반환 |
| 워커 여러 개 | `FOR UPDATE SKIP LOCKED` |
| 워커가 죽음 | 5분 넘은 RUNNING 작업을 1분마다 대기로 되돌림 |
| 동시 입장으로 정원 초과 | `UPDATE ... WHERE member_count < max_members` + CHECK 제약 |
| 분석 중 기록 삭제 | 최종 반영은 `WHERE status = 'ANALYZING'` 조건부 UPDATE, 실패 시 새 파일 삭제 |

## Render 배포 (무료 플랜)

1. 외부 서비스 준비
   - **Neon 또는 Supabase**: Postgres 생성. JDBC 형식으로 `DATABASE_URL=jdbc:postgresql://<host>/<db>?sslmode=require`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`
   - **Upstash**: Redis 생성. `REDIS_URL=rediss://default:<password>@<host>:<port>`
   - **Cloudflare R2**: 버킷 생성, API 토큰 발급, `temp/` 접두사에 1일 삭제 수명 주기 규칙
     `STORAGE_ENDPOINT=https://<account-id>.r2.cloudflarestorage.com`, `STORAGE_BUCKET`, `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY`
2. Render → New → Blueprint → 이 저장소 선택 (`render.yaml` 사용) → 위 환경변수 입력
3. 배포 후 `GET /health`로 확인

무료 인스턴스는 15분 동안 요청이 없으면 잠듭니다. 앱은 켜질 때 `/health`를 먼저 호출해 깨우세요.

### AI 연결

기본값 `AI_PROVIDER=stub`은 고정된 결과를 돌려줍니다. 실제 분석은 `AI_PROVIDER=anthropic`, `AI_API_KEY`, `AI_MODEL`(최신 모델명 확인)을 설정하세요.
AI에는 사진, 목표 타입, 선택 프로필, 최근 7일 요약만 보내고 닉네임 등 식별자는 보내지 않습니다.
