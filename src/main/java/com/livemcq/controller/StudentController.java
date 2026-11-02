package com.livemcq.controller;

import com.livemcq.domain.AppUser;
import com.livemcq.repo.UserRepo;
import com.livemcq.service.AttemptService;
import com.livemcq.service.AuthService;
import com.livemcq.service.ExamService;
import com.livemcq.service.ResultService;
import com.livemcq.web.ApiException;
import com.livemcq.web.AuthUser;
import com.livemcq.web.Dtos.*;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class StudentController {
    private final ExamService exams;
    private final AttemptService attempts;
    private final ResultService results;
    private final UserRepo users;
    private final AuthService auth;

    public StudentController(ExamService exams, AttemptService attempts, ResultService results, UserRepo users, AuthService auth) {
        this.exams = exams;
        this.attempts = attempts;
        this.results = results;
        this.users = users;
        this.auth = auth;
    }

    @PostMapping("/me/password")
    public java.util.Map<String, Boolean> changePassword(@RequestAttribute("auth") AuthUser me, @RequestBody PasswordIn in) {
        if (in == null) throw new ApiException(400, "পাসওয়ার্ড দিন");
        auth.changePassword(me.id(), in.oldPassword(), in.newPassword());
        return java.util.Map.of("changed", true);
    }

    @GetMapping("/me")
    public UserDto me(@RequestAttribute("auth") AuthUser me) {
        AppUser u = users.findById(me.id()).orElseThrow(() -> new ApiException(401, "ব্যবহারকারী পাওয়া যায়নি"));
        return auth.toDto(u);
    }

    /** নিজের শ্রেণির পরীক্ষা: চলমান/দেওয়া যাবে আগে, পুরনো পরে. */
    @GetMapping("/exams")
    public List<ExamSummary> myExams(@RequestAttribute("auth") AuthUser me) {
        return exams.forStudent(me);
    }

    @PostMapping("/exams/{id}/start")
    public StartRes start(@RequestAttribute("auth") AuthUser me, @PathVariable long id) {
        return attempts.start(me, id);
    }

    @PutMapping("/attempts/{id}/answers")
    public Map<String, Boolean> save(@RequestAttribute("auth") AuthUser me, @PathVariable long id, @RequestBody AnswersIn in) {
        attempts.saveAnswers(me, id, in == null ? null : in.answers());
        return Map.of("saved", true);
    }

    @PostMapping("/attempts/{id}/submit")
    public SubmitRes submit(@RequestAttribute("auth") AuthUser me, @PathVariable long id, @RequestBody(required = false) AnswersIn in) {
        return attempts.submit(me, id, in == null ? null : in.answers());
    }

    @GetMapping("/results/{examId}")
    public ResultRes result(@RequestAttribute("auth") AuthUser me, @PathVariable long examId) {
        return results.myResult(me, examId);
    }

    @GetMapping("/exams/{id}/leaderboard")
    public Board leaderboard(@RequestAttribute("auth") AuthUser me, @PathVariable long id) {
        return results.board(me, id);
    }
}
