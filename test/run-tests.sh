#!/bin/bash
# Runs the JVM tests: protocol parsers (typing, trackpad), pairing gate, adb client (against test/fake_adbd.py) and the
# accessibility-list editing (Java and adb-shell versions). Needs Java 17+, Python 3 with `cryptography`.
set -u
cd "$(dirname "$0")/.."
B=build/test; rm -rf $B; mkdir -p $B
S=src/io/github/tvoverlaysuite
javac --release 8 -Xlint:-options -encoding UTF-8 -d $B $S/Ink.java $S/InkServer.java $S/Extras.java $S/Pairing.java \
    $S/Adb.java $S/ServiceList.java test/ExtrasTest.java test/PairingTest.java test/TestHost.java \
    test/AdbProbe.java test/AdbTest.java test/ServiceListProbe.java test/SlowPhoneTest.java \
    $S/TypeProto.java test/TypeTest.java $S/PadProto.java $S/PadRelay.java test/PadTest.java || exit 1
fail=0
java -cp $B ExtrasTest | tail -1 | grep -q "ALL PASS" && echo "PASS ExtrasTest" || { echo "FAIL ExtrasTest"; fail=1; }
java -cp $B PairingTest > $B/pairing.log || fail=1
grep -E "FAIL|ALL PASS" $B/pairing.log
java -cp $B SlowPhoneTest > $B/slow.log 2>&1 || fail=1
grep -E "PASS|FAIL" $B/slow.log | sed "s/^/SlowPhoneTest: /"
java -cp $B TypeTest > $B/type.log 2>&1 || fail=1
grep -E "PASS|FAIL" $B/type.log | sed "s/^/TypeTest: /"
java -cp $B PadTest > $B/pad.log 2>&1 || fail=1
grep -E "PASS|FAIL" $B/pad.log | sed "s/^/PadTest: /"
javac --release 8 -Xlint:-options -encoding UTF-8 -cp $B -d $B phone/src/io/github/tvoverlaysuite/remote/WsClient.java \
    test/remote/PhoneLinkTest.java test/remote/PadLinkTest.java || exit 1
java -cp $B io.github.tvoverlaysuite.remote.PhoneLinkTest > $B/link.log 2>&1 || fail=1
grep -E "PASS|FAIL" $B/link.log | sed "s/^/PhoneLinkTest: /"
java -cp $B io.github.tvoverlaysuite.remote.PadLinkTest > $B/padlink.log 2>&1 || fail=1
grep -E "PASS|FAIL|INFO" $B/padlink.log | sed "s/^/PadLinkTest: /"
python3 -I test/fake_adbd.py 15555 > $B/adbd.log 2>&1 & ADBD=$!
sleep 1
java -cp $B AdbTest 15555 $B/k.pem | grep -E "PASS|FAIL" || fail=1
grep -q PROTOCOL-ERROR $B/adbd.log && { echo "FAIL fake adbd saw a protocol error"; cat $B/adbd.log; fail=1; }
kill $ADBD 2>/dev/null
java -cp $B io.github.tvoverlaysuite.ServiceListProbe | grep -E "FAIL" && fail=1 || echo "PASS ServiceList.remove cases"
bash test/servicelist_test.sh $B || fail=1
[ $fail = 0 ] && echo "ALL PASS" || echo "SOME TESTS FAILED"
exit $fail
