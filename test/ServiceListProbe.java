package io.github.tvoverlaysuite;

/** Prints ServiceList results for test/servicelist_test.sh. */
public final class ServiceListProbe {
    public static void main(String[] a) {
        String me = "io.github.tvoverlaysuite/io.github.tvoverlaysuite.MarkerService", pkg = "io.github.tvoverlaysuite";
        if (a.length > 0 && a[0].equals("shell")) { System.out.println(ServiceList.shellToggle(me)); return; }
        String[][] cases = {
            {null, ""}, {"null", ""}, {"", ""},
            {me, ""},
            {"a.b/a.b.X:" + me + ":c.d/.Y", "a.b/a.b.X:c.d/.Y"},
            {"io.github.tvoverlaysuite/.MarkerService:a.b/a.b.X", "a.b/a.b.X"},
            {"a.b/a.b.X::c.d/.Y", "a.b/a.b.X:c.d/.Y"},
            {"io.github.tvoverlaysuite.other/x.Y", "io.github.tvoverlaysuite.other/x.Y"},
        };
        boolean ok = true;
        for (String[] c : cases) {
            String got = ServiceList.remove(c[0], me, pkg);
            boolean p = got.equals(c[1]);
            ok &= p;
            System.out.println((p ? "PASS" : "FAIL") + " remove(" + c[0] + ") = " + got);
        }
        System.exit(ok ? 0 : 1);
    }
}
