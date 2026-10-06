package phase1;

import phase1.Topics.Mastery;
import phase1.Topics.Topic;
import phase1.adapter.AnthropicChat;
import phase1.adapter.OpenAiChat;
import phase1.port.LlmClient;
import phase1.port.LlmClient.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 잡 A(커리큘럼)와 잡 B(① 개요 · ② 본문 · ③ 카드)를 05 의 규칙대로 돈다. 호출은 전부 Recorder 에 남는다.
 * 잡 하나의 결과는 data/round1/{key}.json 에 저장하고, 이미 있으면 건너뛴다 — 한도 때문에 멈췄다 이어서 돌 수 있게.
 *
 * 재요청과 재시도는 다르다: 파싱 재요청 · 강제 종결은 05 의 프로토콜이라 한다(호출 수에 센다).
 * HTTP 실패는 재시도하지 않는다. 단 **계정 한도 429** 만은 기다렸다 한 번 다시 보낸다 — 행에 accountLimit 으로 구분한다.
 */
public final class Pipeline {
    /** 잡 결과 저장 위치 — 1차 실측은 data/round1, 파일럿은 data/pilot/{프롬프트 버전}. */
    public static Path OUT = Path.of("data/round1");
    /** 파일럿에서 이어 쓰기를 일부러 일으키려고 — 잡 키별 본문 출력 상한. 원자료에 forcedBodyLimit 으로 남는다. */
    public static final Map<String, Integer> bodyLimitOverride = new java.util.HashMap<>();
    static final Duration TIMEOUT = Duration.ofSeconds(300);
    static final int MAX_OUTLINE_CALLS = 4, MAX_TOOL_ROUNDS = 3;   // 05 §3.3 · §9
    // 출력 상한은 프롬프트 버전의 limits.txt 가 정한다. 없을 때의 기본값 — 파일럿 v1 에서 개요 1,500 이 모자랐다 (LOG ①)
    static final int DEF_CURRICULUM = 4000, DEF_OUTLINE = 2500, DEF_BODY = 3000, DEF_CARDS = 2500;

    public record Ctx(String provider, LlmClient client, String mid, String low,
                      Recorder rec, Pacer pacer, Prompts prompts, double budgetUsd, ToolSpec lookupTool) {}

    /** nextLesson: 다음 강의 한 줄(예고용 — Q193). 마지막 강의면 null. */
    public record LessonIn(String topicId, String curriculumTitle, int order, String title, String objective,
                           List<String> keywords, String sourceKey, String nextLesson) {}

    /** 잡 하나의 토큰 · 비용 · 호출 수를 모은다. */
    static final class Tally {
        int calls; long in, out; double usd; long waitMs;
        Map<String, Object> toMap() {
            return new LinkedHashMap<>(Map.of("calls", calls, "inputTokens", in, "outputTokens", out, "costUsd", usd, "pacingWaitMs", waitMs));
        }
    }

    public static Optional<Map<String, Object>> load(String key) {
        Path p = OUT.resolve(key + ".json");
        if (!Files.exists(p)) return Optional.empty();
        return JsonText.object(read(p));
    }

    // ───────────────────────────── 잡 A — 커리큘럼 ─────────────────────────────

    public static Map<String, Object> jobA(Ctx c, Topic t, boolean fixture, List<Mastery> injected, String key) {
        var done = load(key);
        if (done.isPresent()) return done.get();

        long t0 = System.currentTimeMillis();
        Tally tally = new Tally();
        List<Mastery> mastery = injected != null ? injected : fixture ? t.fixture() : List.of();
        var tags = tags(key, t.id(), fixture, injected != null ? "inj200" : "normal");
        String system = c.prompts().render("curriculum.system.txt", Map.of());
        String user = c.prompts().render("curriculum.user.txt", Map.of(
                "topic", t.topic(), "purpose", t.purpose(), "weeks", String.valueOf(t.weeks()),
                "minLessons", String.valueOf(t.minLessons()), "maxLessons", String.valueOf(t.maxLessons()),
                "masteryList", Topics.masteryList(mastery), "feedbackLine", ""));

        var result = base(key, c.provider(), t, fixture);
        result.put("kind", "curriculum");
        result.put("model", c.mid());
        result.put("injectedConcepts", mastery.size());
        List<Turn> turns = new ArrayList<>(List.of(new UserText(user)));
        Optional<Map<String, Object>> parsed = Optional.empty();
        String raw = null;
        for (int attempt = 0; attempt < 2 && parsed.isEmpty(); attempt++) {   // 파싱 재요청 1회 (05 §7)
            if (attempt == 1) turns.add(new UserText(c.prompts().read("reparse.txt")));
            var res = call(c, attempt == 0 ? "jobA" : "jobA.reparse", tags, tally,
                    new LlmRequest(c.mid(), system, List.copyOf(turns), List.of(), c.prompts().limit("curriculum", DEF_CURRICULUM), TIMEOUT));
            if (res == null) return fail(result, "jobA", "http", tally, t0, key);
            raw = res.text();
            turns.add(new AssistantTurn(res.providerMessage()));
            parsed = JsonText.object(raw).filter(m -> m.get("lessons") instanceof List<?> l && !l.isEmpty());
            if (res.stopReason() == StopReason.MAX_TOKENS) {
                result.put("truncated", true);
                if (parsed.isEmpty()) break;   // 잘린 JSON 은 재요청하지 않는다
            }
        }
        result.put("rawText", raw);
        if (parsed.isEmpty()) return fail(result, "jobA", "parse", tally, t0, key);
        var p = parsed.get();
        result.put("ok", true);
        result.put("title", p.get("title"));
        result.put("masteryReflection", p.get("masteryReflection"));
        result.put("masteryReflectionPresent", p.containsKey("masteryReflection"));
        result.put("lessons", p.get("lessons"));
        result.put("lessonCount", ((List<?>) p.get("lessons")).size());
        return finish(result, tally, t0, key);
    }

    // ───────────────────────────── 잡 B — 강의자료 ─────────────────────────────

    @SuppressWarnings("unchecked")
    public static Map<String, Object> jobB(Ctx c, Topic t, LessonIn lesson, boolean fixture, boolean lowOutline, String key) {
        var done = load(key);
        if (done.isPresent()) return done.get();

        long t0 = System.currentTimeMillis();
        Tally tally = new Tally();
        var tags = tags(key, t.id(), fixture, lowOutline ? "e3-low-outline" : "normal");
        tags.put("lessonSource", lesson.sourceKey());
        tags.put("lessonOrder", lesson.order());
        var result = base(key, c.provider(), t, fixture);
        result.put("kind", "material");
        result.put("lesson", lesson);
        result.put("lowOutline", lowOutline);
        String outlineModel = lowOutline ? c.low() : c.mid();
        result.put("models", Map.of("outline", outlineModel, "body", c.mid(), "cards", c.low()));
        var lessonVars = Map.of(
                "curriculumTitle", lesson.curriculumTitle(), "topic", t.topic(), "purpose", t.purpose(),
                "lessonOrder", String.valueOf(lesson.order()), "lessonTitle", lesson.title(),
                "lessonObjective", lesson.objective(), "lessonKeywords", String.join(", ", lesson.keywords()),
                "nextLesson", lesson.nextLesson() == null ? "(없음 — 마지막 강의다)" : lesson.nextLesson());

        // ① 개요 + 숙련도 조사 — 유일한 자율 구간. 총 호출 4회 안에서 툴 라운드 · 강제 종결 · 재요청을 나눠 쓴다 (05 §3.3)
        String system1 = c.prompts().render("outline.system.txt", Map.of());
        List<Turn> turns = new ArrayList<>(List.of(new UserText(c.prompts().render("outline.user.txt", lessonVars))));
        List<ToolSpec> tools = List.of(c.lookupTool());
        int calls = 0, toolRounds = 0;
        boolean forced = false;
        List<Object> requested = new ArrayList<>();
        Optional<Map<String, Object>> outline = Optional.empty();
        String raw1 = null;
        boolean finalOnly = false;
        while (calls < MAX_OUTLINE_CALLS && outline.isEmpty()) {
            String stage = calls == 0 ? "outline" : finalOnly ? (forced ? "outline.force" : "outline.reparse") : "outline.round" + (toolRounds + 1);
            var res = call(c, stage, tags, tally, new LlmRequest(outlineModel, system1, List.copyOf(turns), tools, c.prompts().limit("outline", DEF_OUTLINE), TIMEOUT, finalOnly));
            calls++;
            if (res == null) return fail(result, "outline", "http", tally, t0, key);
            turns.add(new AssistantTurn(res.providerMessage()));
            raw1 = res.text();
            if (!res.toolCalls().isEmpty() && !finalOnly) {
                List<ToolResult> results = new ArrayList<>();
                for (var tc : res.toolCalls()) {
                    List<String> names = (List<String>) JsonText.object(tc.argumentsJson()).map(m -> m.get("conceptNames")).orElse(List.of());
                    requested.add(names);
                    if (names.size() > 20) tags.put("toolOverLimit", true);   // 05 §9 — 툴 1회당 20개
                    results.add(new ToolResult(tc.id(), JsonText.write(Topics.lookup(names, fixture ? t.fixture() : List.of()))));
                }
                turns.add(new ToolResults(results));
                toolRounds++;
                if (toolRounds >= MAX_TOOL_ROUNDS) {   // 라운드 3 에서도 툴 → 툴을 막고 1회 더
                    turns.add(new UserText(c.prompts().read("outline.force-final.txt")));
                    finalOnly = true;
                    forced = true;
                }
                continue;
            }
            if (res.stopReason() == StopReason.MAX_TOKENS) {   // 잘린 응답은 재요청해도 또 잘린다 — 기록하고 끝낸다 (LOG 파일럿 v1 ②)
                result.put("outlineCalls", calls);
                result.put("outlineRawText", raw1);
                return fail(result, "outline", "truncated", tally, t0, key);
            }
            outline = JsonText.object(raw1).filter(m -> m.get("outline") instanceof Map);
            if (outline.isEmpty()) {   // 파싱 실패 → 같은 4회 안에서 재요청
                turns.add(new UserText(c.prompts().read("reparse.txt")));
                finalOnly = true;
            }
        }
        result.put("outlineCalls", calls);
        result.put("toolRounds", toolRounds);
        result.put("forcedFinal", forced);
        result.put("conceptsRequested", requested);
        if (outline.isEmpty()) {
            result.put("outlineRawText", raw1);
            return fail(result, "outline", "no-outline", tally, t0, key);   // 개요가 없으면 FAILED (05 §3.3)
        }
        var o = outline.get();
        result.put("outline", o.get("outline"));
        result.put("masteryFindings", o.get("masteryFindings"));
        result.put("masteryFindingsPresent", o.get("masteryFindings") instanceof List<?> l && !l.isEmpty());

        // ② 본문 — 4섹션 · max_tokens 3,000 · 잘림은 실패가 아니다 (05 §4 · §7)
        String system2 = c.prompts().render("body.system.txt", Map.of());
        var vars2 = new LinkedHashMap<>(lessonVars);
        vars2.put("outlineJson", JsonText.write(o.get("outline")));
        List<Turn> t2 = new ArrayList<>(List.of(new UserText(c.prompts().render("body.user.txt", vars2))));
        String body = null;
        boolean truncated = false, sectionsOk = false;
        List<String> sections = c.prompts().sections();
        int bodyLimit = bodyLimitOverride.getOrDefault(key, c.prompts().limit("body", DEF_BODY));
        if (bodyLimitOverride.containsKey(key)) tags.put("forcedBodyLimit", bodyLimit);
        boolean canContinue = c.prompts().has("continue.txt");   // v5~ (Q199)
        Integer continuedAt = null;
        // 호출은 최대 2회 — 두 번째는 잘렸으면 이어 쓰기, 섹션이 빠졌으면 재요청. 잡의 호출 상한(05 §9)은 그대로다
        for (int attempt = 0; attempt < 2; attempt++) {
            String stage = attempt == 0 ? "body" : continuedAt != null ? "body.continue" : "body.reparse";
            var res = call(c, stage, tags, tally, new LlmRequest(c.mid(), system2, List.copyOf(t2), List.of(), bodyLimit, TIMEOUT));
            if (res == null) return fail(result, "body", "http", tally, t0, key);
            t2.add(new AssistantTurn(res.providerMessage()));
            boolean cut = res.stopReason() == StopReason.MAX_TOKENS;
            body = "body.continue".equals(stage) ? body + res.text() : res.text();
            truncated = cut;
            String b = body;
            sectionsOk = sections.stream().allMatch(s -> java.util.Arrays.stream(s.split("\\|")).anyMatch(b::contains));
            if ((sectionsOk && !cut) || attempt == 1) break;
            if (cut && canContinue) {   // 끊긴 곳부터 이어 쓴다 — prefill 은 Sonnet 5.5 에서 막혀 있어 사용자 메시지로
                continuedAt = body.length();
                t2.add(new UserText(c.prompts().read("continue.txt")));
                continue;
            }
            if (cut) break;   // v1~v4: 잘린 자료는 기록하고 진행
            t2.add(new UserText("다음 섹션을 이 이름 그대로 모두 포함해 강의자료 전체를 다시 써라: " + String.join(" · ", sections)));
        }
        result.put("continuedAt", continuedAt);
        result.put("body", body);
        result.put("truncated", truncated);
        result.put("sectionsOk", sectionsOk);
        result.put("mermaidBlocks", count(body, "```mermaid"));
        result.put("displayMathBlocks", count(body, "$$") / 2);
        if (!sectionsOk && !truncated) return fail(result, "body", "sections", tally, t0, key);

        // ③ 카드 — 하위 모델 · 최대 10장 · type 포함 (Q187)
        String system3 = c.prompts().render("cards.system.txt", Map.of());
        List<Turn> t3 = new ArrayList<>(List.of(new UserText(c.prompts().render("cards.user.txt", Map.of("topic", t.topic(), "materialBody", body)))));
        Optional<Map<String, Object>> cards = Optional.empty();
        for (int attempt = 0; attempt < 2 && cards.isEmpty(); attempt++) {
            if (attempt == 1) t3.add(new UserText(c.prompts().read("reparse.txt")));
            var res = call(c, attempt == 0 ? "cards" : "cards.reparse", tags, tally, new LlmRequest(c.low(), system3, List.copyOf(t3), List.of(), c.prompts().limit("cards", DEF_CARDS), TIMEOUT));
            if (res == null) return fail(result, "cards", "http", tally, t0, key);
            t3.add(new AssistantTurn(res.providerMessage()));
            cards = JsonText.object(res.text()).filter(m -> m.get("cards") instanceof List<?> l && !l.isEmpty());
            if (cards.isEmpty()) result.put("cardsRawText", res.text());
            if (cards.isEmpty() && res.stopReason() == StopReason.MAX_TOKENS) {   // 잘린 JSON 은 재요청해도 또 잘린다 (LOG 파일럿 v2)
                result.put("cardsTruncated", true);
                break;
            }
        }
        if (cards.isEmpty()) return fail(result, "cards", "parse", tally, t0, key);
        result.put("cards", cards.get().get("cards"));
        result.put("concepts", cards.get().get("concepts"));
        result.put("ok", true);
        return finish(result, tally, t0, key);
    }

    // ───────────────────────────── 공통 ─────────────────────────────

    /** 호출 1회 — 예산 확인 · 한도 대기 · 기록 · 계정 한도 429 만 한 번 다시. */
    static LlmResponse call(Ctx c, String stage, Map<String, Object> tags, Tally tally, LlmRequest req) {
        if (c.rec().totalUsd() > c.budgetUsd()) throw new IllegalStateException("예산 상한 $" + c.budgetUsd() + " 도달 — 멈춘다 (20 §5)");
        String reasoning = c.client() instanceof AnthropicChat ? AnthropicChat.reasoningSetting(req.model()) : OpenAiChat.reasoningSetting(req.model());
        for (int attempt = 1; attempt <= 2; attempt++) {
            long waited = c.pacer().beforeCall(c.provider(), req);
            tally.waitMs += waited;
            var rowTags = new LinkedHashMap<>(tags);
            if (waited > 0) rowTags.put("pacingWaitMs", waited);
            if (attempt > 1) rowTags.put("attempt", attempt);
            try {
                var res = c.client().call(req);
                c.rec().llm(c.provider(), stage, rowTags, req, reasoning, res, null);
                c.pacer().afterCall(c.provider(), req.model(), res.rateLimitHeaders());
                tally.calls++;
                res.usage().ifPresent(u -> {
                    tally.in += u.inputTokens();
                    tally.out += u.outputTokens();
                    double usd = Prices.chatUsd(req.model(), u.inputTokens(), u.outputTokens());
                    tally.usd += Double.isNaN(usd) ? 0 : usd;
                });
                System.out.printf("  %-28s %-18s %6dms in=%-6s out=%-5s %s%n", tags.get("key") + " " + stage, req.model(), res.latencyMs(),
                        res.usage().map(Usage::inputTokens).orElse(null), res.usage().map(Usage::outputTokens).orElse(null), res.rawStopReason());
                return res;
            } catch (Pacer.QuotaExhausted q) {
                throw q;
            } catch (Exception e) {
                boolean accountLimit = e.getClass().getSimpleName().equals("RateLimitException")
                        && !String.valueOf(e.getMessage()).contains("credits");
                if (accountLimit) rowTags.put("accountLimit", true);
                c.rec().llm(c.provider(), stage, rowTags, req, reasoning, null, e);
                tally.calls++;
                System.out.printf("  %-28s %-18s FAIL %s%n", tags.get("key") + " " + stage, req.model(), oneLine(e));
                if (!accountLimit || attempt == 2) return null;
                sleep(Duration.ofSeconds(65));   // 계정 한도 — 분 단위 창이 넘어가길 기다렸다 한 번만 다시
            }
        }
        return null;
    }

    static Map<String, Object> tags(String key, String topic, boolean fixture, String variant) {
        var m = new LinkedHashMap<String, Object>();
        m.put("key", key);
        m.put("topic", topic);
        m.put("fixture", fixture);
        m.put("variant", variant);
        return m;
    }

    static LinkedHashMap<String, Object> base(String key, String provider, Topic t, boolean fixture) {
        var m = new LinkedHashMap<String, Object>();
        m.put("key", key);
        m.put("provider", provider);
        m.put("topic", t.id());
        m.put("fixture", fixture);
        m.put("ok", false);
        return m;
    }

    static Map<String, Object> fail(Map<String, Object> r, String stage, String reason, Tally tally, long t0, String key) {
        r.put("failedStage", stage);
        r.put("failure", reason);
        return finish(r, tally, t0, key);
    }

    static Map<String, Object> finish(Map<String, Object> r, Tally tally, long t0, String key) {
        r.put("tally", tally.toMap());
        r.put("durationMs", System.currentTimeMillis() - t0);
        // 실패한 잡은 failed/ 에 시각을 붙여 남기고, 성공 자리는 비워 둔다 — 다음 실행에서 다시 시도된다. 실패도 원자료(jsonl)에 행으로 있다
        Path p = OUT.resolve((Boolean.TRUE.equals(r.get("ok")) ? "" : "failed/") + key + ".json");
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(Boolean.TRUE.equals(r.get("ok")) ? p : p.resolveSibling(p.getFileName() + "." + System.currentTimeMillis()),
                    JsonText.write(r));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        System.out.printf("→ %s %s  calls=%s cost=$%.4f %ds%s%n", key, Boolean.TRUE.equals(r.get("ok")) ? "OK" : "FAIL(" + r.get("failedStage") + ":" + r.get("failure") + ")",
                tally.calls, tally.usd, (System.currentTimeMillis() - t0) / 1000, tally.waitMs > 0 ? " (한도 대기 " + tally.waitMs / 1000 + "s)" : "");
        return r;
    }

    static int count(String s, String needle) {
        if (s == null) return 0;
        int n = 0, i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    static String read(Path p) {
        try { return Files.readString(p); } catch (IOException e) { throw new IllegalStateException(e); }
    }

    static void sleep(Duration d) {
        try { Thread.sleep(d.toMillis()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static String oneLine(Exception e) {
        String m = (e.getClass().getSimpleName() + ": " + e.getMessage()).replace("\n", " ");
        return m.length() > 300 ? m.substring(0, 300) + "…" : m;
    }
}
