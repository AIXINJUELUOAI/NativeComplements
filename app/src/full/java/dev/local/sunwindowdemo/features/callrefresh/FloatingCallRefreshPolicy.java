package dev.local.sunwindowdemo.features.callrefresh;

final class FloatingCallRefreshPolicy {
    private static final float CAMERA_CAP = 60f;
    private static final float TOLERANCE = 0.01f;

    private FloatingCallRefreshPolicy() {}

    static boolean isCallApp(String packageName) {
        return "com.tencent.mobileqq".equals(packageName) || "com.tencent.mm".equals(packageName);
    }

    static boolean possibleVote(float value) {
        return value > 0f && value <= CAMERA_CAP + TOLERANCE;
    }

    static float adjust(float vote, boolean minimum, float rangeMin, float rangeMax) {
        if (!possibleVote(vote) || !possibleVote(rangeMin) || rangeMin > rangeMax
                || Math.abs(rangeMax - CAMERA_CAP) > TOLERANCE || !Float.isFinite(rangeMax)) return vote;
        float bound = minimum ? rangeMin : rangeMax;
        return Math.abs(vote - bound) <= TOLERANCE ? 0f : vote;
    }
}
