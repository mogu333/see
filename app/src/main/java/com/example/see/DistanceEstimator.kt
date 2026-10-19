package com.example.see

import kotlin.math.roundToInt

/**
 * 单目距离估算：根据物体在图像中的检测框宽度，反推其与摄像头的距离。
 *
 * 公式：distance = (realWidthMeters * 焦距系数) / 检测框像素宽度
 * 焦距系数用经验值 [FOCAL_FACTOR]，配合「检测框在缩放后图像坐标系」调校，
 * 适用于普通手机后摄的近似估算。
 *
 * 说明：
 * - 距离结果按合理范围裁剪（[MIN_DISTANCE_METERS]~[MAX_DISTANCE_METERS]），
 *   避免偶发小框/误检导致报出离谱距离；
 * - 无映射类别用 [DEFAULT_WIDTH_METERS] 兜底。
 */
object DistanceEstimator {

    /**
     * 焦距系数（像素）。该值对应「检测框所在图像坐标系」，即 CameraX 分析分辨率：
     * 本项目 ImageAnalysis 为 4:3 默认 640×480，对应约 60° 水平视场角时焦距 ≈ 320/tan(30°)≈554。
     * 之前的 800 偏大约 1.4 倍，会把 1.4 米外的障碍物误算成 >2 米而被行走模式静默过滤，
     * 表现为“有检测框但不播报”。这里校准为 640×480 下的经验值。
     */
    private const val FOCAL_FACTOR = 520f

    /** 无法识别类别时使用的默认物体宽度（米） */
    private const val DEFAULT_WIDTH_METERS = 0.3f

    /** 距离合理值上限（米），超过视为异常返回 -1 */
    private const val MAX_DISTANCE_METERS = 15f

    /** 距离合理值下限（米），小于视为贴脸 */
    private const val MIN_DISTANCE_METERS = 0.1f

    /** 常见物体的参考宽度，键为检测模型返回的英文标签（小写），单位米 */
    private val REFERENCE_WIDTHS: Map<String, Float> = mapOf(
        "person" to 0.45f,
        "chair" to 0.45f,
        "dining table" to 1.0f,
        "table" to 1.0f,
        "bottle" to 0.08f,
        "tv" to 1.0f,
        "book" to 0.2f,
        "cell phone" to 0.07f,
        "mobile phone" to 0.07f,
        "phone" to 0.07f,
        "laptop" to 0.35f,
        "cup" to 0.08f,
        "backpack" to 0.35f,
        "potted plant" to 0.3f,
        "keyboard" to 0.45f,
        "couch" to 1.5f,
        "bed" to 1.5f,
        "car" to 1.8f,
        "bus" to 2.5f,
        "truck" to 2.5f,
        "bicycle" to 0.5f,
        "motorcycle" to 0.7f,
        "dog" to 0.4f,
        "cat" to 0.25f,
        "umbrella" to 0.5f,
        "suitcase" to 0.4f,
        "bench" to 1.2f,
        "stop sign" to 0.6f,
        "fire hydrant" to 0.3f,
        "traffic light" to 0.3f,
        "refrigerator" to 0.6f
    )

    /**
     * 估算距离。
     *
     * @param englishLabel 检测模型返回的英文标签（大小写不敏感）
     * @param boxWidthPx   检测框在图像中的像素宽度
     * @return 距离（米，保留 1 位小数）；无法估算时返回 -1f
     */
    fun estimate(englishLabel: String, boxWidthPx: Float): Float {
        if (boxWidthPx <= 0f) return -1f
        val realWidth = REFERENCE_WIDTHS[englishLabel.lowercase()] ?: DEFAULT_WIDTH_METERS
        val distance = realWidth * FOCAL_FACTOR / boxWidthPx
        if (distance < MIN_DISTANCE_METERS) return MIN_DISTANCE_METERS
        if (distance > MAX_DISTANCE_METERS) return -1f
        return (distance * 10f).roundToInt() / 10f
    }
}
