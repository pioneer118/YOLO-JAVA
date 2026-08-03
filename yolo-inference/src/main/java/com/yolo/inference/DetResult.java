package com.yolo.inference;

public class DetResult {
    public int classId;
    public String className;
    public float confidence;
    public float x1, y1, x2, y2, x3, y3, x4, y4;
    // OBB parameters for pure-Java IoU
    public float cx, cy, w, h, angle;
}
