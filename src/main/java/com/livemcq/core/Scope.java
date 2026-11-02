package com.livemcq.core;

import java.util.Set;

/**
 * কোন কোন শ্রেণির ওপর কারো অধিকার আছে. অ্যাডমিনের: সব; শিক্ষকের: নির্দিষ্ট কয়েকটি.
 * Spring ছাড়া, তাই সহজে টেস্ট করা যায়.
 */
public final class Scope {
    private static final Scope ALL = new Scope(null);
    private final Set<Long> classIds; // null = সব

    private Scope(Set<Long> classIds) {
        this.classIds = classIds;
    }

    public static Scope all() {
        return ALL;
    }

    public static Scope of(Set<Long> classIds) {
        return new Scope(Set.copyOf(classIds));
    }

    public boolean isAll() {
        return classIds == null;
    }

    public boolean allows(Long classId) {
        if (classIds == null) return true;
        return classId != null && classIds.contains(classId);
    }
}
