package com.livemcq.service;

import com.livemcq.core.Answers;
import com.livemcq.core.Grader;
import com.livemcq.core.Ranker;
import com.livemcq.domain.*;
import com.livemcq.live.LiveHub;
import com.livemcq.repo.*;
import com.livemcq.web.ApiException;
import com.livemcq.web.AuthUser;
import com.livemcq.web.Dtos.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExamService {
    private final ExamRepo exams;
    private final QuestionRepo questions;
    private final AttemptRepo attempts;
    private final ClassRepo classes;
    private final UserRepo users;
    private final LiveHub hub;

    public ExamService(ExamRepo exams, QuestionRepo questions, AttemptRepo attempts, ClassRepo classes, UserRepo users, LiveHub hub) {
        this.hub = hub;
        this.exams = exams;
        this.questions = questions;
        this.attempts = attempts;
        this.classes = classes;
        this.users = users;
    }

    // ---------- অ্যাডমিন ----------

    @Transactional
    public ExamSummary create(ExamIn in) {
        validate(in);
        Exam e = new Exam();
        e.published = false;
        e.createdAt = Instant.now();
        fill(e, in);
        exams.save(e);
        saveQuestions(e.id, in);
        announce("exam_created", e);
        return summary(e);
    }

    /**
     * পরীক্ষা সম্পাদনা. কেউ একবার শুরু করে ফেললে (বা ফলাফল প্রকাশ হলে) প্রশ্ন বদলানো যাবে না,
     * কারণ তখন ছাত্রদের জমা উত্তর পুরনো প্রশ্নের সাথে মেলানো.
     * (সীমাবদ্ধতা: চেক ও আপডেটের মাঝের কয়েক মিলিসেকেন্ডে কেউ শুরু করলে ধরা পড়বে না; পরে ডাটাবেস লক লাগাতে হবে.)
     */
    @Transactional
    public ExamSummary update(long id, ExamIn in) {
        Exam e = get(id);
        if (e.published) throw new ApiException(409, "ফলাফল প্রকাশের পর পরীক্ষা বদলানো যায় না");
        if (attempts.countByExamId(id) > 0) throw new ApiException(409, "কেউ পরীক্ষা শুরু করে ফেলেছে, এখন আর বদলানো যাবে না");
        validate(in);
        fill(e, in);
        exams.save(e);
        questions.deleteByExamId(id);
        questions.flush();
        saveQuestions(id, in);
        announce("exam_updated", e);
        return summary(e);
    }

    private void fill(Exam e, ExamIn in) {
        e.title = in.title().trim();
        e.classId = in.classId();
        e.durationMin = in.durationMin();
        e.perQ = in.perQ();
        e.neg = in.neg();
        e.shuffle = Boolean.TRUE.equals(in.shuffle());
        e.startAt = in.startAt();
        e.endAt = in.endAt();
    }

    private void saveQuestions(Long examId, ExamIn in) {
        int pos = 0;
        for (QuestionIn qi : in.questions()) {
            Question q = new Question();
            q.examId = examId;
            q.sortNo = pos++;
            q.text = qi.text().trim();
            q.opt0 = qi.options().get(0).trim();
            q.opt1 = qi.options().get(1).trim();
            q.opt2 = qi.options().get(2).trim();
            q.opt3 = qi.options().get(3).trim();
            q.correct = qi.correct();
            questions.save(q);
        }
    }

    /** শ্রেণির ছাত্র ও অ্যাডমিনদের জানায় যে কিছু বদলেছে (commit হওয়ার পর). */
    private void announce(String type, Exam e) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", type);
        ev.put("examId", e.id);
        ev.put("title", e.title);
        Long classId = e.classId;
        LiveHub.afterCommit(() -> hub.toClassAndStaff(classId, ev));
    }

    private void validate(ExamIn in) {
        if (in == null || in.title() == null || in.title().isBlank()) throw new ApiException(400, "পরীক্ষার নাম দিন");
        if (in.classId() == null || !classes.existsById(in.classId())) throw new ApiException(400, "শ্রেণি পাওয়া যায়নি");
        if (in.durationMin() < 1 || in.durationMin() > 300) throw new ApiException(400, "সময় ১ থেকে ৩০০ মিনিটের মধ্যে হতে হবে");
        if (in.perQ() <= 0 || in.neg() < 0) throw new ApiException(400, "নম্বরের মান সঠিক নয়");
        if (in.startAt() != null && in.endAt() != null && !in.endAt().isAfter(in.startAt()))
            throw new ApiException(400, "শেষের সময় শুরুর সময়ের পরে হতে হবে");
        if (in.questions() == null || in.questions().isEmpty() || in.questions().size() > 200)
            throw new ApiException(400, "১ থেকে ২০০টি প্রশ্ন দিন");
        int i = 1;
        for (QuestionIn q : in.questions()) {
            if (q.text() == null || q.text().isBlank()) throw new ApiException(400, "প্রশ্ন " + i + ": প্রশ্নের লেখা নেই");
            if (q.options() == null || q.options().size() != 4 || q.options().stream().anyMatch(o -> o == null || o.isBlank()))
                throw new ApiException(400, "প্রশ্ন " + i + ": ৪টি অপশনই দিন");
            if (q.correct() < 0 || q.correct() > 3) throw new ApiException(400, "প্রশ্ন " + i + ": সঠিক উত্তর বেছে নিন");
            i++;
        }
    }

    public List<ExamSummary> adminList() {
        List<ExamSummary> out = new ArrayList<>();
        for (Exam e : exams.findAllByOrderByIdDesc()) out.add(summary(e));
        return out;
    }

    @Transactional
    public void setPublished(long id, boolean published) {
        Exam e = get(id);
        e.published = published;
        exams.save(e);
        announce(published ? "published" : "unpublished", e);
    }

    @Transactional
    public void delete(long id) {
        Exam e = get(id);
        attempts.deleteByExamId(id);
        questions.deleteByExamId(id);
        exams.deleteById(id);
        announce("exam_deleted", e);
    }

    public AdminExamDetail detail(long id) {
        Exam e = get(id);
        List<Question> qs = questions.findByExamIdOrderBySortNo(id);
        List<QuestionFull> full = new ArrayList<>();
        for (Question q : qs) full.add(new QuestionFull(q.id, q.text, q.options(), q.correct));
        return new AdminExamDetail(summary(e), full, stats(e, qs));
    }

    private ExamStats stats(Exam e, List<Question> qs) {
        List<Attempt> list = attempts.findByExamIdAndStatus(e.id, AttemptStatus.SUBMITTED);
        int n = list.size();
        int[] c = new int[qs.size()], w = new int[qs.size()], b = new int[qs.size()];
        double sum = 0, max = 0, min = n == 0 ? 0 : Double.MAX_VALUE;
        long time = 0;
        double passMark = Grader.round2(qs.size() * e.perQ * 0.4);
        int pass = 0;
        for (Attempt a : list) {
            sum += a.marks;
            max = Math.max(max, a.marks);
            min = Math.min(min, a.marks);
            time += a.timeSec;
            if (a.marks >= passMark) pass++;
            int[] ans = Answers.parse(a.answers, qs.size());
            for (int i = 0; i < qs.size(); i++) {
                if (ans[i] < 0) b[i]++;
                else if (ans[i] == qs.get(i).correct) c[i]++;
                else w[i]++;
            }
        }
        List<QuestionStat> per = new ArrayList<>();
        for (int i = 0; i < qs.size(); i++) per.add(new QuestionStat(c[i], w[i], b[i]));
        if (n == 0) return new ExamStats(0, 0, 0, 0, 0, passMark, 0, per);
        return new ExamStats(n, Grader.round2(sum / n), max, min, (int) Math.round(pass * 100.0 / n), passMark, (int) (time / n), per);
    }

    // ---------- ছাত্র ----------

    public List<ExamSummary> forStudent(AuthUser me) {
        AppUser u = users.findById(me.id()).orElseThrow(() -> new ApiException(401, "ব্যবহারকারী পাওয়া যায়নি"));
        Map<Long, Attempt> mine = new HashMap<>();
        for (Attempt a : attempts.findByUserId(u.id)) mine.put(a.examId, a);
        List<ExamSummary> current = new ArrayList<>(), old = new ArrayList<>();
        for (Exam e : exams.findByClassIdOrderByIdDesc(u.classId == null ? -1L : u.classId)) {
            Attempt a = mine.get(e.id);
            String state;
            Double marks = null;
            Integer rank = null, part = null;
            Instant now = Instant.now();
            if (a == null) {
                if (e.published || (e.endAt != null && !now.isBefore(e.endAt))) state = "missed";
                else if (e.startAt != null && now.isBefore(e.startAt)) state = "upcoming";
                else state = "open";
            }
            else if (a.status == AttemptStatus.IN_PROGRESS) state = "inprogress";
            else if (!e.published) state = "pending";
            else {
                state = "published";
                List<Ranker.Ranked> rk = ranking(e.id);
                part = rk.size();
                marks = a.marks;
                for (Ranker.Ranked r : rk) if (r.entry().id().equals(String.valueOf(u.id))) rank = r.rank();
            }
            ExamSummary s = base(e, state, marks, rank, part);
            if (state.equals("open") || state.equals("inprogress") || state.equals("upcoming")) current.add(s);
            else old.add(s);
        }
        current.addAll(old);
        return current;
    }

    /** সাবমিট হওয়া সব attempt এর র‍্যাংক. Entry.id = userId. */
    public List<Ranker.Ranked> ranking(long examId) {
        List<Ranker.Entry> entries = new ArrayList<>();
        for (Attempt a : attempts.findByExamIdAndStatus(examId, AttemptStatus.SUBMITTED))
            entries.add(new Ranker.Entry(String.valueOf(a.userId), a.marks, a.timeSec));
        return Ranker.rank(entries);
    }

    /** অ্যাডমিনের জন্য একজন ছাত্রের সব পরীক্ষার সারাংশ (প্রকাশ হোক বা না হোক). */
    public StudentReport studentReport(long userId) {
        AppUser u = users.findById(userId).filter(x -> x.role == Role.STUDENT)
                .orElseThrow(() -> new ApiException(404, "ছাত্র পাওয়া যায়নি"));
        Map<Long, Attempt> mine = new HashMap<>();
        for (Attempt a : attempts.findByUserId(userId)) mine.put(a.examId, a);
        List<StudentExamRow> rows = new ArrayList<>();
        int taken = 0;
        if (u.classId != null) {
            for (Exam e : exams.findByClassIdOrderByIdDesc(u.classId)) {
                Attempt a = mine.get(e.id);
                boolean done = a != null && a.status == AttemptStatus.SUBMITTED;
                double total = Grader.round2(questions.countByExamId(e.id) * e.perQ);
                Integer rank = null, part = null;
                if (done) {
                    taken++;
                    List<Ranker.Ranked> rk = ranking(e.id);
                    part = rk.size();
                    for (Ranker.Ranked r : rk) if (r.entry().id().equals(String.valueOf(userId))) rank = r.rank();
                }
                rows.add(new StudentExamRow(e.id, e.title, e.published, a != null, done,
                        done ? a.marks : null, total, rank, part,
                        done ? a.correct : null, done ? a.wrong : null, done ? a.blank : null, done ? a.timeSec : null));
            }
        }
        String cn = u.classId == null ? "" : classes.findById(u.classId).map(c -> c.name).orElse("");
        UserDto dto = new UserDto(u.id, u.role.name(), u.name, u.roll, u.classId, cn, u.section);
        return new StudentReport(dto, rows, taken, rows.size());
    }

    // ---------- সাহায্যকারী ----------

    public Exam get(long id) {
        return exams.findById(id).orElseThrow(() -> new ApiException(404, "পরীক্ষা পাওয়া যায়নি"));
    }

    public ExamSummary summary(Exam e) {
        return base(e, null, null, null, null);
    }

    private ExamSummary base(Exam e, String state, Double marks, Integer rank, Integer part) {
        int qc = (int) questions.countByExamId(e.id);
        String cn = classes.findById(e.classId).map(c -> c.name).orElse("");
        return new ExamSummary(e.id, e.title, e.classId, cn, e.durationMin, e.perQ, e.neg, qc, Grader.round2(qc * e.perQ),
                e.published, attempts.countByExamId(e.id), state, marks, rank, part,
                Boolean.TRUE.equals(e.shuffle), e.startAt, e.endAt);
    }
}
