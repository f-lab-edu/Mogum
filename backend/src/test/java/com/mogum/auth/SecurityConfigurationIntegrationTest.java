package com.mogum.auth;

import com.mogum.account.domain.UserFixtures;
import com.mogum.account.repository.UserRepository;
import com.mogum.support.IntegrationTest;
import com.mogum.support.TestRequests;
import com.mogum.support.IntegrationTestConfiguration;
import com.mogum.support.TestSessions;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.json.JsonAssert;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class SecurityConfigurationIntegrationTest extends SecurityScenario {
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired org.flywaydb.core.Flyway flyway;

    @ParameterizedTest(name = "요청 {0} {1} 쿠키 {2}")
    @CsvSource({"GET,/api/me,''", "GET,/api/me,unknown", "POST,/api/missing,''"})
    @DisplayName("로그인 없거나 모르는 쿠키는 리다이렉트 없는 JSON 401이다")
    void rejectsMissingOrUnknownSession(String method, String path, String value) {
        // given
        var client = requests.anonymous();
        // when
        var response = client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path)
                .cookie("SESSION", value).exchange().returnResult(String.class);
        // then
        assertError(response, 401, "UNAUTHORIZED");
    }

    @Test
    @DisplayName("만료된 세션은 JSON 401이다")
    void rejectsExpiredSession() {
        // given
        var cookie = session();
        jdbc.update("UPDATE spring_session SET last_access_time = 0, expiry_time = 0 WHERE session_id = ?", new TestSessions(sessions).id(cookie));
        // when
        var response = client(cookie).get().uri("/api/me").exchange().returnResult(String.class);
        // then
        assertError(response, 401, "UNAUTHORIZED");
    }

    @Test
    @DisplayName("세션 마이그레이션은 Spring Session 공식 PostgreSQL 스키마와 같다")
    void appliesOfficialSessionSchema() throws Exception {
        // when
        var migrations = java.util.Arrays.stream(flyway.info().applied()).map(m -> m.getScript()).toList();
        // then
        assertThat(migrations).contains("V3__create_spring_session.sql");
        try (var schema = getClass().getResourceAsStream("/org/springframework/session/jdbc/schema-postgresql.sql");
             var migration = getClass().getResourceAsStream("/db/migration/V3__create_spring_session.sql")) {
            assertThat(schema).isNotNull();
            assertThat(migration).isNotNull();
            assertThat(migration.readAllBytes()).isEqualTo(schema.readAllBytes());
        }
    }

    @Test
    @DisplayName("세션은 7일 만료와 회원 ID 속성 하나를 HTTP 요청 뒤에도 보존한다")
    void storesOnlyUserIdWithSevenDayExpiry() {
        // given
        var cookie = session();
        var id = new TestSessions(sessions).id(cookie);
        var before = jdbc.queryForMap("SELECT principal_name, max_inactive_interval FROM spring_session WHERE session_id = ?", id);
        // when
        var response = client(cookie).get().uri("/api/me").exchange();
        // then
        response.expectStatus().isOk();
        assertThat(before.get("max_inactive_interval")).isEqualTo(604800);
        org.springframework.session.Session stored = sessions.findById(id);
        assertThat(stored).isNotNull();
        assertThat(stored.getAttributeNames()).containsExactly(org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME);
        assertThat((String) stored.getAttribute(org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME))
                .isEqualTo(before.get("principal_name"));
        assertThat(before.get("principal_name")).isEqualTo(users.findByProviderAndSubject("google", "session-sub").orElseThrow().getId().toString());
    }

    @Test
    @DisplayName("현재 회원 응답은 상태와 이메일과 이름만 반환한다")
    void returnsPublicFields() {
        // given
        var cookie = session();
        // when
        var response = client(cookie).get().uri("/api/me").exchange();
        // then
        response.expectStatus().isOk().expectBody().json("""
                {"data":{"status":"APPLIED","email":"session@example.com","name":"session"}}
                """, JsonCompareMode.STRICT);
    }

    @Test
    @DisplayName("구글 신원 도우미의 세션은 주인 색인과 회원 행 없이 내 정보를 읽는다")
    void readsNotAppliedSessionOverHttp() {
        // given
        var identity = new AccountPrincipal.NotApplied("google", "new-sub", "new@example.com", "새 이름");
        var helper = new TestSessions(sessions);
        var cookie = helper.createNotApplied(identity);

        // when
        var response = client(cookie).get().uri("/api/me").exchange();

        // then
        response.expectStatus().isOk().expectBody().json("""
                {"data":{"status":"NOT_APPLIED","email":"new@example.com","name":"새 이름"}}
                """, JsonCompareMode.STRICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT principal_name FROM spring_session WHERE session_id = ?",
                String.class, helper.id(cookie))).isNull();
        org.springframework.session.Session stored = sessions.findById(helper.id(cookie));
        assertThat(stored.getAttributeNames()).containsExactly(AccountPrincipal.NOT_APPLIED_SESSION_ATTRIBUTE);
        assertThat((Object) stored.getAttribute(AccountPrincipal.NOT_APPLIED_SESSION_ATTRIBUTE)).isEqualTo(identity);
    }

    @ParameterizedTest(name = "깨진 세션 {0}")
    @ValueSource(strings = {"empty", "wrong-type", "blank-name", "mixed"})
    @DisplayName("JDBC에 저장된 깨진 신원은 실제 HTTP에서도 공통 JSON 401로 버린다")
    void rejectsMalformedStoredIdentity(String condition) {
        // given
        var jdbcSession = sessions.createSession();
        org.springframework.session.Session stored = jdbcSession;
        if (condition.equals("wrong-type")) stored.setAttribute(AccountPrincipal.NOT_APPLIED_SESSION_ATTRIBUTE, "broken");
        if (condition.equals("blank-name") || condition.equals("mixed")) {
            stored.setAttribute(AccountPrincipal.NOT_APPLIED_SESSION_ATTRIBUTE,
                    new AccountPrincipal.NotApplied("google", "sub", "me@example.com", condition.equals("blank-name") ? " " : "이름"));
        }
        if (condition.equals("mixed")) stored.setAttribute(org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, "17");
        sessions.save(jdbcSession);
        var cookie = new Cookie("SESSION", java.util.Base64.getEncoder().encodeToString(
                stored.getId().getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        // when
        var response = client(cookie).get().uri("/api/me").exchange().returnResult(String.class);

        // then
        assertError(response, 401, "UNAUTHORIZED");
        assertThat(sessions.findById(stored.getId())).isNull();
    }

    @ParameterizedTest(name = "변경 후 상태 {0}")
    @ValueSource(strings = {"APPLIED", "APPROVED", "REJECTED"})
    @DisplayName("같은 세션의 상태는 DB에서 매 요청 다시 읽는다")
    void reloadsUserStatus(String status) {
        // given
        var cookie = session();
        client(cookie).get().uri("/api/me").exchange().expectBody().jsonPath("$.data.status").isEqualTo("APPLIED");
        jdbc.update("""
                UPDATE users SET status = ?,
                    approved_at = CASE WHEN ? = 'APPROVED' THEN now() END,
                    credit_refill_at = CASE WHEN ? = 'APPROVED' THEN now() + interval '30 days' END,
                    updated_at = now()
                """, status, status, status);
        // when
        var response = client(cookie).get().uri("/api/me").exchange();
        // then
        response.expectStatus().isOk().expectBody().jsonPath("$.data.status").isEqualTo(status);
    }

    @ParameterizedTest(name = "삭제 후 세션 {0}")
    @ValueSource(ints = {0, 1})
    @DisplayName("회원 삭제 후 각 기기의 세션은 401로 거절되고 무효화된다")
    void invalidatesDeletedUserSessions(int device) {
        // given
        var first = session();
        var userId = users.findByProviderAndSubject("google", "session-sub").orElseThrow().getId();
        var second = new TestSessions(sessions).create(userId);
        jdbc.update("DELETE FROM users");
        var cookie = device == 0 ? first : second;
        // when
        var response = client(cookie).get().uri("/api/me").exchange().returnResult(String.class);
        // then
        assertError(response, 401, "UNAUTHORIZED");
        assertThat(sessions.findById(new TestSessions(sessions).id(cookie))).isNull();
    }

    @Test
    @DisplayName("삭제된 회원의 세션으로 로그인을 시작해도 JSON 401이다")
    void rejectsLoginStartForDeletedUser() {
        // given
        var cookie = session();
        jdbc.update("DELETE FROM users");
        // when
        var response = client(cookie).get().uri("/auth/google").exchange().returnResult(String.class);
        // then
        assertError(response, 401, "UNAUTHORIZED");
        assertThat(sessions.findById(new TestSessions(sessions).id(cookie))).isNull();
    }

    @Test
    @DisplayName("아키텍처 위반 픽스처는 공용 앱 컨텍스트에 등록되지 않는다")
    void excludesArchitectureFixtureControllers() {
        // when
        var types = java.util.Arrays.stream(context.getBeanNamesForAnnotation(org.springframework.web.bind.annotation.RestController.class))
                .map(context::getType).map(Class::getName).toList();
        // then
        assertThat(types).noneMatch(name -> name.startsWith("com.mogum.ArchitectureTest$"));
        assertThat(types).contains(IntegrationTestConfiguration.ValidationController.class.getName());
        assertThat(types).noneMatch(name -> name.startsWith("com.mogum.common.api.RequestValidationIT$"));
    }
}

abstract class SecurityScenario {
    @Autowired TestRequests requests;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcIndexedSessionRepository sessions;

    Cookie session() {
        var user = users.save(UserFixtures.user("google", "session-sub", "session@example.com"));
        return new TestSessions(sessions).create(user.getId());
    }

    Cookie approvedSession() {
        var user = users.save(UserFixtures.approved("google", "session-sub", "session@example.com"));
        return new TestSessions(sessions).create(user.getId());
    }

    RestTestClient client(Cookie cookie) {
        return requests.anonymous().mutate().defaultCookie(cookie.getName(), cookie.getValue()).build();
    }

    static void assertError(EntityExchangeResult<String> result, int status, String code) {
        assertThat(result.getStatus().value()).isEqualTo(status);
        assertThat(result.getResponseHeaders().getFirst("Location")).isNull();
        assertThat(result.getResponseHeaders().getFirst("Content-Type")).startsWith("application/json");
        assertThat(result.getResponseHeaders().getFirst("Retry-After")).isNull();
        String message = switch (code) {
            case "UNAUTHORIZED" -> "로그인이 필요합니다";
            case "CSRF_INVALID", "INVALID_REQUEST" -> "요청을 확인할 수 없습니다";
            case "NOT_FOUND" -> "찾을 수 없습니다";
            case "METHOD_NOT_ALLOWED" -> "지원하지 않는 요청 방식입니다";
            default -> "요청을 처리할 수 없습니다";
        };
        JsonAssert.comparator(JsonCompareMode.STRICT).assertIsMatch(
                "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}", result.getResponseBody());
        assertThat(result.getResponseBody()).doesNotContain("private servlet failure", "request log test failure");
    }
}
