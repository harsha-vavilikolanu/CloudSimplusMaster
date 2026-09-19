package org.cloudsimplus.examples;

import java.util.Arrays;

/**
 * State representation used by the Double-DQN load-balancing agent.
 *
 * Features:
 *
 * 0 -> Mean VM CPU utilization
 * 1 -> VM CPU utilization standard deviation
 * 2 -> Waiting Cloudlet queue load
 * 3 -> Normalized remaining system workload
 * 4 -> Normalized energy rate
 * 5 -> Normalized cost rate
 * 6 -> Normalized incoming Cloudlet workload
 * 7 -> Normalized workload imbalance
 */
public class DqnState {

    public static final int STATE_DIM = 8;

    private final double[] features;

    public DqnState(
            double meanUtilization,
            double utilizationStdDev,
            double queueLoad,
            double remainingWorkload,
            double energyRate,
            double costRate,
            double incomingWorkload,
            double workloadImbalance) {

        features = new double[]{
                clamp(meanUtilization),
                clamp(utilizationStdDev),
                clamp(queueLoad),
                clamp(remainingWorkload),
                clamp(energyRate),
                clamp(costRate),
                clamp(incomingWorkload),
                clamp(workloadImbalance)
        };
    }

    public double getFeature(int index) {

        if (index < 0 || index >= STATE_DIM) {
            throw new IllegalArgumentException(
                    "Invalid feature index: " + index);
        }

        return features[index];
    }

    public double[] getFeatures() {
        return Arrays.copyOf(
                features,
                features.length);
    }

    public static int getDimension() {
        return STATE_DIM;
    }

    private static double clamp(double value) {

        if (Double.isNaN(value)
                || Double.isInfinite(value)) {
            return 0.0;
        }

        return Math.max(
                0.0,
                Math.min(
                        1.0,
                        value));
    }

    @Override
    public String toString() {
        return Arrays.toString(features);
    }
}