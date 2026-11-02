package com.livemcq.service;

import com.livemcq.domain.AppUser;
import com.livemcq.domain.Role;
import com.livemcq.repo.ClassRepo;
import com.livemcq.repo.UserRepo;
import com.livemcq.web.ApiException;
import com.livemcq.web.Dtos.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final UserRepo users;
    private final ClassRepo classes;
    private final JwtService jwt;
    private final BCryptPasswordEncoder enc = new BCryptPasswordEncoder();

    /** খালি = যে কেউ নিবন্ধন করতে পারে; দিলে নিবন্ধনে এই কোড লাগবে (app.registration.code / REGISTRATION_CODE). */
    @Value("${app.registration.code:}")
    String registrationCode = "";

    public AuthService(UserRepo users, ClassRepo classes, JwtService jwt) {
        this.users = users;
        this.classes = classes;
        this.jwt = jwt;
    }

    public String hash(String raw) {
        return enc.encode(raw);
    }

    public LoginRes login(LoginReq r) {
        if (r == null || r.roll() == null || r.password() == null) throw new ApiException(400, "রোল ও পাসওয়ার্ড দিন");
        AppUser u = users.findByRoll(r.roll().trim()).orElse(null);
        if (u == null || !enc.matches(r.password(), u.passwordHash)) throw new ApiException(401, "রোল বা পাসওয়ার্ড ভুল");
        return new LoginRes(jwt.create(u), toDto(u));
    }

    public LoginRes register(RegisterReq r) {
        if (r == null || blank(r.roll()) || blank(r.name()) || blank(r.password()) || r.classId() == null)
            throw new ApiException(400, "নাম, রোল, পাসওয়ার্ড ও শ্রেণি দিন");
        if (r.password().length() < 4) throw new ApiException(400, "পাসওয়ার্ড কমপক্ষে ৪ অক্ষর");
        if (registrationCode != null && !registrationCode.isBlank()
                && !registrationCode.equals(r.code() == null ? "" : r.code().trim()))
            throw new ApiException(403, "নিবন্ধন কোড ভুল বা দেওয়া হয়নি");
        if (!classes.existsById(r.classId())) throw new ApiException(400, "শ্রেণি পাওয়া যায়নি");
        String roll = r.roll().trim();
        if (users.existsByRoll(roll)) throw new ApiException(409, "এই রোল আগে থেকেই আছে");
        AppUser u = new AppUser();
        u.roll = roll;
        u.name = r.name().trim();
        u.passwordHash = enc.encode(r.password());
        u.role = Role.STUDENT;
        u.classId = r.classId();
        u.section = r.section();
        u.phone = r.phone();
        users.save(u);
        return new LoginRes(jwt.create(u), toDto(u));
    }

    /** নিজের পাসওয়ার্ড বদল: পুরনোটি মিলতে হবে. */
    public void changePassword(long userId, String oldPw, String newPw) {
        AppUser u = users.findById(userId).orElseThrow(() -> new ApiException(401, "ব্যবহারকারী পাওয়া যায়নি"));
        if (oldPw == null || !enc.matches(oldPw, u.passwordHash)) throw new ApiException(400, "বর্তমান পাসওয়ার্ড ভুল");
        checkNew(newPw);
        u.passwordHash = enc.encode(newPw);
        users.save(u);
    }

    /** অ্যাডমিন যে কারও পাসওয়ার্ড বদলে দেয় (ছাত্র ভুলে গেলে). */
    public void resetPassword(long targetId, String newPw) {
        AppUser u = users.findById(targetId).orElseThrow(() -> new ApiException(404, "ব্যবহারকারী পাওয়া যায়নি"));
        checkNew(newPw);
        u.passwordHash = enc.encode(newPw);
        users.save(u);
    }

    private static void checkNew(String p) {
        if (p == null || p.length() < 4) throw new ApiException(400, "নতুন পাসওয়ার্ড কমপক্ষে ৪ অক্ষর");
    }

    public UserDto toDto(AppUser u) {
        String cn = u.classId == null ? "" : classes.findById(u.classId).map(c -> c.name).orElse("");
        return new UserDto(u.id, u.role.name(), u.name, u.roll, u.classId, cn, u.section);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
