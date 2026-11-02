package com.livemcq.core;

import java.util.Arrays;

/** উত্তরের তালিকা ↔ "0,-1,2" লেখা. -১ মানে উত্তর দেয়নি. */
public final class Answers {
    private Answers() {}

    public static int[] blanks(int n) {
        int[] a = new int[n];
        Arrays.fill(a, -1);
        return a;
    }

    /** n টি প্রশ্নের জন্য ঠিক n দৈর্ঘ্যের তালিকা; ০-৩ এর বাইরের মান -১ হয়ে যায়. */
    public static int[] sanitize(int[] raw, int n) {
        int[] a = blanks(n);
        if (raw == null) return a;
        for (int i = 0; i < n && i < raw.length; i++) a[i] = (raw[i] >= 0 && raw[i] <= 3) ? raw[i] : -1;
        return a;
    }

    public static String format(int[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(a[i]);
        }
        return sb.toString();
    }

    public static int[] parse(String csv, int n) {
        int[] a = blanks(n);
        if (csv == null || csv.isBlank()) return a;
        String[] p = csv.split(",");
        for (int i = 0; i < n && i < p.length; i++) {
            try {
                int v = Integer.parseInt(p[i].trim());
                a[i] = (v >= 0 && v <= 3) ? v : -1;
            } catch (NumberFormatException ignored) {
                a[i] = -1;
            }
        }
        return a;
    }
}
