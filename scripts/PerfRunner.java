import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Load script for the scoring path. Runs with any JDK 21, no build needed (works on Windows/Linux/macOS):
 *
 *   java scripts/PerfRunner.java                                  (5000 requests, concurrency 32, localhost:8080)
 *   java scripts/PerfRunner.java --requests 10000 --concurrency 64 --url http://host:8080 --label "degraded"
 *
 * Traffic mix: bank-a 60%, bank-b 25%, bank-c 15%; ~5% of requests replay an earlier transaction with the
 * identical payload (switch retries). Prints request/error counts, client-side p50/p95/p99 and the
 * server-reported evaluationMs percentiles, as a Markdown block ready for docs/architecture/perf-results.md.
 */
public class PerfRunner {

    private static final Pattern EVALUATION_MS = Pattern.compile("\"evaluationMs\":(\\d+)");
    private static final Pattern DEGRADED = Pattern.compile("\"degraded\":true");
    private static final String[] COUNTRIES = {"IN", "IN", "IN", "IN", "AE", "SG", "GB", "US", "IR"};

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        String baseUrl = opts.getOrDefault("url", "http://localhost:8080");
        int total = Integer.parseInt(opts.getOrDefault("requests", "5000"));
        int concurrency = Integer.parseInt(opts.getOrDefault("concurrency", "32"));
        String label = opts.getOrDefault("label", "normal");

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1).build();
        List<String[]> sent = Collections.synchronizedList(new ArrayList<>()); // [tenant, body] for replays
        List<Long> latenciesMicros = Collections.synchronizedList(new ArrayList<>(total));
        List<Integer> serverMs = Collections.synchronizedList(new ArrayList<>(total));
        Map<Integer, AtomicInteger> statusCounts = new ConcurrentHashMap<>();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        AtomicInteger degraded = new AtomicInteger();

        warmUp(http, baseUrl);
        Semaphore inFlight = new Semaphore(concurrency);
        long started = System.nanoTime();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < total; i++) {
                inFlight.acquire();
                pool.submit(() -> {
                    try {
                        String[] request = nextRequest(sent, duplicates);
                        HttpRequest httpRequest = HttpRequest.newBuilder(
                                        URI.create(baseUrl + "/v1/tenants/" + request[0] + "/screenings"))
                                .timeout(Duration.ofSeconds(5))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(request[1]))
                                .build();
                        long t0 = System.nanoTime();
                        HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                        latenciesMicros.add((System.nanoTime() - t0) / 1_000);
                        statusCounts.computeIfAbsent(response.statusCode(), k -> new AtomicInteger()).incrementAndGet();
                        if (response.statusCode() != 200) {
                            errors.incrementAndGet();
                        } else {
                            Matcher m = EVALUATION_MS.matcher(response.body());
                            if (m.find()) {
                                serverMs.add(Integer.parseInt(m.group(1)));
                            }
                            if (DEGRADED.matcher(response.body()).find()) {
                                degraded.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        inFlight.release();
                    }
                });
            }
        }
        double seconds = (System.nanoTime() - started) / 1e9;

        List<Long> lat = new ArrayList<>(latenciesMicros);
        Collections.sort(lat);
        List<Integer> srv = new ArrayList<>(serverMs);
        Collections.sort(srv);
        System.out.printf("""
                ### Run: %s
                | Metric | Value |
                |---|---|
                | Requests | %d (of which replayed duplicates: %d) |
                | Concurrency | %d |
                | Errors (non-200 or transport) | %d |
                | Status codes | %s |
                | Degraded responses | %d |
                | Throughput | %.0f req/s |
                | Client latency p50 / p95 / p99 | %s / %s / %s ms |
                | Server evaluationMs p50 / p95 / p99 | %s / %s / %s ms |
                | Targets at HTTP boundary | p95 40 ms / p99 80 ms |
                %n""",
                label, total, duplicates.get(), concurrency, errors.get(), statusCounts, degraded.get(),
                total / seconds,
                ms(pct(lat, 50)), ms(pct(lat, 95)), ms(pct(lat, 99)),
                pctInt(srv, 50), pctInt(srv, 95), pctInt(srv, 99));
    }

    private static String[] nextRequest(List<String[]> sent, AtomicInteger duplicates) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (sent.size() > 100 && rnd.nextInt(100) < 5) {
            duplicates.incrementAndGet();
            return sent.get(rnd.nextInt(sent.size()));
        }
        int pick = rnd.nextInt(100);
        String tenant = pick < 60 ? "bank-a" : pick < 85 ? "bank-b" : "bank-c";
        String amount = BigDecimal.valueOf(rnd.nextDouble(10, 15000)).setScale(2, RoundingMode.HALF_UP).toPlainString();
        String body = """
                {"transactionId":"perf-%s","accountToken":"acct-%04d","amount":%s,"currency":"INR","merchantCategory":"5411","beneficiaryId":"ben-%03d","country":"%s","timestamp":"%s"}"""
                .formatted(UUID.randomUUID(), rnd.nextInt(2000), amount, rnd.nextInt(300),
                        COUNTRIES[rnd.nextInt(COUNTRIES.length)], Instant.now());
        String[] request = {tenant, body};
        sent.add(request);
        return request;
    }

    private static void warmUp(HttpClient http, String baseUrl) throws Exception {
        HttpRequest health = HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/health")).GET().build();
        http.send(health, HttpResponse.BodyHandlers.discarding());
    }

    private static long pct(List<Long> sorted, int p) {
        return sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
    }

    private static String pctInt(List<Integer> sorted, int p) {
        return sorted.isEmpty() ? "n/a" : String.valueOf(sorted.get(Math.min(sorted.size() - 1,
                (int) Math.ceil(p / 100.0 * sorted.size()) - 1)));
    }

    private static String ms(long micros) {
        return String.format("%.1f", micros / 1000.0);
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> opts = new ConcurrentHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            opts.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        return opts;
    }
}
