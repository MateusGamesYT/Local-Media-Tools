"""Compares the app's MP3 encoder with LAME and shine on the same recordings.

  python3 mp3_compare.py <harness run.sh> <work dir> <kbps> <file.wav>...

Each file is encoded by the app (through the harness), LAME and shine at the same constant bit
rate, decoded by FFmpeg (with -err_detect explode: any bitstream error fails), lined up with the
original (cross-correlation) and compared: SNR, segmental SNR (20 ms segments, each clipped to
−10…60 dB) and the noise in critical bands relative to the signal there (NMR-like, averaged over
bands and segments, lower is better), and a noise-to-mask ratio from a simple masking model (mean
NMR and the share of frames where noise rises above the mask; lower is better). These are
measurements, not listening tests.
"""
import os, subprocess, sys
import numpy as np

run, work, kbps, files = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4:]
os.makedirs(work, exist_ok=True)


def sh(*a):
    subprocess.run(a, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def decode(path, ch):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-err_detect", "explode", "-i", path, "-f", "f32le", "-ac", str(ch), "-"], check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype="<f4").reshape(-1, ch).astype(np.float64)


def align(ref, x):
    a = ref[:44100 * 6, 0]; b = x[:44100 * 6 + 4000, 0]
    n = 1 << int(np.ceil(np.log2(len(a) + len(b))))
    c = np.fft.irfft(np.fft.rfft(b, n) * np.conj(np.fft.rfft(a, n)), n)
    lag = int(np.argmax(c[:4000]))
    y = x[lag:lag + len(ref)]
    if len(y) < len(ref): y = np.vstack([y, np.zeros((len(ref) - len(y), ref.shape[1]))])
    return y, lag


def bark_bands(rate, n):
    f = np.fft.rfftfreq(n, 1 / rate)
    z = 13 * np.arctan(0.00076 * f) + 3.5 * np.arctan((f / 7500) ** 2)
    return np.minimum(z.astype(int), 24)


def measures(ref, y, rate):
    e = y - ref
    snr = 10 * np.log10(np.sum(ref ** 2) / max(np.sum(e ** 2), 1e-20))
    seg = 882
    segs = []
    for k in range(0, len(ref) - seg, seg):
        s = np.sum(ref[k:k + seg] ** 2); nn = np.sum(e[k:k + seg] ** 2)
        if s < 1e-6: continue
        segs.append(min(60, max(-10, 10 * np.log10(s / max(nn, 1e-20)))))
    # Noise per critical band against the signal there (2048-sample frames, bands up to 16 kHz).
    n = 2048; win = np.hanning(n); band = bark_bands(rate, n)
    nmr = []
    for k in range(0, len(ref) - n, n):
        for c in range(ref.shape[1]):
            S = np.abs(np.fft.rfft(ref[k:k + n, c] * win)) ** 2
            N = np.abs(np.fft.rfft(e[k:k + n, c] * win)) ** 2
            if S.sum() < 1e-3: continue
            for b in range(1, 22):
                m = band == b
                s, q = S[m].sum(), N[m].sum()
                if s > 1e-9 * S.sum(): nmr.append(10 * np.log10(max(q, 1e-20) / s))
    return snr, float(np.mean(segs)), float(np.mean(nmr))


def nmr_measure(ref, y, rate):
    """Noise-to-mask ratio after Brandenburg/Johnston: per 2048-sample frame (hop 1024) and channel the
    original's energy in 1-Bark bands is spread (Schroeder), lowered by an offset between tones
    (14.5 + band dB) and noise (5.5 dB) from the spectral flatness, and floored by the threshold of
    hearing (full scale = 92 dB SPL). Returns the mean NMR (dB, energy-averaged over bands, then over
    frames) and the share of frames where some band's noise is above its mask (audible, by this model)."""
    n, hop = 2048, 1024
    win = np.hanning(n)
    f = np.fft.rfftfreq(n, 1 / rate)
    z = 13 * np.arctan(0.00076 * f) + 3.5 * np.arctan((f / 7500) ** 2)
    nb = int(z.max()) + 1
    band = np.minimum(z.astype(int), nb - 1)
    onehot = np.zeros((len(f), nb)); onehot[np.arange(len(f)), band] = 1
    dz = np.arange(nb)[None, :] - np.arange(nb)[:, None]          # maskee − masker
    spread = 10 ** ((15.81 + 7.5 * (dz + 0.474) - 17.5 * np.sqrt(1 + (dz + 0.474) ** 2)) / 10)
    k = np.maximum(f, 20) / 1000
    ath_db = 3.64 * k ** -0.8 - 6.5 * np.exp(-0.6 * (k - 3.3) ** 2) + 1e-3 * k ** 4
    # A full-scale sine's peak bin with this window and FFT: (n/4)² → 92 dB SPL.
    ath = (n / 4) ** 2 * 10 ** ((np.minimum(ath_db, 120) - 92) / 10)
    ath_band = (onehot * ath[:, None]).min(0, where=onehot > 0, initial=np.inf) * np.bincount(band, minlength=nb)
    totals, disturbed = [], 0
    count = 0
    for c in range(ref.shape[1]):
        idx = np.arange(0, len(ref) - n, hop)
        R = np.abs(np.fft.rfft(np.stack([ref[i:i + n, c] for i in idx]) * win, axis=1)) ** 2
        E = np.abs(np.fft.rfft(np.stack([y[i:i + n, c] - ref[i:i + n, c] for i in idx]) * win, axis=1)) ** 2
        Rb = R @ onehot; Eb = E @ onehot
        sfm = 10 * np.log10(np.exp(np.mean(np.log(R[:, 1:] + 1e-20), 1)) / (np.mean(R[:, 1:], 1) + 1e-20) + 1e-20)
        alpha = np.minimum(sfm / -60, 1)[:, None]
        offset = alpha * (14.5 + np.arange(nb))[None, :] + (1 - alpha) * 5.5
        mask = np.maximum((Rb @ spread) * 10 ** (-offset / 10), ath_band[None, :])
        live = Rb.sum(1) > (n / 4) ** 2 * 1e-7                      # frames above −70 dBFS
        ratio = Eb[live] / mask[live]
        totals.extend(10 * np.log10(ratio.mean(1) + 1e-20))
        disturbed += int(np.sum(ratio.max(1) > 1)); count += int(live.sum())
    return float(np.mean(totals)), 100.0 * disturbed / max(count, 1)


print(f"{'file':34} {'encoder':6} {'bytes':>9} {'SNR':>6} {'segSNR':>7} {'band N/S':>8} {'NMR':>6} {'disturbed':>9}  lag")
for path in files:
    name = os.path.basename(path)[:30]
    info = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=sample_rate,channels", "-of", "csv=p=0", path], capture_output=True, text=True).stdout.strip().split(",")
    rate, ch = int(info[0]), int(info[1])
    f32 = os.path.join(work, "in.f32")
    sh("ffmpeg", "-v", "error", "-y", "-i", path, "-f", "f32le", "-acodec", "pcm_f32le", f32)
    ref = np.fromfile(f32, dtype="<f4").reshape(-1, ch).astype(np.float64)
    outs = {"app": os.path.join(work, "app.mp3"), "lame": os.path.join(work, "lame.mp3"), "shine": os.path.join(work, "shine.mp3")}
    subprocess.run([run, "mp3", str(rate), str(ch), str(kbps), f32, outs["app"]], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    sh("ffmpeg", "-v", "error", "-y", "-i", path, "-c:a", "libmp3lame", "-b:a", f"{kbps}k", outs["lame"])
    sh("ffmpeg", "-v", "error", "-y", "-i", path, "-c:a", "libshine", "-b:a", f"{kbps}k", outs["shine"])
    for enc, p in outs.items():
        try:
            y, lag = align(ref, decode(p, ch))
            snr, seg, nmr = measures(ref, y, rate)
            tot, dist = nmr_measure(ref, y, rate)
            print(f"{name:34} {enc:6} {os.path.getsize(p):9d} {snr:6.1f} {seg:7.1f} {nmr:8.1f} {tot:6.1f} {dist:8.1f}%  {lag}")
        except subprocess.CalledProcessError:
            print(f"{name:34} {enc:6} DECODE ERROR")
