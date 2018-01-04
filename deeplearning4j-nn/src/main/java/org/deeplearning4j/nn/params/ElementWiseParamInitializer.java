package org.deeplearning4j.nn.params;

import org.apache.commons.math3.util.FastMath;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.FeedForwardLayer;
import org.deeplearning4j.nn.conf.layers.Layer;
import org.deeplearning4j.nn.params.DefaultParamInitializer;
import org.deeplearning4j.nn.weights.WeightInit;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.rng.distribution.Distribution;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.deeplearning4j.nn.weights.WeightInitUtil.DEFAULT_WEIGHT_INIT_ORDER;
import static org.deeplearning4j.nn.weights.WeightInitUtil.initWeights;

/**
 * created by jingshu
 */
public class ElementWiseParamInitializer extends DefaultParamInitializer{

    private static final ElementWiseParamInitializer INSTANCE = new ElementWiseParamInitializer();

    public static ElementWiseParamInitializer getInstance() {
        return INSTANCE;
    }

    @Override
    public int numParams(Layer layer) {
        FeedForwardLayer layerConf = (FeedForwardLayer) layer;
        int nIn = layerConf.getNIn();
        return nIn*2; //weights + bias
    }

    /**
     * Initialize the parameters
     *
     * @param conf             the configuration
     * @param paramsView       a view of the full network (backprop) parameters
     * @param initializeParams if true: initialize the parameters according to the configuration. If false: don't modify the
     *                         values in the paramsView array (but do select out the appropriate subset, reshape etc as required)
     * @return Map of parameters keyed by type (view of the 'paramsView' array)
     */
    @Override
    public Map<String, INDArray> init(NeuralNetConfiguration conf, INDArray paramsView, boolean initializeParams) {
        if (!(conf.getLayer() instanceof org.deeplearning4j.nn.conf.layers.FeedForwardLayer))
            throw new IllegalArgumentException("unsupported layer type: " + conf.getLayer().getClass().getName());

        Map<String, INDArray> params = Collections.synchronizedMap(new LinkedHashMap<String, INDArray>());

        int length = numParams(conf);
        if (paramsView.length() != length)
            throw new IllegalStateException(
                    "Expected params view of length " + length + ", got length " + paramsView.length());

        org.deeplearning4j.nn.conf.layers.FeedForwardLayer layerConf =
                (org.deeplearning4j.nn.conf.layers.FeedForwardLayer) conf.getLayer();
        int nIn = layerConf.getNIn();

        int nWeightParams = nIn ;
        INDArray weightView = paramsView.get(NDArrayIndex.point(0), NDArrayIndex.interval(0, nWeightParams));
        INDArray biasView = paramsView.get(NDArrayIndex.point(0),
                NDArrayIndex.interval(nWeightParams, nWeightParams + nIn));


        params.put(WEIGHT_KEY, createWeightMatrix(conf, weightView, initializeParams));
        params.put(BIAS_KEY, createBias(conf, biasView, initializeParams));
        conf.addVariable(WEIGHT_KEY);
        conf.addVariable(BIAS_KEY);

        return params;
    }

    /**
     * Return a map of gradients (in their standard non-flattened representation), taken from the flattened (row vector) gradientView array.
     * The idea is that operates in exactly the same way as the the paramsView does in
     * thus the position in the view (and, the array orders) must match those of the parameters
     *
     * @param conf         Configuration
     * @param gradientView The flattened gradients array, as a view of the larger array
     * @return A map containing an array by parameter type, that is a view of the full network gradients array
     */
    @Override
    public Map<String, INDArray> getGradientsFromFlattened(NeuralNetConfiguration conf, INDArray gradientView) {
        org.deeplearning4j.nn.conf.layers.FeedForwardLayer layerConf =
                (org.deeplearning4j.nn.conf.layers.FeedForwardLayer) conf.getLayer();
        int nIn = layerConf.getNIn();
        int nOut = layerConf.getNOut();
        int nWeightParams = nIn ;

        INDArray weightGradientView = gradientView.get(NDArrayIndex.point(0), NDArrayIndex.interval(0, nWeightParams));
        INDArray biasView = gradientView.get(NDArrayIndex.point(0),
                NDArrayIndex.interval(nWeightParams, nWeightParams + nOut)); //Already a row vector

        Map<String, INDArray> out = new LinkedHashMap<>();
        out.put(WEIGHT_KEY, weightGradientView);
        out.put(BIAS_KEY, biasView);

        return out;
    }

    protected INDArray createWeightMatrix(int nIn, int nOut, WeightInit weightInit, Distribution dist,
                                          INDArray weightParamView, boolean initializeParameters) {
        int[] shape = new int[] {1,nIn};

        if (initializeParameters) {
            INDArray ret = initWeights(nIn, //Fan in
                    nOut, //Fan out
                    shape, weightInit, dist, DEFAULT_WEIGHT_INIT_ORDER, weightParamView);
            return ret;
        } else {
            return weightParamView;
        }
    }


}

