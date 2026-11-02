package com.livemcq.web;

import java.time.Instant;
import java.util.List;

public final class Dtos {
    private Dtos() {}

    public record LoginReq(String roll, String password) {}
    public record RegisterReq(String roll, String name, String password, Long classId, String section, String phone, String code) {}
    public record PasswordIn(String oldPassword, String newPassword) {}
    public record NewPasswordIn(String newPassword) {}
    public record UserDto(Long id, String role, String name, String roll, Long classId, String className, String section) {}
    public record LoginRes(String token, UserDto user) {}
    public record ClassIn(String name, String sections) {}
    public record SectionIn(String name) {}
    public record ClassDto(Long id, String name, String sections) {}

    public record QuestionIn(String text, List<String> options, int correct) {}
    public record ExamIn(String title, Long classId, int durationMin, double perQ, double neg, List<QuestionIn> questions,
                         Boolean shuffle, Instant startAt, Instant endAt) {}
    public record QuestionOut(Long id, String text, List<String> options) {}
    public record QuestionFull(Long id, String text, List<String> options, int correct) {}

    /** state: open | inprogress | pending | published | missed (ছাত্রের জন্য). */
    public record ExamSummary(Long id, String title, Long classId, String className, int durationMin, double perQ, double neg,
                              int qCount, double total, boolean published, long attempts,
                              String state, Double marks, Integer rank, Integer participants,
                              Boolean shuffle, Instant startAt, Instant endAt) {}

    public record QuestionStat(int correct, int wrong, int blank) {}
    public record ExamStats(int n, double avg, double max, double min, int passPercent, double passMark, int avgTimeSec, List<QuestionStat> questions) {}
    public record AdminExamDetail(ExamSummary exam, List<QuestionFull> questions, ExamStats stats) {}

    public record StartRes(Long attemptId, Long examId, String title, Instant deadline, Instant serverNow, int durationMin,
                           List<QuestionOut> questions, int[] answers) {}
    public record AnswersIn(int[] answers) {}
    public record SubmitRes(String status, String message) {}

    public record ReviewItem(String text, List<String> options, int correct, int mine) {}
    public record ResultRes(Long examId, String title, double marks, double total, int correct, int wrong, int blank,
                            int timeSec, int rank, int participants, List<ReviewItem> review) {}
    public record BoardRow(int rank, String name, String roll, double marks, int correct, int wrong, int blank, int timeSec, boolean me) {}
    public record TeacherIn(String roll, String name, String password, List<Long> classIds) {}
    public record ClassIdsIn(List<Long> classIds) {}
    public record TeacherDto(Long id, String roll, String name, List<Long> classIds) {}
    public record EnrollIn(Long classId, String section) {}
    public record StudentExamRow(Long examId, String title, boolean published, boolean started, boolean submitted,
                                 Double marks, double total, Integer rank, Integer participants,
                                 Integer correct, Integer wrong, Integer blank, Integer timeSec) {}
    public record StudentReport(UserDto student, List<StudentExamRow> exams, int taken, int totalExams) {}
    public record Board(ExamSummary exam, List<BoardRow> rows) {}
}
