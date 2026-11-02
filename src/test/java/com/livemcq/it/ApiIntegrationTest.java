package com.livemcq.it;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** REST API, JWT, ভূমিকা-সীমা, ভ্যালিডেশন ও ত্রুটির JSON — আসল ফিল্টার ও ডাটাবেসসহ. */
class ApiIntegrationTest extends IntegrationBase {

    // ---------- লগইন ও টোকেন ----------

    @Test
    void healthIsOpenButApiNeedsToken() throws Exception {
        call(HttpMethod.GET, "/api/health", null, null).andExpect(status().isOk());
        call(HttpMethod.GET, "/api/exams", null, null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").exists());
        call(HttpMethod.GET, "/api/exams", "garbage.token.value", null).andExpect(status().isUnauthorized());
    }

    @Test
    void loginSuccessAndFailure() throws Exception {
        call(HttpMethod.POST, "/api/auth/login", null, Map.of("roll", "admin", "password", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.user.role").value("ADMIN"));
        call(HttpMethod.POST, "/api/auth/login", null, Map.of("roll", "admin", "password", "wrong")).andExpect(status().isUnauthorized());
        call(HttpMethod.POST, "/api/auth/login", null, Map.of("roll", "nobody", "password", "x")).andExpect(status().isUnauthorized());
        call(HttpMethod.POST, "/api/auth/login", null, Map.of("roll", "t1", "password", "1234"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value("TEACHER"));
    }

    @Test
    void registerRejectsDuplicateRoll() throws Exception {
        JsonNode classes = json(text(call(HttpMethod.GET, "/api/auth/classes", null, null)));
        long cid = classes.get(0).path("id").asLong();
        Map<String, Object> body = new HashMap<>();
        body.put("roll", "new-" + System.nanoTime());
        body.put("name", "নতুন ছাত্র");
        body.put("password", "abcd");
        body.put("classId", cid);
        call(HttpMethod.POST, "/api/auth/register", null, body).andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value("STUDENT"));
        call(HttpMethod.POST, "/api/auth/register", null, body).andExpect(status().isConflict()).andExpect(jsonPath("$.error").exists());
    }

    // ---------- ভুল ইনপুট ----------

    @Test
    void errorsAreJsonWithMessage() throws Exception {
        String admin = login("admin", "admin");
        call(HttpMethod.POST, "/api/admin/exams", admin, "{bad json").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        long cid = classId(admin, "নবম শ্রেণি");
        Map<String, Object> noTitle = examBody("", cid, false, null, null);
        call(HttpMethod.POST, "/api/admin/exams", admin, noTitle).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        call(HttpMethod.GET, "/api/admin/exams/999999", admin, null).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").exists());
        Map<String, Object> badWindow = examBody("সময়", cid, false, "2031-01-02T00:00:00Z", "2031-01-01T00:00:00Z");
        call(HttpMethod.POST, "/api/admin/exams", admin, badWindow).andExpect(status().isBadRequest());
    }

    @Test
    void examCreatedWithoutNewFieldsStillWorks() throws Exception {
        // পুরনো ক্লায়েন্ট/LoadTest shuffle, startAt, endAt পাঠায় না
        String admin = login("admin", "admin");
        long cid = classId(admin, "নবম শ্রেণি");
        Map<String, Object> m = examBody("পুরনো ধাঁচ", cid, false, null, null);
        m.remove("shuffle");
        m.remove("startAt");
        m.remove("endAt");
        call(HttpMethod.POST, "/api/admin/exams", admin, m).andExpect(status().isOk()).andExpect(jsonPath("$.shuffle").value(false));
    }

    // ---------- সময়ের JSON রূপ ----------

    @Test
    void instantsRoundTripAsIsoStrings() throws Exception {
        String admin = login("admin", "admin");
        long cid = classId(admin, "নবম শ্রেণি");
        Map<String, Object> body = examBody("সময়সূচি", cid, false, "2031-01-01T10:00:00Z", "2031-01-01T12:00:00Z");
        call(HttpMethod.POST, "/api/admin/exams", admin, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startAt").value("2031-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.endAt").value("2031-01-01T12:00:00Z"));
        // ছাত্র দেখে upcoming (এখনো শুরু হয়নি)
        String stu = login("101", "1234");
        JsonNode list = json(text(call(HttpMethod.GET, "/api/exams", stu, null)));
        boolean found = false;
        for (JsonNode e : list) if (e.path("title").asText().equals("সময়সূচি")) { found = true; assertEquals("upcoming", e.path("state").asText()); }
        assertTrue(found, "ছাত্রের তালিকায় পরীক্ষাটি আছে");
    }

    // ---------- পুরো ছাত্র-যাত্রা (shuffle চালু) ----------

    @Test
    void fullStudentJourneyWithShuffle() throws Exception {
        String admin = login("admin", "admin"), stu = login("102", "1234");
        long exam = createExam(admin, classId(admin, "নবম শ্রেণি"), true);

        JsonNode st = json(text(call(HttpMethod.POST, "/api/exams/" + exam + "/start", stu, null)));
        long attempt = st.path("attemptId").asLong();
        assertEquals(5, st.path("questions").size());
        assertTrue(st.path("deadline").isTextual(), "deadline ISO লেখা");
        int[] best = allCorrect(st);
        for (int b : best) assertTrue(b >= 0, "সঠিক অপশন পর্দায় আছে");

        // অটোসেভ, তারপর আবার start (চালিয়ে যাওয়া): একই উত্তর, একই ক্রম
        int[] half = {best[0], best[1], -1, -1, -1};
        call(HttpMethod.PUT, "/api/attempts/" + attempt + "/answers", stu, Map.of("answers", list(half))).andExpect(status().isOk());
        JsonNode again = json(text(call(HttpMethod.POST, "/api/exams/" + exam + "/start", stu, null)));
        assertEquals(attempt, again.path("attemptId").asLong());
        assertEquals(st.path("questions").get(0).path("text").asText(), again.path("questions").get(0).path("text").asText());
        for (int i = 0; i < 5; i++) assertEquals(half[i], again.path("answers").get(i).asInt());

        // জমা (দুবার) এবং ফলাফল প্রকাশের আগে দেখা যায় না
        call(HttpMethod.POST, "/api/attempts/" + attempt + "/submit", stu, Map.of("answers", list(best))).andExpect(status().isOk());
        call(HttpMethod.POST, "/api/attempts/" + attempt + "/submit", stu, Map.of("answers", list(best)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("আগেই জমা হয়েছে"));
        call(HttpMethod.GET, "/api/results/" + exam, stu, null).andExpect(status().isForbidden());
        call(HttpMethod.GET, "/api/exams/" + exam + "/leaderboard", stu, null).andExpect(status().isForbidden());
        call(HttpMethod.POST, "/api/exams/" + exam + "/start", stu, null).andExpect(status().isConflict());

        call(HttpMethod.POST, "/api/admin/exams/" + exam + "/publish", admin, null).andExpect(status().isOk());
        call(HttpMethod.GET, "/api/results/" + exam, stu, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marks").value(5.0))
                .andExpect(jsonPath("$.correct").value(5))
                .andExpect(jsonPath("$.rank").value(1))
                .andExpect(jsonPath("$.review.length()").value(5));
        call(HttpMethod.GET, "/api/exams/" + exam + "/leaderboard", stu, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].me").value(true));
        // প্রকাশের পর নতুন কেউ শুরু করতে পারে না
        call(HttpMethod.POST, "/api/exams/" + exam + "/start", login("103", "1234"), null).andExpect(status().isForbidden());
    }

    @Test
    void examOfAnotherClassIsForbidden() throws Exception {
        String admin = login("admin", "admin");
        long exam = createExam(admin, classId(admin, "নবম শ্রেণি"), false);
        call(HttpMethod.POST, "/api/exams/" + exam + "/start", login("201", "1234"), null).andExpect(status().isForbidden());
    }

    // ---------- সম্পাদনা ----------

    @Test
    void editAllowedOnlyBeforeAnyoneStarts() throws Exception {
        String admin = login("admin", "admin");
        long cid = classId(admin, "নবম শ্রেণি");
        long exam = createExam(admin, cid, false);
        call(HttpMethod.PUT, "/api/admin/exams/" + exam, admin, examBody("বদলানো", cid, true, null, null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("বদলানো")).andExpect(jsonPath("$.shuffle").value(true));
        call(HttpMethod.POST, "/api/exams/" + exam + "/start", login("104", "1234"), null).andExpect(status().isOk());
        call(HttpMethod.PUT, "/api/admin/exams/" + exam, admin, examBody("আবার", cid, true, null, null)).andExpect(status().isConflict());
    }

    // ---------- ভূমিকা ----------

    @Test
    void studentCannotUseAdminApi() throws Exception {
        String stu = login("101", "1234");
        call(HttpMethod.GET, "/api/admin/exams", stu, null).andExpect(status().isForbidden());
        call(HttpMethod.GET, "/api/admin/students", stu, null).andExpect(status().isForbidden());
        call(HttpMethod.POST, "/api/admin/classes", stu, Map.of("name", "হ্যাক")).andExpect(status().isForbidden());
    }

    @Test
    void teacherSeesAndChangesOnlyOwnClass() throws Exception {
        String admin = login("admin", "admin"), t = login("t1", "1234");
        long c9 = classId(admin, "নবম শ্রেণি"), c10 = classId(admin, "দশম শ্রেণি");
        long mine = createExam(admin, c9, false), other = createExam(admin, c10, false);

        JsonNode exams = json(text(call(HttpMethod.GET, "/api/admin/exams", t, null)));
        boolean sawMine = false;
        for (JsonNode e : exams) {
            assertEquals(c9, e.path("classId").asLong(), "শিক্ষক শুধু নিজের শ্রেণির পরীক্ষা দেখে");
            if (e.path("id").asLong() == mine) sawMine = true;
            assertNotEquals(other, e.path("id").asLong());
        }
        assertTrue(sawMine);

        JsonNode classes = json(text(call(HttpMethod.GET, "/api/admin/classes", t, null)));
        assertEquals(1, classes.size());
        for (JsonNode s : json(text(call(HttpMethod.GET, "/api/admin/students", t, null)))) assertEquals(c9, s.path("classId").asLong());

        call(HttpMethod.GET, "/api/admin/exams/" + mine, t, null).andExpect(status().isOk());
        call(HttpMethod.GET, "/api/admin/exams/" + other, t, null).andExpect(status().isForbidden());
        call(HttpMethod.POST, "/api/admin/exams/" + other + "/publish", t, null).andExpect(status().isForbidden());
        call(HttpMethod.DELETE, "/api/admin/exams/" + other, t, null).andExpect(status().isForbidden());
        call(HttpMethod.GET, "/api/admin/exams/" + other + "/results", t, null).andExpect(status().isForbidden());
        call(HttpMethod.POST, "/api/admin/exams", t, examBody("অন্যের শ্রেণিতে", c10, false, null, null)).andExpect(status().isForbidden());
        call(HttpMethod.POST, "/api/admin/exams", t, examBody("নিজের শ্রেণিতে", c9, false, null, null)).andExpect(status().isOk());
        // নিজের পরীক্ষা অন্য শ্রেণিতে সরানো যায় না
        call(HttpMethod.PUT, "/api/admin/exams/" + mine, t, examBody("সরানো", c10, false, null, null)).andExpect(status().isForbidden());

        // শুধু অ্যাডমিনের কাজ
        call(HttpMethod.GET, "/api/admin/teachers", t, null).andExpect(status().isForbidden());
        call(HttpMethod.POST, "/api/admin/classes", t, Map.of("name", "নতুন")).andExpect(status().isForbidden());
        call(HttpMethod.PUT, "/api/admin/students/1/enroll", t, Map.of("classId", c10, "section", "ক")).andExpect(status().isForbidden());
        // শিক্ষক পরীক্ষা দিতে পারে না
        call(HttpMethod.POST, "/api/exams/" + mine + "/start", t, null).andExpect(status().isForbidden());
    }

    @Test
    void adminManagesTeachers() throws Exception {
        String admin = login("admin", "admin");
        long c10 = classId(admin, "দশম শ্রেণি");
        String roll = "tz" + System.nanoTime();
        Map<String, Object> body = new HashMap<>();
        body.put("roll", roll);
        body.put("name", "নতুন শিক্ষক");
        body.put("password", "abcd");
        body.put("classIds", List.of(c10));
        String created = text(call(HttpMethod.POST, "/api/admin/teachers", admin, body).andExpect(status().isOk()));
        long id = json(created).path("id").asLong();
        call(HttpMethod.POST, "/api/admin/teachers", admin, body).andExpect(status().isConflict());

        // নতুন শিক্ষক শুধু দশমের পরীক্ষা দেখে
        String tz = login(roll, "abcd");
        JsonNode classes = json(text(call(HttpMethod.GET, "/api/admin/classes", tz, null)));
        assertEquals(1, classes.size());
        assertEquals(c10, classes.get(0).path("id").asLong());

        // শ্রেণি বদল: এবার নবম (আবার লগইন না করলেও HTTP-তে সাথে সাথে কার্যকর, কারণ প্রতিবার ডাটাবেস থেকে দেখে)
        long c9 = classId(admin, "নবম শ্রেণি");
        call(HttpMethod.PUT, "/api/admin/teachers/" + id + "/classes", admin, Map.of("classIds", List.of(c9)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.classIds[0]").value(c9));
        assertEquals(c9, json(text(call(HttpMethod.GET, "/api/admin/classes", tz, null))).get(0).path("id").asLong());

        call(HttpMethod.DELETE, "/api/admin/teachers/" + id, admin, null).andExpect(status().isOk());
        call(HttpMethod.DELETE, "/api/admin/teachers/" + id, admin, null).andExpect(status().isNotFound());
    }

    // ---------- অ্যাডমিন: শ্রেণি, শাখা, ছাত্র ----------

    @Test
    void classesSectionsAndEnroll() throws Exception {
        String admin = login("admin", "admin");
        String name = "পরীক্ষামূলক-" + System.nanoTime();
        long cid = json(text(call(HttpMethod.POST, "/api/admin/classes", admin, Map.of("name", name, "sections", "")).andExpect(status().isOk()))).path("id").asLong();
        call(HttpMethod.POST, "/api/admin/classes/" + cid + "/sections", admin, Map.of("name", "ক")).andExpect(status().isOk()).andExpect(jsonPath("$.sections").value("ক"));
        call(HttpMethod.POST, "/api/admin/classes/" + cid + "/sections", admin, Map.of("name", "ক")).andExpect(status().isBadRequest());
        call(HttpMethod.POST, "/api/admin/classes/" + cid + "/sections", admin, Map.of("name", "খ")).andExpect(status().isOk()).andExpect(jsonPath("$.sections").value("ক,খ"));

        // ছাত্র সরানো: ভুল শাখায় ৪০০, ঠিক শাখায় ৫০০০ নয়
        JsonNode students = json(text(call(HttpMethod.GET, "/api/admin/students", admin, null)));
        long sid = 0;
        for (JsonNode s : students) if (s.path("roll").asText().equals("105")) sid = s.path("id").asLong();
        assertTrue(sid > 0);
        call(HttpMethod.PUT, "/api/admin/students/" + sid + "/enroll", admin, Map.of("classId", cid, "section", "ঘ")).andExpect(status().isBadRequest());
        call(HttpMethod.PUT, "/api/admin/students/" + sid + "/enroll", admin, Map.of("classId", cid, "section", "খ"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.className").value(name)).andExpect(jsonPath("$.section").value("খ"));
        call(HttpMethod.GET, "/api/admin/students/" + sid + "/report", admin, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.student.roll").value("105")).andExpect(jsonPath("$.totalExams").value(0));
        // আগের শ্রেণিতে ফিরিয়ে দিই, যাতে অন্য টেস্ট না ভাঙে
        long c9 = classId(admin, "নবম শ্রেণি");
        call(HttpMethod.PUT, "/api/admin/students/" + sid + "/enroll", admin, Map.of("classId", c9, "section", "ক")).andExpect(status().isOk());
    }

    @Test
    void deletingExamRemovesIt() throws Exception {
        String admin = login("admin", "admin");
        long exam = createExam(admin, classId(admin, "নবম শ্রেণি"), false);
        call(HttpMethod.DELETE, "/api/admin/exams/" + exam, admin, null).andExpect(status().isOk());
        call(HttpMethod.GET, "/api/admin/exams/" + exam, admin, null).andExpect(status().isNotFound());
    }

    // ---------- পাসওয়ার্ড ----------

    @Test
    void passwordChangeAndAdminReset() throws Exception {
        String admin = login("admin", "admin");
        long cid = classId(admin, "নবম শ্রেণি");
        String roll = "pw" + System.nanoTime();
        Map<String, Object> reg = new HashMap<>();
        reg.put("roll", roll);
        reg.put("name", "পাসওয়ার্ড টেস্ট");
        reg.put("password", "abcd");
        reg.put("classId", cid);
        String res = text(call(HttpMethod.POST, "/api/auth/register", null, reg).andExpect(status().isOk()));
        long id = json(res).path("user").path("id").asLong();
        String tok = json(res).path("token").asText();

        call(HttpMethod.POST, "/api/me/password", tok, Map.of("oldPassword", "wrong", "newPassword", "newpass")).andExpect(status().isBadRequest());
        call(HttpMethod.POST, "/api/me/password", tok, Map.of("oldPassword", "abcd", "newPassword", "ab")).andExpect(status().isBadRequest());
        call(HttpMethod.POST, "/api/me/password", null, Map.of("oldPassword", "abcd", "newPassword", "newpass")).andExpect(status().isUnauthorized());
        call(HttpMethod.POST, "/api/me/password", tok, Map.of("oldPassword", "abcd", "newPassword", "newpass")).andExpect(status().isOk());
        call(HttpMethod.POST, "/api/auth/login", null, Map.of("roll", roll, "password", "abcd")).andExpect(status().isUnauthorized());
        call(HttpMethod.POST, "/api/auth/login", null, Map.of("roll", roll, "password", "newpass")).andExpect(status().isOk());

        // ছাত্র/শিক্ষক অন্যের পাসওয়ার্ড বদলাতে পারে না; অ্যাডমিন পারে
        call(HttpMethod.PUT, "/api/admin/users/" + id + "/password", tok, Map.of("newPassword", "hack1")).andExpect(status().isForbidden());
        call(HttpMethod.PUT, "/api/admin/users/" + id + "/password", login("t1", "1234"), Map.of("newPassword", "hack1")).andExpect(status().isForbidden());
        call(HttpMethod.PUT, "/api/admin/users/" + id + "/password", admin, Map.of("newPassword", "reset99")).andExpect(status().isOk());
        call(HttpMethod.POST, "/api/auth/login", null, Map.of("roll", roll, "password", "reset99")).andExpect(status().isOk());
    }
}
