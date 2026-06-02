"""
TF Lite Softmax Model Oluşturucu
=================================
Gereksinim: TensorFlow 2.x  (pip install tensorflow)
Çalıştır  : python create_model.py

Model mimarisi (basit, signature gerektirmez):
  Girdi : logits[1, 10]  — Kotlin'de tutulan kategori ağırlıkları
  Çıktı : probs [1, 10]  — softmax(logits) olasılıkları

Ağırlık güncelleme (gradient) Kotlin tarafında yapılır;
TFLite sadece softmax hesabını yapar.
"""

import sys
import os
import shutil

# ── Tanılama ──────────────────────────────────────────────────────────────────
print(f"Python çalıştırılabilir: {sys.executable}")
print(f"Python sürümü          : {sys.version.split()[0]}")

try:
    import tensorflow as tf
    import numpy as np
except ImportError as e:
    print(f"\nHATA: {e}")
    print("Yüklemek için: pip install tensorflow numpy")
    sys.exit(1)

print(f"TensorFlow sürümü      : {tf.__version__}")
print(f"TensorFlow konumu      : {tf.__file__}\n")

major = int(tf.__version__.split(".")[0])
if major < 2:
    print(
        f"HATA: TensorFlow 2.x gerekli (mevcut: {tf.__version__})\n"
        f"Yükseltmek için:\n"
        f"  pip install --upgrade tensorflow\n"
        f"\nDikkat: 'python' komutu farklı bir ortama işaret ediyor olabilir.\n"
        f"Şu anda kullanılan Python: {sys.executable}\n"
        f"Doğru ortamda olduğunuzdan emin olun:\n"
        f"  conda activate <ortam_adı>  veya\n"
        f"  <venv_yolu>\\Scripts\\activate"
    )
    sys.exit(1)

# ── Model tanımı ──────────────────────────────────────────────────────────────

NUM_CATEGORIES = 10
CATEGORIES = [
    "Siyaset", "Ekonomi", "Spor", "Teknoloji",
    "Bilim", "Dünya", "Doğa", "Magazin", "Yaşam", "Gündem",
]

# Basit fonksiyonel Keras modeli: logits → softmax
# Ağırlıklar Kotlin'de FloatArray olarak tutulur ve her inference'ta girilir.
inp = tf.keras.Input(shape=(NUM_CATEGORIES,), name="logits", dtype=tf.float32)
out = tf.keras.layers.Softmax(axis=-1, name="probabilities")(inp)
model = tf.keras.Model(inputs=inp, outputs=out, name="news_recommender")
model.summary()

# ── TFLite dönüşümü ───────────────────────────────────────────────────────────

converter = tf.lite.TFLiteConverter.from_keras_model(model)
converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
tflite_bytes = converter.convert()

here = os.path.dirname(os.path.abspath(__file__))
out_path = os.path.join(here, "news_model.tflite")

with open(out_path, "wb") as f:
    f.write(tflite_bytes)
print(f"\n✓ Model kaydedildi: {out_path} ({len(tflite_bytes):,} bytes)")

# assets/ klasörüne kopyala
assets_dir = os.path.join(here, "..", "app", "src", "main", "assets")
os.makedirs(assets_dir, exist_ok=True)
dest = os.path.join(assets_dir, "news_model.tflite")
shutil.copy(out_path, dest)
print(f"✓ Assets'e kopyalandı: {dest}")

# ── Doğrulama ─────────────────────────────────────────────────────────────────

interp = tf.lite.Interpreter(model_path=out_path)
interp.allocate_tensors()
inp_det = interp.get_input_details()[0]
out_det = interp.get_output_details()[0]

print(f"✓ Girdi  : {inp_det['name']} {inp_det['shape']} {inp_det['dtype']}")
print(f"✓ Çıktı  : {out_det['name']} {out_det['shape']} {out_det['dtype']}")

test = np.zeros((1, NUM_CATEGORIES), dtype=np.float32)
interp.set_tensor(inp_det["index"], test)
interp.invoke()
probs = interp.get_tensor(out_det["index"])[0]

assert abs(probs.sum() - 1.0) < 1e-4, "Softmax toplamı 1 değil!"
print(f"✓ Başlangıç çıktısı (uniform, tüm değerler ≈ {1/NUM_CATEGORIES:.4f}):")
for cat, p in zip(CATEGORIES, probs):
    print(f"    {cat:12s}: {p:.4f}")

print("\n✓ Model hazır! Artık uygulamayı build edebilirsiniz.")
