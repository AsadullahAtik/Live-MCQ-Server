import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;

/**
 * লোড টেস্ট: N জন ভার্চুয়াল ছাত্র একসাথে লগইন → পরীক্ষা শুরু → উত্তর সেভ → জমা.
 * প্রতিটি ছাত্র "ডাবল-ক্লিক" ও করে (জমা দুবার একসাথে), সার্ভার যেন একবারই নেয়.
 * চালান:  java tools/LoadTest.java http://localhost:8080 50
 */
public class LoadTest {
    static final HttpClient http = HttpClient.newBuilder().executor(Executors.newFixedThreadPool(64)).build();
    static String base;

    static String call(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 400) throw new RuntimeException(method + " " + path + " -> " + r.statusCode() + " " + r.body());
        return r.body();
    }

    static String find(String json, String regex) {
        Matcher m = Pattern.compile(regex).matcher(json);
        if (!m.find()) throw new RuntimeException("not found: " + regex + " in " + json);
        return m.group(1);
    }

    public static void main(String[] args) throws Exception {
        base = args.length > 0 ? args[0] : "http://localhost:8080";
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 50;
        String run = Long.toString(System.currentTimeMillis() % 1_000_000);

        String admin = find(call("POST", "/api/auth/login", null, "{\"roll\":\"admin\",\"password\":\"admin\"}"), "\"token\":\"([^\"]+)\"");
        String classId = find(call("POST", "/api/admin/classes", admin, "{\"name\":\"LT-" + run + "\",\"sections\":\"ক\"}"), "\"id\":(\\d+)");
        StringBuilder qs = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i > 0) qs.append(',');
            qs.append("{\"text\":\"প্রশ্ন ").append(i + 1).append("\",\"options\":[\"ক\",\"খ\",\"গ\",\"ঘ\"],\"correct\":").append(i % 4).append('}');
        }
        String examId = find(call("POST", "/api/admin/exams", admin,
                "{\"title\":\"Load " + run + "\",\"classId\":" + classId + ",\"durationMin\":5,\"perQ\":1,\"neg\":0.25,\"questions\":[" + qs + "]}"), "\"id\":(\\d+)");

        for (int i = 0; i < n; i++) {
            call("POST", "/api/auth/register", null, "{\"roll\":\"LT" + run + "-" + i + "\",\"name\":\"Student " + i + "\",\"password\":\"pass1234\",\"classId\":" + classId + ",\"section\":\"ক\"}");
        }
        System.out.println("প্রস্তুত: " + n + " জন ছাত্র, পরীক্ষা #" + examId);

        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n), go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger(), dupRejectedOrIdem = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            final int k = i;
            fs.add(pool.submit(() -> {
                try {
                    String tok = find(call("POST", "/api/auth/login", null, "{\"roll\":\"LT" + run + "-" + k + "\",\"password\":\"pass1234\"}"), "\"token\":\"([^\"]+)\"");
                    ready.countDown();
                    go.await();
                    // ডাবল-ক্লিক: একসাথে দুবার start
                    CompletableFuture<String> s1 = CompletableFuture.supplyAsync(() -> safe(() -> call("POST", "/api/exams/" + examId + "/start", tok, null)));
                    CompletableFuture<String> s2 = CompletableFuture.supplyAsync(() -> safe(() -> call("POST", "/api/exams/" + examId + "/start", tok, null)));
                    String start = s1.get() != null ? s1.get() : s2.get();
                    String attemptId = find(start, "\"attemptId\":(\\d+)");
                    StringBuilder ans = new StringBuilder();
                    for (int q = 0; q < 10; q++) {
                        if (q > 0) ans.append(',');
                        ans.append((q + k) % 5 == 0 ? -1 : (q + k) % 4);
                    }
                    call("PUT", "/api/attempts/" + attemptId + "/answers", tok, "{\"answers\":[" + ans + "]}");
                    // ডাবল-ক্লিক: একসাথে দুবার জমা
                    CompletableFuture<String> j1 = CompletableFuture.supplyAsync(() -> safe(() -> call("POST", "/api/attempts/" + attemptId + "/submit", tok, "{\"answers\":[" + ans + "]}")));
                    CompletableFuture<String> j2 = CompletableFuture.supplyAsync(() -> safe(() -> call("POST", "/api/attempts/" + attemptId + "/submit", tok, "{\"answers\":[" + ans + "]}")));
                    if (j1.get() != null && j2.get() != null) dupRejectedOrIdem.incrementAndGet();
                    ok.incrementAndGet();
                } catch (Exception e) {
                    System.out.println("ব্যর্থ #" + k + ": " + e.getMessage());
                }
                return null;
            }));
        }
        ready.await();
        go.countDown();
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        double sec = (System.nanoTime() - t0) / 1e9;

        call("POST", "/api/admin/exams/" + examId + "/publish", admin, null);
        String board = call("GET", "/api/admin/exams/" + examId + "/results", admin, null);
        Matcher m = Pattern.compile("\"rank\":(\\d+),\"name\":\"[^\"]*\",\"roll\":\"[^\"]*\",\"marks\":([0-9.]+)").matcher(board);
        int rows = 0, lastRank = 0;
        double lastMarks = Double.MAX_VALUE;
        boolean order = true;
        while (m.find()) {
            rows++;
            int rk = Integer.parseInt(m.group(1));
            double mk = Double.parseDouble(m.group(2));
            if (rk < lastRank || mk > lastMarks) order = false;
            lastRank = rk;
            lastMarks = mk;
        }
        System.out.printf("সম্পন্ন: %d/%d জন সফল, %.2f সেকেন্ডে%n", ok.get(), n, sec);
        System.out.println("ফলাফল তালিকায় সারি: " + rows + " (প্রত্যাশিত " + n + ") → " + (rows == n ? "ঠিক" : "ভুল!"));
        System.out.println("র‍্যাংক ক্রম ঠিক আছে: " + order);
        System.out.println("ডাবল জমা নিরাপদ (দুবার জমা দিলেও ত্রুটি নয়): " + dupRejectedOrIdem.get() + "/" + n);
        if (rows != n || !order || ok.get() != n) System.exit(1);
    }

    interface Call { String run() throws Exception; }

    static String safe(Call c) {
        try { return c.run(); } catch (Exception e) { return null; }
    }
}
