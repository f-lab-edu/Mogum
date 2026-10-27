package com.mogum.common.api;

import com.mogum.account.domain.UserRole;
import com.mogum.account.domain.UserStatus;
import com.mogum.account.dto.MeResponse;
import com.mogum.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.json.JsonAssert;
import org.springframework.test.json.JsonCompareMode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class ApiResponseTest extends PostgresTestConfiguration {
    @Autowired private ObjectMapper mapper;

    @Test
    void 성공_DTO는_값이_없는_필드도_null로_싣고_최상위는_data_하나뿐이다() {
        var response = ApiResponse.of(new MeResponse("me@example.com", UserStatus.ACTIVE,
                UserRole.ADMIN, true, null));

        assertJson("""
                {"data":{"email":"me@example.com","status":"ACTIVE","role":"ADMIN",
                 "termsAgreed":true,"waitlistPosition":null}}
                """, response);
    }

    @Test
    void 성공_DTO의_Instant는_숫자가_아닌_ISO_8601_UTC_문자열이다() {
        assertJson("""
                {"data":{"createdAt":"2026-12-14T15:00:00Z"}}
                """, ApiResponse.of(new TimestampResponse(Instant.parse("2026-12-14T15:00:00Z"))));
    }

    @Test
    void 성공_DTO의_enum은_대문자_문자열이다() {
        assertJson("""
                {"data":{"status":"ACTIVE"}}
                """, ApiResponse.of(new StatusResponse(UserStatus.ACTIVE)));
    }

    @Test
    void 성공_DTO의_null과_달리_오류의_빈_선택_필드는_생략하고_data로_감싸지_않는다() {
        assertJson("""
                {"code":"NOT_FOUND","message":"찾을 수 없습니다"}
                """, ApiErrorResponses.of(ErrorCode.NOT_FOUND, null).getBody());
    }

    @Test
    void 성공_data는_팩토리와_직접_생성_모두_null을_거부한다() {
        assertThrows(NullPointerException.class, () -> ApiResponse.of(null));
        assertThrows(NullPointerException.class, () -> new ApiResponse<>(null));
    }

    private void assertJson(String expected, Object response) {
        JsonAssert.comparator(JsonCompareMode.STRICT).assertIsMatch(expected, mapper.writeValueAsString(response));
    }

    private record TimestampResponse(Instant createdAt) {
    }

    private record StatusResponse(UserStatus status) {
    }
}
