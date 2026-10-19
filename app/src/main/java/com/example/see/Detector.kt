package com.example.see

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * TFLite 本地物体检测器（EfficientDet-Lite0 原始输出版）。
 *
 * 模型：assets/models/yolov8n.tflite（实际为 EfficientDet-Lite0 320x320，COCO 90 类标签图）
 * 输入：serving_default_images:0 → [1, 320, 320, 3]，float32，像素归一化到 [0,1]
 * 输出（原始 anchor，无内置 NMS/解码）：
 *   - boxes  [1, 19206, 4] 相对 anchor 的编码值 (ty, tx, th, tw)，不是绝对坐标
 *   - scores [1, 19206, 90] 每个 anchor 对 90 个类别的概率得分
 *
 * 因此需要：先按 anchor 解码框 → 按得分阈值过滤 → NMS 抑制重叠框。
 * 线程模型：非线程安全，仅在单线程分析线程上调用 detect()。
 */
class Detector(context: Context) {

    /** 单条检测结果：英文类别名 + 图像像素坐标框 + 置信度 */
    data class Detection(val label: String, val box: RectF, val score: Float)

    private val interpreter: Interpreter

    /** 模型输入边长（本模型为 320） */
    private val inputSize: Int

    /** anchor 数量（本模型为 19206） */
    private val numAnchors: Int

    /** 类别数（本模型为 90） */
    private val numClasses: Int

    private val boxesOutputIndex: Int
    private val scoresOutputIndex: Int

    // 复用的输入/输出缓冲，避免每帧分配导致 GC 抖动
    private val inputBuffer: ByteBuffer
    private val scaledBitmap: Bitmap
    private val scaledCanvas: Canvas
    private val pixels: IntArray
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dstRect: Rect

    private val boxesOutput: Array<Array<FloatArray>>
    private val scoresOutput: Array<Array<FloatArray>>
    private val outputs: MutableMap<Int, Any>

    /** 预生成的 anchor（归一化）：中心 y/x 与 高/宽，与模型输出的 anchor 顺序一一对应 */
    private val anchorCenterY: FloatArray
    private val anchorCenterX: FloatArray
    private val anchorH: FloatArray
    private val anchorW: FloatArray

    init {
        val modelFile = loadModel(context, MODEL_ASSET)
        interpreter = Interpreter(
            modelFile,
            Interpreter.Options().apply { setNumThreads(4) }
        )

        val inputShape = interpreter.getInputTensor(0).shape()
        inputSize = inputShape[1]

        // 按最后一维区分 boxes(4) 与 scores(类别数)
        var boxesIdx = -1
        var scoresIdx = -1
        for (i in 0 until interpreter.outputTensorCount) {
            val shape = interpreter.getOutputTensor(i).shape()
            if (shape.size < 3) continue
            if (shape[shape.size - 1] == 4) boxesIdx = i else scoresIdx = i
        }
        require(boxesIdx >= 0 && scoresIdx >= 0) { "模型输出不匹配：需要 boxes[1,N,4] 与 scores[1,N,C]" }
        boxesOutputIndex = boxesIdx
        scoresOutputIndex = scoresIdx

        numAnchors = interpreter.getOutputTensor(boxesOutputIndex).shape()[1]
        numClasses = interpreter.getOutputTensor(scoresOutputIndex).shape()[2]

        inputBuffer = ByteBuffer
            .allocateDirect(1 * inputSize * inputSize * 3 * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
        scaledBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        scaledCanvas = Canvas(scaledBitmap)
        pixels = IntArray(inputSize * inputSize)
        dstRect = Rect(0, 0, inputSize, inputSize)

        boxesOutput = Array(1) { Array(numAnchors) { FloatArray(4) } }
        scoresOutput = Array(1) { Array(numAnchors) { FloatArray(numClasses) } }

        outputs = HashMap(2)
        outputs[boxesOutputIndex] = boxesOutput
        outputs[scoresOutputIndex] = scoresOutput

        anchorCenterY = FloatArray(numAnchors)
        anchorCenterX = FloatArray(numAnchors)
        anchorH = FloatArray(numAnchors)
        anchorW = FloatArray(numAnchors)
        buildAnchors()

        Log.i(TAG, "模型加载完成 input=$inputSize anchors=$numAnchors classes=$numClasses")
    }

    /**
     * 对一帧位图做一次检测。
     *
     * @param bitmap 已按显示方向旋转摆正的位图
     * @return 过滤 + NMS 后的检测结果，box 为该位图像素坐标
     */
    fun detect(bitmap: Bitmap): List<Detection> {
        // 1) 预处理：缩放到输入尺寸并归一化写入输入张量
        scaledCanvas.drawBitmap(bitmap, null, dstRect, bitmapPaint)
        scaledBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        inputBuffer.rewind()
        for (pixel in pixels) {
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
            inputBuffer.putFloat((pixel and 0xFF) / 255f)
        }
        inputBuffer.rewind()

        // 2) 推理
        // 本模型有两个输出（boxes 与 scores），必须用 runForMultipleInputsOutputs：
        // interpreter.run(input, output) 只能处理「单输入单输出」，传入 Map 会被误当成
        // 单个输出张量导致类型不匹配抛异常（异常又被 detectFrame 吞掉，表现为完全不识别）。
        // 虽然该 API 标记为 deprecated，但多输出模型当前仍以它为准，正确性优先。
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(inputBuffer), outputs)

        val boxes = boxesOutput[0]
        val scores = scoresOutput[0]

        // 3) 每个 anchor 取最高分类别，按阈值过滤；得分已是概率，无需 sigmoid
        val frameWidth = bitmap.width.toFloat()
        val frameHeight = bitmap.height.toFloat()
        val candidates = ArrayList<Detection>(INITIAL_CAPACITY)
        for (anchor in 0 until numAnchors) {
            val row = scores[anchor]
            var bestClass = -1
            var bestScore = 0f
            for (c in 0 until numClasses) {
                val s = row[c]
                if (s > bestScore) {
                    bestScore = s
                    bestClass = c
                }
            }
            if (bestClass < 0 || bestScore < SCORE_THRESHOLD) continue

            val name = COCO_LABELS_90.getOrNull(bestClass) ?: continue
            if (name == PLACEHOLDER) continue

            // 4) 按 anchor 解码：ty/tx 是中心相对 anchor 的偏移，th/tw 是对数尺寸比
            val b = boxes[anchor]
            val ah = anchorH[anchor]
            val aw = anchorW[anchor]
            val centerY = b[0] * ah + anchorCenterY[anchor]
            val centerX = b[1] * aw + anchorCenterX[anchor]
            val h = kotlin.math.exp(b[2].toDouble()).toFloat() * ah
            val w = kotlin.math.exp(b[3].toDouble()).toFloat() * aw

            // 归一化坐标 → 位图像素坐标（超出画面部分裁剪掉）
            val left = (centerX - w / 2f).coerceIn(0f, 1f) * frameWidth
            val top = (centerY - h / 2f).coerceIn(0f, 1f) * frameHeight
            val right = (centerX + w / 2f).coerceIn(0f, 1f) * frameWidth
            val bottom = (centerY + h / 2f).coerceIn(0f, 1f) * frameHeight
            if (right - left <= 0f || bottom - top <= 0f) continue

            candidates.add(Detection(name, RectF(left, top, right, bottom), bestScore))
        }

        if (candidates.isEmpty()) return emptyList()

        // 5) 按得分降序做 NMS
        candidates.sortByDescending { it.score }
        val kept = ArrayList<Detection>(MAX_RESULTS)
        for (candidate in candidates) {
            if (kept.size >= MAX_RESULTS) break
            var overlapped = false
            for (k in kept) {
                if (iou(k.box, candidate.box) > NMS_IOU_THRESHOLD) {
                    overlapped = true
                    break
                }
            }
            if (!overlapped) kept.add(candidate)
        }
        return kept
    }

    /** 释放解释器资源 */
    fun close() {
        try {
            interpreter.close()
        } catch (e: Exception) {
            Log.e(TAG, "释放解释器异常", e)
        }
    }

    /** 把 assets 中的模型拷贝到 cache（已存在则跳过），再用文件加载 */
    private fun loadModel(context: Context, assetPath: String): File {
        val out = File(context.cacheDir, assetPath.substringAfterLast('/'))
        if (!out.exists() || out.length() == 0L) {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
        }
        return out
    }

    /**
     * 预生成 EfficientDet 的 anchor（归一化到 [0,1]），与模型训练时的 AnchorGenerator 一致：
     * level 3..7（stride 8/16/32/64/128），每格 3 种尺度（anchor_scale * stride * 2^(i/3)）
     * × 3 种长宽比（1:1、2:1、1:2）。
     *
     * 遍历顺序（必须与模型输出的 anchor 顺序一致）：level 升序 → 行 → 列 → 尺度 → 长宽比。
     */
    private fun buildAnchors() {
        val size = inputSize.toFloat()
        var index = 0
        for (level in MIN_LEVEL..MAX_LEVEL) {
            val stride = 1 shl level
            val featureH = (inputSize + stride - 1) / stride
            val featureW = featureH
            for (row in 0 until featureH) {
                val centerY = (row + 0.5f) * stride
                for (col in 0 until featureW) {
                    val centerX = (col + 0.5f) * stride
                    for (i in 0 until NUM_SCALES) {
                        val octave = 2.0.pow(i.toDouble() / NUM_SCALES).toFloat()
                        for (aspect in ASPECT_RATIOS) {
                            val anchorSize = ANCHOR_SCALE * stride * octave
                            val h = anchorSize / kotlin.math.sqrt(aspect)
                            val w = anchorSize * kotlin.math.sqrt(aspect)
                            anchorCenterY[index] = centerY / size
                            anchorCenterX[index] = centerX / size
                            anchorH[index] = h / size
                            anchorW[index] = w / size
                            index++
                        }
                    }
                }
            }
        }
        check(index == numAnchors) { "anchor 数量不匹配：生成 $index 个，模型 $numAnchors 个" }
    }

    /** 两个矩形框的交并比 */
    private fun iou(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val interW = right - left
        val interH = bottom - top
        if (interW <= 0f || interH <= 0f) return 0f
        val inter = interW * interH
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    companion object {
        private const val TAG = "Detector"

        /** 模型资源路径（文件名为 yolov8n.tflite，内容为 EfficientDet-Lite0 320x320） */
        private const val MODEL_ASSET = "models/yolov8n.tflite"

        private const val FLOAT_BYTES = 4

        /** 置信度阈值 */
        private const val SCORE_THRESHOLD = 0.4f

        /** NMS 交并比阈值 */
        private const val NMS_IOU_THRESHOLD = 0.45f

        /** 单帧最多返回的检测数量 */
        private const val MAX_RESULTS = 10

        private const val INITIAL_CAPACITY = 32

        /** 标签图中的空位 */
        private const val PLACEHOLDER = "???"

        // ---- EfficientDet anchor 生成参数（必须与模型训练配置一致） ----
        private const val MIN_LEVEL = 3
        private const val MAX_LEVEL = 7
        private const val NUM_SCALES = 3
        private const val ANCHOR_SCALE = 4f
        private val ASPECT_RATIOS = floatArrayOf(1f, 2f, 0.5f)

        /** COCO-90 标签图（含 ??? 空位，索引与模型输出类别一一对应） */
        val COCO_LABELS_90: Array<String> = arrayOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
            "boat", "traffic light", "fire hydrant", "???", "stop sign", "parking meter", "bench",
            "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe",
            "???", "backpack", "umbrella", "???", "???", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard",
            "surfboard", "tennis racket", "bottle", "???", "wine glass", "cup", "fork", "knife",
            "spoon", "bowl", "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog",
            "pizza", "donut", "cake", "chair", "couch", "potted plant", "bed", "???", "dining table",
            "???", "???", "toilet", "???", "tv", "laptop", "mouse", "remote", "keyboard",
            "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "???", "book",
            "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
        )
    }
}
