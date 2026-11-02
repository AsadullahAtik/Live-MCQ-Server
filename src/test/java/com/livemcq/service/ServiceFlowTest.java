package com.livemcq.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livemcq.domain.*;
import com.livemcq.live.LiveHub;
import com.livemcq.support.Fakes;
import com.livemcq.web.ApiException;
import com.livemcq.web.AuthUser;
import com.livemcq.web.Dtos.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * সার্ভিস স্তরের আসল কোড, মেমরির নকল repository দিয়ে (Spring context/ডাটাবেস ছাড়া, তাই কয়েক সেকেন্ডে চলে).
 * যা ধরে: shuffle ঘুরে এলে উত্তর ঠিক থাকা, সম্পাদনার নিয়ম, সময়সূচি, sweeper, শিক্ষকের সীমা, শিক্ষক ব্যবস্থাপনা.
 * যা ধরে না: SQL/JPA mapping, HTTP/JSON, WebSocket — সেগুলোর জন্য আসল Spring integration test লাগবে.
 */
class ServiceFlowTest {
    Fakes.Exams exams;
    Fakes.Questions qs;
    Fakes.Attempts atts;
    Fakes.Classes classes;
    Fakes.Users users;
    Fakes.TeacherClasses tcs;
    ExamService examSvc;
    AttemptService attSvc;
    AccessService access;
    ResultService resSvc;
    TeacherService teacherSvc;
    SchoolClass c1, c2;
    AppUser admin, s1, s2, s3, teacher;

    /** লেনদেন ছাড়া সরাসরি চালায় (আসল TransactionTemplate-এর manager লাগে). */
    static class DirectTx extends TransactionTemplate {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }

        @Override
        public void executeWithoutResult(Consumer<TransactionStatus> action) {
            action.accept(null);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        exams = new Fakes.Exams(); qs = new Fakes.Questions(); atts = new Fakes.Attempts();
        classes = new Fakes.Classes(); users = new Fakes.Users(); tcs = new Fakes.TeacherClasses();
        LiveHub hub = new LiveHub(new ObjectMapper());
        examSvc = new ExamService(exams.repo, qs.repo, atts.repo, classes.repo, users.repo, hub);
        attSvc = new AttemptService(atts.repo, exams.repo, qs.repo, users.repo, new DirectTx(), hub);
        var grace = AttemptService.class.getDeclaredField("grace");
        grace.setAccessible(true);
        grace.setLong(attSvc, 3); // @Value ছাড়া চালাচ্ছি, তাই হাতে বসাই
        access = new AccessService(tcs.repo);
        resSvc = new ResultService(examSvc, atts.repo, qs.repo, users.repo, access);
        AuthService auth = new AuthService(users.repo, classes.repo, new JwtService("0123456789012345678901234567890123456789", 12));
        teacherSvc = new TeacherService(users.repo, classes.repo, tcs.repo, auth);

        c1 = cls("নবম"); c2 = cls("দশম");
        admin = user("admin", Role.ADMIN, null);
        s1 = user("s1", Role.STUDENT, c1.id); s2 = user("s2", Role.STUDENT, c1.id); s3 = user("s3", Role.STUDENT, c2.id);
        teacher = user("t1", Role.TEACHER, null);
        TeacherClass link = new TeacherClass();
        link.userId = teacher.id;
        link.classId = c1.id;
        tcs.repo.save(link);
    }

    // ---------- সাহায্যকারী ----------

    SchoolClass cls(String n) {
        SchoolClass c = new SchoolClass();
        c.name = n;
        c.sections = "ক,খ";
        return classes.repo.save(c);
    }

    AppUser user(String roll, Role role, Long classId) {
        AppUser u = new AppUser();
        u.roll = roll; u.name = "N-" + roll; u.passwordHash = "x"; u.role = role; u.classId = classId;
        return users.repo.save(u);
    }

    /** প্রশ্ন i: অপশন "অi, আi, ইi, ঈi"; সঠিক = (i*3)%4 */
    static List<QuestionIn> five() {
        List<QuestionIn> l = new ArrayList<>();
        for (int i = 0; i < 5; i++) l.add(new QuestionIn("প্রশ্ন" + i, List.of("অ" + i, "আ" + i, "ই" + i, "ঈ" + i), (i * 3) % 4));
        return l;
    }

    ExamSummary exam(String title, Long classId, boolean shuffle, Instant from, Instant to) {
        return examSvc.create(new ExamIn(title, classId, 5, 1, 0.25, five(), shuffle, from, to));
    }

    static AuthUser as(AppUser u) {
        return new AuthUser(u.id, u.role.name());
    }

    static int statusOf(org.junit.jupiter.api.function.Executable e) {
        return assertThrows(ApiException.class, e).status;
    }

    /** পর্দার ক্রমে সাজানো প্রশ্ন থেকে "সব সঠিক" উত্তর (পর্দার অপশন-সূচিতে) বের করা — লেখা মিলিয়ে. */
    static int[] allCorrect(StartRes st) {
        int[] best = new int[st.questions().size()];
        for (int p = 0; p < best.length; p++) {
            QuestionOut o = st.questions().get(p);
            int orig = Integer.parseInt(o.text().substring("প্রশ্ন".length()));
            String correctText = new String[]{"অ", "আ", "ই", "ঈ"}[(orig * 3) % 4] + orig;
            best[p] = o.options().indexOf(correctText);
        }
        return best;
    }

    // ---------- পরীক্ষা ----------

    @Test
    void shuffledExamGradesCorrectlyAndResumesSameOrder() {
        ExamSummary ex = exam("শাফল", c1.id, true, null, null);
        StartRes st = attSvc.start(as(s1), ex.id());
        assertEquals(5, st.questions().size());
        assertEquals(5, st.questions().stream().map(QuestionOut::text).distinct().count(), "প্রশ্ন বাদ/দ্বিগুণ হয়নি");

        int[] best = allCorrect(st);
        for (int b : best) assertTrue(b >= 0, "সঠিক অপশন পর্দায় আছে");

        int[] half = {best[0], best[1], best[2], -1, -1};
        attSvc.saveAnswers(as(s1), st.attemptId(), half);
        StartRes again = attSvc.start(as(s1), ex.id());
        assertArrayEquals(half, again.answers(), "চালিয়ে গেলে একই ক্রমে একই উত্তর");
        assertEquals(st.questions().get(0).options(), again.questions().get(0).options(), "অপশনের ক্রম একই");

        attSvc.submit(as(s1), st.attemptId(), best);
        Attempt done = atts.repo.findById(st.attemptId()).orElseThrow();
        assertEquals(AttemptStatus.SUBMITTED, done.status);
        assertEquals(5, done.correct);
        assertEquals(5.0, done.marks);
        assertEquals(409, statusOf(() -> attSvc.start(as(s1), ex.id())));
        assertEquals("আগেই জমা হয়েছে", attSvc.submit(as(s1), st.attemptId(), best).message(), "দ্বিতীয় জমা গণ্য নয়");
    }

    @Test
    void unshuffledExamKeepsOriginalOrder() {
        ExamSummary ex = exam("সাধারণ", c1.id, false, null, null);
        StartRes st = attSvc.start(as(s2), ex.id());
        for (int i = 0; i < 5; i++) {
            assertEquals("প্রশ্ন" + i, st.questions().get(i).text());
            assertEquals("অ" + i, st.questions().get(i).options().get(0));
        }
    }

    @Test
    void editRules() {
        ExamSummary ex = exam("সম্পাদনা", c1.id, false, null, null);
        examSvc.update(ex.id(), new ExamIn("নতুন নাম", c1.id, 7, 2, 0.5, five().subList(0, 3), true, null, null));
        assertEquals("নতুন নাম", exams.repo.findById(ex.id()).orElseThrow().title);
        assertEquals(3, qs.repo.countByExamId(ex.id()));

        attSvc.start(as(s1), ex.id());
        assertEquals(409, statusOf(() -> examSvc.update(ex.id(), new ExamIn("x", c1.id, 5, 1, 0, five(), false, null, null))), "শুরুর পর");

        ExamSummary ex2 = exam("প্রকাশিত", c1.id, false, null, null);
        examSvc.setPublished(ex2.id(), true);
        assertEquals(409, statusOf(() -> examSvc.update(ex2.id(), new ExamIn("y", c1.id, 5, 1, 0, five(), false, null, null))), "প্রকাশের পর");

        assertEquals(400, statusOf(() -> exam("", c1.id, false, null, null)), "নাম ফাঁকা");
        assertEquals(400, statusOf(() -> exam("t", c1.id, false, Instant.now().plusSeconds(100), Instant.now().plusSeconds(50))), "শেষ < শুরু");
    }

    @Test
    void schedule() {
        ExamSummary future = exam("ভবিষ্যৎ", c1.id, false, Instant.now().plusSeconds(3600), null);
        assertEquals(403, statusOf(() -> attSvc.start(as(s2), future.id())));
        assertEquals("upcoming", stateOf(s2, future.id()));

        ExamSummary closed = exam("বন্ধ", c1.id, false, null, Instant.now().minusSeconds(10));
        assertEquals(403, statusOf(() -> attSvc.start(as(s2), closed.id())));
        assertEquals("missed", stateOf(s2, closed.id()));

        ExamSummary tight = exam("সংকীর্ণ", c1.id, false, null, Instant.now().plusSeconds(60));
        StartRes ts = attSvc.start(as(s2), tight.id()); // সময় ৫ মিনিট, কিন্তু জানালা ১ মিনিটে বন্ধ
        assertTrue(ts.deadline().isBefore(Instant.now().plusSeconds(61)), "deadline জানালার শেষে ছাঁটা");
    }

    String stateOf(AppUser student, Long examId) {
        return examSvc.forStudent(as(student)).stream().filter(e -> e.id().equals(examId)).findFirst().orElseThrow().state();
    }

    @Test
    void sweeperAutoSubmitsExpiredAttempts() {
        ExamSummary ex = exam("সময়", c1.id, false, null, null);
        StartRes st = attSvc.start(as(s2), ex.id());
        Attempt a = atts.repo.findById(st.attemptId()).orElseThrow();
        a.deadline = Instant.now().minusSeconds(60);
        atts.repo.save(a);
        attSvc.sweep();
        assertEquals(AttemptStatus.SUBMITTED, atts.repo.findById(st.attemptId()).orElseThrow().status);
    }

    // ---------- অধিকার ----------

    @Test
    void teacherScope() {
        ExamSummary mine = exam("নবম", c1.id, false, null, null);
        ExamSummary other = exam("দশম", c2.id, false, null, null);
        AuthUser t = as(teacher), ad = as(admin);

        access.requireClass(t, c1.id);
        assertEquals(403, statusOf(() -> access.requireClass(t, c2.id)));
        access.requireClass(ad, c2.id);
        assertEquals(403, statusOf(() -> access.requireAdmin(t)));

        StartRes st = attSvc.start(as(s1), mine.id());
        attSvc.submit(as(s1), st.attemptId(), allCorrect(st));

        Board b = resSvc.board(t, mine.id()); // প্রকাশের আগেও শিক্ষক দেখে
        assertEquals(1, b.rows().size());
        assertFalse(b.rows().get(0).me(), "শিক্ষকের জন্য 'আপনি' চিহ্ন নেই");
        assertEquals(403, statusOf(() -> resSvc.board(t, other.id())));
        assertEquals(403, statusOf(() -> resSvc.board(as(s1), mine.id())), "ছাত্র প্রকাশের আগে পায় না");

        examSvc.setPublished(mine.id(), true);
        assertTrue(resSvc.board(as(s1), mine.id()).rows().get(0).me());
        assertEquals(5.0, resSvc.myResult(as(s1), mine.id()).marks());
        assertEquals(5, resSvc.myResult(as(s1), mine.id()).review().size());

        assertEquals(403, statusOf(() -> attSvc.start(as(teacher), other.id())), "শিক্ষক পরীক্ষা দেয় না");
        assertEquals(403, statusOf(() -> attSvc.start(as(s3), mine.id())), "অন্য শ্রেণির ছাত্র");
    }

    @Test
    void studentReport() {
        ExamSummary ex = exam("রিপোর্ট", c1.id, false, null, null);
        StartRes st = attSvc.start(as(s1), ex.id());
        attSvc.submit(as(s1), st.attemptId(), allCorrect(st));
        StudentReport r = examSvc.studentReport(s1.id);
        assertEquals(1, r.taken());
        assertEquals(1, r.totalExams());
        assertEquals(404, statusOf(() -> examSvc.studentReport(teacher.id)));
    }

    // ---------- শিক্ষক ব্যবস্থাপনা ----------

    @Test
    void passwordChangeResetAndRegistrationCode() {
        // JWT ছাড়া শুধু পাসওয়ার্ড/কোডের যুক্তি যাচাই; টোকেন তৈরি আসল Spring টেস্টে যাচাই হয়
        JwtService noJwt = new JwtService("0123456789012345678901234567890123456789", 12) {
            @Override public String create(AppUser u) { return "t"; }
        };
        AuthService auth = new AuthService(users.repo, classes.repo, noJwt);
        // নিবন্ধন কোড
        auth.registrationCode = "SECRET";
        assertEquals(403, statusOf(() -> auth.register(new RegisterReq("n1", "নতুন", "abcd", c1.id, null, null, null))), "কোড নেই");
        assertEquals(403, statusOf(() -> auth.register(new RegisterReq("n1", "নতুন", "abcd", c1.id, null, null, "WRONG"))), "ভুল কোড");
        LoginRes ok = auth.register(new RegisterReq("n1", "নতুন", "abcd", c1.id, null, null, "SECRET"));
        assertEquals("STUDENT", ok.user().role());
        auth.registrationCode = "";
        auth.register(new RegisterReq("n2", "আরেক", "abcd", c1.id, null, null, null)); // কোড বন্ধ থাকলে লাগে না

        // নিজের পাসওয়ার্ড বদল
        long id = ok.user().id();
        assertEquals(400, statusOf(() -> auth.changePassword(id, "wrong", "newpass")), "পুরনো ভুল");
        assertEquals(400, statusOf(() -> auth.changePassword(id, "abcd", "ab")), "নতুনটি ছোট");
        auth.changePassword(id, "abcd", "newpass");
        assertEquals(401, statusOf(() -> auth.login(new LoginReq("n1", "abcd"))), "পুরনো আর চলে না");
        assertEquals("STUDENT", auth.login(new LoginReq("n1", "newpass")).user().role());

        // অ্যাডমিন রিসেট
        auth.resetPassword(id, "reset99");
        assertEquals("STUDENT", auth.login(new LoginReq("n1", "reset99")).user().role());
        assertEquals(404, statusOf(() -> auth.resetPassword(99999L, "reset99")));
        assertEquals(400, statusOf(() -> auth.resetPassword(id, "x")));
    }

    @Test
    void teacherManagement() {
        TeacherDto t2 = teacherSvc.create(new TeacherIn("t2", "দ্বিতীয়", "abcd", List.of(c1.id, c2.id)));
        assertEquals(2, t2.classIds().size());
        assertEquals(409, statusOf(() -> teacherSvc.create(new TeacherIn("t2", "x", "abcd", List.of()))), "একই আইডি");
        assertEquals(400, statusOf(() -> teacherSvc.create(new TeacherIn("t9", "x", "ab", List.of()))), "ছোট পাসওয়ার্ড");
        assertEquals(400, statusOf(() -> teacherSvc.create(new TeacherIn("t9", "x", "abcd", List.of(999L)))), "অজানা শ্রেণি");

        assertEquals(List.of(c2.id), teacherSvc.setClasses(t2.id(), List.of(c2.id)).classIds());
        assertEquals(1, teacherSvc.setClasses(t2.id(), List.of(c2.id)).classIds().size(), "ডুপ্লিকেট নেই");
        AuthUser tu = new AuthUser(t2.id(), "TEACHER");
        assertTrue(access.scope(tu).allows(c2.id));
        assertFalse(access.scope(tu).allows(c1.id));

        teacherSvc.delete(t2.id());
        assertEquals(1, teacherSvc.list().size());
        assertEquals(404, statusOf(() -> teacherSvc.delete(t2.id())));
    }

    @Test
    void deleteExamRemovesQuestionsAndAttempts() {
        ExamSummary ex = exam("মুছুন", c1.id, false, null, null);
        attSvc.start(as(s1), ex.id());
        examSvc.delete(ex.id());
        assertEquals(0, qs.repo.countByExamId(ex.id()));
        assertTrue(atts.repo.findByUserId(s1.id).isEmpty());
    }

    // ---------- সমান্তরাল ----------

    /** একই ছাত্র একসাথে ৫০ বার "শুরু" চাপলে attempt ঠিক একটাই তৈরি হবে. */
    @Test
    void doubleStartCreatesOneAttempt() throws Exception {
        ExamSummary ex = exam("ডাবল", c1.id, true, null, null);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Long>> fs = new ArrayList<>();
        for (int i = 0; i < 50; i++)
            fs.add(pool.submit(() -> { go.await(); return attSvc.start(as(s1), ex.id()).attemptId(); }));
        go.countDown();
        Set<Long> ids = new HashSet<>();
        for (Future<Long> f : fs) ids.add(f.get(20, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(1, ids.size(), "সবাই একই attempt পেল");
        assertEquals(1, atts.repo.countByExamId(ex.id()));
    }

    /** ৪০ জন ছাত্র একসাথে শুরু করে প্রত্যেকে দুবার জমা দিলে: ৪০টি attempt, প্রতিটি একবারই গণ্য, র‍্যাংক ঠিক. */
    @Test
    void manyStudentsStartAndDoubleSubmit() throws Exception {
        ExamSummary ex = exam("ভিড়", c1.id, true, null, null);
        List<AppUser> crowd = new ArrayList<>();
        for (int i = 0; i < 40; i++) crowd.add(user("c" + i, Role.STUDENT, c1.id));
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> fs = new ArrayList<>();
        for (AppUser u : crowd) {
            fs.add(pool.submit(() -> {
                go.await();
                StartRes st = attSvc.start(as(u), ex.id());
                int[] ans = allCorrect(st);
                Future<String> a = CompletableFuture.supplyAsync(() -> attSvc.submit(as(u), st.attemptId(), ans).message());
                Future<String> b = CompletableFuture.supplyAsync(() -> attSvc.submit(as(u), st.attemptId(), ans).message());
                return a.get(20, TimeUnit.SECONDS) + "|" + b.get(20, TimeUnit.SECONDS);
            }));
        }
        go.countDown();
        for (Future<String> f : fs) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        List<Attempt> done = atts.repo.findByExamIdAndStatus(ex.id(), AttemptStatus.SUBMITTED);
        assertEquals(40, done.size(), "৪০টি জমা");
        assertTrue(done.stream().allMatch(a -> a.marks == 5.0 && a.correct == 5), "সবার নম্বর ৫ (shuffle সত্ত্বেও)");
        assertEquals(40, examSvc.ranking(ex.id()).size());
    }
}
