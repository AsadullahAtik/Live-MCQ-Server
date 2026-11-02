package com.livemcq;

import com.livemcq.domain.AppUser;
import com.livemcq.domain.Role;
import com.livemcq.domain.SchoolClass;
import com.livemcq.domain.TeacherClass;
import com.livemcq.repo.ClassRepo;
import com.livemcq.repo.TeacherClassRepo;
import com.livemcq.repo.UserRepo;
import com.livemcq.service.AuthService;
import com.livemcq.service.ExamService;
import com.livemcq.web.Dtos.ExamIn;
import com.livemcq.web.Dtos.QuestionIn;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/** ফাঁকা ডাটাবেসে ডেমো ডেটা: admin/admin, ছাত্র 250112/1234 ইত্যাদি, দুটি পরীক্ষা. */
@Component
public class DataSeeder implements CommandLineRunner {
    private final UserRepo users;
    private final ClassRepo classes;
    private final AuthService auth;
    private final ExamService exams;
    private final TeacherClassRepo teacherClasses;

    /** false হলে ডেমো ছাত্র/শিক্ষক/পরীক্ষা বসে না; শুধু একজন admin (পাসওয়ার্ড ADMIN_PASSWORD). */
    @Value("${app.seed.demo:true}")
    boolean seedDemo = true;
    @Value("${app.admin.password:}")
    String adminPassword = "";

    public DataSeeder(UserRepo users, ClassRepo classes, AuthService auth, ExamService exams, TeacherClassRepo teacherClasses) {
        this.teacherClasses = teacherClasses;
        this.users = users;
        this.classes = classes;
        this.auth = auth;
        this.exams = exams;
    }

    @Override
    public void run(String... args) {
        if (users.count() > 0) {
            if (seedDemo) ensureDemoTeacher(); // পুরনো ডাটাবেসেও ডেমো শিক্ষক থাকুক
            return;
        }
        if (!seedDemo) {
            String pw = adminPassword == null ? "" : adminPassword;
            if (pw.length() < 6) throw new IllegalStateException("app.seed.demo=false হলে ADMIN_PASSWORD (কমপক্ষে ৬ অক্ষর) দিতে হবে");
            user("admin", "অ্যাডমিন", pw, Role.ADMIN, null, null);
            return;
        }
        SchoolClass c9 = cls("নবম শ্রেণি", "ক,খ");
        SchoolClass c10 = cls("দশম শ্রেণি", "ক,খ");

        user("admin", "অ্যাডমিন", "admin", Role.ADMIN, null, null);
        user("250112", "আসাদুল্লাহ আতিক", "1234", Role.STUDENT, c9.id, "ক");
        String[][] others = {{"101", "রাফি আহমেদ"}, {"102", "তানজিলা ইসলাম"}, {"103", "মেহেদী হাসান"}, {"104", "ফারিয়া তাবাসসুম"}, {"105", "আরিফ হোসেন"}};
        for (String[] o : others) user(o[0], o[1], "1234", Role.STUDENT, c9.id, "ক");
        user("201", "সাদিয়া রহমান", "1234", Role.STUDENT, c10.id, "ক");
        ensureDemoTeacher();

        exams.create(new ExamIn("সাধারণ বিজ্ঞান – মডেল টেস্ট ১", c9.id, 5, 1, 0.25, List.of(
                q("পানির রাসায়নিক সংকেত কোনটি?", 1, "CO₂", "H₂O", "O₂", "NaCl"),
                q("সূর্যের সবচেয়ে কাছের গ্রহ কোনটি?", 0, "বুধ", "শুক্র", "পৃথিবী", "মঙ্গল"),
                q("আলোর গতি প্রায় কত?", 2, "৩ লক্ষ মি/সে", "৩ হাজার কি.মি./সে", "৩ লক্ষ কি.মি./সে", "৩০ হাজার কি.মি./সে"),
                q("উদ্ভিদ খাদ্য তৈরি করে কোন প্রক্রিয়ায়?", 3, "শ্বসন", "পরিপাক", "বাষ্পমোচন", "সালোকসংশ্লেষণ"),
                q("মানবদেহে রক্ত পরিবহন করে কোনটি?", 0, "হৃৎপিণ্ড ও রক্তনালি", "ফুসফুস", "যকৃত", "কিডনি")), true, null, null));
        exams.create(new ExamIn("ICT – প্রাথমিক ধারণা", c9.id, 5, 1, 0.25, List.of(
                q("কোনটি ইন্টারনেট ব্রাউজার?", 0, "ক্রোম", "এক্সেল", "ওয়ার্ড", "পেইন্ট"),
                q("কোনটি ইনপুট ডিভাইস?", 2, "প্রিন্টার", "স্পিকার", "কিবোর্ড", "মনিটর"),
                q("১ বাইট সমান কত বিট?", 1, "৪", "৮", "১৬", "২"),
                q("WWW এর পূর্ণরূপ কী?", 0, "World Wide Web", "World Web Window", "Wide World Web", "Web World Wide"),
                q("কোনটি আউটপুট ডিভাইস?", 3, "মাউস", "স্ক্যানার", "কিবোর্ড", "মনিটর")), true, null, null));
    }

    /** ডেমো শিক্ষক t1 / 1234: শুধু প্রথম শ্রেণির (নবম) দায়িত্বে. */
    private void ensureDemoTeacher() {
        if (users.existsByRoll("t1") || classes.count() == 0) return;
        SchoolClass first = classes.findAll().get(0);
        user("t1", "শিক্ষক রহিম", "1234", Role.TEACHER, null, null);
        TeacherClass t = new TeacherClass();
        t.userId = users.findByRoll("t1").orElseThrow().id;
        t.classId = first.id;
        teacherClasses.save(t);
    }

    private SchoolClass cls(String name, String sections) {
        SchoolClass c = new SchoolClass();
        c.name = name;
        c.sections = sections;
        return classes.save(c);
    }

    private void user(String roll, String name, String pw, Role role, Long classId, String section) {
        AppUser u = new AppUser();
        u.roll = roll;
        u.name = name;
        u.passwordHash = auth.hash(pw);
        u.role = role;
        u.classId = classId;
        u.section = section;
        users.save(u);
    }

    private static QuestionIn q(String text, int correct, String a, String b, String c, String d) {
        return new QuestionIn(text, List.of(a, b, c, d), correct);
    }
}
