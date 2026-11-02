package com.livemcq.web;

/** টোকেন থেকে পাওয়া বর্তমান ব্যবহারকারী. */
public record AuthUser(long id, String role) {
    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    public boolean isTeacher() {
        return "TEACHER".equals(role);
    }

    /** অ্যাডমিন বা শিক্ষক: /api/admin/** ব্যবহার করতে পারে. */
    public boolean isStaff() {
        return isAdmin() || isTeacher();
    }
}
