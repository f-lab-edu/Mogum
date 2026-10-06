package phase1.adapter;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.HttpResponseFor;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import phase1.port.EmbeddingClient;

import java.time.Duration;
import java.util.List;

public final class OpenAiEmbedding implements EmbeddingClient {
    private final OpenAIClient client;
    private final String model;

    public OpenAiEmbedding(String apiKey, String model) {
        this.client = OpenAIOkHttpClient.builder().apiKey(apiKey).maxRetries(0).timeout(Duration.ofSeconds(10)).build();
        this.model = model;
    }

    @Override public String model() { return model; }

    @Override
    public BatchResult embedBatch(List<String> texts) {
        long t0 = System.nanoTime();
        CreateEmbeddingResponse r = client.embeddings().create(EmbeddingCreateParams.builder().model(model).inputOfArrayOfStrings(texts).build());
        long latency = (System.nanoTime() - t0) / 1_000_000;
        var out = new java.util.ArrayList<float[]>();
        r.data().stream().sorted(java.util.Comparator.comparingLong(d -> d.index())).forEach(d -> {
            List<? extends Number> v = d.embedding();
            float[] vec = new float[v.size()];
            for (int i = 0; i < vec.length; i++) vec[i] = v.get(i).floatValue();
            out.add(vec);
        });
        return new BatchResult(out, r.usage().totalTokens(), latency);
    }

    @Override
    public EmbeddingResult embed(String text) {
        long t0 = System.nanoTime();
        HttpResponseFor<CreateEmbeddingResponse> raw = client.embeddings().withRawResponse()
                .create(EmbeddingCreateParams.builder().model(model).input(text).build());
        CreateEmbeddingResponse r = raw.parse();
        long latency = (System.nanoTime() - t0) / 1_000_000;
        List<? extends Number> v = r.data().get(0).embedding();
        float[] vec = new float[v.size()];
        for (int i = 0; i < vec.length; i++) vec[i] = v.get(i).floatValue();
        var headers = Headers.rateLimit(raw.headers().names(), n -> String.join(",", raw.headers().values(n)));
        return new EmbeddingResult(vec, r.usage().totalTokens(), latency, headers);
    }
}
