package com.livemcq.core;

import java.util.ArrayList;
import java.util.List;

/** নম্বর বেশি আগে; সমান হলে কম সময় আগে; নম্বর ও সময় দুটোই সমান হলে একই র‍্যাংক, পরের র‍্যাংক বাদ যায়. */
public final class Ranker {
    private Ranker() {}

    public record Entry(String id, double marks, int timeSec) {}

    public record Ranked(Entry entry, int rank) {}

    public static List<Ranked> rank(List<Entry> input) {
        List<Entry> list = new ArrayList<>(input);
        list.sort((a, b) -> {
            int c = Double.compare(b.marks(), a.marks());
            return c != 0 ? c : Integer.compare(a.timeSec(), b.timeSec());
        });
        List<Ranked> out = new ArrayList<>(list.size());
        int rank = 0;
        for (int i = 0; i < list.size(); i++) {
            Entry cur = list.get(i);
            if (i > 0) {
                Entry prev = list.get(i - 1);
                if (prev.marks() == cur.marks() && prev.timeSec() == cur.timeSec()) {
                    out.add(new Ranked(cur, rank));
                    continue;
                }
            }
            rank = i + 1;
            out.add(new Ranked(cur, rank));
        }
        return out;
    }
}
