package com.livemcq.service;

import com.livemcq.core.Answers;
import com.livemcq.core.Grader;
import com.livemcq.core.KeyedLock;
import com.livemcq.core.Shuffler;
import com.livemcq.domain.*;
import com.livemcq.live.LiveHub;
import com.livemcq.repo.*;
import com.livemcq.web.ApiException;
import com.livemcq.web.AuthUser;
import com.livemcq.web.Dtos.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * পরীক্ষা দেওয়ার মূল অংশ. একসাথে অনেক ছাত্র (অনেক থ্রেড) এলে নিরাপত্তা:
 *  - KeyedLock: একই ছাত্র+পরীক্ষা / একই attempt এর কাজ একটার পর একটা চলে
 *  - ডাটাবেসের unique (exam_id, user_id): শেষ ভরসা
 *  - সময় সবসময় সার্ভারের ঘড়িতে; সময় শেষ হলে sweeper নিজেই জমা নেয়
 */
@Service
public class AttemptService {
    private static final Logger log = LoggerFactory.getLogger(AttemptService.class);

    private final AttemptRepo attempts;
    private final ExamRepo exams;
    private final QuestionRepo questions;
    private final UserRepo users;
    private final TransactionTemplate tx;
    private final LiveHub hub;
    private final KeyedLock lock = new KeyedLock();

    @Value("${app.submit.grace-seconds:3}")
    private long grace;

    public AttemptService(AttemptRepo attempts, ExamRepo exams, QuestionRepo questions, UserRepo users, TransactionTemplate tx, LiveHub hub) {
        this.hub = hub;
        this.attempts = attempts;
        this.exams = exams;
        this.questions = questions;
        this.users = users;
        this.tx = tx;
    }

    public StartRes start(AuthUser me, long examId) {
        Exam ex = exams.findById(examId).orElseThrow(() -> new ApiException(404, "পরীক্ষা পাওয়া যায়নি"));
        AppUser u = users.findById(me.id()).orElseThrow(() -> new ApiException(401, "ব্যবহারকারী পাওয়া যায়নি"));
        if (me.isAdmin() || !Objects.equals(ex.classId, u.classId)) throw new ApiException(403, "এই পরীক্ষা আপনার শ্রেণির নয়");

        boolean[] created = {false}; // এই কলেই নতুন attempt তৈরি হলো কি না (লাইভ ইভেন্টের জন্য)
        StartRes res = lock.with("start:" + examId + ":" + me.id(), () -> tx.execute(ts -> {
            Instant now = Instant.now();
            List<Question> qs = questions.findByExamIdOrderBySortNo(examId);
            Attempt a = attempts.findByExamIdAndUserId(examId, me.id()).orElse(null);
            if (a == null) {
                if (ex.published) throw new ApiException(403, "এই পরীক্ষা শেষ হয়ে গেছে");
                if (ex.startAt != null && now.isBefore(ex.startAt)) throw new ApiException(403, "পরীক্ষা এখনো শুরু হয়নি");
                if (ex.endAt != null && !now.isBefore(ex.endAt)) throw new ApiException(403, "পরীক্ষা দেওয়ার সময় শেষ হয়ে গেছে");
                a = new Attempt();
                a.examId = examId;
                a.userId = me.id();
                a.startedAt = now;
                Instant deadline = now.plusSeconds(ex.durationMin * 60L);
                // পরীক্ষার জানালা বন্ধ হওয়ার সময় পার করে চলতে দেওয়া হয় না
                if (ex.endAt != null && ex.endAt.isBefore(deadline)) deadline = ex.endAt;
                a.deadline = deadline;
                a.status = AttemptStatus.IN_PROGRESS;
                a.answers = Answers.format(Answers.blanks(qs.size()));
                a.shuffleSeed = Boolean.TRUE.equals(ex.shuffle) ? ThreadLocalRandom.current().nextLong() : null;
                attempts.save(a);
                created[0] = true;
            } else if (a.status == AttemptStatus.SUBMITTED) {
                throw new ApiException(409, "আপনি এই পরীক্ষা ইতিমধ্যে দিয়েছেন");
            } else if (now.isAfter(a.deadline.plusSeconds(grace))) {
                finish(a, ex, qs, a.deadline);
                return null; // সময় শেষ: স্বয়ংক্রিয় জমা হলো (নিচে 409)
            }
            Shuffler.Plan plan = planFor(a, qs.size());
            List<QuestionOut> out = new ArrayList<>();
            for (int p = 0; p < qs.size(); p++) {
                Question q = qs.get(plan.qOrder()[p]);
                List<String> orig = q.options();
                List<String> shown = new ArrayList<>();
                for (int k = 0; k < 4; k++) shown.add(orig.get(plan.optOrder()[p][k]));
                out.add(new QuestionOut(q.id, q.text, shown));
            }
            int[] presented = plan.toPresented(Answers.parse(a.answers, qs.size()));
            return new StartRes(a.id, ex.id, ex.title, a.deadline, now, ex.durationMin, out, presented);
        }));
        if (res == null) {
            notifySubmitted(examId, me.id(), true);
            throw new ApiException(409, "আপনার সময় শেষ হয়ে গেছে, উত্তর স্বয়ংক্রিয়ভাবে জমা হয়েছে");
        }
        if (created[0]) notifyEvent("attempt_started", examId, me.id());
        return res;
    }

    /** পরীক্ষার মাঝে উত্তর সংরক্ষণ (অটোসেভ). নেট কেটে গেলেও সর্বশেষ সংরক্ষিত উত্তর থেকে যায়. */
    public void saveAnswers(AuthUser me, long attemptId, int[] answers) {
        lock.with("att:" + attemptId, () -> {
            tx.executeWithoutResult(ts -> {
                Attempt a = owned(me, attemptId);
                if (a.status != AttemptStatus.IN_PROGRESS || Instant.now().isAfter(a.deadline.plusSeconds(grace)))
                    throw new ApiException(409, "পরীক্ষার সময় শেষ বা জমা হয়ে গেছে");
                int n = (int) questions.countByExamId(a.examId);
                a.answers = Answers.format(planFor(a, n).toCanonical(Answers.sanitize(answers, n)));
                attempts.save(a);
            });
            return null;
        });
    }

    public SubmitRes submit(AuthUser me, long attemptId, int[] answers) {
        boolean[] newlyDone = {false};
        long[] examIdBox = {0};
        SubmitRes res = lock.with("att:" + attemptId, () -> tx.execute(ts -> {
            Attempt a = owned(me, attemptId);
            if (a.status == AttemptStatus.SUBMITTED) return new SubmitRes("SUBMITTED", "আগেই জমা হয়েছে");
            Exam ex = exams.findById(a.examId).orElseThrow(() -> new ApiException(404, "পরীক্ষা পাওয়া যায়নি"));
            List<Question> qs = questions.findByExamIdOrderBySortNo(a.examId);
            Instant now = Instant.now();
            boolean late = now.isAfter(a.deadline.plusSeconds(grace));
            if (!late && answers != null)
                a.answers = Answers.format(planFor(a, qs.size()).toCanonical(Answers.sanitize(answers, qs.size())));
            finish(a, ex, qs, late ? a.deadline : now);
            newlyDone[0] = true;
            examIdBox[0] = a.examId;
            return new SubmitRes("SUBMITTED", late ? "সময় শেষ, সংরক্ষিত উত্তর জমা হয়েছে" : "উত্তর জমা হয়েছে");
        }));
        if (newlyDone[0]) notifySubmitted(examIdBox[0], me.id(), false);
        return res;
    }

    /** সময় শেষ হওয়া কিন্তু জমা না হওয়া attempt গুলো স্বয়ংক্রিয়ভাবে জমা করে. */
    @Scheduled(fixedDelayString = "${app.sweeper.interval-ms:5000}")
    public void sweep() {
        Instant cut = Instant.now().minusSeconds(grace);
        for (Attempt x : attempts.findByStatusAndDeadlineBefore(AttemptStatus.IN_PROGRESS, cut)) {
            try {
                Boolean finished = lock.with("att:" + x.id, () -> tx.execute(ts -> {
                    Attempt a = attempts.findById(x.id).orElse(null);
                    if (a == null || a.status != AttemptStatus.IN_PROGRESS) return null;
                    Exam ex = exams.findById(a.examId).orElse(null);
                    if (ex == null) return null;
                    finish(a, ex, questions.findByExamIdOrderBySortNo(a.examId), a.deadline);
                    log.info("স্বয়ংক্রিয় জমা: attempt={} exam={} user={}", a.id, a.examId, a.userId);
                    return Boolean.TRUE;
                }));
                if (finished != null) notifySubmitted(x.examId, x.userId, true);
            } catch (RuntimeException e) {
                log.warn("sweep ব্যর্থ attempt={}: {}", x.id, e.toString());
            }
        }
    }

    /** attempt এর seed থেকে ক্রম বের করা; seed না থাকলে এলোমেলো নয়. */
    private static Shuffler.Plan planFor(Attempt a, int n) {
        return Shuffler.plan(n, a.shuffleSeed);
    }

    private void notifySubmitted(long examId, long userId, boolean auto) {
        notifyEvent(auto ? "attempt_auto_submitted" : "attempt_submitted", examId, userId);
    }

    /** স্টাফকে (অ্যাডমিন + ওই শ্রেণির শিক্ষক) জানায় কে কী করল. ছাত্রদের নাম অন্য ছাত্রকে পাঠানো হয় না. */
    private void notifyEvent(String type, long examId, long userId) {
        Exam exam = exams.findById(examId).orElse(null);
        if (exam == null) return;
        AppUser u = users.findById(userId).orElse(null);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", type);
        ev.put("examId", examId);
        ev.put("name", u == null ? "?" : u.name);
        ev.put("roll", u == null ? "" : u.roll);
        hub.toStaff(exam.classId, ev);
    }

    private Attempt owned(AuthUser me, long attemptId) {
        Attempt a = attempts.findById(attemptId).orElseThrow(() -> new ApiException(404, "চেষ্টা পাওয়া যায়নি"));
        if (a.userId != me.id()) throw new ApiException(403, "এটি আপনার পরীক্ষা নয়");
        return a;
    }

    private void finish(Attempt a, Exam ex, List<Question> qs, Instant at) {
        int[] key = new int[qs.size()];
        for (int i = 0; i < key.length; i++) key[i] = qs.get(i).correct;
        Grader.Result r = Grader.grade(key, Answers.parse(a.answers, key.length), ex.perQ, ex.neg);
        a.correct = r.correct();
        a.wrong = r.wrong();
        a.blank = r.blank();
        a.marks = r.marks();
        a.status = AttemptStatus.SUBMITTED;
        a.submittedAt = at;
        long secs = Duration.between(a.startedAt, at).getSeconds();
        a.timeSec = (int) Math.max(0, Math.min(ex.durationMin * 60L, secs));
        attempts.save(a);
    }
}
