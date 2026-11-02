package com.livemcq.support;

import com.livemcq.domain.*;
import com.livemcq.repo.*;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * মেমরির নকল repository (Spring/ডাটাবেস ছাড়া) — সার্ভিসের আসল কোড দ্রুত পরীক্ষা করার জন্য.
 * java.lang.reflect.Proxy দিয়ে: সাধারণ মেথড (save, findById…) এক জায়গায়, derived query আলাদা করে লেখা.
 * (সরাসরি JpaRepository implement করলে ডজনখানেক মেথড লিখতে হতো.)
 */
public final class Fakes {
    private Fakes() {}

    /** একটি entity-র সব সারি: id → বস্তু. */
    public static final class Store<T> {
        /** id অনুযায়ী সাজানো ও থ্রেড-নিরাপদ, যাতে সমান্তরাল পরীক্ষাতেও নকল repo নিজে ভাঙে না. */
        final Map<Long, T> rows = new java.util.concurrent.ConcurrentSkipListMap<>();
        final java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong();

        public List<T> all() {
            return new ArrayList<>(rows.values());
        }

        static Long idOf(Object e) {
            try { Field f = e.getClass().getField("id"); return (Long) f.get(e); } catch (Exception x) { throw new RuntimeException(x); }
        }

        Object save(Object e) {
            Long id = idOf(e);
            if (id == null) {
                id = seq.incrementAndGet();
                try { e.getClass().getField("id").set(e, id); } catch (Exception x) { throw new RuntimeException(x); }
            }
            @SuppressWarnings("unchecked") T t = (T) e;
            rows.put(id, t);
            return e;
        }
    }

    @SuppressWarnings("unchecked")
    private static <R, T> R proxy(Class<R> iface, Store<T> s, Map<String, Function<Object[], Object>> derived) {
        InvocationHandler h = (Object p, Method m, Object[] a) -> {
            switch (m.getName()) {
                case "save": return s.save(a[0]);
                case "findById": return Optional.ofNullable(s.rows.get((Long) a[0]));
                case "findAll": return s.all();
                case "existsById": return s.rows.containsKey((Long) a[0]);
                case "deleteById": s.rows.remove((Long) a[0]); return null;
                case "count": return (long) s.rows.size();
                case "flush": return null;
                case "findAllById": {
                    List<T> out = new ArrayList<>();
                    for (Object id : (Iterable<Object>) a[0]) if (s.rows.containsKey((Long) id)) out.add(s.rows.get((Long) id));
                    return out;
                }
                case "toString": return iface.getSimpleName() + "(fake)";
                case "hashCode": return System.identityHashCode(p);
                case "equals": return p == a[0];
                default:
                    Function<Object[], Object> f = derived.get(m.getName());
                    if (f == null) throw new UnsupportedOperationException("নকল repo-তে নেই: " + m.getName());
                    return f.apply(a);
            }
        };
        return (R) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, h);
    }

    // ---------- প্রতিটি repository ----------

    public static final class Exams {
        public final Store<Exam> store = new Store<>();
        public final ExamRepo repo;

        public Exams() {
            Map<String, Function<Object[], Object>> d = new HashMap<>();
            d.put("findAllByOrderByIdDesc", a -> store.all().stream().sorted(Comparator.comparing((Exam e) -> e.id).reversed()).collect(Collectors.toList()));
            d.put("findByClassIdOrderByIdDesc", a -> store.all().stream().filter(e -> Objects.equals(e.classId, a[0]))
                    .sorted(Comparator.comparing((Exam e) -> e.id).reversed()).collect(Collectors.toList()));
            repo = proxy(ExamRepo.class, store, d);
        }
    }

    public static final class Questions {
        public final Store<Question> store = new Store<>();
        public final QuestionRepo repo;

        public Questions() {
            Map<String, Function<Object[], Object>> d = new HashMap<>();
            d.put("findByExamIdOrderBySortNo", a -> store.all().stream().filter(q -> q.examId.equals(a[0])).sorted(Comparator.comparingInt(q -> q.sortNo)).collect(Collectors.toList()));
            d.put("countByExamId", a -> store.all().stream().filter(q -> q.examId.equals(a[0])).count());
            d.put("deleteByExamId", a -> { store.rows.values().removeIf(q -> q.examId.equals(a[0])); return null; });
            repo = proxy(QuestionRepo.class, store, d);
        }
    }

    public static final class Attempts {
        public final Store<Attempt> store = new Store<>();
        public final AttemptRepo repo;

        public Attempts() {
            Map<String, Function<Object[], Object>> d = new HashMap<>();
            d.put("findByExamIdAndUserId", a -> store.all().stream().filter(x -> x.examId.equals(a[0]) && x.userId.equals(a[1])).findFirst());
            d.put("findByExamIdAndStatus", a -> store.all().stream().filter(x -> x.examId.equals(a[0]) && x.status == a[1]).collect(Collectors.toList()));
            d.put("findByUserId", a -> store.all().stream().filter(x -> x.userId.equals(a[0])).collect(Collectors.toList()));
            d.put("findByStatusAndDeadlineBefore", a -> store.all().stream().filter(x -> x.status == a[0] && x.deadline.isBefore((Instant) a[1])).collect(Collectors.toList()));
            d.put("countByExamId", a -> store.all().stream().filter(x -> x.examId.equals(a[0])).count());
            d.put("deleteByExamId", a -> { store.rows.values().removeIf(x -> x.examId.equals(a[0])); return null; });
            repo = proxy(AttemptRepo.class, store, d);
        }
    }

    public static final class Classes {
        public final Store<SchoolClass> store = new Store<>();
        public final ClassRepo repo = proxy(ClassRepo.class, store, new HashMap<>());
    }

    public static final class Users {
        public final Store<AppUser> store = new Store<>();
        public final UserRepo repo;

        public Users() {
            Map<String, Function<Object[], Object>> d = new HashMap<>();
            d.put("findByRoll", a -> store.all().stream().filter(u -> u.roll.equals(a[0])).findFirst());
            d.put("existsByRoll", a -> store.all().stream().anyMatch(u -> u.roll.equals(a[0])));
            d.put("findByRoleOrderByRoll", a -> store.all().stream().filter(u -> u.role == a[0]).sorted(Comparator.comparing((AppUser u) -> u.roll)).collect(Collectors.toList()));
            repo = proxy(UserRepo.class, store, d);
        }
    }

    public static final class TeacherClasses {
        public final Store<TeacherClass> store = new Store<>();
        public final TeacherClassRepo repo;

        public TeacherClasses() {
            Map<String, Function<Object[], Object>> d = new HashMap<>();
            d.put("findByUserId", a -> store.all().stream().filter(t -> t.userId.equals(a[0])).collect(Collectors.toList()));
            d.put("deleteByUserId", a -> { store.rows.values().removeIf(t -> t.userId.equals(a[0])); return null; });
            repo = proxy(TeacherClassRepo.class, store, d);
        }
    }
}
