package org.deeplearning4j.nn.layers;

import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.api.MaskState;
import org.deeplearning4j.nn.conf.CacheMode;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.gradient.DefaultGradient;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.optimize.api.ConvexOptimizer;
import org.deeplearning4j.optimize.api.IterationListener;
import org.deeplearning4j.util.OneTimeLogger;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.primitives.Pair;

import java.util.Collection;
import java.util.Map;

/**
 * For purposes of transfer learning
 * A frozen layers wraps another dl4j layer within it.
 * The params of the layer within it are "frozen" or in other words held constant
 * During the forward pass the frozen layer behaves as the layer within it would during test regardless of the training/test mode the network is in.
 * Backprop is skipped since parameters are not be updated.
 * @author susaneraly
 */

@Slf4j
public class FrozenLayer implements Layer {

    private Layer insideLayer;
    private boolean logUpdate = false;
    private boolean logFit = false;
    private boolean logTestMode = false;
    private boolean logGradient = false;
    private Gradient zeroGradient;

    public FrozenLayer(Layer insideLayer) {
        this.insideLayer = insideLayer;
        if (insideLayer instanceof OutputLayer) {
            throw new IllegalArgumentException("Output Layers are not allowed to be frozen " + layerId());
        }
        this.insideLayer = insideLayer;
        this.zeroGradient = new DefaultGradient(insideLayer.params());
        if (insideLayer.paramTable() != null) {
            for (String paramType : insideLayer.paramTable().keySet()) {
                //save memory??
                zeroGradient.setGradientFor(paramType, null);
            }
        }
    }

    @Override
    public void setCacheMode(CacheMode mode) {
        // no-op
    }

    protected String layerId() {
        String name = insideLayer.conf().getLayer().getLayerName();
        return "(layer name: " + (name == null ? "\"\"" : name) + ", layer index: " + insideLayer.getIndex() + ")";
    }

    @Override
    public double calcL2(boolean backpropOnlyParams) {
        return 0;
    }

    @Override
    public double calcL1(boolean backpropOnlyParams) {
        return 0;
    }

    @Override
    public Type type() {
        return insideLayer.type();
    }

    //FIXME
    @Override
    public Pair<Gradient, INDArray> backpropGradient(INDArray epsilon) {
        return new Pair<>(zeroGradient, null);
    }

    @Override
    public INDArray preOutput(INDArray x) {
        return insideLayer.preOutput(x);
    }

    @Override
    public INDArray preOutput(INDArray x, TrainingMode training) {
        logTestMode(training);
        return insideLayer.preOutput(x, TrainingMode.TEST);
    }

    @Override
    public INDArray activate(TrainingMode training) {
        logTestMode(training);
        return insideLayer.activate(TrainingMode.TEST);
    }

    @Override
    public INDArray activate(INDArray input, TrainingMode training) {
        logTestMode(training);
        return insideLayer.activate(input, TrainingMode.TEST);
    }

    @Override
    public INDArray preOutput(INDArray x, boolean training) {
        logTestMode(training);
        return preOutput(x, TrainingMode.TEST);
    }

    @Override
    public INDArray activate(boolean training) {
        logTestMode(training);
        return insideLayer.activate(false);
    }

    @Override
    public INDArray activate(INDArray input, boolean training) {
        logTestMode(training);
        return insideLayer.activate(input, false);
    }

    @Override
    public INDArray activate() {
        return insideLayer.activate();
    }

    @Override
    public INDArray activate(INDArray input) {
        return insideLayer.activate(input);
    }

    @Override
    public Layer transpose() {
        return new FrozenLayer(insideLayer.transpose());
    }

    @Override
    public Layer clone() {
        OneTimeLogger.info(log, "Frozen layers are cloned as their original versions.");
        return new FrozenLayer(insideLayer.clone());
    }

    @Override
    public Collection<IterationListener> getListeners() {
        return insideLayer.getListeners();
    }

    @Override
    public void setListeners(IterationListener... listeners) {
        insideLayer.setListeners(listeners);
    }

    /**
     * This method ADDS additional IterationListener to existing listeners
     *
     * @param listener
     */
    @Override
    public void addListeners(IterationListener... listener) {
        insideLayer.addListeners(listener);
    }

    @Override
    public void fit() {
        if (!logFit) {
            OneTimeLogger.info(log, "Frozen layers cannot be fit. Warning will be issued only once per instance");
            logFit = true;
        }
        //no op
    }

    @Override
    public void update(Gradient gradient) {
        if (!logUpdate) {
            OneTimeLogger.info(log, "Frozen layers will not be updated. Warning will be issued only once per instance");
            logUpdate = true;
        }
        //no op
    }

    @Override
    public void update(INDArray gradient, String paramType) {
        if (!logUpdate) {
            OneTimeLogger.info(log, "Frozen layers will not be updated. Warning will be issued only once per instance");
            logUpdate = true;
        }
        //no op
    }

    @Override
    public double score() {
        return insideLayer.score();
    }

    @Override
    public void computeGradientAndScore() {
        if (!logGradient) {
            OneTimeLogger.info(log,
                            "Gradients for the frozen layer are not set and will therefore will not be updated.Warning will be issued only once per instance");
            logGradient = true;
        }
        insideLayer.score();
        //no op
    }

    @Override
    public void accumulateScore(double accum) {
        insideLayer.accumulateScore(accum);
    }

    @Override
    public INDArray params() {
        return insideLayer.params();
    }

    @Override
    public int numParams() {
        return insideLayer.numParams();
    }

    @Override
    public int numParams(boolean backwards) {
        return insideLayer.numParams(backwards);
    }

    @Override
    public void setParams(INDArray params) {
        insideLayer.setParams(params);
    }

    @Override
    public void setParamsViewArray(INDArray params) {
        insideLayer.setParamsViewArray(params);
    }

    @Override
    public INDArray getGradientsViewArray() {
        return insideLayer.getGradientsViewArray();
    }

    @Override
    public void setBackpropGradientsViewArray(INDArray gradients) {
        if (!logGradient) {
            OneTimeLogger.info(log,
                            "Gradients for the frozen layer are not set and will therefore will not be updated.Warning will be issued only once per instance");
            logGradient = true;
        }
        //no-op
    }

    @Override
    public void fit(INDArray data) {
        if (!logFit) {
            OneTimeLogger.info(log, "Frozen layers cannot be fit.Warning will be issued only once per instance");
            logFit = true;
        }
    }

    //FIXME - what is iterate
    @Override
    public void iterate(INDArray input) {
        insideLayer.iterate(input);
    }

    @Override
    public Gradient gradient() {
        return zeroGradient;
    }

    //FIXME
    @Override
    public Pair<Gradient, Double> gradientAndScore() {
        if (!logGradient) {
            OneTimeLogger.info(log,
                            "Gradients for the frozen layer are not set and will therefore will not be updated.Warning will be issued only once per instance");
            logGradient = true;
        }
        return new Pair<>(zeroGradient, insideLayer.score());
    }

    @Override
    public int batchSize() {
        return insideLayer.batchSize();
    }

    @Override
    public NeuralNetConfiguration conf() {
        return insideLayer.conf();
    }

    @Override
    public void setConf(NeuralNetConfiguration conf) {
        insideLayer.setConf(conf);
    }

    @Override
    public INDArray input() {
        return insideLayer.input();
    }

    @Override
    public void validateInput() {
        insideLayer.validateInput();
    }

    @Override
    public ConvexOptimizer getOptimizer() {
        return insideLayer.getOptimizer();
    }

    @Override
    public INDArray getParam(String param) {
        return insideLayer.getParam(param);
    }

    @Override
    public void initParams() {
        insideLayer.initParams();
    }

    @Override
    public Map<String, INDArray> paramTable() {
        return insideLayer.paramTable();
    }

    @Override
    public Map<String, INDArray> paramTable(boolean backpropParamsOnly) {
        return insideLayer.paramTable(backpropParamsOnly);
    }

    @Override
    public void setParamTable(Map<String, INDArray> paramTable) {
        insideLayer.setParamTable(paramTable);
    }

    @Override
    public void setParam(String key, INDArray val) {
        insideLayer.setParam(key, val);
    }

    @Override
    public void clear() {
        insideLayer.clear();
    }

    @Override
    public void applyConstraints(int iteration, int epoch) {
        //No-op
    }

    /**
     * Init the model
     */
    @Override
    public void init() {

    }

    @Override
    public void setListeners(Collection<IterationListener> listeners) {
        insideLayer.setListeners(listeners);
    }

    @Override
    public void setIndex(int index) {
        insideLayer.setIndex(index);
    }

    @Override
    public int getIndex() {
        return insideLayer.getIndex();
    }

    @Override
    public int getIterationCount() {
        return insideLayer.getIterationCount();
    }

    @Override
    public int getEpochCount() {
        return insideLayer.getEpochCount();
    }

    @Override
    public void setIterationCount(int iterationCount) {
        insideLayer.setIterationCount(iterationCount);
    }

    @Override
    public void setEpochCount(int epochCount) {
        insideLayer.setEpochCount(epochCount);
    }

    @Override
    public void setInput(INDArray input) {
        insideLayer.setInput(input);
    }

    @Override
    public void setInputMiniBatchSize(int size) {
        insideLayer.setInputMiniBatchSize(size);
    }

    @Override
    public int getInputMiniBatchSize() {
        return insideLayer.getInputMiniBatchSize();
    }

    @Override
    public void setMaskArray(INDArray maskArray) {
        insideLayer.setMaskArray(maskArray);
    }

    @Override
    public INDArray getMaskArray() {
        return insideLayer.getMaskArray();
    }

    @Override
    public boolean isPretrainLayer() {
        return insideLayer.isPretrainLayer();
    }

    @Override
    public void clearNoiseWeightParams() {
        insideLayer.clearNoiseWeightParams();
    }

    @Override
    public Pair<INDArray, MaskState> feedForwardMaskArray(INDArray maskArray, MaskState currentMaskState,
                    int minibatchSize) {
        return insideLayer.feedForwardMaskArray(maskArray, currentMaskState, minibatchSize);
    }

    public void logTestMode(boolean training) {
        if (!training)
            return;
        if (logTestMode) {
            return;
        } else {
            OneTimeLogger.info(log,
                            "Frozen layer instance found! Frozen layers are treated as always in test mode. Warning will only be issued once per instance");
            logTestMode = true;
        }
    }

    public void logTestMode(TrainingMode training) {
        if (training.equals(TrainingMode.TEST))
            return;
        if (logTestMode) {
            return;
        } else {
            OneTimeLogger.info(log,
                            "Frozen layer instance found! Frozen layers are treated as always in test mode. Warning will only be issued once per instance");
            logTestMode = true;
        }
    }

    public Layer getInsideLayer() {
        return insideLayer;
    }
}


