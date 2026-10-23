package com.nutrilens.ai.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

class ModelNotFoundException(message: String) : Exception(message)

data class Prediction(
    val label: String,
    /** Calibrated probability of [label] (temperature scaling, see ml/calibrate.py). */
    val confidence: Float,
    val top: List<Pair<String, Float>>,
    /** Calibrated probability per label, in labels.txt order. */
    val scores: Map<String, Float> = emptyMap(),
    /** L2-normalised image fingerprint from the model's pooling layer, if the model exports it. */
    val embedding: FloatArray? = null
)

/**
 * On-device food classifier backed by a TensorFlow Lite (MobileNetV3) model.
 *
 * Expected assets:
 *  - `food_model.tflite`  image classifier, NHWC input [1, H, W, 3], one score per class
 *  - `labels.txt`         one class label per line, in the model's output order
 *
 * See ml/train_model.py for a script that produces a matching model and labels file.
 */
@Singleton
class FoodClassifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var inputSize = 224
    private var inputType = DataType.FLOAT32
    private var outputType = DataType.FLOAT32
    private var classCount = 0
    private var labelOffset = 0
    private var probsOutput = 0
    private var embeddingOutput = -1
    private var embeddingSize = 0
    private var temperature = 1f
    private val lock = Any()

    private fun ensureLoaded(): Interpreter =
        interpreter ?: synchronized(lock) {
            interpreter ?: loadInterpreter().also { interpreter = it }
        }

    private fun loadInterpreter(): Interpreter {
        val labelList = try {
            context.assets.open(LABELS_FILE).bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }
        } catch (e: FileNotFoundException) {
            throw ModelNotFoundException("labels.txt was not found in app/src/main/assets.")
        }

        val model = try {
            loadModelFile()
        } catch (e: FileNotFoundException) {
            throw ModelNotFoundException(
                "The food model ($MODEL_FILE) was not found in app/src/main/assets."
            )
        }

        val interp = Interpreter(model, Interpreter.Options().apply { setNumThreads(4) })
        val inputTensor = interp.getInputTensor(0)

        // Class scores = the output with one value per label; a second output, if any, is the
        // image embedding (TFLite does not keep Keras' output order, so match by shape).
        val outputs = (0 until interp.outputTensorCount).map { it to interp.getOutputTensor(it) }
        val probs = outputs.firstOrNull { (_, t) ->
            val last = t.shape().last()
            last - labelList.size in 0..1 || (last in 100..labelList.size && last != 960)
        } ?: outputs.maxByOrNull { it.second.shape().last() }
        if (probs == null) {
            interp.close()
            throw IllegalStateException(
                "No model output matches the ${labelList.size} labels in labels.txt: " +
                    outputs.joinToString { it.second.shape().contentToString() }
            )
        }
        val outputTensor = probs.second
        val outputClasses = outputTensor.shape().last()
        val modelLabels = if (labelList.size > outputClasses) labelList.take(outputClasses) else labelList
        val offset = if (outputClasses > modelLabels.size) outputClasses - modelLabels.size else 0
        probsOutput = probs.first
        outputs.firstOrNull { it.first != probs.first }?.let { (i, t) ->
            embeddingOutput = i
            embeddingSize = t.shape().last()
        }
        temperature = readTemperature()

        inputSize = inputTensor.shape()[1]
        inputType = inputTensor.dataType()
        outputType = outputTensor.dataType()
        classCount = outputClasses
        labels = modelLabels
        labelOffset = offset
        return interp
    }

    /** Calibration temperature from assets/model_info.json; 1 (no change) if absent. */
    private fun readTemperature(): Float = runCatching {
        val json = context.assets.open(INFO_FILE).bufferedReader().use { it.readText() }
        JSONObject(json).optDouble("temperature", 1.0).toFloat()
    }.getOrDefault(1f).takeIf { it > 0f } ?: 1f

    private fun loadModelFile(): MappedByteBuffer =
        context.assets.openFd(MODEL_FILE).use { fd ->
            FileInputStream(fd.fileDescriptor).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }

    /** Loads the interpreter and runs one blank image through it (first inference is the slow one). */
    suspend fun warmUp() {
        val blank = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val started = SystemClock.elapsedRealtime()
        classify(blank)
        blank.recycle()
        Log.d(TAG, "warm-up ms=${SystemClock.elapsedRealtime() - started}")
    }

    suspend fun classify(bitmap: Bitmap): Prediction = withContext(Dispatchers.Default) {
        val interp = ensureLoaded()
        val started = SystemClock.elapsedRealtime()
        val input = preprocess(bitmap)

        val outputs = HashMap<Int, Any>()
        val quantizedOut = outputType == DataType.UINT8
        val probsOut: Any = if (quantizedOut) Array(1) { ByteArray(classCount) } else Array(1) { FloatArray(classCount) }
        outputs[probsOutput] = probsOut
        val embOut = if (embeddingOutput >= 0) Array(1) { FloatArray(embeddingSize) } else null
        if (embOut != null) outputs[embeddingOutput] = embOut
        synchronized(lock) { interp.runForMultipleInputsOutputs(arrayOf(input), outputs) }

        val probs = if (quantizedOut) {
            @Suppress("UNCHECKED_CAST")
            val out = (probsOut as Array<ByteArray>)[0]
            FloatArray(classCount) { (out[it].toInt() and 0xFF) / 255f }
        } else {
            @Suppress("UNCHECKED_CAST")
            (probsOut as Array<FloatArray>)[0]
        }
        val raw = if (needsSoftmax(probs)) softmax(probs) else probs

        // Compare this line with ml/verify_tflite.py for the same images (uncalibrated values).
        val rawTop = topIndices(raw).map { labels[it - labelOffset] to raw[it] }
        Log.d(TAG, "top3=${rawTop.joinToString { "%s=%.4f".format(Locale.US, it.first, it.second) }} " +
            "ms=${SystemClock.elapsedRealtime() - started}")

        val calibrated = calibrate(raw, temperature)
        val ranked = topIndices(calibrated).map { labels[it - labelOffset] to calibrated[it] }
        val best = ranked.first()
        Prediction(
            label = best.first,
            confidence = best.second,
            top = ranked,
            scores = (labelOffset until classCount).associate { labels[it - labelOffset] to calibrated[it] },
            embedding = embOut?.get(0)?.let(::normalize)
        )
    }

    private fun topIndices(scores: FloatArray, n: Int = 3) =
        scores.indices.filter { it >= labelOffset }.sortedByDescending { scores[it] }.take(n)

    /** Resizes to the model input size and converts pixels to the model's input format. */
    private fun preprocess(source: Bitmap): ByteBuffer {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val rgb = resizeBilinearRgb(pixels, w, h, inputSize)

        val quantized = inputType == DataType.UINT8
        val bytesPerChannel = if (quantized) 1 else 4
        val buffer = ByteBuffer
            .allocateDirect(inputSize * inputSize * 3 * bytesPerChannel)
            .order(ByteOrder.nativeOrder())

        for (v in rgb) {
            if (quantized) buffer.put(v.roundToInt().toByte())
            else buffer.putFloat((v - FLOAT_MEAN) / FLOAT_STD)
        }
        buffer.rewind()
        return buffer
    }

    private fun needsSoftmax(v: FloatArray): Boolean {
        if (v.any { it < 0f || it > 1f }) return true
        return outputType != DataType.UINT8 && abs(v.sum() - 1f) > 0.05f
    }

    private fun softmax(v: FloatArray): FloatArray {
        val maxV = v.max()
        val exps = FloatArray(v.size) { exp(v[it] - maxV) }
        val sum = exps.sum()
        return FloatArray(v.size) { exps[it] / sum }
    }

    companion object {
        private const val TAG = "FoodClassifier"
        const val INFO_FILE = "model_info.json"
        const val MODEL_FILE = "food_model.tflite"
        const val LABELS_FILE = "labels.txt"

        // Float input normalization: (pixel - MEAN) / STD.
        // The model produced by ml/train_model.py (Keras MobileNetV3) rescales internally and
        // expects raw 0..255 values, so MEAN = 0 and STD = 1.
        // For a model that expects [-1, 1] use MEAN = 127.5, STD = 127.5; for [0, 1] use 0 and 255.
        const val FLOAT_MEAN = 0f
        const val FLOAT_STD = 1f
    }
}

/**
 * Bilinear resize of ARGB [pixels] (w x h) to size x size RGB floats in 0..255, with
 * half-pixel centers and no antialiasing: the same math as tf.image.resize used in training
 * (ml/train_model.py) and ml/verify_tflite.py. Done by hand because Bitmap.createScaledBitmap
 * goes through Skia, whose filtering is unspecified and gave probabilities up to 0.2 away from
 * Python on the same photos.
 */
internal fun resizeBilinearRgb(pixels: IntArray, w: Int, h: Int, size: Int): FloatArray {
    fun axis(nIn: Int): Triple<IntArray, IntArray, FloatArray> {
        val lo = IntArray(size)
        val hi = IntArray(size)
        val frac = FloatArray(size)
        for (i in 0 until size) {
            val pos = ((i + 0.5f) * nIn / size - 0.5f).coerceIn(0f, (nIn - 1).toFloat())
            lo[i] = pos.toInt()
            hi[i] = minOf(lo[i] + 1, nIn - 1)
            frac[i] = pos - lo[i]
        }
        return Triple(lo, hi, frac)
    }
    val (y0, y1, wy) = axis(h)
    val (x0, x1, wx) = axis(w)
    val out = FloatArray(size * size * 3)
    var o = 0
    for (y in 0 until size) {
        for (x in 0 until size) {
            val tl = pixels[y0[y] * w + x0[x]]
            val tr = pixels[y0[y] * w + x1[x]]
            val bl = pixels[y1[y] * w + x0[x]]
            val br = pixels[y1[y] * w + x1[x]]
            for (shift in intArrayOf(16, 8, 0)) {
                val top = (tl shr shift and 0xFF) * (1 - wx[x]) + (tr shr shift and 0xFF) * wx[x]
                val bottom = (bl shr shift and 0xFF) * (1 - wx[x]) + (br shr shift and 0xFF) * wx[x]
                out[o++] = top * (1 - wy[y]) + bottom * wy[y]
            }
        }
    }
    return out
}

/**
 * Temperature scaling: p_i^(1/T) renormalised, i.e. softmax(log p / T). T > 1 softens
 * over-confident scores; T = 1 changes nothing. The ranking never changes.
 */
internal fun calibrate(probs: FloatArray, temperature: Float): FloatArray {
    if (temperature == 1f) return probs
    val logs = FloatArray(probs.size) { ln(max(probs[it], 1e-12f)) / temperature }
    val m = logs.max()
    val e = FloatArray(logs.size) { exp(logs[it] - m) }
    val sum = e.sum()
    return FloatArray(e.size) { e[it] / sum }
}

/** Scales [v] to unit length so dot products are cosine similarities. */
internal fun normalize(v: FloatArray): FloatArray {
    val n = sqrt(v.fold(0f) { acc, x -> acc + x * x })
    return if (n == 0f) v else FloatArray(v.size) { v[it] / n }
}
