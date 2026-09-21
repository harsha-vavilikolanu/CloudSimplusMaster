package org.cloudsimplus.examples;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Standard Double-DQN agent featuring separated training/evaluation modes 
 * and unbiased Xavier weight initialization.
 */
public class DqnAgent {

    private final int stateDimension;
    private final int actionCount;
    private final Random random;

    private final NeuralNetwork onlineNetwork;
    private final NeuralNetwork targetNetwork;

    private final List<Transition> replayBuffer = new ArrayList<>();

    private static final int MAX_REPLAY_SIZE = 25_000;
    private static final int BATCH_SIZE = 32; 
    private static final int MIN_REPLAY_SIZE = 128; 

    private static final double GAMMA = 0.95; 
    private static final double LEARNING_RATE = 0.001; 

    private double epsilon = 0.50; 
    private static final double MIN_EPSILON = 0.01;
    private static final double EPSILON_DECAY = 0.999;

    private static final int TARGET_UPDATE_FREQUENCY = 50;
    private static final double GRADIENT_CLIP = 1.0;

    private int trainingStep = 0;
    private boolean trainingMode = true;

    public DqnAgent(int stateDimension, int actionCount, long seed) {
        this.stateDimension = stateDimension;
        this.actionCount = actionCount;
        this.random = new Random(seed);

        this.onlineNetwork = new NeuralNetwork(stateDimension, actionCount, random);
        this.targetNetwork = new NeuralNetwork(stateDimension, actionCount, random);

        targetNetwork.copyFrom(onlineNetwork);
    }

    public void setTrainingMode(boolean trainingMode) {
        this.trainingMode = trainingMode;
        if (!trainingMode) {
            this.epsilon = 0.0; // Strictly exploit optimal learned policy during evaluation
        }
    }

    public int selectAction(DqnState state) {
        if (state == null || (trainingMode && random.nextDouble() < epsilon)) {
            return random.nextInt(actionCount);
        }
        return argMax(onlineNetwork.predict(state.getFeatures()));
    }

    public void recordTransition(DqnState state, int action, double reward, DqnState nextState, boolean done) {
        if (!trainingMode || state == null || nextState == null) return;
        
        if (Double.isNaN(reward) || Double.isInfinite(reward)) reward = 0.0;
        reward = Math.max(-10.0, Math.min(10.0, reward));

        if (replayBuffer.size() >= MAX_REPLAY_SIZE) {
            replayBuffer.remove(0);
        }

        replayBuffer.add(new Transition(state.getFeatures(), action, reward, nextState.getFeatures(), done));
        train();
    }

    private void train() {
        if (replayBuffer.size() < MIN_REPLAY_SIZE) return;

        for (int i = 0; i < BATCH_SIZE; i++) {
            final Transition transition = replayBuffer.get(random.nextInt(replayBuffer.size()));
            final double[] currentQ = onlineNetwork.predict(transition.state);
            final double target;

            if (transition.done) {
                target = transition.reward;
            } else {
                final double[] nextOnlineQ = onlineNetwork.predict(transition.nextState);
                final double[] nextTargetQ = targetNetwork.predict(transition.nextState);
                target = transition.reward + GAMMA * nextTargetQ[argMax(nextOnlineQ)];
            }

            double error = target - currentQ[transition.action];
            currentQ[transition.action] += Math.max(-GRADIENT_CLIP, Math.min(GRADIENT_CLIP, error));
            onlineNetwork.train(transition.state, currentQ, LEARNING_RATE);

            trainingStep++;
            if (trainingStep % TARGET_UPDATE_FREQUENCY == 0) {
                targetNetwork.copyFrom(onlineNetwork);
            }
        }
        epsilon = Math.max(MIN_EPSILON, epsilon * EPSILON_DECAY);
    }

    private int argMax(double[] values) {
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] > values[best]) best = i;
        }
        return best;
    }

    private static class Transition {
        final double[] state; final int action; final double reward; final double[] nextState; final boolean done;
        Transition(double[] s, int a, double r, double[] ns, boolean d) {
            state = s.clone(); action = a; reward = r; nextState = ns.clone(); done = d;
        }
    }

    private static class NeuralNetwork {
        private final int inputSize, outputSize, hiddenSize = 64;
        private final double[][] wInH, wHOut;
        private final double[] bH, bOut;

        NeuralNetwork(int in, int out, Random random) {
            inputSize = in; outputSize = out;
            wInH = new double[hiddenSize][inputSize]; 
            wHOut = new double[outputSize][hiddenSize];
            bH = new double[hiddenSize]; 
            bOut = new double[outputSize];

            // Standard, unbiased Xavier weight initialization
            double scaleIn = Math.sqrt(6.0 / (inputSize + hiddenSize));
            for (int i = 0; i < hiddenSize; i++) {
                bH[i] = 0.0;
                for (int j = 0; j < inputSize; j++) {
                    wInH[i][j] = (random.nextDouble() * 2.0 - 1.0) * scaleIn;
                }
            }

            double scaleOut = Math.sqrt(6.0 / (hiddenSize + outputSize));
            for (int i = 0; i < outputSize; i++) {
                bOut[i] = 0.0;
                for (int j = 0; j < hiddenSize; j++) {
                    wHOut[i][j] = (random.nextDouble() * 2.0 - 1.0) * scaleOut;
                }
            }
        }

        double[] predict(double[] in) {
            double[] h = new double[hiddenSize], out = new double[outputSize];
            for (int i = 0; i < hiddenSize; i++) {
                double sum = bH[i];
                for (int j = 0; j < inputSize; j++) sum += wInH[i][j] * in[j];
                h[i] = Math.max(0.0, sum);
            }
            for (int i = 0; i < outputSize; i++) {
                double sum = bOut[i];
                for (int j = 0; j < hiddenSize; j++) sum += wHOut[i][j] * h[j];
                out[i] = sum;
            }
            return out;
        }

        void train(double[] in, double[] target, double lr) {
            double[] h = new double[hiddenSize], out = predict(in);
            double[] dOut = new double[outputSize], dH = new double[hiddenSize];

            for (int i = 0; i < hiddenSize; i++) {
                double sum = bH[i];
                for (int j = 0; j < inputSize; j++) sum += wInH[i][j] * in[j];
                h[i] = Math.max(0.0, sum);
            }
            for (int i = 0; i < outputSize; i++) {
                dOut[i] = Math.max(-GRADIENT_CLIP, Math.min(GRADIENT_CLIP, out[i] - target[i]));
            }
            for (int j = 0; j < hiddenSize; j++) {
                double err = 0.0;
                for (int i = 0; i < outputSize; i++) err += dOut[i] * wHOut[i][j];
                dH[j] = h[j] > 0.0 ? Math.max(-GRADIENT_CLIP, Math.min(GRADIENT_CLIP, err)) : 0.0;
            }
            for (int i = 0; i < outputSize; i++) {
                for (int j = 0; j < hiddenSize; j++) wHOut[i][j] -= lr * dOut[i] * h[j];
                bOut[i] -= lr * dOut[i];
            }
            for (int i = 0; i < hiddenSize; i++) {
                for (int j = 0; j < inputSize; j++) wInH[i][j] -= lr * dH[i] * in[j];
                bH[i] -= lr * dH[i];
            }
        }

        void copyFrom(NeuralNetwork o) {
            for (int i = 0; i < hiddenSize; i++) { 
                bH[i] = o.bH[i]; 
                System.arraycopy(o.wInH[i], 0, wInH[i], 0, inputSize); 
            }
            for (int i = 0; i < outputSize; i++) { 
                bOut[i] = o.bOut[i]; 
                System.arraycopy(o.wHOut[i], 0, wHOut[i], 0, hiddenSize); 
            }
        }
    }
}