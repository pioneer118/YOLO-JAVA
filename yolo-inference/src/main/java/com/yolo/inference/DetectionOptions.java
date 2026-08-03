package com.yolo.inference;

public record DetectionOptions(float confidenceThreshold, float iouThreshold) {
    public DetectionOptions(float confidenceThreshold) {
        this(confidenceThreshold, 0.5f);
    }
}
