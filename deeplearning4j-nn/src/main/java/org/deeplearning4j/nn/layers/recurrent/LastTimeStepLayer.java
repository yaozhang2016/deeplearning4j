package org.deeplearning4j.nn.layers.recurrent;

import lombok.NonNull;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.api.MaskState;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.nn.layers.wrapper.BaseWrapperLayer;
import org.deeplearning4j.util.TimeSeriesUtils;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.INDArrayIndex;
import org.nd4j.linalg.primitives.Pair;

import java.util.Arrays;

import static org.nd4j.linalg.indexing.NDArrayIndex.all;
import static org.nd4j.linalg.indexing.NDArrayIndex.point;

/**
 * LastTimeStep is a "wrapper" layer: it wraps any RNN layer, and extracts out the last time step during forward pass,
 * and returns it as a row vector (per example). That is, for 3d (time series) input (with shape [minibatch, layerSize,
 * timeSeriesLength]), we take the last time step and return it as a 2d array with shape [minibatch, layerSize].<br>
 * Note that the last time step operation takes into account any mask arrays, if present: thus, variable length time
 * series (in the same minibatch) are handled as expected here.
 *
 * @author Alex Black
 */
public class LastTimeStepLayer extends BaseWrapperLayer {

    private int[] lastTimeStepIdxs;
    private int[] origOutputShape;

    public LastTimeStepLayer(@NonNull Layer underlying){
        super(underlying);
    }

    @Override
    public Type type() {
        return Type.FEED_FORWARD;
    }

    @Override
    public Pair<Gradient, INDArray> backpropGradient(INDArray epsilon) {
        INDArray newEps = Nd4j.create(origOutputShape, 'f');
        if(lastTimeStepIdxs == null){
            //no mask case
            newEps.put(new INDArrayIndex[]{all(), all(), point(origOutputShape[2]-1)}, epsilon);
        } else {
            INDArrayIndex[] arr = new INDArrayIndex[]{null, all(), null};
            //TODO probably possible to optimize this with reshape + scatter ops...
            for( int i=0; i<lastTimeStepIdxs.length; i++ ){
                arr[0] = point(i);
                arr[2] = point(lastTimeStepIdxs[i]);
                newEps.put(arr, epsilon.getRow(i));
            }
        }
        return underlying.backpropGradient(newEps);
    }


    @Override
    public INDArray preOutput(INDArray x) {
        return getLastStep(underlying.preOutput(x));
    }

    @Override
    public INDArray preOutput(INDArray x, TrainingMode training) {
        return getLastStep(underlying.preOutput(x, training));
    }

    @Override
    public INDArray activate(TrainingMode training) {
        return getLastStep(underlying.activate(training));
    }

    @Override
    public INDArray activate(INDArray input, TrainingMode training) {
        return getLastStep(underlying.activate(input, training));
    }

    @Override
    public INDArray preOutput(INDArray x, boolean training) {
        return getLastStep(underlying.activate(x, training));
    }

    @Override
    public INDArray activate(boolean training) {
        return getLastStep(underlying.activate(training));
    }

    @Override
    public INDArray activate(INDArray input, boolean training) {
        INDArray a = underlying.activate(input, training);
        return getLastStep(a);
    }

    @Override
    public INDArray activate() {
        return getLastStep(underlying.activate());
    }

    @Override
    public INDArray activate(INDArray input) {
        return getLastStep(underlying.activate(input));
    }


    @Override
    public Pair<INDArray, MaskState> feedForwardMaskArray(INDArray maskArray, MaskState currentMaskState, int minibatchSize) {
        underlying.feedForwardMaskArray(maskArray, currentMaskState, minibatchSize);

        //Input: 2d mask array, for masking a time series. After extracting out the last time step, we no longer need the mask array
        return new Pair<>(null, currentMaskState);
    }


    private INDArray getLastStep(INDArray in){
        if(in.rank() != 3){
            throw new IllegalArgumentException("Expected rank 3 input with shape [minibatch, layerSize, tsLength]. Got " +
                    "rank " + in.rank() + " with shape " + Arrays.toString(in.shape()));
        }
        origOutputShape = in.shape();

        INDArray mask = underlying.getMaskArray();
        Pair<INDArray,int[]> p = TimeSeriesUtils.pullLastTimeSteps(in, mask);
        lastTimeStepIdxs = p.getSecond();
        return p.getFirst();
    }
}
