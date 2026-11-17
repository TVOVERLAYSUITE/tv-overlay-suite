package io.github.tvoverlaysuite;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Small, fast prefix dictionary (English + Spanish) plus words the user types. */
final class Dict {
    private final Context ctx;
    private volatile boolean ready;
    private String[] keys = new String[0];
    private String[] words = new String[0];
    private int[] scores = new int[0];
    // learned word -> count (main thread only)
    private final HashMap<String, Integer> learned = new HashMap<>();
    private boolean dirty;

    Dict(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    /** Runs on a background thread. */
    void load() {
        ArrayList<String> k = new ArrayList<>(14000);
        ArrayList<String> w = new ArrayList<>(14000);
        ArrayList<Integer> s = new ArrayList<>(14000);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open("words.tsv"), "UTF-8"), 32768)) {
            String line;
            while ((line = br.readLine()) != null) {
                int a = line.indexOf('\t');
                int b = line.indexOf('\t', a + 1);
                if (a <= 0 || b <= a) continue;
                k.add(line.substring(0, a));
                w.add(line.substring(a + 1, b));
                s.add(Integer.parseInt(line.substring(b + 1)));
            }
        } catch (Exception ignored) { }
        String[] ka = k.toArray(new String[0]);
        String[] wa = w.toArray(new String[0]);
        int[] sa = new int[s.size()];
        for (int i = 0; i < sa.length; i++) sa[i] = s.get(i);
        keys = ka; words = wa; scores = sa;
        ready = true;
    }

    void loadLearned() {
        SharedPreferences p = ctx.getSharedPreferences("learned", Context.MODE_PRIVATE);
        String all = p.getString("w", "");
        if (all.isEmpty()) return;
        for (String item : all.split("\\|")) {
            int c = item.lastIndexOf(':');
            if (c <= 0) continue;
            try { learned.put(item.substring(0, c), Integer.parseInt(item.substring(c + 1))); }
            catch (NumberFormatException ignored) { }
        }
    }

    void learn(String word) {
        if (word == null || word.length() < 2 || word.length() > 30) return;
        String lw = word.toLowerCase();
        Integer c = learned.get(lw);
        learned.put(lw, c == null ? 1 : Math.min(c + 1, 50));
        dirty = true;
    }

    void save() {
        if (!dirty) return;
        dirty = false;
        // keep the 400 most-used words
        List<Map.Entry<String, Integer>> list = new ArrayList<>(learned.entrySet());
        java.util.Collections.sort(list, (x, y) -> y.getValue() - x.getValue());
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, Integer> e : list) {
            if (n++ >= 400) break;
            if (sb.length() > 0) sb.append('|');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        ctx.getSharedPreferences("learned", Context.MODE_PRIVATE).edit().putString("w", sb.toString()).apply();
    }

    static String fold(String s) {
        String n = Normalizer.normalize(s.toLowerCase(), Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (Character.getType(c) != Character.NON_SPACING_MARK) sb.append(c);
        }
        return sb.toString();
    }

    /** Top suggestions for a typed prefix. */
    List<String> suggest(String typed, int max) {
        ArrayList<String> out = new ArrayList<>(max);
        if (typed.isEmpty()) return out;
        String p = fold(typed);
        String typedLower = typed.toLowerCase();
        String[] bw = new String[max];
        int[] bs = new int[max];
        int filled = 0;

        if (ready) {
            String[] ks = keys;
            int lo = 0, hi = ks.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (ks[mid].compareTo(p) < 0) lo = mid + 1; else hi = mid;
            }
            for (int i = lo; i < ks.length && ks[i].startsWith(p); i++) {
                String w = words[i];
                if (w.equals(typedLower)) continue;
                Integer lc = learned.get(w);
                int sc = scores[i] + (lc == null ? 0 : 300 + lc * 60);
                filled = insert(bw, bs, filled, w, sc);
            }
        }
        for (Map.Entry<String, Integer> e : learned.entrySet()) {
            String w = e.getKey();
            if (w.equals(typedLower) || !fold(w).startsWith(p)) continue;
            boolean dup = false;
            for (int j = 0; j < filled; j++) if (bw[j].equals(w)) { dup = true; break; }
            if (dup) continue;
            filled = insert(bw, bs, filled, w, 500 + e.getValue() * 60);
        }
        boolean cap = Character.isUpperCase(typed.charAt(0));
        boolean allCap = typed.length() > 1 && typed.equals(typed.toUpperCase());
        for (int j = 0; j < filled; j++) {
            String w = bw[j];
            if (allCap) w = w.toUpperCase();
            else if (cap) w = Character.toUpperCase(w.charAt(0)) + w.substring(1);
            out.add(w);
        }
        return out;
    }

    private static int insert(String[] bw, int[] bs, int filled, String w, int sc) {
        int max = bw.length;
        if (filled == max && sc <= bs[max - 1]) return filled;
        int pos = filled < max ? filled : max - 1;
        while (pos > 0 && bs[pos - 1] < sc) {
            if (pos < max) { bw[pos] = bw[pos - 1]; bs[pos] = bs[pos - 1]; }
            pos--;
        }
        bw[pos] = w; bs[pos] = sc;
        return filled < max ? filled + 1 : filled;
    }
}
