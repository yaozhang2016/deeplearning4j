package org.deeplearning4j.nn.conf.layers;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.api.ParamInitializer;
import org.deeplearning4j.nn.conf.InputPreProcessor;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.memory.LayerMemoryReport;
import org.deeplearning4j.nn.conf.memory.MemoryReport;
import org.deeplearning4j.nn.params.DefaultParamInitializer;
import org.deeplearning4j.nn.params.EmptyParamInitializer;
import org.deeplearning4j.optimize.api.IterationListener;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.lossfunctions.ILossFunction;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.nd4j.linalg.lossfunctions.LossFunctions.LossFunction;

import java.util.Collection;
import java.util.Map;

/**
 * Recurrent Neural Network Loss Layer.<br>
 * Handles calculation of gradients etc for various objective functions.<br>
 * NOTE: Unlike {@link RnnOutputLayer} this RnnLossLayer does not have any parameters - i.e., there is no time
 * distributed dense component here. Consequently, the output activations size is equal to the input size.<br>
 * Input and output activations are same as other RNN layers: 3 dimensions with shape
 * [miniBatchSize,nIn,timeSeriesLength] and [miniBatchSize,nOut,timeSeriesLength] respectively.
 *
 * @author Alex Black
 * @see RnnOutputLayer
 */
@Data
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class RnnLossLayer extends FeedForwardLayer {

    protected ILossFunction lossFn;

    private RnnLossLayer(Builder builder) {
        super(builder);
        this.lossFn = builder.lossFn;
    }

    @Override
    public Layer instantiate(NeuralNetConfiguration conf, Collection<IterationListener> iterationListeners,
                    int layerIndex, INDArray layerParamsView, boolean initializeParams) {
        org.deeplearning4j.nn.layers.recurrent.RnnLossLayer ret =
                        new org.deeplearning4j.nn.layers.recurrent.RnnLossLayer(conf);
        ret.setListeners(iterationListeners);
        ret.setIndex(layerIndex);
        ret.setParamsViewArray(layerParamsView);
        Map<String, INDArray> paramTable = initializer().init(conf, layerParamsView, initializeParams);
        ret.setParamTable(paramTable);
        ret.setConf(conf);
        return ret;
    }

    @Override
    public ParamInitializer initializer() {
        return EmptyParamInitializer.getInstance();
    }

    @Override
    public InputType getOutputType(int layerIndex, InputType inputType) {
        if (inputType == null || inputType.getType() != InputType.Type.RNN) {
            throw new IllegalStateException("Invalid input type for RnnLossLayer (layer index = " + layerIndex
                            + ", layer name=\"" + getLayerName() + "\"): Expected RNN input, got " + inputType);
        }
        return inputType;
    }

    @Override
    public InputPreProcessor getPreProcessorForInputType(InputType inputType) {
        return InputTypeUtil.getPreprocessorForInputTypeRnnLayers(inputType, getLayerName());
    }

    @Override
    public LayerMemoryReport getMemoryReport(InputType inputType) {
        //During inference and training: dup the input array. But, this counts as *activations* not working memory
        return new LayerMemoryReport.Builder(layerName, LossLayer.class, inputType, inputType).standardMemory(0, 0) //No params
                .workingMemory(0, 0, 0, 0)
                .cacheMemory(MemoryReport.CACHE_MODE_ALL_ZEROS, MemoryReport.CACHE_MODE_ALL_ZEROS) //No caching
                .build();
    }

    @Override
    public void setNIn(InputType inputType, boolean override) {
        //No op
    }


    public static class Builder extends BaseOutputLayer.Builder<Builder> {

        public Builder() {

        }

        public Builder(LossFunctions.LossFunction lossFunction) {
            lossFunction(lossFunction);
        }

        public Builder(ILossFunction lossFunction) {
            this.lossFn = lossFunction;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Builder nIn(int nIn) {
            throw new UnsupportedOperationException("Ths layer has no parameters, thus nIn will always equal nOut.");
        }

        @Override
        @SuppressWarnings("unchecked")
        public Builder nOut(int nOut) {
            throw new UnsupportedOperationException("Ths layer has no parameters, thus nIn will always equal nOut.");
        }

        @Override
        @SuppressWarnings("unchecked")
        public RnnLossLayer build() {
            return new RnnLossLayer(this);
        }
    }
}
