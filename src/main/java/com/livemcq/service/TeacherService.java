package com.livemcq.service;

import com.livemcq.domain.AppUser;
import com.livemcq.domain.Role;
import com.livemcq.domain.TeacherClass;
import com.livemcq.repo.ClassRepo;
import com.livemcq.repo.TeacherClassRepo;
import com.livemcq.repo.UserRepo;
import com.livemcq.web.ApiException;
import com.livemcq.web.Dtos.TeacherDto;
import com.livemcq.web.Dtos.TeacherIn;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** শিক্ষক অ্যাকাউন্ট তৈরি/বদল/মোছা এবং কোন শ্রেণির দায়িত্বে তা ঠিক করা (শুধু অ্যাডমিন). */
@Service
public class TeacherService {
    private final UserRepo users;
    private final ClassRepo classes;
    private final TeacherClassRepo links;
    private final AuthService auth;

    public TeacherService(UserRepo users, ClassRepo classes, TeacherClassRepo links, AuthService auth) {
        this.users = users;
        this.classes = classes;
        this.links = links;
        this.auth = auth;
    }

    public List<TeacherDto> list() {
        List<TeacherDto> out = new ArrayList<>();
        for (AppUser u : users.findByRoleOrderByRoll(Role.TEACHER)) out.add(dto(u));
        return out;
    }

    @Transactional
    public TeacherDto create(TeacherIn in) {
        if (in == null || blank(in.roll()) || blank(in.name()) || blank(in.password()))
            throw new ApiException(400, "নাম, ইউজার আইডি (রোল) ও পাসওয়ার্ড দিন");
        if (in.password().length() < 4) throw new ApiException(400, "পাসওয়ার্ড কমপক্ষে ৪ অক্ষর");
        String roll = in.roll().trim();
        if (users.existsByRoll(roll)) throw new ApiException(409, "এই আইডি আগে থেকেই আছে");
        Set<Long> ids = validClasses(in.classIds());
        AppUser u = new AppUser();
        u.roll = roll;
        u.name = in.name().trim();
        u.passwordHash = auth.hash(in.password());
        u.role = Role.TEACHER;
        users.save(u);
        link(u.id, ids);
        return dto(u);
    }

    @Transactional
    public TeacherDto setClasses(long id, List<Long> classIds) {
        AppUser u = teacher(id);
        Set<Long> ids = validClasses(classIds);
        links.deleteByUserId(id);
        links.flush(); // পুরনো সারি আগে মুছুক, নইলে একই জোড়া আবার বসালে unique ভাঙে (Hibernate insert আগে চালায়)
        link(id, ids);
        return dto(u);
    }

    @Transactional
    public void delete(long id) {
        teacher(id);
        links.deleteByUserId(id);
        users.deleteById(id);
    }

    private AppUser teacher(long id) {
        return users.findById(id).filter(x -> x.role == Role.TEACHER)
                .orElseThrow(() -> new ApiException(404, "শিক্ষক পাওয়া যায়নি"));
    }

    private Set<Long> validClasses(List<Long> classIds) {
        Set<Long> ids = new LinkedHashSet<>();
        if (classIds != null) ids.addAll(classIds);
        for (Long cid : ids) if (cid == null || !classes.existsById(cid)) throw new ApiException(400, "শ্রেণি পাওয়া যায়নি");
        return ids;
    }

    private void link(Long userId, Set<Long> ids) {
        for (Long cid : ids) {
            TeacherClass t = new TeacherClass();
            t.userId = userId;
            t.classId = cid;
            links.save(t);
        }
    }

    private TeacherDto dto(AppUser u) {
        List<Long> ids = new ArrayList<>();
        for (TeacherClass t : links.findByUserId(u.id)) ids.add(t.classId);
        return new TeacherDto(u.id, u.roll, u.name, ids);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
