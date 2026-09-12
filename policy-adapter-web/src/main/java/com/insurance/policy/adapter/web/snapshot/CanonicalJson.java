package com.insurance.policy.adapter.web.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

/**
 * 체크섬 계산용 정규화 JSON.
 *
 * <p>정규화 = <b>키 정렬 + 공백 제거</b>. 같은 내용이면 언제 어디서 직렬화해도
 * 바이트가 같아야 체크섬이 안정적이다.
 *
 * <p>응답 직렬화에 쓰는 ObjectMapper와 <b>분리된 인스턴스</b>를 쓴다.
 * 애플리케이션 전역 Jackson 설정(들여쓰기, 필드 제외 등)이 바뀌면
 * 과거에 계산한 체크섬과 달라져 claims의 검증이 전부 실패하기 때문이다.
 *
 * <p>claims도 동일한 규칙으로 재계산한다. 두 레포가 어긋나지 않는지는
 * 계약 테스트가 검증한다.
 */
@Component
public class CanonicalJson {

    private final ObjectMapper canonicalMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(SerializationFeature.INDENT_OUTPUT, false)
            .build()
            .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS);

    /**
     * 체크섬 대상 문자열을 만든다.
     *
     * <p>인자로 받는 응답은 {@code checksum} 필드가 {@code null}이어야 한다 —
     * 체크섬을 체크섬 계산에 포함시킬 수는 없기 때문이다.
     */
    /**
     * 캐시 저장용 — <b>체크섬을 포함한</b> 완성 본문을 직렬화한다.
     *
     * <p>{@link #of}와 같은 매퍼를 쓴다. 캐시에서 꺼낸 바이트가 방금 렌더한 것과
     * 같아야 하고, 전역 Jackson 설정이 바뀌어도 흔들리면 안 되기 때문이다.
     */
    public String serialize(PolicySnapshotResponse response) {
        try {
            return canonicalMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("스냅샷 직렬화 실패", e);
        }
    }

    public String of(PolicySnapshotResponse response) {
        if (response.checksum() != null) {
            throw new IllegalArgumentException(
                    "체크섬은 자기 자신을 포함해 계산할 수 없습니다. checksum이 null인 본문을 넘기세요.");
        }
        try {
            return canonicalMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("스냅샷 정규화 직렬화 실패", e);
        }
    }
}
