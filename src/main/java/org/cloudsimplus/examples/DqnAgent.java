package org.cloudsimplus.examples;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class DqnAgent {

    private final int stateDimension;
    private final int actionCount;
    private final Random random;

    private final NeuralNetwork onlineNetwork;
    private final NeuralNetwork targetNetwork;

    private final List<Transition> replayBuffer =
            new ArrayList<>();

    private static final int MAX_REPLAY_SIZE = 10_000;
    private static final int BATCH_SIZE = 32;

    private static final double GAMMA = 0.95;
    private static final double LEARNING_RATE = 0.001;

    private static final double INITIAL_EPSILON = 1.0;
    private static final double MIN_EPSILON = 0.05;
    private static final double EPSILON_DECAY = 0.995;

    private static final int TARGET_UPDATE_FREQUENCY = 100;

    private double epsilon =
            INITIAL_EPSILON;

    private int trainingStep = 0;

    public DqnAgent(
            int stateDimension,
            int actionCount,
            long seed) {

        this.stateDimension = stateDimension;
        this.actionCount = actionCount;

        this.random = new Random(seed);

        this.onlineNetwork =
                new NeuralNetwork(
                        stateDimension,
                        actionCount,
                        random);

        this.targetNetwork =
                new NeuralNetwork(
                        stateDimension,
                        actionCount,
                        random);

        targetNetwork.copyFrom(
                onlineNetwork);
    }

    public int selectAction(
            DqnState state) {

        if (random.nextDouble() < epsilon) {
            return random.nextInt(actionCount);
        }

        double[] qValues =
                onlineNetwork.predict(
                        state.getFeatures());

        return argMax(qValues);
    }

    public void recordTransition(
            DqnState state,
            int action,
            double reward,
            DqnState nextState,
            boolean done) {

        if (state == null
                || nextState == null) {
            return;
        }

        if (action < 0
                || action >= actionCount) {
            return;
        }

        if (replayBuffer.size()
                >= MAX_REPLAY_SIZE) {

            replayBuffer.remove(0);
        }

        replayBuffer.add(
                new Transition(
                        state.getFeatures(),
                        action,
                        reward,
                        nextState.getFeatures(),
                        done));

        train();
    }

    private void train() {

        if (replayBuffer.size()
                < BATCH_SIZE) {
            decayEpsilon();
            return;
        }

        for (int i = 0;
             i < BATCH_SIZE;
             i++) {

            Transition transition =
                    replayBuffer.get(
                            random.nextInt(
                                    replayBuffer.size()));

            double[] currentQ =
                    onlineNetwork.predict(
                            transition.state);

            double target;

            if (transition.done) {

                target =
                        transition.reward;

            } else {

                /*
                 * Double-DQN:
                 *
                 * 1. Online network chooses
                 *    next action.
                 *
                 * 2. Target network evaluates
                 *    that action.
                 */
                double[] nextOnlineQ =
                        onlineNetwork.predict(
                                transition.nextState);

                int bestNextAction =
                        argMax(nextOnlineQ);

                double[] nextTargetQ =
                        targetNetwork.predict(
                                transition.nextState);

                target =
                        transition.reward
                                + GAMMA
                                * nextTargetQ[
                                        bestNextAction];
            }

            currentQ[
                    transition.action] = target;

            onlineNetwork.train(
                    transition.state,
                    currentQ,
                    LEARNING_RATE);

            trainingStep++;

            if (trainingStep
                    % TARGET_UPDATE_FREQUENCY
                    == 0) {

                targetNetwork.copyFrom(
                        onlineNetwork);
            }
        }

        decayEpsilon();
    }

    private void decayEpsilon() {

        epsilon =
                Math.max(
                        MIN_EPSILON,
                        epsilon * EPSILON_DECAY);
    }

    private int argMax(
            double[] values) {

        int best = 0;

        for (int i = 1;
             i < values.length;
             i++) {

            if (values[i] > values[best]) {
                best = i;
            }
        }

        return best;
    }

    public double getEpsilon() {
        return epsilon;
    }

    private static class Transition {

        private final double[] state;
        private final int action;
        private final double reward;
        private final double[] nextState;
        private final boolean done;

        private Transition(
                double[] state,
                int action,
                double reward,
                double[] nextState,
                boolean done) {

            this.state = state.clone();
            this.action = action;
            this.reward = reward;
            this.nextState = nextState.clone();
            this.done = done;
        }
    }

    private static class NeuralNetwork {

        private final int inputSize;
        private final int outputSize;
        private final int hiddenSize;

        private final double[][] weightsInputHidden;
        private final double[] biasHidden;

        private final double[][] weightsHiddenOutput;
        private final double[] biasOutput;

        private final Random random;

        NeuralNetwork(
                int inputSize,
                int outputSize,
                Random random) {

            this.inputSize = inputSize;
            this.outputSize = outputSize;
            this.hiddenSize = 32;
            this.random = random;

            weightsInputHidden =
                    new double[
                            hiddenSize][inputSize];

            biasHidden =
                    new double[hiddenSize];

            weightsHiddenOutput =
                    new double[
                            outputSize][hiddenSize];

            biasOutput =
                    new double[outputSize];

            initialize();
        }

        private void initialize() {

            for (int i = 0;
                 i < hiddenSize;
                 i++) {

                for (int j = 0;
                     j < inputSize;
                     j++) {

                    weightsInputHidden[i][j] =
                            (random.nextDouble()
                                    - 0.5) * 0.1;
                }
            }

            for (int i = 0;
                 i < outputSize;
                 i++) {

                for (int j = 0;
                     j < hiddenSize;
                     j++) {

                    weightsHiddenOutput[i][j] =
                            (random.nextDouble()
                                    - 0.5) * 0.1;
                }
            }
        }

        double[] predict(
                double[] input) {

            double[] hidden =
                    new double[hiddenSize];

            for (int i = 0;
                 i < hiddenSize;
                 i++) {

                double sum =
                        biasHidden[i];

                for (int j = 0;
                     j < inputSize;
                     j++) {

                    sum +=
                            weightsInputHidden[i][j]
                                    * input[j];
                }

                hidden[i] =
                        Math.max(
                                0.0,
                                sum);
            }

            double[] output =
                    new double[outputSize];

            for (int i = 0;
                 i < outputSize;
                 i++) {

                double sum =
                        biasOutput[i];

                for (int j = 0;
                     j < hiddenSize;
                     j++) {

                    sum +=
                            weightsHiddenOutput[i][j]
                                    * hidden[j];
                }

                output[i] = sum;
            }

            return output;
        }

        void train(
                double[] input,
                double[] target,
                double learningRate) {

            double[] hidden =
                    new double[hiddenSize];

            double[] hiddenPreActivation =
                    new double[hiddenSize];

            for (int i = 0;
                 i < hiddenSize;
                 i++) {

                double sum =
                        biasHidden[i];

                for (int j = 0;
                     j < inputSize;
                     j++) {

                    sum +=
                            weightsInputHidden[i][j]
                                    * input[j];
                }

                hiddenPreActivation[i] =
                        sum;

                hidden[i] =
                        Math.max(0.0, sum);
            }

            double[] output =
                    new double[outputSize];

            for (int i = 0;
                 i < outputSize;
                 i++) {

                double sum =
                        biasOutput[i];

                for (int j = 0;
                     j < hiddenSize;
                     j++) {

                    sum +=
                            weightsHiddenOutput[i][j]
                                    * hidden[j];
                }

                output[i] = sum;
            }

            double[] outputError =
                    new double[outputSize];

            for (int i = 0;
                 i < outputSize;
                 i++) {

                outputError[i] =
                        output[i] - target[i];
            }

            double[] hiddenError =
                    new double[hiddenSize];

            for (int j = 0;
                 j < hiddenSize;
                 j++) {

                double error = 0.0;

                for (int i = 0;
                     i < outputSize;
                     i++) {

                    error +=
                            outputError[i]
                                    * weightsHiddenOutput[i][j];
                }

                if (hiddenPreActivation[j]
                        <= 0.0) {

                    error = 0.0;
                }

                hiddenError[j] = error;
            }

            for (int i = 0;
                 i < outputSize;
                 i++) {

                for (int j = 0;
                     j < hiddenSize;
                     j++) {

                    weightsHiddenOutput[i][j]
                            -= learningRate
                            * outputError[i]
                            * hidden[j];
                }

                biasOutput[i]
                        -= learningRate
                        * outputError[i];
            }

            for (int i = 0;
                 i < hiddenSize;
                 i++) {

                for (int j = 0;
                     j < inputSize;
                     j++) {

                    weightsInputHidden[i][j]
                            -= learningRate
                            * hiddenError[i]
                            * input[j];
                }

                biasHidden[i]
                        -= learningRate
                        * hiddenError[i];
            }
        }

        void copyFrom(
                NeuralNetwork other) {

            for (int i = 0;
                 i < hiddenSize;
                 i++) {

                biasHidden[i] =
                        other.biasHidden[i];

                for (int j = 0;
                     j < inputSize;
                     j++) {

                    weightsInputHidden[i][j] =
                            other.weightsInputHidden[i][j];
                }
            }

            for (int i = 0;
                 i < outputSize;
                 i++) {

                biasOutput[i] =
                        other.biasOutput[i];

                for (int j = 0;
                     j < hiddenSize;
                     j++) {

                    weightsHiddenOutput[i][j] =
                            other.weightsHiddenOutput[i][j];
                }
            }
        }
    }
}