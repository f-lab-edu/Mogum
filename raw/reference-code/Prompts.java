package phase1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** prompts/{version}/*.txt 를 읽어 자리표시를 채운다. 남은 자리표시가 있으면 실패 — 빈 칸이 섞인 프롬프트로 재지 않는다. */
public final class Prompts {
    private static final Pattern SLOT = Pattern.compile("\\{\\{([A-Za-z]+)}}");
    private final Path dir;
    private final String version;
    private final String terms;

    public Prompts(String version) {
        this.version = version;
        this.dir = Path.of("prompts", version);
        this.terms = read("_terms.txt");
    }

    public String version() { return version; }

    public String render(String file, Map<String, String> values) {
        String text = read(file).replace("{{TERMS}}", terms);
        Matcher m = SLOT.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            if (v == null) throw new IllegalArgumentException(file + " 의 자리표시 {{" + m.group(1) + "}} 를 채우지 않았다");
            m.appendReplacement(out, Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        return out.toString().strip();
    }

    /** 이 버전의 본문 섹션 목록 (sections.txt, `|` 는 둘 중 하나). 없으면 v1 · v2 의 4섹션. */
    public List<String> sections() {
        if (!Files.exists(dir.resolve("sections.txt"))) return List.of("## 핵심 요약", "## 개념 설명", "## 예시", "## 확인 질문");
        return read("sections.txt").lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
    }

    /** 이 버전의 출력 상한 (limits.txt). 상한도 프롬프트 버전의 일부다 — 같은 버전이면 같은 조건으로 잰다. */
    public int limit(String stage, int fallback) {
        if (!Files.exists(dir.resolve("limits.txt"))) return fallback;
        return read("limits.txt").lines().map(String::strip).filter(l -> l.startsWith(stage + "="))
                .map(l -> Integer.parseInt(l.substring(stage.length() + 1).strip())).findFirst().orElse(fallback);
    }

    public boolean has(String file) { return Files.exists(dir.resolve(file)); }

    public String read(String file) {
        try {
            return Files.readString(dir.resolve(file)).strip();
        } catch (IOException e) {
            throw new IllegalStateException(dir.resolve(file) + " 를 읽지 못했다", e);
        }
    }
}
