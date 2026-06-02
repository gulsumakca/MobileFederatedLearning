package com.example.federatedapp.federated

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TF Lite softmax modeli.
 * Model: assets/news_model.tflite  (fl_server/create_model.py ile oluşturulur)
 *
 * Girdi : logits [1, 10]  — her kategori için ham skor
 * Çıktı : probs  [1, 10]  — softmax olasılıkları
 *
 * Ağırlıklar Kotlin'de tutulur; TFLite sadece softmax hesaplar.
 * Model bulunamazsa isAvailable=false → pure Kotlin fallback devreye girer.
 */
// Constructor: Sınıf oluşturulurken news_model.tflite dosyasını yüklemeye çalışır.
// Dosya bulunamazsa kendi Kotlin hesaplamalarını kullanır.
class TFLiteNewsModel(context: Context) {

    companion object {
        private const val TAG = "TFLiteNewsModel"
        private const val MODEL_FILE = "news_model.tflite"
        val NUM_CATEGORIES = CategoryPreferenceFlowerClient.KNOWN_CATEGORIES.size
    }

    private val logits = FloatArray(NUM_CATEGORIES) { 0f }

    private val interpreter: Interpreter? = runCatching {
        val buffer = loadModelBuffer(context)
        Interpreter(buffer, Interpreter.Options().apply { setNumThreads(2) })
            .also { Log.d(TAG, "TFLite model yüklendi.") }
    }.onFailure {
        Log.w(TAG, "assets/$MODEL_FILE bulunamadı — Kotlin fallback kullanılacak.")
    }.getOrNull()

    // TFLite modelinin başarıyla yüklenip yüklenmediğini kontrol eder (true/false).
    val isAvailable: Boolean get() = interpreter != null

    // Mevcut ağırlıkları (logits) kullanarak her kategori için olasılık hesaplar.
    // TFLite modeli varsa onunla, yoksa Kotlin'deki softmax() fonksiyonuyla hesaplar.
    fun infer(): FloatArray {
        val interp = interpreter ?: return softmax(logits)
        return runCatching {
            val input = arrayOf(logits.copyOf())
            val output = Array(1) { FloatArray(NUM_CATEGORIES) }
            interp.run(input, output)
            output[0]
        }.getOrElse { softmax(logits) }
    }

    // Modeldeki mevcut ağırlıkları (kategori skorlarını) döndürür.
    fun getWeights(): FloatArray = logits.copyOf()

    // Dışarıdan gelen ağırlıkları modele yükler. Değerlerin aşırı büyümemesi için
    // -10 ile 10 arasında sınırlar.
    fun setWeights(weights: FloatArray) {
        for (i in logits.indices)
            logits[i] = (weights.getOrElse(i) { 0f }).coerceIn(-10f, 10f)
    }

    // Kullanıcı bir kategoriye tıkladığında modeli eğitir. Tıklanan kategorinin puanını
    // artırır, diğerlerinin puanını biraz düşürür. Böylece model kullanıcının ilgi
    // alanlarını öğrenir. (logit -= lr * sampleWeight * (olasılık - hedef))
    fun trainStep(categoryIndex: Int, learningRate: Float = 0.1f, sampleWeight: Float = 1f) {
        val probs = infer()
        for (i in logits.indices) {
            val grad = probs[i] - if (i == categoryIndex) 1f else 0f
            logits[i] -= learningRate * sampleWeight * grad
        }
    }

    // TensorFlow Lite yorumlayıcısını kapatır ve kullanılan kaynakları serbest bırakır.
    fun close() = interpreter?.close()

    // assets klasöründeki news_model.tflite dosyasını belleğe yükler ve TFLite'ın
    // kullanabileceği hale getirir.
    private fun loadModelBuffer(context: Context): MappedByteBuffer {
        val fd = context.assets.openFd(MODEL_FILE)
        return FileInputStream(fd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
        )
    }

    // Ham skorları (logitleri) olasılıklara dönüştürür; tüm kategorilerin
    // olasılıklarının toplamı 1 olur.
    private fun softmax(w: FloatArray): FloatArray {
        val max = w.max()
        val exp = FloatArray(w.size) { Math.exp((w[it] - max).toDouble()).toFloat() }
        val sum = exp.sum()
        return FloatArray(exp.size) { exp[it] / sum }
    }
}
