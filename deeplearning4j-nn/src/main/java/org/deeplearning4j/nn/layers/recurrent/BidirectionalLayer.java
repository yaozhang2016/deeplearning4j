package org.deeplearning4j.nn.layers.recurrent;

import lombok.NonNull;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.api.MaskState;
import org.deeplearning4j.nn.api.layers.RecurrentLayer;
import org.deeplearning4j.nn.conf.CacheMode;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.recurrent.Bidirectional;
import org.deeplearning4j.nn.gradient.DefaultGradient;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.nn.params.BidirectionalParamInitializer;
import org.deeplearning4j.optimize.api.ConvexOptimizer;
import org.deeplearning4j.optimize.api.IterationListener;
import org.deeplearning4j.util.TimeSeriesUtils;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.primitives.Pair;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.nd4j.linalg.indexing.NDArrayIndex.all;
import static org.nd4j.linalg.indexing.NDArrayIndex.interval;
import static org.nd4j.linalg.indexing.NDArrayIndex.point;

/**
 * Bidirectional is a "wrapper" layer: it wraps any uni-directional RNN layer to make it bidirectional.<br>
 * Note that multiple different modes are supported - these specify how the activations should be combined from
 * the forward and backward RNN networks. See {@link Bidirectional.Mode} javadoc for more details.<br>
 * Parameters are not shared here - there are 2 separate copies of the wrapped RNN layer, each with separate parameters.
 * <br>
 * Usage: {@code .layer(new Bidirectional(new LSTM.Builder()....build())}
 *
 * @author Alex Black
 */
public class BidirectionalLayer implements RecurrentLayer {

    private NeuralNetConfiguration conf;
    private RecurrentLayer fwd;
    private RecurrentLayer bwd;

    private Bidirectional layerConf;
    private INDArray paramsView;
    private INDArray gradientView;
    private transient Map<String, INDArray> gradientViews;
    private INDArray input;

    //Next 2 variables: used *only* for MUL case (needed for backprop)
    private INDArray outFwd;
    private INDArray outBwd;

    public BidirectionalLayer(@NonNull NeuralNetConfiguration conf, @NonNull RecurrentLayer fwd, @NonNull RecurrentLayer bwd) {
        this.conf = conf;
        this.fwd = fwd;
        this.bwd = bwd;
        this.layerConf = (Bidirectional) conf.getLayer();
    }

    @Override
    public INDArray rnnTimeStep(INDArray input) {
        throw new UnsupportedOperationException("Cannot RnnTimeStep bidirectional layers");
    }

    @Override
    public Map<String, INDArray> rnnGetPreviousState() {
        throw new UnsupportedOperationException("Not supported: cannot RnnTimeStep bidirectional layers therefore " +
                "no previous state is supported");
    }

    @Override
    public void rnnSetPreviousState(Map<String, INDArray> stateMap) {
        throw new UnsupportedOperationException("Not supported: cannot RnnTimeStep bidirectional layers therefore " +
                "no previous state is supported");
    }

    @Override
    public void rnnClearPreviousState() {
        //No op
    }

    @Override
    public INDArray rnnActivateUsingStoredState(INDArray input, boolean training, boolean storeLastForTBPTT) {
        throw new UnsupportedOperationException("Not supported: cannot use this method (or truncated BPTT) with bidirectional layers");
    }

    @Override
    public Map<String, INDArray> rnnGetTBPTTState() {
        throw new UnsupportedOperationException("Not supported: cannot use this method (or truncated BPTT) with bidirectional layers");
    }

    @Override
    public void rnnSetTBPTTState(Map<String, INDArray> state) {
        throw new UnsupportedOperationException("Not supported: cannot use this method (or truncated BPTT) with bidirectional layers");
    }

    @Override
    public Pair<Gradient, INDArray> tbpttBackpropGradient(INDArray epsilon, int tbpttBackLength) {
        throw new UnsupportedOperationException("Not supported: cannot use this method (or truncated BPTT) with bidirectional layers");
    }

    @Override
    public void setCacheMode(CacheMode mode) {
        fwd.setCacheMode(mode);
        bwd.setCacheMode(mode);
    }

    @Override
    public double calcL2(boolean backpropOnlyParams) {
        return fwd.calcL2(backpropOnlyParams) + bwd.calcL2(backpropOnlyParams);
    }

    @Override
    public double calcL1(boolean backpropOnlyParams) {
        return fwd.calcL1(backpropOnlyParams) + bwd.calcL1(backpropOnlyParams);
    }

    @Override
    public Type type() {
        return Type.RECURRENT;
    }

    @Override
    public Pair<Gradient, INDArray> backpropGradient(INDArray epsilon) {
        INDArray eFwd;
        INDArray eBwd;

        int n = epsilon.size(1)/2;
        switch (layerConf.getMode()){
            case ADD:
                eFwd = epsilon;
                eBwd = epsilon;
                break;
            case MUL:
                eFwd = epsilon.dup(epsilon.ordering()).muli(outBwd);
                eBwd = epsilon.dup(epsilon.ordering()).muli(outFwd);
                break;
            case AVERAGE:
                eFwd = epsilon.dup(epsilon.ordering()).muli(0.5);
                eBwd = eFwd;
                break;
            case CONCAT:
                eFwd = epsilon.get(all(), interval(0,n), all());
                eBwd = epsilon.get(all(), interval(n, 2*n), all());
                break;
            default:
                throw new RuntimeException("Unknown mode: " + layerConf.getMode());
        }

        eBwd = TimeSeriesUtils.reverseTimeSeries(eBwd);

        Pair<Gradient,INDArray> g1 = fwd.backpropGradient(eFwd);
        Pair<Gradient,INDArray> g2 = bwd.backpropGradient(eBwd);

        Gradient g = new DefaultGradient(gradientView);
        for(Map.Entry<String,INDArray> e : g1.getFirst().gradientForVariable().entrySet()){
            g.gradientForVariable().put(BidirectionalParamInitializer.FORWARD_PREFIX + e.getKey(), e.getValue());
        }
        for(Map.Entry<String,INDArray> e : g2.getFirst().gradientForVariable().entrySet()){
            g.gradientForVariable().put(BidirectionalParamInitializer.BACKWARD_PREFIX + e.getKey(), e.getValue());
        }

        INDArray g2Reversed = TimeSeriesUtils.reverseTimeSeries(g2.getRight());
        INDArray epsOut = g1.getRight().addi(g2Reversed);

        return new Pair<>(g, epsOut);
    }

    @Override
    public INDArray preOutput(INDArray x) {
        return activate(x);
    }

    @Override
    public INDArray preOutput(INDArray x, TrainingMode training) {
        return activate(x, training);
    }

    @Override
    public INDArray activate(TrainingMode training) {
        return activate(training == TrainingMode.TRAIN);
    }

    @Override
    public INDArray activate(INDArray input, TrainingMode training) {
        setInput(input);
        return activate(training);
    }

    @Override
    public INDArray preOutput(INDArray x, boolean training) {
        return activate(x, training);
    }

    @Override
    public INDArray activate(boolean training) {
        INDArray out1 = fwd.activate(training);
        INDArray out2 = bwd.activate(training);
        out2 = TimeSeriesUtils.reverseTimeSeries(out2);

        switch (layerConf.getMode()){
            case ADD:
                return out1.addi(out2);
            case MUL:
                //TODO may be more efficient ways than this...
                this.outFwd = out1.detach();
                this.outBwd = out2.detach();
                return out1.mul(out2);
            case AVERAGE:
                return out1.addi(out2).muli(0.5);
            case CONCAT:
                return Nd4j.concat(1, out1, out2);
            default:
                throw new RuntimeException("Unknown mode: " + layerConf.getMode());
        }
    }

    @Override
    public INDArray activate(INDArray input, boolean training) {
        setInput(input);
        return activate(training);
    }

    @Override
    public INDArray activate() {
        return activate(false);
    }

    @Override
    public INDArray activate(INDArray input) {
        setInput(input);
        return activate();
    }

    @Override
    public Layer transpose() {
        throw new UnsupportedOperationException("Cannot transpose layer");
    }

    @Override
    public Layer clone() {
        throw new UnsupportedOperationException("Clone not supported");
    }

    @Override
    public Collection<IterationListener> getListeners() {
        return fwd.getListeners();
    }

    @Override
    public void setListeners(IterationListener... listeners) {
        fwd.setListeners(listeners);
        bwd.setListeners(listeners);
    }

    @Override
    public void addListeners(IterationListener... listener) {
        fwd.addListeners(listener);
        bwd.addListeners(listener);
    }

    @Override
    public void fit() {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public void update(Gradient gradient) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public void update(INDArray gradient, String paramType) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public double score() {
        return fwd.score() + bwd.score();
    }

    @Override
    public void computeGradientAndScore() {
        fwd.computeGradientAndScore();
        bwd.computeGradientAndScore();
    }

    @Override
    public void accumulateScore(double accum) {
        fwd.accumulateScore(accum);
        bwd.accumulateScore(accum);
    }

    @Override
    public INDArray params() {
        return paramsView;
    }

    @Override
    public int numParams() {
        return fwd.numParams() + bwd.numParams();
    }

    @Override
    public int numParams(boolean backwards) {
        return fwd.numParams(backwards) + bwd.numParams(backwards);
    }

    @Override
    public void setParams(INDArray params) {
        this.paramsView.assign(params);
    }

    @Override
    public void setParamsViewArray(INDArray params) {
        this.paramsView = params;
        int n = params.length();
        fwd.setParamsViewArray(params.get(point(0), interval(0, n)));
        bwd.setParamsViewArray(params.get(point(0), interval(n, 2*n)));
    }

    @Override
    public INDArray getGradientsViewArray() {
        return gradientView;
    }

    @Override
    public void setBackpropGradientsViewArray(INDArray gradients) {
        if (this.paramsView != null && gradients.length() != numParams())
            throw new IllegalArgumentException("Invalid input: expect gradients array of length " + numParams(true)
                    + ", got array of length " + gradients.length());

        this.gradientView = gradients;
        int n = gradients.length() / 2;
        INDArray g1 = gradients.get(point(0), interval(0,n));
        INDArray g2 = gradients.get(point(0), interval(n, 2*n));
        fwd.setBackpropGradientsViewArray(g1);
        bwd.setBackpropGradientsViewArray(g2);
    }

    @Override
    public void fit(INDArray data) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public void iterate(INDArray input) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public Gradient gradient() {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public Pair<Gradient, Double> gradientAndScore() {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public int batchSize() {
        return fwd.batchSize();
    }

    @Override
    public NeuralNetConfiguration conf() {
        return conf;
    }

    @Override
    public void setConf(NeuralNetConfiguration conf) {
        this.conf = conf;
    }

    @Override
    public INDArray input() {
        return input;
    }

    @Override
    public void validateInput() {
        //no op
    }

    @Override
    public ConvexOptimizer getOptimizer() {
        return null;
    }

    @Override
    public INDArray getParam(String param) {
        String sub = param.substring(1);
        if(param.startsWith(BidirectionalParamInitializer.FORWARD_PREFIX)){
            return fwd.getParam(sub);
        } else {
            return bwd.getParam(sub);
        }
    }

    @Override
    public void initParams() {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public Map<String, INDArray> paramTable() {
        return paramTable(false);
    }

    @Override
    public Map<String, INDArray> paramTable(boolean backpropParamsOnly) {
        Map<String,INDArray> m = new LinkedHashMap<>();
        for(Map.Entry<String,INDArray> e : fwd.paramTable(backpropParamsOnly).entrySet()){
            m.put(BidirectionalParamInitializer.FORWARD_PREFIX + e.getKey(), e.getValue());
        }
        for(Map.Entry<String,INDArray> e : bwd.paramTable(backpropParamsOnly).entrySet()){
            m.put(BidirectionalParamInitializer.BACKWARD_PREFIX + e.getKey(), e.getValue());
        }
        return m;
    }

    @Override
    public void setParamTable(Map<String, INDArray> paramTable) {
        for(Map.Entry<String,INDArray> e : paramTable.entrySet()){
            setParam(e.getKey(), e.getValue());
        }
    }

    @Override
    public void setParam(String key, INDArray val) {
        String sub = key.substring(1);
        if(key.startsWith(BidirectionalParamInitializer.FORWARD_PREFIX)){
            fwd.setParam(sub, val);
        } else {
            bwd.setParam(sub, val);
        }
    }

    @Override
    public void clear() {
        fwd.clear();
        bwd.clear();
        input = null;
        outFwd = null;
        outBwd = null;
    }

    @Override
    public void applyConstraints(int iteration, int epoch) {
        fwd.applyConstraints(iteration, epoch);
        bwd.applyConstraints(iteration, epoch);
    }

    @Override
    public void init() {
        //No op
    }

    @Override
    public void setListeners(Collection<IterationListener> listeners) {
        fwd.setListeners(listeners);
        bwd.setListeners(listeners);
    }

    @Override
    public void setIndex(int index) {
        fwd.setIndex(index);
        bwd.setIndex(index);
    }

    @Override
    public int getIndex() {
        return fwd.getIndex();
    }

    @Override
    public int getIterationCount() {
        return fwd.getIterationCount();
    }

    @Override
    public int getEpochCount() {
        return fwd.getEpochCount();
    }

    @Override
    public void setIterationCount(int iterationCount) {
        fwd.setIterationCount(iterationCount);
        bwd.setIterationCount(iterationCount);
    }

    @Override
    public void setEpochCount(int epochCount) {
        fwd.setEpochCount(epochCount);
        bwd.setEpochCount(epochCount);
    }

    @Override
    public void setInput(INDArray input) {
        this.input = input;
        fwd.setInput(input);
        bwd.setInput(TimeSeriesUtils.reverseTimeSeries(input));
    }

    @Override
    public void setInputMiniBatchSize(int size) {
        fwd.setInputMiniBatchSize(size);
        bwd.setInputMiniBatchSize(size);
    }

    @Override
    public int getInputMiniBatchSize() {
        return fwd.getInputMiniBatchSize();
    }

    @Override
    public void setMaskArray(INDArray maskArray) {
        fwd.setMaskArray(maskArray);
        bwd.setMaskArray(TimeSeriesUtils.reverseTimeSeriesMask(maskArray));
    }

    @Override
    public INDArray getMaskArray() {
        return fwd.getMaskArray();
    }

    @Override
    public boolean isPretrainLayer() {
        return fwd.isPretrainLayer();
    }

    @Override
    public void clearNoiseWeightParams() {
        fwd.clearNoiseWeightParams();
        bwd.clearNoiseWeightParams();
    }

    @Override
    public Pair<INDArray, MaskState> feedForwardMaskArray(INDArray maskArray, MaskState currentMaskState, int minibatchSize) {
        Pair<INDArray,MaskState> ret = fwd.feedForwardMaskArray(maskArray, currentMaskState, minibatchSize);
        bwd.feedForwardMaskArray(TimeSeriesUtils.reverseTimeSeriesMask(maskArray), currentMaskState, minibatchSize);
        return ret;
    }
}
