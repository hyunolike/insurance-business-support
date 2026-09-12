/**
 * REST 어댑터.
 *
 * <p>가장 중요한 엔드포인트는 계약 스냅샷 API다:
 * {@code GET /policies/{no}/snapshot?asOf=&knownAt=}
 *
 * <p>이 API는 claims 전용 편의 API가 아니라 <b>공표된 공용 계약</b>(Open Host Service)이다.
 * 같은 {@code (policyNo, asOf, knownAt)}은 영원히 같은 응답을 줘야 하고,
 * 필드 삭제·의미 변경은 새 버전으로만 한다.
 *
 * <p>최소권한을 지킨다 — 고지사항 원문, 계약자 주소, 모집인 정보, 보험료 금액은 주지 않는다.
 * claims가 필요한 것은 고지 내용이 아니라 그 결과물인 부담보 조건이다.
 *
 * <p>Phase 1에서 구현. 명세: {@code docs/design/05-api.md}
 */
package com.insurance.policy.adapter.web;
