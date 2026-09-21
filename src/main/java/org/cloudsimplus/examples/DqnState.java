package org.cloudsimplus.examples;

import java.util.Arrays;

public class DqnState {

    public static final int STATE_DIM = 8;
    private final double[] features;

    public DqnState(
            double meanUtilization,
            double utilizationStdDev,
            double queueProgress,
            double remainingWorkloadRatio,
            double energyMetric,
            double costMetric,
            double incomingTaskRatio,
            double imbalanceMetric) {

        features = new double[]{
                clamp(meanUtilization),
                clamp(utilizationStdDev),
                clamp(queueProgress),
                clamp(remainingWorkloadRatio),
                clamp(energyMetric),
                clamp(costMetric),
                clamp(incomingTaskRatio),
                clamp(imbalanceMetric)
        };
    }

    public double getFeature(int index) {
        if (index < 0 || index >= STATE_DIM) {
            throw new IllegalArgumentException("Invalid feature index: " + index);
        }
        return features[index];
    }

    public double[] getFeatures() {
        return Arrays.copyOf(features, features.length);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    @Override
    public String toString() {
        return Arrays.toString(features);
    }
}