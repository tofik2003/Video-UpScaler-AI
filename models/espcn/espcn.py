#!/usr/bin/env python3
"""
ESPCN sub-pixel CNN — Tier 2 of the tier matrix in docs/PLAN.md §4.

Why this architecture:
  * Sub-pixel convolution (PixelShuffle) is integer-scale only, which is exactly what the tier
    design assumes. Non-integer targets are handled by upscaling 2x here and downscaling with
    Lanczos afterwards — never by a fractional network.
  * It is the most temporally deterministic of the learned options. Every output pixel is a
    deterministic function of a local neighbourhood, so it does not flicker across frames the way
    GAN-based Tier 3 does. See PLAN.md §4 and risk R2.
  * It is small enough to plausibly clear the Phase 0 gate (<=20 ms at 640x360 on a mid-ranger).

IMPORTANT — about the default training data
  Training here runs on SYNTHETIC images (gradients, shapes, stripes, checkerboards). That is
  enough to produce a model that loads, runs, and measurably beats bicubic, which is what makes it
  useful for wiring up and benchmarking Phase 2 end to end. It is NOT a shippable model.

  For a production model, train on real imagery:

      python3 models/espcn/espcn.py train --data /path/to/images --epochs 300

  and check dataset licensing first (PLAN.md §9). Record provenance in MODEL_CARD.md.
"""

from __future__ import annotations

import argparse
import pathlib
import sys

import numpy as np

# Static deployment input. Matches the Phase 0 benchmark config in PLAN.md §7 (640x360 in).
DEPLOY_H, DEPLOY_W = 360, 640


def build_espcn(scale: int, input_shape=(None, None, 3)):
    """Original ESPCN topology (Shi et al. 2016), tanh activations as in the paper."""
    import tensorflow as tf
    from tensorflow.keras import layers

    n_out = 3 * scale * scale
    return tf.keras.Sequential(
        [
            layers.Input(shape=input_shape),
            layers.Conv2D(64, 5, padding="same", activation="tanh"),
            layers.Conv2D(32, 3, padding="same", activation="tanh"),
            # r*r channels per pixel, folded into space instead of using a strided deconv.
            layers.Conv2D(n_out, 3, padding="same"),
            # Keras 3 has no DepthToSpace layer. tf.nn.depth_to_space converts cleanly to the
            # TFLite DEPTH_TO_SPACE op (verified), and unlike UpSampling2D+bilinear it is a pure
            # channel fold — no interpolation, so it cannot invent detail the conv did not predict.
            layers.Lambda(lambda x: tf.nn.depth_to_space(x, scale), name="pixel_shuffle"),
            layers.Activation("sigmoid"),  # output is 0..1 imagery
        ],
        name=f"espcn_x{scale}",
    )


# --------------------------------------------------------------------------------------------
# Synthetic training data
# --------------------------------------------------------------------------------------------

def synthetic_batch(rng: np.random.Generator, n: int, lr_size: int, scale: int):
    """Random-but-structured images: sharp edges and high-frequency content, which is what a
    sub-pixel net actually needs to learn. Returns (lr, hr) in 0..1, NHWC."""
    hr = lr_size * scale
    out = np.empty((n, hr, hr, 3), dtype=np.float32)

    for i in range(n):
        kind = int(rng.integers(0, 5))
        yy, xx = np.mgrid[0:hr, 0:hr].astype(np.float32)
        img = np.zeros((hr, hr, 3), dtype=np.float32)
        col = rng.random(3).astype(np.float32)

        if kind == 0:  # multi-stop gradient
            ang = rng.random() * np.pi
            g = (xx * np.cos(ang) + yy * np.sin(ang)) / hr
            img = g[..., None] * col[None, None, :]
        elif kind == 1:  # rectangles
            img[:] = rng.random(3).astype(np.float32)
            for _ in range(int(rng.integers(3, 9))):
                y0, x0 = rng.integers(0, hr, 2)
                h, w = rng.integers(hr // 20, hr // 4, 2)
                img[y0:y0 + h, x0:x0 + w] = rng.random(3)
        elif kind == 2:  # angled stripes (high frequency, anisotropic)
            freq = float(rng.uniform(4, 40))
            ang = rng.random() * np.pi
            ph = (xx * np.cos(ang) + yy * np.sin(ang)) / hr * freq
            img = ((np.sin(ph * 2 * np.pi) * 0.5 + 0.5))[..., None] * col[None, None, :]
        elif kind == 3:  # checkerboard
            cs = int(rng.integers(2, hr // 8))
            c = (((xx // cs) + (yy // cs)) % 2).astype(np.float32)
            img = c[..., None] * col[None, None, :] + (1 - c[..., None]) * rng.random(3)
        else:  # circles / discs
            img[:] = rng.random(3).astype(np.float32)
            for _ in range(int(rng.integers(2, 7))):
                cy, cx = rng.integers(0, hr, 2)
                r = float(rng.integers(hr // 24, hr // 6))
                m = ((xx - cx) ** 2 + (yy - cy) ** 2) <= r * r
                img[m] = rng.random(3)

        img = np.clip(img, 0.0, 1.0)
        # Slight noise: real video is never clean, and a model trained on noiseless data
        # over-sharpens on real footage.
        img = np.clip(img + rng.normal(0, 0.01, img.shape).astype(np.float32), 0.0, 1.0)
        out[i] = img

    # Downscale with bicubic to form the LR input. tf.image.resize needs a batch tensor.
    import tensorflow as tf

    lr = tf.image.resize(out, (lr_size, lr_size), method="bicubic", antialias=True).numpy()
    lr = np.clip(lr, 0.0, 1.0).astype(np.float32)
    return lr, out


def image_batches(data_dir: pathlib.Path, n: int, lr_size: int, scale: int, seed: int = 0):
    """Crop random patches out of real images. Yields (lr, hr) arrays."""
    import tensorflow as tf

    exts = {".png", ".jpg", ".jpeg", ".webp", ".bmp"}
    files = sorted(p for p in data_dir.rglob("*") if p.suffix.lower() in exts)
    if not files:
        sys.exit(f"no images found under {data_dir}")

    hr = lr_size * scale
    rng = np.random.default_rng(seed)
    while True:
        lrs, hrs = [], []
        for _ in range(n):
            raw = tf.io.read_file(str(files[int(rng.integers(0, len(files)))]))
            img = tf.image.decode_image(raw, channels=3, expand_animations=False)
            img = tf.image.convert_image_dtype(img, tf.float32)
            img = tf.image.random_crop(img, (hr, hr, 3))
            lr = tf.image.resize(img, (lr_size, lr_size), method="bicubic", antialias=True)
            lrs.append(lr.numpy())
            hrs.append(img.numpy())
        yield np.stack(lrs).astype(np.float32), np.stack(hrs).astype(np.float32)


# --------------------------------------------------------------------------------------------
# Commands
# --------------------------------------------------------------------------------------------

def _interpreter(model_path: str):
    """LiteRT interpreter. Prefers ai_edge_litert — the same runtime Android uses — over the
    tf.lite.Interpreter, which TF 2.20 has deprecated."""
    try:
        from ai_edge_litert.interpreter import Interpreter
    except ImportError:
        from tensorflow.lite import Interpreter
    return Interpreter(model_path=str(model_path))


def cmd_train(a):
    import tensorflow as tf

    rng = np.random.default_rng(a.seed)
    model = build_espcn(a.scale)
    model.compile(optimizer=tf.keras.optimizers.Adam(a.lr), loss="mae")
    model.summary(print_fn=print)

    steps = a.steps_per_epoch
    if a.data:
        gen = image_batches(pathlib.Path(a.data), a.batch, a.patch, a.scale, a.seed)
        print(f"training on real images from {a.data}")
    else:
        def gen_fn():
            while True:
                yield synthetic_batch(rng, a.batch, a.patch, a.scale)
        gen = gen_fn()
        print("WARNING: training on SYNTHETIC data — produces a wiring/benchmark model, "
              "not a shippable one. See this file's docstring.")

    model.fit(gen, steps_per_epoch=steps, epochs=a.epochs, verbose=2)

    out = pathlib.Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    model.save(out, save_format="keras")
    print(f"saved keras model -> {out}")

    # Re-attach the fixed deployment shape and export. Fully-convolutional, so the trained
    # weights transfer; only the input signature changes.
    fixed = build_espcn(a.scale, input_shape=(DEPLOY_H, DEPLOY_W, 3))
    fixed.set_weights(model.get_weights())

    conv = tf.lite.TFLiteConverter.from_keras_model(fixed)
    if a.fp16:
        # FP16 weights, FP32 compute. The GPU delegate handles this well and it halves the
        # model size versus FP32. Quantization to INT8 is deliberately not used: sub-pixel
        # convolutions are sensitive to it and the quality loss is visible on gradients.
        conv.optimizations = [tf.lite.Optimize.DEFAULT]
        conv.target_spec.supported_types = [tf.float16]
    tflite = conv.convert()

    tflite_path = out.with_suffix(".tflite")
    tflite_path.write_bytes(tflite)
    print(f"wrote {tflite_path} ({len(tflite)/1024:.1f} KB)")
    return tflite_path


def cmd_eval(a):
    """Report PSNR against bicubic — the quality half of the Phase 2 gate.

    Runs the model at its NATIVE input shape. An earlier version resized a square crop into the
    model's non-square 360x640 input, which distorted the aspect ratio before inference and made a
    working model look broken. Any harness that compares against a reference must feed the network
    the geometry it was exported for.
    """
    import tensorflow as tf

    interp = _interpreter(a.model)
    interp.allocate_tensors()
    inp, outp = interp.get_input_details()[0], interp.get_output_details()[0]
    _, h, w, _ = inp["shape"]

    # synthetic_batch only makes square patches, so generate at w*scale and crop to the model's
    # output geometry. Cropping is safe here: PSNR is computed over whatever region both images
    # actually cover, and both cover the same one.
    hr_full = synthetic_batch(np.random.default_rng(a.seed), 1, w, a.scale)[1]
    # Ground truth is the model's OUTPUT geometry (h*scale x w*scale), not its input.
    hr = hr_full[:, :h * a.scale, :w * a.scale, :]
    lr = tf.image.resize(hr, (h, w), method="bicubic", antialias=True).numpy()

    interp.set_tensor(inp["index"], lr.astype(inp["dtype"]))
    interp.invoke()
    sr = np.asarray(interp.get_tensor(outp["index"]), dtype=np.float32)

    bic = tf.image.resize(lr, (hr.shape[1], hr.shape[2]), method="bicubic").numpy()

    import sys
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2] / "tools" / "eval"))
    from quality import report

    print(f"model input {h}x{w} -> output {sr.shape[1]}x{sr.shape[2]}, "
          f"ground truth {hr.shape[1]}x{hr.shape[2]}")
    ok = report("espcn", sr[0], hr[0], baseline=bic[0])
    print("Phase 2 gate: ESPCN must beat the bicubic/Lanczos reference on BOTH metrics.")
    return ok


def cmd_bench(a):
    import time

    import tensorflow as tf

    interp = _interpreter(a.model)
    interp.allocate_tensors()
    inp, outp = interp.get_input_details()[0], interp.get_output_details()[0]
    x = np.zeros(inp["shape"], dtype=inp["dtype"])

    for _ in range(a.warmup):  # warm the caches; first-invoke is never representative
        interp.set_tensor(inp["index"], x)
        interp.invoke()

    t = []
    for _ in range(a.iters):
        s = time.perf_counter()
        interp.set_tensor(inp["index"], x)
        interp.invoke()
        t.append((time.perf_counter() - s) * 1000)

    t = np.array(t)
    print(f"host CPU, {list(inp['shape'])}, {a.iters} iters: "
          f"mean {t.mean():.1f} ms  median {np.median(t):.1f} ms  p95 {np.percentile(t,95):.1f} ms")
    print("NOTE: this is x86 CPU. The Phase 0 gate (<=20 ms) must be measured on-device, "
          "and again inside BaseGlShaderProgram.queueInputFrame to capture two-copy overhead.")


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    t = sub.add_parser("train", help="train and export a .tflite")
    t.add_argument("--scale", type=int, default=2, choices=[2, 3, 4])
    t.add_argument("--data", help="directory of real images; synthetic if omitted")
    t.add_argument("--out", default="models/espcn/espcn_x2.keras")
    t.add_argument("--epochs", type=int, default=30)
    t.add_argument("--steps-per-epoch", type=int, default=40)
    t.add_argument("--batch", type=int, default=16)
    t.add_argument("--patch", type=int, default=96, help="LR patch size")
    t.add_argument("--lr", type=float, default=1e-3)
    t.add_argument("--seed", type=int, default=1234)
    t.add_argument("--no-fp16", dest="fp16", action="store_false")
    t.set_defaults(func=cmd_train)

    e = sub.add_parser("eval", help="PSNR vs bicubic (Phase 2 quality gate)")
    e.add_argument("--model", required=True)
    e.add_argument("--scale", type=int, default=2)
    e.add_argument("--seed", type=int, default=99)
    e.set_defaults(func=cmd_eval)

    b = sub.add_parser("bench", help="host-CPU latency (not the Phase 0 gate)")
    b.add_argument("--model", required=True)
    b.add_argument("--iters", type=int, default=30)
    b.add_argument("--warmup", type=int, default=5)
    b.set_defaults(func=cmd_bench)

    args = p.parse_args()
    args.func(args)
