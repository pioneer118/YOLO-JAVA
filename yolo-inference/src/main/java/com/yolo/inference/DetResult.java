package com.yolo.inference;

/**
 * 单个检测结果（一个"检测到的目标"）。
 *
 * <p>这是推理引擎输出的最终结果，每个对象代表"检测到一个目标"，
 * 包含目标的类别、置信度、以及旋转边界框（OBB）的位置信息。
 *
 * <p>注意：这是普通类（不是 record），字段是 public 直接访问，
 * 因为推理引擎内部频繁读写这些字段，用 public 字段最方便。
 */
public class DetResult {
    public int classId;        // 类别编号（对应模型输出的类别索引，如 0=航空母舰）
    public String className;   // 类别名称（如 "驱逐舰"）

    public float confidence;   // 置信度（0~1，越大越确定）

    // 旋转边界框的四个角点坐标（OBB，能倾斜，不是水平矩形）
    public float x1, y1;   // 左上角点
    public float x2, y2;   // 右上角点
    public float x3, y3;   // 右下角点
    public float x4, y4;   // 左下角点

    // OBB 参数（用于纯 Java 的 IoU 计算）
    public float cx, cy;    // 旋转框的中心点坐标
    public float w, h;      // 旋转框的宽和高
    public float angle;     // 旋转框的旋转角度（弧度）
}
