package io.github.tvoverlaysuite;

/**
 * v1.6: which trackpad app is in front, and passing the phone's trackpad lines to it. Plain Java (the JVM tests drive
 * it with fake apps); {@link PadService} wires it to the apps on the TV.
 *
 * The front app is the one that most recently said "f 1" and has not said "f 0" or disconnected since. Phone lines
 * go only to that app, so an app in the background never receives the trackpad.
 */
public final class PadRelay {

    /** One connected trackpad app. send() must not block (PadService uses one-way messages). */
    public interface App {
        void send(String line);
    }

    /** Where front changes go (every connected phone app). */
    public interface Phones {
        void send(String line);
    }

    private final Phones phones;
    private App front;
    private String frontName = "";

    public PadRelay(Phones phones) { this.phones = phones; }

    /** A line from a trackpad app. Any thread. */
    public void fromApp(App a, String line) {
        char[] kind = new char[1];
        PadProto.Front f = PadProto.parseApp(line, kind);
        if (kind[0] != 'f' || f == null) return;
        String tell = null;
        synchronized (this) {
            if (f.on) {
                if (front != a || !frontName.equals(f.name)) {
                    front = a;
                    frontName = f.name;
                    tell = PadProto.front(true, f.name);
                }
            } else if (front == a) {
                front = null;
                frontName = "";
                tell = PadProto.front(false, null);
            }
        }
        if (tell != null) phones.send(tell);
    }

    /** A trackpad app disconnected. Any thread. */
    public void closed(App a) {
        boolean was;
        synchronized (this) {
            was = front == a;
            if (was) { front = null; frontName = ""; }
        }
        if (was) phones.send(PadProto.front(false, null));
    }

    /** A "P ..." line from a phone app. Returns true when it reached an app. Any thread (the phone's network thread). */
    public boolean fromPhone(String line) {
        String out = PadProto.toApp(line);
        if (out == null) return false;
        App f;
        synchronized (this) { f = front; }
        if (f == null) return false;
        f.send(out);
        return true;
    }

    /** The current state, for a phone app that just connected. */
    public synchronized String frontLine() { return PadProto.front(front != null, frontName); }

    public synchronized String frontName() { return front == null ? null : frontName; }
}
