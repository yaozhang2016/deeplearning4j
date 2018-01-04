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

package org.deeplearning4j.nn.params;

import org.deeplearning4j.nn.api.ParamInitializer;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.distribution.Distributions;
import org.deeplearning4j.nn.conf.layers.LSTM;
import org.deeplearning4j.nn.conf.layers.Layer;
import org.deeplearning4j.nn.weights.WeightInitUtil;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.rng.distribution.Distribution;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.INDArrayIndex;
import org.nd4j.linalg.indexing.NDArrayIndex;

import java.util.*;

/**LSTM Parameter initializer, for LSTM based on
 * Graves: Supervised Sequence Labelling with Recurrent Neural Networks
 * http://www.cs.toronto.edu/~graves/phd.pdf
 */
public class LSTMParamInitializer implements ParamInitializer {

    private static final LSTMParamInitializer INSTANCE = new LSTMParamInitializer();

    public static LSTMParamInitializer getInstance() {
        return INSTANCE;
    }

    /** Weights for previous time step -> current time step connections */
    public final static String RECURRENT_WEIGHT_KEY = "RW";
    public final static String BIAS_KEY = DefaultParamInitializer.BIAS_KEY;
    public final static String INPUT_WEIGHT_KEY = DefaultParamInitializer.WEIGHT_KEY;

    @Override
    public int numParams(NeuralNetConfiguration conf) {
        return numParams(conf.getLayer());
    }

    @Override
    public int numParams(Layer l) {
        LSTM layerConf = (LSTM) l;

        int nL = layerConf.getNOut(); //i.e., n neurons in this layer
        int nLast = layerConf.getNIn(); //i.e., n neurons in previous layer

        int nParams = nLast * (4 * nL) //"input" weights
                        + nL * (4 * nL) //recurrent weights
                        + 4 * nL; //bias

        return nParams;
    }

    @Override
    public List<String> paramKeys(Layer layer) {
        return Arrays.asList(INPUT_WEIGHT_KEY, RECURRENT_WEIGHT_KEY, BIAS_KEY);
    }

    @Override
    public List<String> weightKeys(Layer layer) {
        return Arrays.asList(INPUT_WEIGHT_KEY, RECURRENT_WEIGHT_KEY);
    }

    @Override
    public List<String> biasKeys(Layer layer) {
        return Collections.singletonList(BIAS_KEY);
    }

    @Override
    public boolean isWeightParam(Layer layer, String key) {
        return RECURRENT_WEIGHT_KEY.equals(key) || INPUT_WEIGHT_KEY.equals(key);
    }

    @Override
    public boolean isBiasParam(Layer layer, String key) {
        return BIAS_KEY.equals(key);
    }

    @Override
    public Map<String, INDArray> init(NeuralNetConfiguration conf, INDArray paramsView, boolean initializeParams) {
        Map<String, INDArray> params = Collections.synchronizedMap(new LinkedHashMap<String, INDArray>());
        org.deeplearning4j.nn.conf.layers.LSTM layerConf = (org.deeplearning4j.nn.conf.layers.LSTM) conf.getLayer();
        double forgetGateInit = layerConf.getForgetGateBiasInit();

        Distribution dist = Distributions.createDistribution(layerConf.getDist());

        int nL = layerConf.getNOut(); //i.e., n neurons in this layer
        int nLast = layerConf.getNIn(); //i.e., n neurons in previous layer

        conf.addVariable(INPUT_WEIGHT_KEY);
        conf.addVariable(RECURRENT_WEIGHT_KEY);
        conf.addVariable(BIAS_KEY);

        int length = numParams(conf);
        if (paramsView.length() != length)
            throw new IllegalStateException(
                            "Expected params view of length " + length + ", got length " + paramsView.length());

        int nParamsIn = nLast * (4 * nL);
        int nParamsRecurrent = nL * (4 * nL);
        int nBias = 4 * nL;
        INDArray inputWeightView = paramsView.get(NDArrayIndex.point(0), NDArrayIndex.interval(0, nParamsIn));
        INDArray recurrentWeightView = paramsView.get(NDArrayIndex.point(0),
                        NDArrayIndex.interval(nParamsIn, nParamsIn + nParamsRecurrent));
        INDArray biasView = paramsView.get(NDArrayIndex.point(0),
                        NDArrayIndex.interval(nParamsIn + nParamsRecurrent, nParamsIn + nParamsRecurrent + nBias));

        if (initializeParams) {
            int fanIn = nL;
            int fanOut = nLast + nL;
            int[] inputWShape = new int[] {nLast, 4 * nL};
            int[] recurrentWShape = new int[] {nL, 4 * nL};

            params.put(INPUT_WEIGHT_KEY, WeightInitUtil.initWeights(fanIn, fanOut, inputWShape,
                            layerConf.getWeightInit(), dist, inputWeightView));
            params.put(RECURRENT_WEIGHT_KEY, WeightInitUtil.initWeights(fanIn, fanOut, recurrentWShape,
                            layerConf.getWeightInit(), dist, recurrentWeightView));
            biasView.put(new INDArrayIndex[] {NDArrayIndex.point(0), NDArrayIndex.interval(nL, 2 * nL)},
                            Nd4j.valueArrayOf(1, nL, forgetGateInit)); //Order: input, forget, output, input modulation, i.e., IFOG}
            /*The above line initializes the forget gate biases to specified value.
             * See Sutskever PhD thesis, pg19:
             * "it is important for [the forget gate activations] to be approximately 1 at the early stages of learning,
             *  which is accomplished by initializing [the forget gate biases] to a large value (such as 5). If it is
             *  not done, it will be harder to learn long range dependencies because the smaller values of the forget
             *  gates will create a vanishing gradients problem."
             *  http://www.cs.utoronto.ca/~ilya/pubs/ilya_sutskever_phd_thesis.pdf
             */
            params.put(BIAS_KEY, biasView);
        } else {
            params.put(INPUT_WEIGHT_KEY, WeightInitUtil.reshapeWeights(new int[] {nLast, 4 * nL}, inputWeightView));
            params.put(RECURRENT_WEIGHT_KEY,
                            WeightInitUtil.reshapeWeights(new int[] {nL, 4 * nL}, recurrentWeightView));
            params.put(BIAS_KEY, biasView);
        }

        return params;
    }

    @Override
    public Map<String, INDArray> getGradientsFromFlattened(NeuralNetConfiguration conf, INDArray gradientView) {
        org.deeplearning4j.nn.conf.layers.LSTM layerConf = (org.deeplearning4j.nn.conf.layers.LSTM) conf.getLayer();

        int nL = layerConf.getNOut(); //i.e., n neurons in this layer
        int nLast = layerConf.getNIn(); //i.e., n neurons in previous layer

        int length = numParams(conf);
        if (gradientView.length() != length)
            throw new IllegalStateException(
                            "Expected gradient view of length " + length + ", got length " + gradientView.length());

        int nParamsIn = nLast * (4 * nL);
        int nParamsRecurrent = nL * (4 * nL);
        int nBias = 4 * nL;
        INDArray inputWeightGradView = gradientView.get(NDArrayIndex.point(0), NDArrayIndex.interval(0, nParamsIn))
                        .reshape('f', nLast, 4 * nL);
        INDArray recurrentWeightGradView = gradientView
                        .get(NDArrayIndex.point(0), NDArrayIndex.interval(nParamsIn, nParamsIn + nParamsRecurrent))
                        .reshape('f', nL, 4 * nL);
        INDArray biasGradView = gradientView.get(NDArrayIndex.point(0),
                        NDArrayIndex.interval(nParamsIn + nParamsRecurrent, nParamsIn + nParamsRecurrent + nBias)); //already a row vector

        Map<String, INDArray> out = new LinkedHashMap<>();
        out.put(INPUT_WEIGHT_KEY, inputWeightGradView);
        out.put(RECURRENT_WEIGHT_KEY, recurrentWeightGradView);
        out.put(BIAS_KEY, biasGradView);

        return out;
    }
}
