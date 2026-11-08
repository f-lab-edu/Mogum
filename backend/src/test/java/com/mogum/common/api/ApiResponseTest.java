package com.mogum.common.api;

import com.mogum.account.domain.UserRole;
import com.mogum.account.domain.UserStatus;
import com.mogum.account.dto.MeResponse;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.json.JsonContent;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JsonTest
class ApiResponseTest {
    @Autowired private ObjectMapper mapper;
    @Autowired private ApplicationContext context;

    @Test
    @DisplayName("성공 DTO는 값이 없는 필드도 null로 싣고 최상위는 data 하나뿐이다")
    void preservesNullFieldsInsideSingleDataEnvelope() {
        // given
        var response = ApiResponse.of(new MeResponse("me@example.com", UserStatus.ACTIVE,
                UserRole.ADMIN, true, null));

        // when
        var json = mapper.writeValueAsString(response);

        // then
        assertThat(new JsonContent(json)).isStrictlyEqualTo("""
                {"data":{"email":"me@example.com","status":"ACTIVE","role":"ADMIN",
                 "termsAgreed":true,"waitlistPosition":null}}
                """);
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(context.getBeansOfType(EntityManagerFactory.class)).isEmpty();
    }

    @Test
    @DisplayName("성공 DTO의 Instant는 ISO-8601 UTC 문자열이다")
    void serializesInstantAsIsoUtcString() {
        // given
        var response = ApiResponse.of(new TimestampResponse(Instant.parse("2026-12-14T15:00:00Z")));

        // when
        var json = mapper.writeValueAsString(response);

        // then
        assertThat(new JsonContent(json)).isStrictlyEqualTo("""
                {"data":{"createdAt":"2026-12-14T15:00:00Z"}}
                """);
    }

    @Test
    @DisplayName("성공 DTO의 enum은 대문자 문자열이다")
    void serializesEnumAsUppercaseString() {
        // given
        var response = ApiResponse.of(new StatusResponse(UserStatus.ACTIVE));

        // when
        var json = mapper.writeValueAsString(response);

        // then
        assertThat(new JsonContent(json)).isStrictlyEqualTo("""
                {"data":{"status":"ACTIVE"}}
                """);
    }

    @Test
    @DisplayName("오류는 빈 선택 필드를 생략하고 data로 감싸지 않는다")
    void omitsOptionalErrorFieldsWithoutDataEnvelope() {
        // given
        var response = ApiErrorResponses.of(ErrorCode.NOT_FOUND, null).getBody();

        // when
        var json = mapper.writeValueAsString(response);

        // then
        assertThat(new JsonContent(json)).isStrictlyEqualTo("""
                {"code":"NOT_FOUND","message":"찾을 수 없습니다"}
                """);
    }

    @Test
    @DisplayName("성공 응답 팩토리는 null 데이터를 거부한다")
    void rejectsNullDataInFactory() {
        // when & then
        assertThatThrownBy(() -> ApiResponse.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("성공 응답 생성자는 null 데이터를 거부한다")
    void rejectsNullDataInConstructor() {
        // when & then
        assertThatThrownBy(() -> new ApiResponse<>(null)).isInstanceOf(NullPointerException.class);
    }

    private record TimestampResponse(Instant createdAt) { }
    private record StatusResponse(UserStatus status) { }
}
