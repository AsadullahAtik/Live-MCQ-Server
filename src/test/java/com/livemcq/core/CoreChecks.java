package com.livemcq.core;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** একই চেকগুলো JUnit (mvn test) এবং সাধারণ main দুই ভাবেই চালানো যায়. */
public final class CoreChecks {
    private CoreChecks() {}

    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    public static void run() throws Exception {
        // মূল্যায়ন
        int[] key = {0, 1, 2, 3, 0};
        var r = Grader.grade(key, new int[]{0, 1, 0, -1, -1}, 1, 0.25);
        check(r.correct() == 2 && r.wrong() == 1 && r.blank() == 2, "count");
        check(r.marks() == 1.75, "marks 1.75 got " + r.marks());
        check(Grader.grade(key, new int[]{1, 2, 3, 0, 1}, 1, 0.25).marks() == 0.0, "floor at zero");
        check(Grader.grade(key, null, 1, 0.25).blank() == 5, "null answers");

        // র‍্যাংক ও tie
        var ranked = Ranker.rank(List.of(
                new Ranker.Entry("a", 5, 200), new Ranker.Entry("b", 5, 150), new Ranker.Entry("c", 4, 100),
                new Ranker.Entry("d", 4, 100), new Ranker.Entry("e", 3, 90)));
        check(ranked.get(0).entry().id().equals("b") && ranked.get(0).rank() == 1, "first b rank1");
        check(ranked.get(1).entry().id().equals("a") && ranked.get(1).rank() == 2, "second a rank2");
        check(ranked.get(2).rank() == 3 && ranked.get(3).rank() == 3, "tie c,d share rank 3");
        check(ranked.get(4).rank() == 5, "next rank skipped -> 5");

        // shuffle: একই seed → একই ক্রম; প্রতিটি ক্রম সত্যিই permutation; পর্দা↔আসল ঘুরে এলে উত্তর অক্ষত
        int n = 12;
        var p1 = Shuffler.plan(n, 42L);
        var pb = Shuffler.plan(n, 42L);
        check(java.util.Arrays.equals(p1.qOrder(), pb.qOrder()), "same seed same question order");
        for (int i = 0; i < n; i++) check(java.util.Arrays.equals(p1.optOrder()[i], pb.optOrder()[i]), "same seed same options");
        check(java.util.Arrays.stream(p1.qOrder()).sorted().boxed().toList().equals(java.util.stream.IntStream.range(0, n).boxed().toList()), "q perm");
        for (int i = 0; i < n; i++)
            check(java.util.Arrays.stream(p1.optOrder()[i]).sorted().boxed().toList().equals(List.of(0, 1, 2, 3)), "opt perm");
        int[] canon = new int[n];
        for (int i = 0; i < n; i++) canon[i] = (i % 5 == 4) ? -1 : i % 4;
        check(java.util.Arrays.equals(p1.toCanonical(p1.toPresented(canon)), canon), "round trip");
        check(java.util.Arrays.equals(Shuffler.plan(n, null).toPresented(canon), canon), "null seed = identity");
        // শিক্ষার্থী পর্দায় যে অপশন বেছেছে, সেটাই আসলে সঠিক উত্তর কি না: সঠিকটা পর্দার কোথায় আছে খুঁজে বেছে নিলে পুরো নম্বর
        int[] skey = new int[n];
        for (int i = 0; i < n; i++) skey[i] = (i * 3) % 4;
        int[] shown = new int[n];
        for (int pp = 0; pp < n; pp++) {
            int orig = skey[p1.qOrder()[pp]];
            for (int k = 0; k < 4; k++) if (p1.optOrder()[pp][k] == orig) shown[pp] = k;
        }
        check(Grader.grade(skey, p1.toCanonical(shown), 1, 0.25).correct() == n, "picking the right shown option is correct");
        // একটু ছোট seed-এর ফাঁকা/অস্বাভাবিক ইনপুট
        check(Shuffler.plan(0, 7L).size() == 0, "empty exam");
        check(java.util.Arrays.stream(p1.toCanonical(new int[]{9, -5})).filter(v -> v >= 0).count() == 0, "garbage -> blanks");

        // Scope: অ্যাডমিন সব; শিক্ষক শুধু নির্ধারিত শ্রেণি; কোনো শ্রেণি না থাকলে কিছুই না
        check(Scope.all().allows(5L) && Scope.all().allows(null), "admin scope allows all");
        Scope teacher = Scope.of(java.util.Set.of(1L, 2L));
        check(teacher.allows(1L) && teacher.allows(2L) && !teacher.allows(3L), "teacher scope");
        check(!teacher.allows(null), "teacher cannot touch classless");
        check(!Scope.of(java.util.Set.of()).allows(1L), "empty scope allows nothing");
        check(Scope.all().isAll() && !teacher.isAll(), "isAll");

        // সমান্তরাল: ২০০ থ্রেড একই key-তে একসাথে — ঠিক একবারই তৈরি হবে
        KeyedLock lock = new KeyedLock();
        ConcurrentHashMap<String, Boolean> created = new ConcurrentHashMap<>();
        AtomicInteger createdCount = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                lock.with("exam1:user1", () -> {
                    if (created.putIfAbsent("exam1:user1", true) == null) {
                        try { Thread.sleep(1); } catch (InterruptedException ignored) {}
                        createdCount.incrementAndGet();
                    }
                    return null;
                });
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) f.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        check(createdCount.get() == 1, "created once, got " + createdCount.get());

        // আলাদা key সমান্তরালে (ডেডলক নেই)
        ExecutorService p2 = Executors.newFixedThreadPool(16);
        AtomicInteger done = new AtomicInteger();
        List<Future<?>> f2 = new java.util.ArrayList<>();
        for (int i = 0; i < 500; i++) {
            final int k = i % 50;
            f2.add(p2.submit(() -> lock.with("k" + k, done::incrementAndGet)));
        }
        for (Future<?> f : f2) f.get(10, TimeUnit.SECONDS);
        p2.shutdown();
        check(done.get() == 500, "all 500 ran");
    }

    public static void main(String[] a) throws Exception {
        run();
        System.out.println("CORE CHECKS PASSED");
    }
}
