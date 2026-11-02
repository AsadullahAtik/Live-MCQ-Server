package com.livemcq.controller;

import com.livemcq.core.Scope;
import com.livemcq.domain.AppUser;
import com.livemcq.domain.Role;
import com.livemcq.domain.SchoolClass;
import com.livemcq.repo.ClassRepo;
import com.livemcq.repo.UserRepo;
import com.livemcq.service.AccessService;
import com.livemcq.service.AuthService;
import com.livemcq.service.ExamService;
import com.livemcq.service.ResultService;
import com.livemcq.service.TeacherService;
import com.livemcq.web.ApiException;
import com.livemcq.web.AuthUser;
import com.livemcq.web.Dtos.*;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** /api/admin/** অ্যাডমিন ও শিক্ষকের (AuthInterceptor); শিক্ষক শুধু নিজের শ্রেণির (AccessService). */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final ExamService exams;
    private final ResultService results;
    private final ClassRepo classes;
    private final UserRepo users;
    private final AuthService auth;
    private final AccessService access;
    private final TeacherService teachers;

    public AdminController(ExamService exams, ResultService results, ClassRepo classes, UserRepo users, AuthService auth,
                           AccessService access, TeacherService teachers) {
        this.access = access;
        this.teachers = teachers;
        this.exams = exams;
        this.results = results;
        this.classes = classes;
        this.users = users;
        this.auth = auth;
    }

    @GetMapping("/classes")
    public List<ClassDto> classes(@RequestAttribute("auth") AuthUser me) {
        Scope scope = access.scope(me);
        return classes.findAll().stream().filter(c -> scope.allows(c.id)).map(c -> new ClassDto(c.id, c.name, c.sections)).toList();
    }

    @PostMapping("/classes")
    public ClassDto addClass(@RequestAttribute("auth") AuthUser me, @RequestBody ClassIn in) {
        access.requireAdmin(me);
        if (in == null || in.name() == null || in.name().isBlank()) throw new ApiException(400, "শ্রেণির নাম দিন");
        SchoolClass c = new SchoolClass();
        c.name = in.name().trim();
        c.sections = in.sections() == null ? "" : in.sections().trim();
        classes.save(c);
        return new ClassDto(c.id, c.name, c.sections);
    }

    /** শ্রেণিতে নতুন শাখা যোগ (sections কমা-আলাদা স্ট্রিং, তাই এখানে জোড়া দেওয়া হয়). */
    @PostMapping("/classes/{id}/sections")
    public ClassDto addSection(@RequestAttribute("auth") AuthUser me, @PathVariable long id, @RequestBody SectionIn in) {
        access.requireAdmin(me);
        if (in == null || in.name() == null || in.name().isBlank()) throw new ApiException(400, "শাখার নাম দিন");
        String name = in.name().trim();
        if (name.contains(",")) throw new ApiException(400, "শাখার নামে কমা থাকতে পারবে না");
        SchoolClass c = classes.findById(id).orElseThrow(() -> new ApiException(404, "শ্রেণি পাওয়া যায়নি"));
        List<String> list = new java.util.ArrayList<>();
        if (c.sections != null && !c.sections.isBlank())
            for (String s : c.sections.split(",")) if (!s.isBlank()) list.add(s.trim());
        if (list.contains(name)) throw new ApiException(400, "এই শাখা আগে থেকেই আছে");
        list.add(name);
        c.sections = String.join(",", list);
        classes.save(c);
        return new ClassDto(c.id, c.name, c.sections);
    }

    @GetMapping("/students")
    public List<UserDto> students(@RequestAttribute("auth") AuthUser me) {
        Scope scope = access.scope(me);
        return users.findByRoleOrderByRoll(Role.STUDENT).stream().filter(u -> scope.allows(u.classId)).map(auth::toDto).toList();
    }

    @GetMapping("/exams")
    public List<ExamSummary> exams(@RequestAttribute("auth") AuthUser me) {
        Scope scope = access.scope(me);
        return exams.adminList().stream().filter(e -> scope.allows(e.classId())).toList();
    }

    @PostMapping("/exams")
    public ExamSummary create(@RequestAttribute("auth") AuthUser me, @RequestBody ExamIn in) {
        if (in != null) access.requireClass(me, in.classId());
        return exams.create(in);
    }

    /** শুধু কেউ শুরু করার আগে (এবং ফলাফল প্রকাশের আগে) সম্পাদনা চলে. */
    @PutMapping("/exams/{id}")
    public ExamSummary update(@RequestAttribute("auth") AuthUser me, @PathVariable long id, @RequestBody ExamIn in) {
        access.requireClass(me, exams.get(id).classId);              // এখনকার শ্রেণি
        if (in != null) access.requireClass(me, in.classId());      // নতুন শ্রেণিতেও অধিকার লাগবে
        return exams.update(id, in);
    }

    @GetMapping("/students/{id}/report")
    public StudentReport studentReport(@RequestAttribute("auth") AuthUser me, @PathVariable long id) {
        StudentReport r = exams.studentReport(id);
        access.requireClass(me, r.student().classId());
        return r;
    }

    /** ছাত্রের শ্রেণি/শাখা বদল. section খালি হলে শাখা ছাড়া. */
    @PutMapping("/students/{id}/enroll")
    public UserDto enroll(@RequestAttribute("auth") AuthUser me, @PathVariable long id, @RequestBody EnrollIn in) {
        access.requireAdmin(me); // ছাত্র সরানো শুধু অ্যাডমিন
        AppUser u = users.findById(id).filter(x -> x.role == Role.STUDENT)
                .orElseThrow(() -> new ApiException(404, "ছাত্র পাওয়া যায়নি"));
        if (in == null || in.classId() == null) throw new ApiException(400, "শ্রেণি বেছে নিন");
        SchoolClass c = classes.findById(in.classId()).orElseThrow(() -> new ApiException(400, "শ্রেণি পাওয়া যায়নি"));
        String sec = in.section() == null ? "" : in.section().trim();
        if (!sec.isEmpty()) {
            boolean ok = false;
            if (c.sections != null) for (String x : c.sections.split(",")) if (x.trim().equals(sec)) ok = true;
            if (!ok) throw new ApiException(400, "এই শ্রেণিতে এই শাখা নেই");
        }
        u.classId = c.id;
        u.section = sec.isEmpty() ? null : sec;
        users.save(u);
        return auth.toDto(u);
    }

    @GetMapping("/exams/{id}")
    public AdminExamDetail detail(@RequestAttribute("auth") AuthUser me, @PathVariable long id) {
        access.requireClass(me, exams.get(id).classId);
        return exams.detail(id);
    }

    @PostMapping("/exams/{id}/publish")
    public Map<String, Boolean> publish(@RequestAttribute("auth") AuthUser me, @PathVariable long id) {
        access.requireClass(me, exams.get(id).classId);
        exams.setPublished(id, true);
        return Map.of("published", true);
    }

    @PostMapping("/exams/{id}/unpublish")
    public Map<String, Boolean> unpublish(@RequestAttribute("auth") AuthUser me, @PathVariable long id) {
        access.requireClass(me, exams.get(id).classId);
        exams.setPublished(id, false);
        return Map.of("published", false);
    }

    @DeleteMapping("/exams/{id}")
    public Map<String, Boolean> delete(@RequestAttribute("auth") AuthUser me, @PathVariable long id) {
        access.requireClass(me, exams.get(id).classId);
        exams.delete(id);
        return Map.of("deleted", true);
    }

    @GetMapping("/exams/{id}/results")
    public Board results(@RequestAttribute("auth") AuthUser me, @PathVariable long id) {
        return results.board(me, id);
    }

    /** অ্যাডমিন যেকোনো ছাত্র/শিক্ষকের পাসওয়ার্ড বদলে দিতে পারে. */
    @PutMapping("/users/{id}/password")
    public Map<String, Boolean> resetPassword(@RequestAttribute("auth") AuthUser me, @PathVariable long id, @RequestBody NewPasswordIn in) {
        access.requireAdmin(me);
        auth.resetPassword(id, in == null ? null : in.newPassword());
        return Map.of("changed", true);
    }

    // ---------- শিক্ষক ব্যবস্থাপনা (শুধু অ্যাডমিন) ----------

    @GetMapping("/teachers")
    public List<TeacherDto> teachers(@RequestAttribute("auth") AuthUser me) {
        access.requireAdmin(me);
        return teachers.list();
    }

    @PostMapping("/teachers")
    public TeacherDto addTeacher(@RequestAttribute("auth") AuthUser me, @RequestBody TeacherIn in) {
        access.requireAdmin(me);
        return teachers.create(in);
    }

    @PutMapping("/teachers/{id}/classes")
    public TeacherDto teacherClasses(@RequestAttribute("auth") AuthUser me, @PathVariable long id, @RequestBody ClassIdsIn in) {
        access.requireAdmin(me);
        return teachers.setClasses(id, in == null ? null : in.classIds());
    }

    @DeleteMapping("/teachers/{id}")
    public Map<String, Boolean> deleteTeacher(@RequestAttribute("auth") AuthUser me, @PathVariable long id) {
        access.requireAdmin(me);
        teachers.delete(id);
        return Map.of("deleted", true);
    }
}
