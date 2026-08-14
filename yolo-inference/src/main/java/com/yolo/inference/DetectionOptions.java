package com.yolo.inference;

/**
 * 检测参数（不可变的配置对象）。
 *
 * <p>用 Java 的 record 定义（record = 自动生成构造器/getter/equals 的不可变类）。
 * 它封装了检测时用到的两个参数：
 * - confidenceThreshold：置信度阈值（低于它的检测被过滤）
 * - iouThreshold：IoU 阈值（NMS 去重时，重叠超过它的框被抑制）
 */
public record DetectionOptions(float confidenceThreshold, float iouThreshold) {   // record 定义两个字段

    /**
     * 便捷构造方法：只传置信度阈值，IoU 阈值用默认值 0.5。
     *
     * @param confidenceThreshold 置信度阈值（如 0.5 = 低于 50% 置信度的检测被丢弃）
     */
    public DetectionOptions(float confidenceThreshold) {
        this(confidenceThreshold, 0.4f);   // 调用主构造器，IoU 阈值默认 0.4（旋转框角度差异会压低 IoU，故用更激进的阈值去重）
    }
}
