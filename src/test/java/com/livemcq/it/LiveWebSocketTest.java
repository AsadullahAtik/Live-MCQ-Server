package com.livemcq.it;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.livemcq.domain.Attempt;
import com.livemcq.live.LiveHub;
import com.livemcq.repo.AttemptRepo;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

/** আসল Tomcat পোর্টে JDK WebSocket ক্লায়েন্ট দিয়ে লাইভ ইভেন্টের পরিধি (কে কী পায়) যাচাই. */
class LiveWebSocketTest extends IntegrationBase {
    @Autowired LiveHub hub;
    @Autowired AttemptRepo attempts;

    /** ইভেন্ট সারিতে জমা করা ক্লায়েন্ট. */
    static final class Client {
        final BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
        final BlockingQueue<String> raw = new LinkedBlockingQueue<>();
        WebSocket ws;
        final StringBuilder buf = new StringBuilder();

        /** type-এর ইভেন্ট examId-সহ আসা পর্যন্ত অপেক্ষা; অন্য ইভেন্ট বাদ. */
        JsonNode await(String type, long examId, long millis) throws Exception {
            long end = System.currentTimeMillis() + millis;
            while (true) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) return null;
                JsonNode n = events.poll(left, TimeUnit.MILLISECONDS);
                if (n == null) return null;
                if (type.equals(n.path("type").asText()) && n.path("examId").asLong() == examId) return n;
            }
        }

        /** type-এর ইভেন্ট এই সময়ে আসে না তো? */
        boolean silent(String type, long examId, long millis) throws Exception {
            return await(type, examId, millis) == null;
        }

        void close() { if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye"); }
    }

    private Client connect(String token, boolean viaHeader) throws Exception {
        Client c = new Client();
        WebSocket.Listener l = new WebSocket.Listener() {
            @Override public void onOpen(WebSocket w) { w.request(1); }
            @Override public CompletionStage<?> onText(WebSocket w, CharSequence d, boolean last) {
                c.buf.append(d);
                if (last) {
                    String s = c.buf.toString();
                    c.buf.setLength(0);
                    c.raw.add(s);
                    try { c.events.add(om.readTree(s)); } catch (Exception ignore) { }
                }
                w.request(1);
                return null;
            }
        };
        HttpClient hc = HttpClient.newHttpClient();
        WebSocket.Builder b = hc.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5));
        String url = "ws://localhost:" + port + "/ws";
        if (token != null) {
            if (viaHeader) b.header("Authorization", "Bearer " + token);
            else url += "?token=" + token;
        }
        c.ws = b.buildAsync(URI.create(url), l).get(5, TimeUnit.SECONDS);
        // ping→pong পেলে নিশ্চিত যে সার্ভার সংযোগ নিবন্ধন করে ফেলেছে
        c.ws.sendText("ping", true);
        JsonNode pong = c.events.poll(5, TimeUnit.SECONDS);
        assertNotNull(pong, "pong এসেছে");
        assertEquals("pong", pong.path("type").asText());
        return c;
    }

    @Test
    void handshakeRejectsMissingOrBadToken() {
        for (String tok : new String[]{null, "bad.token.here"}) {
            ExecutionException ex = assertThrows(ExecutionException.class, () -> {
                HttpClient.newHttpClient().newWebSocketBuilder()
                        .buildAsync(URI.create("ws://localhost:" + port + "/ws" + (tok == null ? "" : "?token=" + tok)),
                                new WebSocket.Listener() { })
                        .get(5, TimeUnit.SECONDS);
            });
            assertNotNull(ex.getCause());
        }
    }

    @Test
    void eventsAreScopedByRoleAndClass() throws Exception {
        String admin = login("admin", "admin");
        long c9 = classId(admin, "নবম শ্রেণি"), c10 = classId(admin, "দশম শ্রেণি");
        Client a = connect(admin, true);
        Client t = connect(login("t1", "1234"), false);     // টোকেন query-তে
        Client s101 = connect(login("101", "1234"), true);
        Client s201 = connect(login("201", "1234"), true);
        try {
            // নবম শ্রেণির পরীক্ষা: অ্যাডমিন, শিক্ষক, ১০১ পায়; ২০১ পায় না
            long exam = createExam(admin, c9, false);
            assertNotNull(a.await("exam_created", exam, 5000), "admin");
            assertNotNull(t.await("exam_created", exam, 5000), "teacher");
            assertNotNull(s101.await("exam_created", exam, 5000), "student 101");
            assertTrue(s201.silent("exam_created", exam, 700), "student 201 পায় না");

            // ছাত্র শুরু ও জমা: শুধু স্টাফ পায়
            String stu = login("101", "1234");
            JsonNode st = json(text(call(HttpMethod.POST, "/api/exams/" + exam + "/start", stu, null)));
            long attempt = st.path("attemptId").asLong();
            JsonNode started = a.await("attempt_started", exam, 5000);
            assertNotNull(started);
            assertEquals("101", started.path("roll").asText());
            assertNotNull(t.await("attempt_started", exam, 5000));
            call(HttpMethod.POST, "/api/attempts/" + attempt + "/submit", stu, Map.of("answers", list(allCorrect(st)))).andReturn();
            assertNotNull(a.await("attempt_submitted", exam, 5000));
            assertNotNull(t.await("attempt_submitted", exam, 5000));
            assertTrue(s101.silent("attempt_started", exam, 500), "ছাত্র attempt ইভেন্ট পায় না");
            assertTrue(s101.silent("attempt_submitted", exam, 300));
            assertTrue(s201.silent("attempt_started", exam, 300));

            // প্রকাশ
            call(HttpMethod.POST, "/api/admin/exams/" + exam + "/publish", admin, null).andReturn();
            assertNotNull(a.await("published", exam, 5000));
            assertNotNull(t.await("published", exam, 5000));
            assertNotNull(s101.await("published", exam, 5000));
            assertTrue(s201.silent("published", exam, 500));

            // দশম শ্রেণির পরীক্ষা: শিক্ষক পায় না, ২০১ পায়
            long other = createExam(admin, c10, false);
            assertNotNull(a.await("exam_created", other, 5000));
            assertNotNull(s201.await("exam_created", other, 5000));
            assertTrue(t.silent("exam_created", other, 700), "শিক্ষক অন্য শ্রেণির ইভেন্ট পায় না");
            assertTrue(s101.silent("exam_created", other, 300));
        } finally {
            a.close(); t.close(); s101.close(); s201.close();
        }
    }

    @Test
    void sweeperAutoSubmitIsBroadcastToStaff() throws Exception {
        String admin = login("admin", "admin");
        long exam = createExam(admin, classId(admin, "নবম শ্রেণি"), false);
        Client a = connect(admin, true);
        try {
            String stu = login("103", "1234");
            long attemptId = json(text(call(HttpMethod.POST, "/api/exams/" + exam + "/start", stu, null))).path("attemptId").asLong();
            assertNotNull(a.await("attempt_started", exam, 5000));
            // সময় পার হয়ে গেছে ধরে নিই; sweeper (১ সেকেন্ড অন্তর) নিজে জমা করবে
            Attempt at = attempts.findById(attemptId).orElseThrow();
            at.deadline = Instant.now().minusSeconds(5);
            attempts.saveAndFlush(at);
            JsonNode ev = a.await("attempt_auto_submitted", exam, 8000);
            assertNotNull(ev, "sweeper অটো-জমা ইভেন্ট");
            assertEquals("103", ev.path("roll").asText());
            // জমা হয়েছে: আবার শুরু করা যায় না
            call(HttpMethod.POST, "/api/exams/" + exam + "/start", stu, null).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        } finally {
            a.close();
        }
    }

    @Test
    void onlineCountDropsAfterClose() throws Exception {
        int before = hub.online();
        Client c = connect(login("admin", "admin"), true);
        assertEquals(before + 1, hub.online());
        c.close();
        long end = System.currentTimeMillis() + 5000;
        while (hub.online() > before && System.currentTimeMillis() < end) Thread.sleep(50);
        assertEquals(before, hub.online());
    }
}
