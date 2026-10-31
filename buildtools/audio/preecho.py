"""Pre-echo: quantisation noise that MP3's long blocks smear before a sudden attack.

  python3 preecho.py <original.f32> <channels> <rate> <decoded.mp3> <lag> [<decoded2.mp3> <lag2> …]

Attacks are where the original's energy in 2 ms steps jumps by more than 20 dB over the 10 ms before;
for each, the noise (decoded − original) in the 15 ms before the attack is compared with the
original's own energy there (dB; lower is better: noise below what was there is not heard).
"""
import subprocess, sys
import numpy as np

orig, ch, rate = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
x = np.fromfile(orig, dtype="<f4").reshape(-1, ch).astype(np.float64).mean(1)
step = rate // 500
e = np.array([np.sum(x[k:k + step] ** 2) for k in range(0, len(x) - step, step)]) + 1e-12
attacks = [k for k in range(5, len(e)) if 10 * np.log10(e[k] / e[k - 5:k].mean()) > 20 and e[k] > 1e-4 * e.max()]
# One attack per 50 ms.
kept = []
for k in attacks:
    if not kept or k - kept[-1] > 25: kept.append(k)
for i in range(4, len(sys.argv), 2):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", sys.argv[i], "-f", "f32le", "-ac", str(ch), "-"], capture_output=True, check=True).stdout
    lag = int(sys.argv[i + 1])
    y = np.frombuffer(raw, dtype="<f4").reshape(-1, ch).astype(np.float64).mean(1)[lag:lag + len(x)]
    y = np.concatenate([y, np.zeros(len(x) - len(y))]) if len(y) < len(x) else y
    vals = []
    for k in kept:
        a = k * step; b0 = max(0, a - int(0.015 * rate))
        noise = np.sum((y[b0:a] - x[b0:a]) ** 2); sig = np.sum(x[b0:a] ** 2) + 1e-12
        vals.append(10 * np.log10(noise / sig + 1e-12))
    v = np.array(vals)
    print(f"{sys.argv[i].split('/')[-1]:12} attacks {len(v):4d}: noise before attacks vs what was there: median {np.median(v):6.1f} dB, 90th percentile {np.percentile(v, 90):6.1f} dB, share above 0 dB {np.mean(v > 0) * 100:5.1f} %")
