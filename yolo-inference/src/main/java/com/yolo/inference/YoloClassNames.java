package com.yolo.inference;

import java.util.List;

/**
 * 三个目标检测模型的类别名称。
 * 按模型类型区分，与 models/ 下的训练 yaml 保持一致。
 */
public final class YoloClassNames {

    /** 舰船模型类别（bestship.onnx，来自 ship.yaml，15 类） */
    public static final List<String> SHIP_CLASSES = List.of(
        "航空母舰", "驱逐舰", "护卫舰", "巡洋舰", "补给舰",
        "医疗舰", "救援舰", "运输舰", "巡逻舰", "两栖船坞登陆舰",
        "两栖攻击舰", "濒海战斗舰", "指挥舰", "扫布雷舰", "其他"
    );

    /** 飞机模型类别（bestplane.onnx，来自 plane.yaml，31 类） */
    public static final List<String> PLANE_CLASSES = List.of(
        "SU-35", "C-130", "C-17", "C-5", "F-16",
        "TU-160", "E-3", "B-52", "P-3C", "B-1B",
        "E-8", "TU-22", "F-15", "KC-135", "F-22",
        "FA-18", "TU-95", "KC-10", "SU-34", "SU-24",
        "Boeing737", "Boeing777", "Boeing747", "Boeing787", "A320",
        "A220", "A330", "A350", "C919", "ARJ21",
        "other-airplane"
    );

    /** 车辆模型类别（bestcar.onnx，来自 car.yaml，1 类） */
    public static final List<String> CAR_CLASSES = List.of("tank");

    /**
     * 根据模型类型返回对应的类别名称列表。
     */
    public static List<String> forModelType(String modelType) {
        if (modelType == null) return SHIP_CLASSES;
        return switch (modelType.toLowerCase()) {
            case "plane" -> PLANE_CLASSES;
            case "car" -> CAR_CLASSES;
            default -> SHIP_CLASSES;
        };
    }

    private YoloClassNames() {}
}
