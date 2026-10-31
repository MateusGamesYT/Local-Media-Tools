"""Writes small PDFs protected the way PDF 2.0 and Acrobat protect them, for the metadata tests.

  python3 make_pdf.py <out dir>

aes256.pdf: AES-256 (revision 6), opened with an empty password (only changes are restricted).
password.pdf: AES-256 with a password needed to open it ("secret"): the app must say it can't read it.
Needs the "cryptography" package.
"""
import hashlib, os, struct, sys
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes


def aes_cbc(key, iv, data, encrypt=True):
    c = Cipher(algorithms.AES(key), modes.CBC(iv))
    x = c.encryptor() if encrypt else c.decryptor()
    return x.update(data) + x.finalize()


def hash2b(pw, salt, udata):
    """ISO 32000-2 algorithm 2.B."""
    k = hashlib.sha256(pw + salt + udata).digest()
    i = 0
    while True:
        k1 = (pw + k + udata) * 64
        e = aes_cbc(k[:16], k[16:32], k1)
        h = {0: hashlib.sha256, 1: hashlib.sha384, 2: hashlib.sha512}[sum(e[:16]) % 3]
        k = h(e).digest()
        i += 1
        if i >= 64 and e[-1] <= i - 32:
            return k[:32]


def encrypt_dict(user, owner, perms, file_key):
    uvs, uks, ovs, oks = os.urandom(8), os.urandom(8), os.urandom(8), os.urandom(8)
    u = hash2b(user, uvs, b"") + uvs + uks
    ue = aes_cbc(hash2b(user, uks, b""), b"\0" * 16, file_key)
    o = hash2b(owner, ovs, u) + ovs + oks
    oe = aes_cbc(hash2b(owner, oks, u), b"\0" * 16, file_key)
    p = struct.pack("<i", perms) + b"\xff\xff\xff\xffTadb" + os.urandom(4)
    c = Cipher(algorithms.AES(file_key), modes.ECB()).encryptor()
    pe = c.update(p) + c.finalize()
    hx = lambda b: "<" + b.hex() + ">"
    return ("<< /Filter /Standard /V 5 /R 6 /Length 256 /CF << /StdCF << /AuthEvent /DocOpen /CFM /AESV3 /Length 32 >> >> "
            f"/StmF /StdCF /StrF /StdCF /O {hx(o)} /U {hx(u)} /OE {hx(oe)} /UE {hx(ue)} /P {perms} /Perms {hx(pe)} >>")


def string(file_key, text):
    iv = os.urandom(16)
    data = b"\xfe\xff" + text.encode("utf-16-be")
    pad = 16 - len(data) % 16
    return "<" + (iv + aes_cbc(file_key, iv, data + bytes([pad]) * pad)).hex() + ">"


def write(path, user, owner):
    key = os.urandom(32)
    perms = -1340  # printing allowed; changes, copying and annotations not
    objs = [
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >>",
        f"<< /Title {string(key, 'Quarterly report')} /Author {string(key, 'Ana Souza')} /Creator {string(key, 'Writer')} "
        f"/Producer {string(key, 'Test producer 1.0')} /CreationDate {string(key, 'D:20240102030405+01' + chr(39) + '00' + chr(39))} >>",
        encrypt_dict(user, owner, perms, key),
    ]
    out = bytearray(b"%PDF-2.0\n%\xe2\xe3\xcf\xd3\n")
    offs = []
    for i, o in enumerate(objs, 1):
        offs.append(len(out))
        out += f"{i} 0 obj\n{o}\nendobj\n".encode("latin1")
    x = len(out)
    out += f"xref\n0 {len(objs) + 1}\n0000000000 65535 f \n".encode()
    for o in offs:
        out += f"{o:010d} 00000 n \n".encode()
    fid = os.urandom(16).hex()
    out += f"trailer\n<< /Size {len(objs) + 1} /Root 1 0 R /Info 4 0 R /Encrypt 5 0 R /ID [<{fid}> <{fid}>] >>\nstartxref\n{x}\n%%EOF\n".encode()
    open(path, "wb").write(out)


d = sys.argv[1]
write(os.path.join(d, "aes256.pdf"), b"", b"owner-pw")
write(os.path.join(d, "password.pdf"), b"secret", b"owner-pw")
