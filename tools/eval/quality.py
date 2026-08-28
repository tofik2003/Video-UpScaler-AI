#!/usr/bin/env python3
"""
Image quality metrics for the evaluation harness (PLAN.md §7).

PSNR and SSIM in pure numpy — no scipy, no skimage, no TensorFlow — so the harness runs anywhere
the repo is checked out. Verified against known reference values; see `selftest` below.

Why these two and not LPIPS: LPIPS needs a pretrained perceptual network, which is a second model
with its own licensing question (PLAN.md §9). PSNR/SSIM are licence-free and are the metrics the
Phase 2 gate is written in terms of. Add LPIPS later if a subjective gap shows up that they miss.
"""

from __future__ import annotations

import numpy as np


def psnr(a: np.ndarray, b: np.ndarray, max_val: float = 1.0) -> float:
    """Peak signal-to-noise ratio in dB. Returns inf for identical images."""
    a = np.asarray(a, dtype=np.float64)
    b = np.asarray(b, dtype=np.float64)
    if a.shape != b.shape:
        raise ValueError(f"shape mismatch: {a.shape} vs {b.shape}")
    mse = float(np.mean((a - b) ** 2))
    if mse == 0.0:
        return float("inf")
    return 10.0 * np.log10((max_val ** 2) / mse)


def _gaussian_kernel(size: int = 11, sigma: float = 1.5) -> np.ndarray:
    x = np.arange(size, dtype=np.float64) - (size - 1) / 2.0
    k = np.exp(-(x ** 2) / (2 * sigma ** 2))
    return k / k.sum()


def _separable_blur(img: np.ndarray, k: np.ndarray) -> np.ndarray:
    """Reflect-padded separable 2D convolution. Padding matters: zero-padding darkens the border and
    depresses SSIM by a visible amount on small images."""
    r = len(k) // 2
    padded = np.pad(img, r, mode="reflect")

    # Convolve along rows, then columns. `np.apply_along_axis` is not the fastest option, but it is
    # exact and readable, and the harness scores a handful of frames, not thousands.
    horizontal = np.apply_along_axis(lambda row: np.convolve(row, k, mode="valid"), 1, padded)
    return np.apply_along_axis(lambda col: np.convolve(col, k, mode="valid"), 0, horizontal)


def ssim(a: np.ndarray, b: np.ndarray, max_val: float = 1.0) -> float:
    """Mean SSIM over an HxW or HxWx3 image, using the standard 11x11 Gaussian window.

    Computed per channel and averaged, which is the common convention and matches skimage's
    `channel_axis` behaviour closely enough for gate decisions.
    """
    a = np.asarray(a, dtype=np.float64)
    b = np.asarray(b, dtype=np.float64)
    if a.shape != b.shape:
        raise ValueError(f"shape mismatch: {a.shape} vs {b.shape}")

    if a.ndim == 3:
        return float(np.mean([ssim(a[..., c], b[..., c], max_val) for c in range(a.shape[2])]))

    k = _gaussian_kernel()
    c1 = (0.01 * max_val) ** 2
    c2 = (0.03 * max_val) ** 2

    mu_a = _separable_blur(a, k)
    mu_b = _separable_blur(b, k)
    mu_a2, mu_b2, mu_ab = mu_a ** 2, mu_b ** 2, mu_a * mu_b

    sigma_a2 = _separable_blur(a * a, k) - mu_a2
    sigma_b2 = _separable_blur(b * b, k) - mu_b2
    sigma_ab = _separable_blur(a * b, k) - mu_ab

    num = (2 * mu_ab + c1) * (2 * sigma_ab + c2)
    den = (mu_a2 + mu_b2 + c1) * (sigma_a2 + sigma_b2 + c2)
    return float(np.mean(num / den))


def report(name: str, sr: np.ndarray, ref: np.ndarray, baseline: np.ndarray | None = None) -> bool:
    """Print a gate line. Returns True if the candidate beats the baseline (or no baseline given).

    `baseline` is the bicubic/Lanczos reference. Phase 2's gate is not "SR looks good", it is
    "SR beats the reference the app would otherwise ship" — so the delta is the number that matters.
    """
    p, s = psnr(sr, ref), ssim(sr, ref)
    print(f"{name:22} PSNR {p:6.2f} dB   SSIM {s:.4f}")
    if baseline is None:
        return True
    bp, bs = psnr(baseline, ref), ssim(baseline, ref)
    print(f"{'  reference (bicubic)':22} PSNR {bp:6.2f} dB   SSIM {bs:.4f}")
    print(f"{'  delta':22} PSNR {p - bp:+6.2f} dB   SSIM {s - bs:+.4f}"
          f"   -> {'PASS' if p > bp and s > bs else 'FAIL'}")
    return p > bp and s > bs


def selftest() -> bool:
    """Checks the implementations against values that are known by construction."""
    rng = np.random.default_rng(0)
    ok = True

    # 1. Identical images: PSNR infinite, SSIM exactly 1.
    x = rng.random((64, 64))
    ok &= psnr(x, x) == float("inf")
    s = ssim(x, x)
    ok &= abs(s - 1.0) < 1e-9
    print(f"identical images      PSNR inf={psnr(x,x) == float('inf')}  SSIM={s:.10f} (expect 1.0)")

    # 2. Known MSE: a constant offset of 0.1 on a 0..1 image gives MSE 0.01, so PSNR must be 20 dB.
    y = np.full((32, 32), 0.5)
    z = np.full((32, 32), 0.6)
    p = psnr(y, z)
    ok &= abs(p - 20.0) < 1e-9
    print(f"constant 0.1 offset   PSNR={p:.6f} dB (expect 20.000000)")

    # 3. Adding noise must lower SSIM, and more noise must lower it further. Monotonic, not just ordered.
    base = np.clip(rng.random((64, 64)) * 0.4 + 0.3, 0, 1)
    s0 = ssim(base, base)
    s1 = ssim(base, np.clip(base + rng.normal(0, 0.05, base.shape), 0, 1))
    s2 = ssim(base, np.clip(base + rng.normal(0, 0.20, base.shape), 0, 1))
    ok &= s0 > s1 > s2
    print(f"noise monotonicity    {s0:.4f} > {s1:.4f} > {s2:.4f} = {s0 > s1 > s2}")

    # 4. A blurred copy must score worse than a sharpened-to-identity case but better than pure noise,
    #    i.e. SSIM must actually track structural similarity rather than pixel difference alone.
    from numpy.lib.stride_tricks import sliding_window_view

    blurred = sliding_window_view(np.pad(base, 1, mode="reflect"), (3, 3)).mean(axis=(-2, -1))
    noise = np.clip(rng.random(base.shape), 0, 1)
    sb, sn = ssim(base, blurred), ssim(base, noise)
    ok &= sb > sn
    print(f"blur vs noise         SSIM blur={sb:.4f} noise={sn:.4f} (blur must win)")

    print("SELFTEST:", "PASS" if ok else "FAIL")
    return bool(ok)


if __name__ == "__main__":
    import sys

    sys.exit(0 if selftest() else 1)
