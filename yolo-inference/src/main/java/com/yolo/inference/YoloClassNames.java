package com.yolo.inference;

import java.util.List;

public final class YoloClassNames {
    public static final List<String> CLASS_NAMES = List.of(
        "航空母舰", "驱逐舰", "护卫舰", "巡洋舰", "补给舰",
        "医疗舰", "救援舰", "运输舰", "巡逻舰", "两栖船坞登陆舰",
        "两栖攻击舰", "濒海战斗舰", "指挥舰", "扫布雷舰", "其他"
    );

    private YoloClassNames() {}
}
