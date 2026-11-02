package com.livemcq.core;

import java.util.Random;

/**
 * প্রশ্ন ও অপশনের এলোমেলো ক্রম. একটা seed (সংখ্যা) থেকে সবসময় একই ক্রম বানানো যায়,
 * তাই ডাটাবেসে শুধু seed রাখলেই চলে; ছাত্র পরীক্ষা চালিয়ে গেলে একই ক্রম ফিরে আসে.
 *
 * নিয়ম: ডাটাবেসে উত্তর সবসময় "আসল" (canonical) ক্রমে থাকে — মূল্যায়ন, বিশ্লেষণ ও রিভিউ তাই বদলাতে হয়নি.
 * ছাত্রের পর্দার (presented) ক্রম ↔ আসল ক্রম বদলানোর কাজ শুধু এখানে.
 */
public final class Shuffler {
    private Shuffler() {}

    /**
     * qOrder[p]      = পর্দার p-তম জায়গায় আসল কোন প্রশ্ন (সূচক)
     * optOrder[p][k] = পর্দার p-তম প্রশ্নের k-তম অপশনের জায়গায় আসল কোন অপশন
     */
    public record Plan(int[] qOrder, int[][] optOrder) {
        public int size() {
            return qOrder.length;
        }

        /** ছাত্রের পাঠানো (পর্দার ক্রমের) উত্তর → আসল ক্রমের উত্তর. */
        public int[] toCanonical(int[] presented) {
            int n = size();
            int[] canon = Answers.blanks(n);
            for (int p = 0; p < n; p++) {
                int v = (presented != null && p < presented.length) ? presented[p] : -1;
                canon[qOrder[p]] = (v >= 0 && v <= 3) ? optOrder[p][v] : -1;
            }
            return canon;
        }

        /** আসল ক্রমের উত্তর → পর্দার ক্রম (চালিয়ে যাওয়ার সময় ছাত্রকে ফেরত দেওয়ার জন্য). */
        public int[] toPresented(int[] canon) {
            int n = size();
            int[] out = Answers.blanks(n);
            for (int p = 0; p < n; p++) {
                int v = canon[qOrder[p]];
                if (v < 0) continue;
                for (int k = 0; k < 4; k++) if (optOrder[p][k] == v) out[p] = k;
            }
            return out;
        }
    }

    /** কোনো এলোমেলো করা নেই: পর্দা = আসল. */
    public static Plan identity(int n) {
        int[] q = new int[n];
        int[][] o = new int[n][];
        for (int i = 0; i < n; i++) {
            q[i] = i;
            o[i] = new int[]{0, 1, 2, 3};
        }
        return new Plan(q, o);
    }

    /** seed == null হলে এলোমেলো নয় (পুরনো attempt ও shuffle বন্ধ পরীক্ষা). */
    public static Plan plan(int n, Long seed) {
        if (seed == null) return identity(n);
        Random rnd = new Random(seed);
        int[] q = perm(n, rnd);
        int[][] o = new int[n][];
        for (int p = 0; p < n; p++) o[p] = perm(4, rnd);
        return new Plan(q, o);
    }

    /** Fisher–Yates. */
    private static int[] perm(int n, Random rnd) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = i;
        for (int i = n - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
        return a;
    }
}
