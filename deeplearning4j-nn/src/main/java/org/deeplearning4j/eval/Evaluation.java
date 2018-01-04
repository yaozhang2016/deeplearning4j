/*-
 *
 *  * Copyright 2015 Skymind,Inc.
 *  *
 *  *    Licensed under the Apache License, Version 2.0 (the "License");
 *  *    you may not use this file except in compliance with the License.
 *  *    You may obtain a copy of the License at
 *  *
 *  *        http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *    Unless required by applicable law or agreed to in writing, software
 *  *    distributed under the License is distributed on an "AS IS" BASIS,
 *  *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *    See the License for the specific language governing permissions and
 *  *    limitations under the License.
 *
 */

package org.deeplearning4j.eval;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.eval.meta.Prediction;
import org.deeplearning4j.eval.serde.ConfusionMatrixDeserializer;
import org.deeplearning4j.eval.serde.ConfusionMatrixSerializer;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.impl.accum.MatchCondition;
import org.nd4j.linalg.api.ops.impl.transforms.Not;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.conditions.Conditions;
import org.nd4j.linalg.lossfunctions.serde.RowVectorDeserializer;
import org.nd4j.linalg.lossfunctions.serde.RowVectorSerializer;
import org.nd4j.linalg.primitives.Counter;
import org.nd4j.linalg.primitives.Pair;
import org.nd4j.shade.jackson.annotation.JsonIgnoreProperties;
import org.nd4j.shade.jackson.databind.annotation.JsonDeserialize;
import org.nd4j.shade.jackson.databind.annotation.JsonSerialize;

import java.io.Serializable;
import java.text.DecimalFormat;
import java.util.*;

/**
 * Evaluation metrics:<br>
 * - precision, recall, f1, fBeta, accuracy, Matthews correlation coefficient, gMeasure<br>
 * - Top N accuracy (if using constructor {@link #Evaluation(List, int)})<br>
 * - Custom binary evaluation decision threshold (use constructor {@link #Evaluation(double)} (default if not set is
 *   argmax / 0.5)<br>
 * - Custom cost array, using {@link #Evaluation(INDArray)} or {@link #Evaluation(List, INDArray)} for multi-class <br>
 * <br>
 * Note that setting a custom binary decision threshold is only possible for the binary case (1 or 2 outputs) and cannot
 * be used if the number of classes exceeds 2. Predictions with probablity > threshold are considered to be class 1,
 * and are considered class 0 otherwise.<br>
 * <br>
 * Cost arrays (a row vector, of size equal to the number of outputs) modify the evaluation process: instead of simply
 * doing predictedClass = argMax(probabilities), we do predictedClass = argMax(cost * probabilities). Consequently, an
 * array of all 1s (or, indeed any array of equal values) will result in the same performance as no cost array; non-
 * equal values will bias the predictions for or against certain classes.
 *
 * @author Adam Gibson
 */
@Slf4j
@EqualsAndHashCode(callSuper = true)
@Getter
@Setter
@JsonIgnoreProperties({"confusionMatrixMetaData"})
public class Evaluation extends BaseEvaluation<Evaluation> {
    //What to output from the precision/recall function when we encounter an edge case
    protected static final double DEFAULT_EDGE_VALUE = 0.0;

    protected final int topN;
    protected int topNCorrectCount = 0;
    protected int topNTotalCount = 0; //Could use topNCountCorrect / (double)getNumRowCounter() - except for eval(int,int), hence separate counters
    protected Counter<Integer> truePositives = new Counter<>();
    protected Counter<Integer> falsePositives = new Counter<>();
    protected Counter<Integer> trueNegatives = new Counter<>();
    protected Counter<Integer> falseNegatives = new Counter<>();
    @JsonSerialize(using = ConfusionMatrixSerializer.class)
    @JsonDeserialize(using = ConfusionMatrixDeserializer.class)
    protected ConfusionMatrix<Integer> confusion;
    protected int numRowCounter = 0;
    @Getter
    @Setter
    protected List<String> labelsList = new ArrayList<>();

    protected Double binaryDecisionThreshold;
    @JsonSerialize(using = RowVectorSerializer.class)
    @JsonDeserialize(using = RowVectorDeserializer.class)
    protected INDArray costArray;

    protected Map<Pair<Integer, Integer>, List<Object>> confusionMatrixMetaData; //Pair: (Actual,Predicted)

    // Empty constructor
    public Evaluation() {
        this.topN = 1;
    }

    /**
     * The number of classes to account
     * for in the evaluation
     * @param numClasses the number of classes to account for in the evaluation
     */
    public Evaluation(int numClasses) {
        this(createLabels(numClasses), 1);
    }

    /**
     * The labels to include with the evaluation.
     * This constructor can be used for
     * generating labeled output rather than just
     * numbers for the labels
     * @param labels the labels to use
     *               for the output
     */
    public Evaluation(List<String> labels) {
        this(labels, 1);
    }

    /**
     * Use a map to generate labels
     * Pass in a label index with the actual label
     * you want to use for output
     * @param labels a map of label index to label value
     */
    public Evaluation(Map<Integer, String> labels) {
        this(createLabelsFromMap(labels), 1);
    }

    /**
     * Constructor to use for top N accuracy
     *
     * @param labels Labels for the classes (may be null)
     * @param topN   Value to use for top N accuracy calculation (<=1: standard accuracy). Note that with top N
     *               accuracy, an example is considered 'correct' if the probability for the true class is one of the
     *               highest N values
     */
    public Evaluation(List<String> labels, int topN) {
        this.labelsList = labels;
        if (labels != null) {
            createConfusion(labels.size());
        }
        this.topN = topN;
    }

    /**
     * Create an evaluation instance with a custom binary decision threshold. Note that binary decision thresholds can
     * only be used with binary classifiers.
     *
     * @param binaryDecisionThreshold Decision threshold to use for binary predictions
     */
    public Evaluation(double binaryDecisionThreshold) {
        this.binaryDecisionThreshold = binaryDecisionThreshold;
        this.topN = 1;
    }

    /**
     *  Created evaluation instance with the specified cost array. A cost array can be used to bias the multi class
     *  predictions towards or away from certain classes. The predicted class is determined using argMax(cost * probability)
     *  instead of argMax(probability) when no cost array is present.
     *
     * @param costArray Row vector cost array. May be null
     */
    public Evaluation(INDArray costArray) {
        this(null, costArray);
    }

    /**
     *  Created evaluation instance with the specified cost array. A cost array can be used to bias the multi class
     *  predictions towards or away from certain classes. The predicted class is determined using argMax(cost * probability)
     *  instead of argMax(probability) when no cost array is present.
     *
     * @param labels Labels for the output classes. May be null
     * @param costArray Row vector cost array. May be null
     */
    public Evaluation(List<String> labels, INDArray costArray) {
        if (costArray != null && !costArray.isRowVector()) {
            throw new IllegalArgumentException("Invalid cost array: must be a row vector (got shape: "
                            + Arrays.toString(costArray.shape()) + ")");
        }
        if (costArray != null && costArray.minNumber().doubleValue() < 0.0) {
            throw new IllegalArgumentException("Invalid cost array: Cost array values must be positive");
        }
        this.labelsList = labels;
        this.costArray = costArray;
        this.topN = 1;
    }

    @Override
    public void reset() {
        confusion = null;
        truePositives = new Counter<>();
        falsePositives = new Counter<>();
        trueNegatives = new Counter<>();
        falseNegatives = new Counter<>();

        topNCorrectCount = 0;
        topNTotalCount = 0;
        numRowCounter = 0;
    }

    private ConfusionMatrix<Integer> confusion() {
        return confusion;
    }

    private static List<String> createLabels(int numClasses) {
        if (numClasses == 1)
            numClasses = 2; //Binary (single output variable) case...
        List<String> list = new ArrayList<>(numClasses);
        for (int i = 0; i < numClasses; i++) {
            list.add(String.valueOf(i));
        }
        return list;
    }

    private static List<String> createLabelsFromMap(Map<Integer, String> labels) {
        int size = labels.size();
        List<String> labelsList = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String str = labels.get(i);
            if (str == null)
                throw new IllegalArgumentException("Invalid labels map: missing key for class " + i
                                + " (expect integers 0 to " + (size - 1) + ")");
            labelsList.add(str);
        }
        return labelsList;
    }

    private void createConfusion(int nClasses) {
        List<Integer> classes = new ArrayList<>();
        for (int i = 0; i < nClasses; i++) {
            classes.add(i);
        }

        confusion = new ConfusionMatrix<>(classes);
    }


    /**
     * Evaluate the output
     * using the given true labels,
     * the input to the multi layer network
     * and the multi layer network to
     * use for evaluation
     * @param trueLabels the labels to ise
     * @param input the input to the network to use
     *              for evaluation
     * @param network the network to use for output
     */
    public void eval(INDArray trueLabels, INDArray input, ComputationGraph network) {
        eval(trueLabels, network.output(false, input)[0]);
    }


    /**
     * Evaluate the output
     * using the given true labels,
     * the input to the multi layer network
     * and the multi layer network to
     * use for evaluation
     * @param trueLabels the labels to ise
     * @param input the input to the network to use
     *              for evaluation
     * @param network the network to use for output
     */
    public void eval(INDArray trueLabels, INDArray input, MultiLayerNetwork network) {
        eval(trueLabels, network.output(input, Layer.TrainingMode.TEST));
    }


    /**
     * Collects statistics on the real outcomes vs the
     * guesses. This is for logistic outcome matrices.
     * <p>
     * Note that an IllegalArgumentException is thrown if the two passed in
     * matrices aren't the same length.
     *
     * @param realOutcomes the real outcomes (labels - usually binary)
     * @param guesses      the guesses/prediction (usually a probability vector)
     */
    public void eval(INDArray realOutcomes, INDArray guesses) {
        eval(realOutcomes, guesses, (List<Serializable>) null);
    }

    /**
     * Evaluate the network, with optional metadata
     *
     * @param realOutcomes   Data labels
     * @param guesses        Network predictions
     * @param recordMetaData Optional; may be null. If not null, should have size equal to the number of outcomes/guesses
     *
     */
    @Override
    public void eval(final INDArray realOutcomes, final INDArray guesses,
                    final List<? extends Serializable> recordMetaData) {
        // Add the number of rows to numRowCounter
        numRowCounter += realOutcomes.size(0);

        // If confusion is null, then Evaluation was instantiated without providing the classes -> infer # classes from
        if (confusion == null) {
            int nClasses = realOutcomes.columns();
            if (nClasses == 1)
                nClasses = 2; //Binary (single output variable) case
            labelsList = new ArrayList<>(nClasses);
            for (int i = 0; i < nClasses; i++)
                labelsList.add(String.valueOf(i));
            createConfusion(nClasses);
        }

        // Length of real labels must be same as length of predicted labels
        if (realOutcomes.length() != guesses.length())
            throw new IllegalArgumentException("Unable to evaluate. Outcome matrices not same length");

        // For each row get the most probable label (column) from prediction and assign as guessMax
        // For each row get the column of the true label and assign as currMax

        final int nCols = realOutcomes.columns();
        final int nRows = realOutcomes.rows();

        if (nCols == 1) {
            INDArray binaryGuesses = guesses.gt(binaryDecisionThreshold == null ? 0.5 : binaryDecisionThreshold);

            INDArray notLabel = Nd4j.getExecutioner().execAndReturn(new Not(realOutcomes.dup()));
            INDArray notGuess = Nd4j.getExecutioner().execAndReturn(new Not(binaryGuesses.dup()));
            //tp: predicted = 1, actual = 1
            int tp = binaryGuesses.mul(realOutcomes).sumNumber().intValue();
            //fp: predicted = 1, actual = 0
            int fp = binaryGuesses.mul(notLabel).sumNumber().intValue();
            //fn: predicted = 0, actual = 1
            int fn = notGuess.mul(realOutcomes).sumNumber().intValue();
            int tn = nRows - tp - fp - fn;

            confusion().add(1, 1, tp);
            confusion().add(1, 0, fn);
            confusion().add(0, 1, fp);
            confusion().add(0, 0, tn);

            truePositives.incrementCount(1, tp);
            falsePositives.incrementCount(1, fp);
            falseNegatives.incrementCount(1, fn);
            trueNegatives.incrementCount(1, tn);

            truePositives.incrementCount(0, tn);
            falsePositives.incrementCount(0, fn);
            falseNegatives.incrementCount(0, fp);
            trueNegatives.incrementCount(0, tp);

            if (recordMetaData != null) {
                for (int i = 0; i < binaryGuesses.size(0); i++) {
                    if (i >= recordMetaData.size())
                        break;
                    int actual = realOutcomes.getDouble(0) == 0.0 ? 0 : 1;
                    int predicted = binaryGuesses.getDouble(0) == 0.0 ? 0 : 1;
                    addToMetaConfusionMatrix(actual, predicted, recordMetaData.get(i));
                }
            }

        } else {
            INDArray guessIndex;
            if (binaryDecisionThreshold != null) {
                if (nCols != 2) {
                    throw new IllegalStateException("Binary decision threshold is set, but number of columns for "
                                    + "predictions is " + nCols
                                    + ". Binary decision threshold can only be used for binary " + "prediction cases");
                }

                INDArray pClass1 = guesses.getColumn(1);
                guessIndex = pClass1.gt(binaryDecisionThreshold);
            } else if (costArray != null) {
                //With a cost array: do argmax(cost * probability) instead of just argmax(probability)
                guessIndex = Nd4j.argMax(guesses.mulRowVector(costArray), 1);
            } else {
                //Standard case: argmax
                guessIndex = Nd4j.argMax(guesses, 1);
            }
            INDArray realOutcomeIndex = Nd4j.argMax(realOutcomes, 1);
            int nExamples = guessIndex.length();

            for (int i = 0; i < nExamples; i++) {
                int actual = (int) realOutcomeIndex.getDouble(i);
                int predicted = (int) guessIndex.getDouble(i);
                confusion().add(actual, predicted);

                if (recordMetaData != null && recordMetaData.size() > i) {
                    Object m = recordMetaData.get(i);
                    addToMetaConfusionMatrix(actual, predicted, m);
                }

                // instead of looping through each label for confusion
                // matrix, instead infer those values by determining if true/false negative/positive,
                // then just add across matrix

                // if actual == predicted, then it's a true positive, assign true negative to every other label
                if (actual == predicted) {
                    truePositives.incrementCount(actual, 1);
                    for (int col = 0; col < nCols; col++) {
                        if (col == actual) {
                            continue;
                        }
                        trueNegatives.incrementCount(col, 1); // all cols prior
                    }
                } else {
                    falsePositives.incrementCount(predicted, 1);
                    falseNegatives.incrementCount(actual, 1);

                    // first determine intervals for adding true negatives
                    int lesserIndex, greaterIndex;
                    if (actual < predicted) {
                        lesserIndex = actual;
                        greaterIndex = predicted;
                    } else {
                        lesserIndex = predicted;
                        greaterIndex = actual;
                    }

                    // now loop through intervals
                    for (int col = 0; col < lesserIndex; col++) {
                        trueNegatives.incrementCount(col, 1); // all cols prior
                    }
                    for (int col = lesserIndex + 1; col < greaterIndex; col++) {
                        trueNegatives.incrementCount(col, 1); // all cols after
                    }
                    for (int col = greaterIndex + 1; col < nCols; col++) {
                        trueNegatives.incrementCount(col, 1); // all cols after
                    }
                }
            }
        }

        if (nCols > 1 && topN > 1) {
            //Calculate top N accuracy
            //TODO: this could be more efficient
            INDArray realOutcomeIndex = Nd4j.argMax(realOutcomes, 1);
            int nExamples = realOutcomeIndex.length();
            for (int i = 0; i < nExamples; i++) {
                int labelIdx = (int) realOutcomeIndex.getDouble(i);
                double prob = guesses.getDouble(i, labelIdx);
                INDArray row = guesses.getRow(i);
                int countGreaterThan = (int) Nd4j.getExecutioner()
                                .exec(new MatchCondition(row, Conditions.greaterThan(prob)), Integer.MAX_VALUE)
                                .getDouble(0);
                if (countGreaterThan < topN) {
                    //For example, for top 3 accuracy: can have at most 2 other probabilities larger
                    topNCorrectCount++;
                }
                topNTotalCount++;
            }
        }
    }

    /**
     * Evaluate a single prediction (one prediction at a time)
     *
     * @param predictedIdx Index of class predicted by the network
     * @param actualIdx    Index of actual class
     */
    public void eval(int predictedIdx, int actualIdx) {
        // Add the number of rows to numRowCounter
        numRowCounter++;

        // If confusion is null, then Evaluation is instantiated without providing the classes
        if (confusion == null) {
            throw new UnsupportedOperationException(
                            "Cannot evaluate single example without initializing confusion matrix first");
        }

        addToConfusion(actualIdx, predictedIdx);

        // If they are equal
        if (predictedIdx == actualIdx) {
            // Then add 1 to True Positive
            // (For a particular label)
            incrementTruePositives(predictedIdx);

            // And add 1 for each negative class that is accurately predicted (True Negative)
            //(For a particular label)
            for (Integer clazz : confusion().getClasses()) {
                if (clazz != predictedIdx)
                    trueNegatives.incrementCount(clazz, 1.0f);
            }
        } else {
            // Otherwise the real label is predicted as negative (False Negative)
            incrementFalseNegatives(actualIdx);
            // Otherwise the prediction is predicted as falsely positive (False Positive)
            incrementFalsePositives(predictedIdx);
            // Otherwise true negatives
            for (Integer clazz : confusion().getClasses()) {
                if (clazz != predictedIdx && clazz != actualIdx)
                    trueNegatives.incrementCount(clazz, 1.0f);

            }
        }
    }

    public String stats() {
        return stats(false);
    }

    /**
     * Method to obtain the classification report as a String
     *
     * @param suppressWarnings whether or not to output warnings related to the evaluation results
     * @return A (multi-line) String with accuracy, precision, recall, f1 score etc
     */
    public String stats(boolean suppressWarnings) {
        String actual, predicted;
        StringBuilder builder = new StringBuilder().append("\n");
        StringBuilder warnings = new StringBuilder();
        ConfusionMatrix<Integer> confusion = confusion();
        if(confusion == null){
            confusion = new ConfusionMatrix<>();    //Empty
        }
        List<Integer> classes = confusion.getClasses();

        List<Integer> falsePositivesWarningClasses = new ArrayList<>();
        List<Integer> falseNegativesWarningClasses = new ArrayList<>();
        for (Integer clazz : classes) {
            actual = resolveLabelForClass(clazz);
            //Output confusion matrix
            for (Integer clazz2 : classes) {
                int count = confusion().getCount(clazz, clazz2);
                if (count != 0) {
                    predicted = resolveLabelForClass(clazz2);
                    builder.append(String.format("Examples labeled as %s classified by model as %s: %d times%n", actual,
                                    predicted, count));
                }
            }

            //Output possible warnings regarding precision/recall calculation
            if (!suppressWarnings && truePositives.getCount(clazz) == 0) {
                if (falsePositives.getCount(clazz) == 0) {
                    falsePositivesWarningClasses.add(clazz);
                }
                if (falseNegatives.getCount(clazz) == 0) {
                    falseNegativesWarningClasses.add(clazz);
                }
            }
        }
        if (falsePositivesWarningClasses.size() > 0) {
            warningHelper(warnings, falsePositivesWarningClasses, "precision");
        }
        if (falseNegativesWarningClasses.size() > 0) {
            warningHelper(warnings, falseNegativesWarningClasses, "recall");
        }

        builder.append("\n");
        builder.append(warnings);

        int nClasses = confusion.getClasses().size();
        DecimalFormat df = new DecimalFormat("0.0000");
        double acc = accuracy();
        double precisionMacro = precision(EvaluationAveraging.Macro);
        double recallMacro = recall(EvaluationAveraging.Macro);
        double f1Macro = f1(EvaluationAveraging.Macro);
        builder.append("\n==========================Scores========================================");
        builder.append("\n # of classes:    ").append(nClasses);
        builder.append("\n Accuracy:        ").append(format(df, acc));
        if (topN > 1) {
            double topNAcc = topNAccuracy();
            builder.append("\n Top ").append(topN).append(" Accuracy:  ").append(format(df, topNAcc));
        }
        builder.append("\n Precision:       ").append(format(df, precisionMacro));
        if (nClasses > 2 && averagePrecisionNumClassesExcluded() > 0) {
            int ex = averagePrecisionNumClassesExcluded();
            builder.append("\t(").append(ex).append(" class");
            if (ex > 1)
                builder.append("es");
            builder.append(" excluded from average)");
        }
        builder.append("\n Recall:          ").append(format(df, recallMacro));
        if (nClasses > 2 && averageRecallNumClassesExcluded() > 0) {
            int ex = averageRecallNumClassesExcluded();
            builder.append("\t(").append(ex).append(" class");
            if (ex > 1)
                builder.append("es");
            builder.append(" excluded from average)");
        }
        builder.append("\n F1 Score:        ").append(format(df, f1Macro));
        if (nClasses > 2 && averageF1NumClassesExcluded() > 0) {
            int ex = averageF1NumClassesExcluded();
            builder.append("\t(").append(ex).append(" class");
            if (ex > 1)
                builder.append("es");
            builder.append(" excluded from average)");
        }
        if (nClasses > 2) {
            builder.append("\nPrecision, recall & F1: macro-averaged (equally weighted avg. of ").append(nClasses)
                            .append(" classes)");
        }
        if (binaryDecisionThreshold != null) {
            builder.append("\nBinary decision threshold: ").append(binaryDecisionThreshold);
        }
        if (costArray != null) {
            builder.append("\nCost array: ").append(Arrays.toString(costArray.dup().data().asFloat()));
        }
        //Note that we could report micro-averaged too - but these are the same as accuracy
        //"Note that for “micro”-averaging in a multiclass setting with all labels included will produce equal precision, recall and F,"
        //http://scikit-learn.org/stable/modules/model_evaluation.html
        builder.append("\n========================================================================");
        return builder.toString();
    }

    private static String format(DecimalFormat f, double num) {
        if (Double.isNaN(num) || Double.isInfinite(num))
            return String.valueOf(num);
        return f.format(num);
    }

    private String resolveLabelForClass(Integer clazz) {
        if (labelsList != null && labelsList.size() > clazz)
            return labelsList.get(clazz);
        return clazz.toString();
    }

    private void warningHelper(StringBuilder warnings, List<Integer> list, String metric) {
        warnings.append("Warning: ").append(list.size()).append(" class");
        String wasWere;
        if (list.size() == 1) {
            wasWere = "was";
        } else {
            wasWere = "were";
            warnings.append("es");
        }
        warnings.append(" ").append(wasWere);
        warnings.append(" never predicted by the model and ").append(wasWere).append(" excluded from average ")
                        .append(metric).append("\nClasses excluded from average ").append(metric).append(": ")
                        .append(list).append("\n");
    }

    /**
     * Returns the precision for a given label
     *
     * @param classLabel the label
     * @return the precision for the label
     */
    public double precision(Integer classLabel) {
        return precision(classLabel, DEFAULT_EDGE_VALUE);
    }

    /**
     * Returns the precision for a given label
     *
     * @param classLabel the label
     * @param edgeCase   What to output in case of 0/0
     * @return the precision for the label
     */
    public double precision(Integer classLabel, double edgeCase) {
        double tpCount = truePositives.getCount(classLabel);
        double fpCount = falsePositives.getCount(classLabel);
        return EvaluationUtils.precision((long) tpCount, (long) fpCount, edgeCase);
    }

    /**
     * Precision based on guesses so far
     * Takes into account all known classes and outputs average precision across all of them.
     * i.e., is macro-averaged precision, equivalent to {@code precision(EvaluationAveraging.Macro)}
     *
     * @return the total precision based on guesses so far
     */
    public double precision() {
        return precision(EvaluationAveraging.Macro);
    }

    /**
     * Calculate the average precision for all classes. Can specify whether macro or micro averaging should be used
     * NOTE: if any classes have tp=0 and fp=0, (precision=0/0) these are excluded from the average
     *
     * @param averaging Averaging method - macro or micro
     * @return Average precision
     */
    public double precision(EvaluationAveraging averaging) {
        if(getNumRowCounter() == 0){
            return 0.0; //No data
        }
        int nClasses = confusion().getClasses().size();
        if (averaging == EvaluationAveraging.Macro) {
            double macroPrecision = 0.0;
            int count = 0;
            for (int i = 0; i < nClasses; i++) {
                double thisClassPrec = precision(i, -1);
                if (thisClassPrec != -1) {
                    macroPrecision += thisClassPrec;
                    count++;
                }
            }
            macroPrecision /= count;
            return macroPrecision;
        } else if (averaging == EvaluationAveraging.Micro) {
            long tpCount = 0;
            long fpCount = 0;
            for (int i = 0; i < nClasses; i++) {
                tpCount += truePositives.getCount(i);
                fpCount += falsePositives.getCount(i);
            }
            return EvaluationUtils.precision(tpCount, fpCount, DEFAULT_EDGE_VALUE);
        } else {
            throw new UnsupportedOperationException("Unknown averaging approach: " + averaging);
        }
    }

    /**
     * When calculating the (macro) average precision, how many classes are excluded from the average due to
     * no predictions – i.e., precision would be the edge case of 0/0
     *
     * @return Number of classes excluded from the  average precision
     */
    public int averagePrecisionNumClassesExcluded() {
        return numClassesExcluded("precision");
    }

    /**
     * When calculating the (macro) average Recall, how many classes are excluded from the average due to
     * no predictions – i.e., recall would be the edge case of 0/0
     *
     * @return Number of classes excluded from the average recall
     */
    public int averageRecallNumClassesExcluded() {
        return numClassesExcluded("recall");
    }

    /**
     * When calculating the (macro) average F1, how many classes are excluded from the average due to
     * no predictions – i.e., F1 would be calculated from a precision or recall of 0/0
     *
     * @return Number of classes excluded from the average F1
     */
    public int averageF1NumClassesExcluded() {
        return numClassesExcluded("f1");
    }

    /**
     * When calculating the (macro) average FBeta, how many classes are excluded from the average due to
     * no predictions – i.e., FBeta would be calculated from a precision or recall of 0/0
     *
     * @return Number of classes excluded from the average FBeta
     */
    public int averageFBetaNumClassesExcluded() {
        return numClassesExcluded("fbeta");
    }

    private int numClassesExcluded(String metric) {
        int countExcluded = 0;
        int nClasses = confusion().getClasses().size();

        for (int i = 0; i < nClasses; i++) {
            double d;
            switch (metric.toLowerCase()) {
                case "precision":
                    d = precision(i, -1);
                    break;
                case "recall":
                    d = recall(i, -1);
                    break;
                case "f1":
                case "fbeta":
                    d = fBeta(1.0, i, -1);
                    break;
                default:
                    throw new RuntimeException("Unknown metric: " + metric);
            }

            if (d == -1) {
                countExcluded++;
            }
        }
        return countExcluded;
    }

    /**
     * Returns the recall for a given label
     *
     * @param classLabel the label
     * @return Recall rate as a double
     */
    public double recall(int classLabel) {
        return recall(classLabel, DEFAULT_EDGE_VALUE);
    }

    /**
     * Returns the recall for a given label
     *
     * @param classLabel the label
     * @param edgeCase   What to output in case of 0/0
     * @return Recall rate as a double
     */
    public double recall(int classLabel, double edgeCase) {
        double tpCount = truePositives.getCount(classLabel);
        double fnCount = falseNegatives.getCount(classLabel);

        return EvaluationUtils.recall((long) tpCount, (long) fnCount, edgeCase);
    }

    /**
     * Recall based on guesses so far
     * Takes into account all known classes and outputs average recall across all of them
     *
     * @return the recall for the outcomes
     */
    public double recall() {
        return recall(EvaluationAveraging.Macro);
    }

    /**
     * Calculate the average recall for all classes - can specify whether macro or micro averaging should be used
     * NOTE: if any classes have tp=0 and fn=0, (recall=0/0) these are excluded from the average
     *
     * @param averaging Averaging method - macro or micro
     * @return Average recall
     */
    public double recall(EvaluationAveraging averaging) {
        if(getNumRowCounter() == 0.0){
            return 0.0; //No data
        }
        int nClasses = confusion().getClasses().size();
        if (averaging == EvaluationAveraging.Macro) {
            double macroRecall = 0.0;
            int count = 0;
            for (int i = 0; i < nClasses; i++) {
                double thisClassRecall = recall(i, -1);
                if (thisClassRecall != -1) {
                    macroRecall += thisClassRecall;
                    count++;
                }
            }
            macroRecall /= count;
            return macroRecall;
        } else if (averaging == EvaluationAveraging.Micro) {
            long tpCount = 0;
            long fnCount = 0;
            for (int i = 0; i < nClasses; i++) {
                tpCount += truePositives.getCount(i);
                fnCount += falseNegatives.getCount(i);
            }
            return EvaluationUtils.recall(tpCount, fnCount, DEFAULT_EDGE_VALUE);
        } else {
            throw new UnsupportedOperationException("Unknown averaging approach: " + averaging);
        }
    }


    /**
     * Returns the false positive rate for a given label
     *
     * @param classLabel the label
     * @return fpr as a double
     */
    public double falsePositiveRate(int classLabel) {
        return falsePositiveRate(classLabel, DEFAULT_EDGE_VALUE);
    }

    /**
     * Returns the false positive rate for a given label
     *
     * @param classLabel the label
     * @param edgeCase   What to output in case of 0/0
     * @return fpr as a double
     */
    public double falsePositiveRate(int classLabel, double edgeCase) {
        double fpCount = falsePositives.getCount(classLabel);
        double tnCount = trueNegatives.getCount(classLabel);

        return EvaluationUtils.falsePositiveRate((long) fpCount, (long) tnCount, edgeCase);
    }

    /**
     * False positive rate based on guesses so far
     * Takes into account all known classes and outputs average fpr across all of them
     *
     * @return the fpr for the outcomes
     */
    public double falsePositiveRate() {
        return falsePositiveRate(EvaluationAveraging.Macro);
    }

    /**
     * Calculate the average false positive rate across all classes. Can specify whether macro or micro averaging should be used
     *
     * @param averaging Averaging method - macro or micro
     * @return Average false positive rate
     */
    public double falsePositiveRate(EvaluationAveraging averaging) {
        int nClasses = confusion().getClasses().size();
        if (averaging == EvaluationAveraging.Macro) {
            double macroFPR = 0.0;
            for (int i = 0; i < nClasses; i++) {
                macroFPR += falsePositiveRate(i);
            }
            macroFPR /= nClasses;
            return macroFPR;
        } else if (averaging == EvaluationAveraging.Micro) {
            long fpCount = 0;
            long tnCount = 0;
            for (int i = 0; i < nClasses; i++) {
                fpCount += falsePositives.getCount(i);
                tnCount += trueNegatives.getCount(i);
            }
            return EvaluationUtils.falsePositiveRate(fpCount, tnCount, DEFAULT_EDGE_VALUE);
        } else {
            throw new UnsupportedOperationException("Unknown averaging approach: " + averaging);
        }
    }

    /**
     * Returns the false negative rate for a given label
     *
     * @param classLabel the label
     * @return fnr as a double
     */
    public double falseNegativeRate(Integer classLabel) {
        return falseNegativeRate(classLabel, DEFAULT_EDGE_VALUE);
    }

    /**
     * Returns the false negative rate for a given label
     *
     * @param classLabel the label
     * @param edgeCase   What to output in case of 0/0
     * @return fnr as a double
     */
    public double falseNegativeRate(Integer classLabel, double edgeCase) {
        double fnCount = falseNegatives.getCount(classLabel);
        double tpCount = truePositives.getCount(classLabel);

        return EvaluationUtils.falseNegativeRate((long) fnCount, (long) tpCount, edgeCase);
    }

    /**
     * False negative rate based on guesses so far
     * Takes into account all known classes and outputs average fnr across all of them
     *
     * @return the fnr for the outcomes
     */
    public double falseNegativeRate() {
        return falseNegativeRate(EvaluationAveraging.Macro);
    }

    /**
     * Calculate the average false negative rate for all classes - can specify whether macro or micro averaging should be used
     *
     * @param averaging Averaging method - macro or micro
     * @return Average false negative rate
     */
    public double falseNegativeRate(EvaluationAveraging averaging) {
        int nClasses = confusion().getClasses().size();
        if (averaging == EvaluationAveraging.Macro) {
            double macroFNR = 0.0;
            for (int i = 0; i < nClasses; i++) {
                macroFNR += falseNegativeRate(i);
            }
            macroFNR /= nClasses;
            return macroFNR;
        } else if (averaging == EvaluationAveraging.Micro) {
            long fnCount = 0;
            long tnCount = 0;
            for (int i = 0; i < nClasses; i++) {
                fnCount += falseNegatives.getCount(i);
                tnCount += trueNegatives.getCount(i);
            }
            return EvaluationUtils.falseNegativeRate(fnCount, tnCount, DEFAULT_EDGE_VALUE);
        } else {
            throw new UnsupportedOperationException("Unknown averaging approach: " + averaging);
        }
    }

    /**
     * False Alarm Rate (FAR) reflects rate of misclassified to classified records
     * http://ro.ecu.edu.au/cgi/viewcontent.cgi?article=1058&context=isw
     *
     * @return the fpr for the outcomes
     */
    public double falseAlarmRate() {
        return (falsePositiveRate() + falseNegativeRate()) / 2.0;
    }

    /**
     * Calculate f1 score for a given class
     *
     * @param classLabel the label to calculate f1 for
     * @return the f1 score for the given label
     */
    public double f1(int classLabel) {
        return fBeta(1.0, classLabel);
    }

    /**
     * Calculate the f_beta for a given class, where f_beta is defined as:<br>
     * (1+beta^2) * (precision * recall) / (beta^2 * precision + recall).<br>
     * F1 is a special case of f_beta, with beta=1.0
     *
     * @param beta       Beta value to use
     * @param classLabel Class label
     * @return F_beta
     */
    public double fBeta(double beta, int classLabel) {
        return fBeta(beta, classLabel, 0.0);
    }

    /**
     * Calculate the f_beta for a given class, where f_beta is defined as:<br>
     * (1+beta^2) * (precision * recall) / (beta^2 * precision + recall).<br>
     * F1 is a special case of f_beta, with beta=1.0
     *
     * @param beta       Beta value to use
     * @param classLabel Class label
     * @param defaultValue Default value to use when precision or recall is undefined (0/0 for prec. or recall)
     * @return F_beta
     */
    public double fBeta(double beta, int classLabel, double defaultValue) {
        double precision = precision(classLabel, -1);
        double recall = recall(classLabel, -1);
        if (precision == -1 || recall == -1) {
            return defaultValue;
        }
        return EvaluationUtils.fBeta(beta, precision, recall);
    }

    /**
     * Calculate the (macro) average F1 score across all classes
     *
     * TP: true positive
     * FP: False Positive
     * FN: False Negative
     * F1 score: 2 * TP / (2TP + FP + FN)
     *
     * @return the f1 score or harmonic mean of precision and recall based on current guesses
     */
    public double f1() {
        return f1(EvaluationAveraging.Macro);
    }

    /**
     * Calculate the average F1 score across all classes, using macro or micro averaging
     *
     * @param averaging Averaging method to use
     */
    public double f1(EvaluationAveraging averaging) {
        return fBeta(1.0, averaging);
    }

    /**
     * Calculate the average F_beta score across all classes, using macro or micro averaging
     *
     * @param beta Beta value to use
     * @param averaging Averaging method to use
     */
    public double fBeta(double beta, EvaluationAveraging averaging) {
        if(getNumRowCounter() == 0.0){
            return Double.NaN;  //No data
        }
        int nClasses = confusion().getClasses().size();

        if (nClasses == 2) {
            return EvaluationUtils.fBeta(beta, (long) truePositives.getCount(1), (long) falsePositives.getCount(1),
                            (long) falseNegatives.getCount(1));
        }

        if (averaging == EvaluationAveraging.Macro) {
            double macroFBeta = 0.0;
            int count = 0;
            for (int i = 0; i < nClasses; i++) {
                double thisFBeta = fBeta(beta, i, -1);
                if (thisFBeta != -1) {
                    macroFBeta += thisFBeta;
                    count++;
                }
            }
            macroFBeta /= count;
            return macroFBeta;
        } else if (averaging == EvaluationAveraging.Micro) {
            long tpCount = 0;
            long fpCount = 0;
            long fnCount = 0;
            for (int i = 0; i < nClasses; i++) {
                tpCount += truePositives.getCount(i);
                fpCount += falsePositives.getCount(i);
                fnCount += falseNegatives.getCount(i);
            }
            return EvaluationUtils.fBeta(beta, tpCount, fpCount, fnCount);
        } else {
            throw new UnsupportedOperationException("Unknown averaging approach: " + averaging);
        }
    }

    /**
     * Calculate the G-measure for the given output
     *
     * @param output The specified output
     * @return The G-measure for the specified output
     */
    public double gMeasure(int output) {
        double precision = precision(output);
        double recall = recall(output);
        return EvaluationUtils.gMeasure(precision, recall);
    }

    /**
     * Calculates the average G measure for all outputs using micro or macro averaging
     *
     * @param averaging Averaging method to use
     * @return Average G measure
     */
    public double gMeasure(EvaluationAveraging averaging) {
        int nClasses = confusion().getClasses().size();
        if (averaging == EvaluationAveraging.Macro) {
            double macroGMeasure = 0.0;
            for (int i = 0; i < nClasses; i++) {
                macroGMeasure += gMeasure(i);
            }
            macroGMeasure /= nClasses;
            return macroGMeasure;
        } else if (averaging == EvaluationAveraging.Micro) {
            long tpCount = 0;
            long fpCount = 0;
            long fnCount = 0;
            for (int i = 0; i < nClasses; i++) {
                tpCount += truePositives.getCount(i);
                fpCount += falsePositives.getCount(i);
                fnCount += falseNegatives.getCount(i);
            }
            double precision = EvaluationUtils.precision(tpCount, fpCount, DEFAULT_EDGE_VALUE);
            double recall = EvaluationUtils.recall(tpCount, fnCount, DEFAULT_EDGE_VALUE);
            return EvaluationUtils.gMeasure(precision, recall);
        } else {
            throw new UnsupportedOperationException("Unknown averaging approach: " + averaging);
        }
    }

    /**
     * Accuracy:
     * (TP + TN) / (P + N)
     *
     * @return the accuracy of the guesses so far
     */
    public double accuracy() {
        if (getNumRowCounter() == 0) {
            return 0.0; //No records
        }
        //Accuracy: sum the counts on the diagonal of the confusion matrix, divide by total
        int nClasses = confusion().getClasses().size();
        int countCorrect = 0;
        for (int i = 0; i < nClasses; i++) {
            countCorrect += confusion().getCount(i, i);
        }

        return countCorrect / (double) getNumRowCounter();
    }

    /**
     * Top N accuracy of the predictions so far. For top N = 1 (default), equivalent to {@link #accuracy()}
     * @return Top N accuracy
     */
    public double topNAccuracy() {
        if (topN <= 1)
            return accuracy();
        if (topNTotalCount == 0)
            return 0.0;
        return topNCorrectCount / (double) topNTotalCount;
    }

    /**
     * Calculate the binary Mathews correlation coefficient, for the specified class.<br>
     * MCC = (TP*TN - FP*FN) / sqrt((TP+FP)(TP+FN)(TN+FP)(TN+FN))<br>
     *
     * @param classIdx Class index to calculate Matthews correlation coefficient for
     */
    public double matthewsCorrelation(int classIdx) {
        return EvaluationUtils.matthewsCorrelation((long) truePositives.getCount(classIdx),
                        (long) falsePositives.getCount(classIdx), (long) falseNegatives.getCount(classIdx),
                        (long) trueNegatives.getCount(classIdx));
    }

    /**
     * Calculate the average binary Mathews correlation coefficient, using macro or micro averaging.<br>
     * MCC = (TP*TN - FP*FN) / sqrt((TP+FP)(TP+FN)(TN+FP)(TN+FN))<br>
     * Note: This is NOT the same as the multi-class Matthews correlation coefficient
     *
     * @param averaging Averaging approach
     * @return Average
     */
    public double matthewsCorrelation(EvaluationAveraging averaging) {
        int nClasses = confusion().getClasses().size();
        if (averaging == EvaluationAveraging.Macro) {
            double macroMatthewsCorrelation = 0.0;
            for (int i = 0; i < nClasses; i++) {
                macroMatthewsCorrelation += matthewsCorrelation(i);
            }
            macroMatthewsCorrelation /= nClasses;
            return macroMatthewsCorrelation;
        } else if (averaging == EvaluationAveraging.Micro) {
            long tpCount = 0;
            long fpCount = 0;
            long fnCount = 0;
            long tnCount = 0;
            for (int i = 0; i < nClasses; i++) {
                tpCount += truePositives.getCount(i);
                fpCount += falsePositives.getCount(i);
                fnCount += falseNegatives.getCount(i);
                tnCount += trueNegatives.getCount(i);
            }
            return EvaluationUtils.matthewsCorrelation(tpCount, fpCount, fnCount, tnCount);
        } else {
            throw new UnsupportedOperationException("Unknown averaging approach: " + averaging);
        }
    }


    // Access counter methods

    /**
     * True positives: correctly rejected
     *
     * @return the total true positives so far
     */
    public Map<Integer, Integer> truePositives() {
        return convertToMap(truePositives, confusion().getClasses().size());
    }

    /**
     * True negatives: correctly rejected
     *
     * @return the total true negatives so far
     */
    public Map<Integer, Integer> trueNegatives() {
        return convertToMap(trueNegatives, confusion().getClasses().size());
    }

    /**
     * False positive: wrong guess
     *
     * @return the count of the false positives
     */
    public Map<Integer, Integer> falsePositives() {
        return convertToMap(falsePositives, confusion().getClasses().size());
    }

    /**
     * False negatives: correctly rejected
     *
     * @return the total false negatives so far
     */
    public Map<Integer, Integer> falseNegatives() {
        return convertToMap(falseNegatives, confusion().getClasses().size());
    }

    /**
     * Total negatives true negatives + false negatives
     *
     * @return the overall negative count
     */
    public Map<Integer, Integer> negative() {
        return addMapsByKey(trueNegatives(), falsePositives());
    }

    /**
     * Returns all of the positive guesses:
     * true positive + false negative
     */
    public Map<Integer, Integer> positive() {
        return addMapsByKey(truePositives(), falseNegatives());
    }

    private Map<Integer, Integer> convertToMap(Counter<Integer> counter, int maxCount) {
        Map<Integer, Integer> map = new HashMap<>();
        for (int i = 0; i < maxCount; i++) {
            map.put(i, (int) counter.getCount(i));
        }
        return map;
    }

    private Map<Integer, Integer> addMapsByKey(Map<Integer, Integer> first, Map<Integer, Integer> second) {
        Map<Integer, Integer> out = new HashMap<>();
        Set<Integer> keys = new HashSet<>(first.keySet());
        keys.addAll(second.keySet());

        for (Integer i : keys) {
            Integer f = first.get(i);
            Integer s = second.get(i);
            if (f == null)
                f = 0;
            if (s == null)
                s = 0;

            out.put(i, f + s);
        }

        return out;
    }


    // Incrementing counters
    public void incrementTruePositives(Integer classLabel) {
        truePositives.incrementCount(classLabel, 1.0f);
    }

    public void incrementTrueNegatives(Integer classLabel) {
        trueNegatives.incrementCount(classLabel, 1.0f);
    }

    public void incrementFalseNegatives(Integer classLabel) {
        falseNegatives.incrementCount(classLabel, 1.0f);
    }

    public void incrementFalsePositives(Integer classLabel) {
        falsePositives.incrementCount(classLabel, 1.0f);
    }


    // Other misc methods

    /**
     * Adds to the confusion matrix
     *
     * @param real  the actual guess
     * @param guess the system guess
     */
    public void addToConfusion(Integer real, Integer guess) {
        confusion().add(real, guess);
    }

    /**
     * Returns the number of times the given label
     * has actually occurred
     *
     * @param clazz the label
     * @return the number of times the label
     * actually occurred
     */
    public int classCount(Integer clazz) {
        return confusion().getActualTotal(clazz);
    }

    public int getNumRowCounter() {
        return numRowCounter;
    }

    /**
     * Return the number of correct predictions according to top N value. For top N = 1 (default) this is equivalent to
     * the number of correct predictions
     * @return Number of correct top N predictions
     */
    public int getTopNCorrectCount() {
        if (confusion == null)
            return 0;
        if (topN <= 1) {
            int nClasses = confusion().getClasses().size();
            int countCorrect = 0;
            for (int i = 0; i < nClasses; i++) {
                countCorrect += confusion().getCount(i, i);
            }
            return countCorrect;
        }
        return topNCorrectCount;
    }

    /**
     * Return the total number of top N evaluations. Most of the time, this is exactly equal to {@link #getNumRowCounter()},
     * but may differ in the case of using {@link #eval(int, int)} as top N accuracy cannot be calculated in that case
     * (i.e., requires the full probability distribution, not just predicted/actual indices)
     * @return Total number of top N predictions
     */
    public int getTopNTotalCount() {
        if (topN <= 1) {
            return getNumRowCounter();
        }
        return topNTotalCount;
    }

    public String getClassLabel(Integer clazz) {
        return resolveLabelForClass(clazz);
    }

    /**
     * Returns the confusion matrix variable
     *
     * @return confusion matrix variable for this evaluation
     */
    public ConfusionMatrix<Integer> getConfusionMatrix() {
        return confusion;
    }

    /**
     * Merge the other evaluation object into this one. The result is that this Evaluation instance contains the counts
     * etc from both
     *
     * @param other Evaluation object to merge into this one.
     */
    @Override
    public void merge(Evaluation other) {
        if (other == null)
            return;

        truePositives.incrementAll(other.truePositives);
        falsePositives.incrementAll(other.falsePositives);
        trueNegatives.incrementAll(other.trueNegatives);
        falseNegatives.incrementAll(other.falseNegatives);

        if (confusion == null) {
            if (other.confusion != null)
                confusion = new ConfusionMatrix<>(other.confusion);
        } else {
            if (other.confusion != null)
                confusion().add(other.confusion);
        }
        numRowCounter += other.numRowCounter;
        if (labelsList.isEmpty())
            labelsList.addAll(other.labelsList);

        if (topN != other.topN) {
            log.warn("Different topN values ({} vs {}) detected during Evaluation merging. Top N accuracy may not be accurate.",
                            topN, other.topN);
        }
        this.topNCorrectCount += other.topNCorrectCount;
        this.topNTotalCount += other.topNTotalCount;
    }

    /**
     * Get a String representation of the confusion matrix
     */
    public String confusionToString() {
        int nClasses = confusion().getClasses().size();

        //First: work out the longest label size
        int maxLabelSize = 0;
        for (String s : labelsList) {
            maxLabelSize = Math.max(maxLabelSize, s.length());
        }

        //Build the formatting for the rows:
        int labelSize = Math.max(maxLabelSize + 5, 10);
        StringBuilder sb = new StringBuilder();
        sb.append("%-3d");
        sb.append("%-");
        sb.append(labelSize);
        sb.append("s | ");

        StringBuilder headerFormat = new StringBuilder();
        headerFormat.append("   %-").append(labelSize).append("s   ");

        for (int i = 0; i < nClasses; i++) {
            sb.append("%7d");
            headerFormat.append("%7d");
        }
        String rowFormat = sb.toString();


        StringBuilder out = new StringBuilder();
        //First: header row
        Object[] headerArgs = new Object[nClasses + 1];
        headerArgs[0] = "Predicted:";
        for (int i = 0; i < nClasses; i++)
            headerArgs[i + 1] = i;
        out.append(String.format(headerFormat.toString(), headerArgs)).append("\n");

        //Second: divider rows
        out.append("   Actual:\n");

        //Finally: data rows
        for (int i = 0; i < nClasses; i++) {

            Object[] args = new Object[nClasses + 2];
            args[0] = i;
            args[1] = labelsList.get(i);
            for (int j = 0; j < nClasses; j++) {
                args[j + 2] = confusion().getCount(i, j);
            }
            out.append(String.format(rowFormat, args));
            out.append("\n");
        }

        return out.toString();
    }


    private void addToMetaConfusionMatrix(int actual, int predicted, Object metaData) {
        if (confusionMatrixMetaData == null) {
            confusionMatrixMetaData = new HashMap<>();
        }

        Pair<Integer, Integer> p = new Pair<>(actual, predicted);
        List<Object> list = confusionMatrixMetaData.get(p);
        if (list == null) {
            list = new ArrayList<>();
            confusionMatrixMetaData.put(p, list);
        }

        list.add(metaData);
    }

    /**
     * Get a list of prediction errors, on a per-record basis<br>
     * <p>
     * <b>Note</b>: Prediction errors are ONLY available if the "evaluate with metadata"  method is used: {@link #eval(INDArray, INDArray, List)}
     * Otherwise (if the metadata hasn't been recorded via that previously mentioned eval method), there is no value in
     * splitting each prediction out into a separate Prediction object - instead, use the confusion matrix to get the counts,
     * via {@link #getConfusionMatrix()}
     *
     * @return A list of prediction errors, or null if no metadata has been recorded
     */
    public List<Prediction> getPredictionErrors() {
        if (this.confusionMatrixMetaData == null)
            return null;

        List<Prediction> list = new ArrayList<>();

        List<Map.Entry<Pair<Integer, Integer>, List<Object>>> sorted =
                        new ArrayList<>(confusionMatrixMetaData.entrySet());
        Collections.sort(sorted, new Comparator<Map.Entry<Pair<Integer, Integer>, List<Object>>>() {
            @Override
            public int compare(Map.Entry<Pair<Integer, Integer>, List<Object>> o1,
                            Map.Entry<Pair<Integer, Integer>, List<Object>> o2) {
                Pair<Integer, Integer> p1 = o1.getKey();
                Pair<Integer, Integer> p2 = o2.getKey();
                int order = Integer.compare(p1.getFirst(), p2.getFirst());
                if (order != 0)
                    return order;
                order = Integer.compare(p1.getSecond(), p2.getSecond());
                return order;
            }
        });

        for (Map.Entry<Pair<Integer, Integer>, List<Object>> entry : sorted) {
            Pair<Integer, Integer> p = entry.getKey();
            if (p.getFirst().equals(p.getSecond())) {
                //predicted = actual -> not an error -> skip
                continue;
            }
            for (Object m : entry.getValue()) {
                list.add(new Prediction(p.getFirst(), p.getSecond(), m));
            }
        }

        return list;
    }

    /**
     * Get a list of predictions, for all data with the specified <i>actual</i> class, regardless of the predicted
     * class.
     * <p>
     * <b>Note</b>: Prediction errors are ONLY available if the "evaluate with metadata"  method is used: {@link #eval(INDArray, INDArray, List)}
     * Otherwise (if the metadata hasn't been recorded via that previously mentioned eval method), there is no value in
     * splitting each prediction out into a separate Prediction object - instead, use the confusion matrix to get the counts,
     * via {@link #getConfusionMatrix()}
     *
     * @param actualClass Actual class to get predictions for
     * @return List of predictions, or null if the "evaluate with metadata" method was not used
     */
    public List<Prediction> getPredictionsByActualClass(int actualClass) {
        if (confusionMatrixMetaData == null)
            return null;

        List<Prediction> out = new ArrayList<>();
        for (Map.Entry<Pair<Integer, Integer>, List<Object>> entry : confusionMatrixMetaData.entrySet()) { //Entry Pair: (Actual,Predicted)
            if (entry.getKey().getFirst() == actualClass) {
                int actual = entry.getKey().getFirst();
                int predicted = entry.getKey().getSecond();
                for (Object m : entry.getValue()) {
                    out.add(new Prediction(actual, predicted, m));
                }
            }
        }
        return out;
    }

    /**
     * Get a list of predictions, for all data with the specified <i>predicted</i> class, regardless of the actual data
     * class.
     * <p>
     * <b>Note</b>: Prediction errors are ONLY available if the "evaluate with metadata"  method is used: {@link #eval(INDArray, INDArray, List)}
     * Otherwise (if the metadata hasn't been recorded via that previously mentioned eval method), there is no value in
     * splitting each prediction out into a separate Prediction object - instead, use the confusion matrix to get the counts,
     * via {@link #getConfusionMatrix()}
     *
     * @param predictedClass Actual class to get predictions for
     * @return List of predictions, or null if the "evaluate with metadata" method was not used
     */
    public List<Prediction> getPredictionByPredictedClass(int predictedClass) {
        if (confusionMatrixMetaData == null)
            return null;

        List<Prediction> out = new ArrayList<>();
        for (Map.Entry<Pair<Integer, Integer>, List<Object>> entry : confusionMatrixMetaData.entrySet()) { //Entry Pair: (Actual,Predicted)
            if (entry.getKey().getSecond() == predictedClass) {
                int actual = entry.getKey().getFirst();
                int predicted = entry.getKey().getSecond();
                for (Object m : entry.getValue()) {
                    out.add(new Prediction(actual, predicted, m));
                }
            }
        }
        return out;
    }

    /**
     * Get a list of predictions in the specified confusion matrix entry (i.e., for the given actua/predicted class pair)
     *
     * @param actualClass    Actual class
     * @param predictedClass Predicted class
     * @return List of predictions that match the specified actual/predicted classes, or null if the "evaluate with metadata" method was not used
     */
    public List<Prediction> getPredictions(int actualClass, int predictedClass) {
        if (confusionMatrixMetaData == null)
            return null;

        List<Prediction> out = new ArrayList<>();
        List<Object> list = confusionMatrixMetaData.get(new Pair<>(actualClass, predictedClass));
        if (list == null)
            return out;

        for (Object meta : list) {
            out.add(new Prediction(actualClass, predictedClass, meta));
        }
        return out;
    }


    public static Evaluation fromJson(String json) {
        return fromJson(json, Evaluation.class);
    }

    public static Evaluation fromYaml(String yaml) {
        return fromYaml(yaml, Evaluation.class);
    }
}
