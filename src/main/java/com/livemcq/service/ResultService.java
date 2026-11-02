package com.livemcq.service;

import com.livemcq.core.Answers;
import com.livemcq.core.Grader;
import com.livemcq.core.Ranker;
import com.livemcq.domain.*;
import com.livemcq.repo.*;
import com.livemcq.web.ApiException;
import com.livemcq.web.AuthUser;
import com.livemcq.web.Dtos.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ResultService {
    private final ExamService examService;
    private final AttemptRepo attempts;
    private final QuestionRepo questions;
    private final UserRepo users;
    private final AccessService access;

    public ResultService(ExamService examService, AttemptRepo attempts, QuestionRepo questions, UserRepo users, AccessService access) {
        this.access = access;
        this.examService = examService;
        this.attempts = attempts;
        this.questions = questions;
        this.users = users;
    }

    /** ছাত্রের নিজের বিস্তারিত ফলাফল (প্রকাশের পর). */
    public ResultRes myResult(AuthUser me, long examId) {
        Exam ex = examService.get(examId);
        AppUser u = users.findById(me.id()).orElseThrow(() -> new ApiException(401, "ব্যবহারকারী পাওয়া যায়নি"));
        if (!Objects.equals(ex.classId, u.classId)) throw new ApiException(403, "এই পরীক্ষা আপনার শ্রেণির নয়");
        Attempt a = attempts.findByExamIdAndUserId(examId, me.id()).orElseThrow(() -> new ApiException(404, "আপনি এই পরীক্ষায় অংশ নেননি"));
        if (a.status != AttemptStatus.SUBMITTED) throw new ApiException(409, "পরীক্ষা এখনো জমা হয়নি");
        if (!ex.published) throw new ApiException(403, "ফলাফল এখনো প্রকাশিত হয়নি");
        List<Question> qs = questions.findByExamIdOrderBySortNo(examId);
        int[] mine = Answers.parse(a.answers, qs.size());
        List<ReviewItem> review = new ArrayList<>();
        for (int i = 0; i < qs.size(); i++) review.add(new ReviewItem(qs.get(i).text, qs.get(i).options(), qs.get(i).correct, mine[i]));
        List<Ranker.Ranked> rk = examService.ranking(examId);
        int rank = 0;
        for (Ranker.Ranked r : rk) if (r.entry().id().equals(String.valueOf(me.id()))) rank = r.rank();
        return new ResultRes(examId, ex.title, a.marks, Grader.round2(qs.size() * ex.perQ), a.correct, a.wrong, a.blank, a.timeSec, rank, rk.size(), review);
    }

    /** লিডারবোর্ড / অ্যাডমিনের ফলাফল তালিকা. ছাত্র শুধু নিজের শ্রেণির প্রকাশিত পরীক্ষা দেখে. */
    public Board board(AuthUser me, long examId) {
        Exam ex = examService.get(examId);
        if (me.isStaff()) {
            // অ্যাডমিন সব; শিক্ষক শুধু নিজের শ্রেণির (প্রকাশের আগেও দেখতে পারে)
            access.requireClass(me, ex.classId);
        } else {
            AppUser u = users.findById(me.id()).orElseThrow(() -> new ApiException(401, "ব্যবহারকারী পাওয়া যায়নি"));
            if (!Objects.equals(ex.classId, u.classId)) throw new ApiException(403, "এই লিডারবোর্ড আপনার শ্রেণির নয়");
            if (!ex.published) throw new ApiException(403, "ফলাফল প্রকাশের পর লিডারবোর্ড দেখা যাবে");
        }
        List<Ranker.Ranked> rk = examService.ranking(examId);
        Map<Long, Attempt> byUser = new HashMap<>();
        for (Attempt a : attempts.findByExamIdAndStatus(examId, AttemptStatus.SUBMITTED)) byUser.put(a.userId, a);
        List<Long> ids = new ArrayList<>(byUser.keySet());
        Map<Long, AppUser> names = new HashMap<>();
        for (AppUser u : users.findAllById(ids)) names.put(u.id, u);
        List<BoardRow> rows = new ArrayList<>();
        for (Ranker.Ranked r : rk) {
            long uid = Long.parseLong(r.entry().id());
            Attempt a = byUser.get(uid);
            AppUser u = names.get(uid);
            rows.add(new BoardRow(r.rank(), u == null ? "?" : u.name, u == null ? "" : u.roll, a.marks, a.correct, a.wrong, a.blank, a.timeSec,
                    !me.isStaff() && uid == me.id()));
        }
        return new Board(examService.summary(ex), rows);
    }
}
