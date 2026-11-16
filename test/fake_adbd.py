"""Stand-in for a TV's adb daemon (classic TCP transport) to test src/.../Adb.java without a TV.
It checks what a real adbd checks: the AUTH signature is verified with RSA PKCS#1 v1.5 over the 20-byte token as a
SHA-1 digest (adbd: RSA_verify(NID_sha1, ...)), and the public key is parsed from Android's android_pubkey format,
with n0inv and R^2 mod n recomputed and compared.
Usage: python3 fake_adbd.py PORT   -> prints READY; runs until killed. Logs one line per event to stdout."""
import base64, os, socket, struct, sys, threading
from cryptography.hazmat.primitives.asymmetric import rsa, padding, utils
from cryptography.hazmat.primitives import hashes
from cryptography.exceptions import InvalidSignature

CNXN, AUTH, OPEN, OKAY, CLSE, WRTE = 0x4e584e43, 0x48545541, 0x4e45504f, 0x59414b4f, 0x45534c43, 0x45545257
trusted = []
lock = threading.Lock()

def log(*a):
    print(*a, flush=True)

def send(c, cmd, a0, a1, data=b''):
    c.sendall(struct.pack('<6I', cmd, a0, a1, len(data), sum(data) & 0xffffffff, cmd ^ 0xffffffff) + data)

def recv_exact(c, n):
    b = b''
    while len(b) < n:
        x = c.recv(n - len(b))
        if not x: raise EOFError
        b += x
    return b

def recv(c):
    cmd, a0, a1, ln, ck, magic = struct.unpack('<6I', recv_exact(c, 24))
    assert magic == cmd ^ 0xffffffff, 'bad magic'
    data = recv_exact(c, ln)
    assert ck == sum(data) & 0xffffffff, 'bad checksum'
    return cmd, a0, a1, data

def parse_pubkey(blob):
    raw = base64.b64decode(blob)
    assert len(raw) == 524, 'android_pubkey must be 524 bytes, got %d' % len(raw)
    words, n0inv = struct.unpack('<II', raw[:8])
    n = int.from_bytes(raw[8:264], 'little'); rr = int.from_bytes(raw[264:520], 'little')
    e = struct.unpack('<I', raw[520:])[0]
    assert words == 64
    assert n0inv == (-pow(n, -1, 2**32)) % 2**32, 'n0inv wrong'
    assert rr == pow(2, 4096, n), 'rr wrong'
    return rsa.RSAPublicNumbers(e, n).public_key()

def serve(c):
    try:
        cmd, a0, a1, data = recv(c)
        assert cmd == CNXN and data.startswith(b'host::'), 'expected CNXN host::'
        token = os.urandom(20)
        send(c, AUTH, 1, 0, token)
        while True:
            cmd, a0, a1, data = recv(c)
            if cmd == AUTH and a0 == 2:
                ok = False
                with lock:
                    for k in trusted:
                        try:
                            k.verify(data, token, padding.PKCS1v15(), utils.Prehashed(hashes.SHA1())); ok = True; break
                        except InvalidSignature:
                            pass
                log('SIGNATURE', 'valid' if ok else 'unknown-key')
                if ok:
                    break
                token = os.urandom(20)
                send(c, AUTH, 1, 0, token)
            elif cmd == AUTH and a0 == 3:
                text = data.rstrip(b'\0').decode()
                blob, _, name = text.partition(' ')
                k = parse_pubkey(blob)
                log('PUBKEY', name, 'PROMPT-ACCEPTED')
                with lock: trusted.append(k)
                break
            else:
                raise AssertionError('unexpected %08x' % cmd)
        send(c, CNXN, 0x01000000, 4096, b'device::ro.product.name=faketv;\0')
        while True:
            cmd, a0, a1, data = recv(c)
            if cmd == OPEN:
                svc = data.rstrip(b'\0').decode()
                log('OPEN', svc)
                local = a0; remote = 7
                send(c, OKAY, remote, local)
                out = ('ran: ' + svc[6:] + '\n').encode()
                for part in (out[:5], out[5:]):
                    send(c, WRTE, remote, local, part)
                    cmd2, b0, b1, _ = recv(c)
                    assert cmd2 == OKAY and b0 == local and b1 == remote, 'client must OKAY each WRTE'
                send(c, CLSE, remote, local)
                cmd2, b0, b1, _ = recv(c)
                assert cmd2 == CLSE, 'client must answer CLSE'
                log('SHELL-DONE')
    except EOFError:
        pass
    except AssertionError as e:
        log('PROTOCOL-ERROR', e)
    finally:
        c.close()

def main():
    s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(('127.0.0.1', int(sys.argv[1]))); s.listen(5)
    log('READY')
    while True:
        c, _ = s.accept()
        threading.Thread(target=serve, args=(c,), daemon=True).start()

main()
