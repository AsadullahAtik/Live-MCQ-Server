package com.livemcq.core;

/** পরীক্ষার মূল্যায়ন: সঠিক × প্রতি প্রশ্ন − ভুল × কাটা নম্বর (০ এর নিচে নামে না). Spring ছাড়া, তাই সহজে টেস্ট করা যায়. */
public final class Grader {
    private Grader() {}

    public record Result(int correct, int wrong, int blank, double marks) {}

    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** answers[i] == -1 মানে উত্তর দেয়নি. */
    public static Result grade(int[] key, int[] answers, double perQ, double neg) {
        int c = 0, w = 0, b = 0;
        for (int i = 0; i < key.length; i++) {
            int a = (answers != null && i < answers.length) ? answers[i] : -1;
            if (a < 0) b++;
            else if (a == key[i]) c++;
            else w++;
        }
        double marks = Math.max(0.0, round2(c * perQ - w * neg));
        return new Result(c, w, b, marks);
    }
}
