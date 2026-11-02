package com.livemcq.service;

import com.livemcq.core.Scope;
import com.livemcq.domain.TeacherClass;
import com.livemcq.repo.TeacherClassRepo;
import com.livemcq.web.ApiException;
import com.livemcq.web.AuthUser;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Service;

/** কে কোন শ্রেণির ওপর কাজ করতে পারে — সব সিদ্ধান্ত এক জায়গায়. */
@Service
public class AccessService {
    private final TeacherClassRepo teacherClasses;

    public AccessService(TeacherClassRepo teacherClasses) {
        this.teacherClasses = teacherClasses;
    }

    /** অ্যাডমিন: সব. শিক্ষক: ডাটাবেসে নির্ধারিত শ্রেণিগুলো. অন্য কেউ: কিছুই না. */
    public Scope scope(AuthUser me) {
        if (me.isAdmin()) return Scope.all();
        Set<Long> ids = new HashSet<>();
        if (me.isTeacher()) for (TeacherClass t : teacherClasses.findByUserId(me.id())) ids.add(t.classId);
        return Scope.of(ids);
    }

    public void requireClass(AuthUser me, Long classId) {
        if (!scope(me).allows(classId)) throw new ApiException(403, "এই শ্রেণি আপনার দায়িত্বে নেই");
    }

    public void requireAdmin(AuthUser me) {
        if (!me.isAdmin()) throw new ApiException(403, "শুধু অ্যাডমিন এই কাজ করতে পারবেন");
    }
}
