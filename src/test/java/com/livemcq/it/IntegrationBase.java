package com.livemcq.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * আসল Spring context (মেমরির H2 ডাটাবেস, আসল JPA/SQL, আসল JWT ফিল্টার, আসল Tomcat পোর্টে WebSocket).
 * DataSeeder নিজে চলে: admin/admin, ছাত্র 250112 ও 101..105 (নবম), 201 (দশম), শিক্ষক t1 (নবম).
 * সব টেস্ট ক্লাস একই context ভাগ করে (একই properties), তাই চালু হতে একবারই সময় লাগে।
 * প্রতিটি টেস্ট নিজের নতুন পরীক্ষা বানায়, অন্যের ডেটা বদলায় না।
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:itest;DB_CLOSE_DELAY=-1",
        "app.sweeper.interval-ms=1000"
})
@AutoConfigureMockMvc
public abstract class IntegrationBase {
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper om;
    @LocalServerPort protected int port;

    // ---------- HTTP সাহায্যকারী ----------

    protected ResultActions call(HttpMethod method, String url, String token, Object body) throws Exception {
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(method, url).contentType(MediaType.APPLICATION_JSON);
        if (token != null) b = b.header("Authorization", "Bearer " + token);
        if (body != null) b = b.content(body instanceof String s ? s : om.writeValueAsString(body));
        return mvc.perform(b);
    }

    protected static String text(ResultActions r) throws Exception {
        MvcResult m = r.andReturn();
        return m.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    protected String login(String roll, String password) throws Exception {
        String body = text(call(HttpMethod.POST, "/api/auth/login", null, Map.of("roll", roll, "password", password)));
        return JsonPath.read(body, "$.token");
    }

    protected JsonNode json(String s) throws Exception {
        return om.readTree(s);
    }

    /** শ্রেণির নাম থেকে id (সিড করা: "নবম শ্রেণি", "দশম শ্রেণি"). */
    protected long classId(String adminToken, String name) throws Exception {
        JsonNode arr = json(text(call(HttpMethod.GET, "/api/admin/classes", adminToken, null)));
        for (JsonNode c : arr) if (c.path("name").asText().equals(name)) return c.path("id").asLong();
        throw new AssertionError("শ্রেণি পাওয়া যায়নি: " + name);
    }

    // ---------- পরীক্ষার ডেটা ----------

    /** প্রশ্ন i: অপশন "অi, আi, ইi, ঈi"; সঠিক = (i*3)%4 */
    protected static List<Map<String, Object>> five() {
        List<Map<String, Object>> qs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Map<String, Object> q = new HashMap<>();
            q.put("text", "প্রশ্ন" + i);
            q.put("options", List.of("অ" + i, "আ" + i, "ই" + i, "ঈ" + i));
            q.put("correct", (i * 3) % 4);
            qs.add(q);
        }
        return qs;
    }

    protected Map<String, Object> examBody(String title, long classId, boolean shuffle, String startAt, String endAt) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", title);
        m.put("classId", classId);
        m.put("durationMin", 5);
        m.put("perQ", 1);
        m.put("neg", 0.25);
        m.put("shuffle", shuffle);
        m.put("startAt", startAt);
        m.put("endAt", endAt);
        m.put("questions", five());
        return m;
    }

    protected long createExam(String adminToken, long classId, boolean shuffle) throws Exception {
        String res = text(call(HttpMethod.POST, "/api/admin/exams", adminToken, examBody("পরীক্ষা-" + System.nanoTime(), classId, shuffle, null, null)));
        return JsonPath.<Number>read(res, "$.id").longValue();
    }

    /** start-এর জবাব থেকে পর্দার ক্রমে "সব সঠিক" উত্তর (লেখা মিলিয়ে). */
    protected int[] allCorrect(JsonNode start) {
        JsonNode qs = start.path("questions");
        int[] best = new int[qs.size()];
        String[] letters = {"অ", "আ", "ই", "ঈ"};
        for (int p = 0; p < best.length; p++) {
            JsonNode q = qs.get(p);
            int orig = Integer.parseInt(q.path("text").asText().substring("প্রশ্ন".length()));
            String correct = letters[(orig * 3) % 4] + orig;
            best[p] = -1;
            for (int k = 0; k < 4; k++) if (q.path("options").get(k).asText().equals(correct)) best[p] = k;
        }
        return best;
    }

    protected static List<Integer> list(int[] a) {
        List<Integer> l = new ArrayList<>();
        for (int x : a) l.add(x);
        return l;
    }
}
