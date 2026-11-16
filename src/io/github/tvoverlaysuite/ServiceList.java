package io.github.tvoverlaysuite;

/** Editing Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES (colon-separated) without touching other services.
 *  Plain Java so it can be tested on a JVM. */
final class ServiceList {
    private ServiceList() { }

    /** The colon-separated list without our service (full or short ".Class" form). Plain logic, kept static. */
    static String remove(String list, String me, String pkg) {
        if (list == null || list.equals("null")) return "";
        String shortForm = pkg + "/." + me.substring(me.indexOf('/') + 1 + pkg.length() + 1);
        StringBuilder b = new StringBuilder();
        for (String s : list.split(":")) {
            String t = s.trim();
            if (t.isEmpty() || t.equalsIgnoreCase(me) || t.equalsIgnoreCase(shortForm)) continue;
            if (b.length() > 0) b.append(':');
            b.append(t);
        }
        return b.toString();
    }

    /** Same switching as toggleInSettings, as one adb shell command. */
    static String shellToggle(String me) {
        String quoted = me.replace(".", "\\.");
        return "S='" + me + "'; c=$(settings get secure enabled_accessibility_services); [ \"$c\" = \"null\" ] && c=\"\"; "
                + "c=$(echo \"$c\" | sed -e 's#" + quoted + "##g' -e 's#::#:#g' -e 's#^:##' -e 's#:$##'); "
                + "settings put secure enabled_accessibility_services \"$c\"; sleep 1; "
                + "settings put secure enabled_accessibility_services \"${c:+$c:}$S\"; "
                + "settings put secure accessibility_enabled 1";
    }
}
