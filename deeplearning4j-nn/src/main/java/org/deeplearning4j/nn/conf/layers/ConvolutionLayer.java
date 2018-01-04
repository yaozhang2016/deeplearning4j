package org.deeplearning4j.nn.conf.layers;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.api.ParamInitializer;
import org.deeplearning4j.nn.conf.*;
import org.deeplearning4j.nn.conf.distribution.Distribution;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.memory.LayerMemoryReport;
import org.deeplearning4j.nn.conf.memory.MemoryReport;
import org.deeplearning4j.nn.params.ConvolutionParamInitializer;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.optimize.api.IterationListener;
import org.deeplearning4j.util.ConvolutionUtils;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.activations.IActivation;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Adam Gibson
 */
@Data
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class ConvolutionLayer extends FeedForwardLayer {
    protected boolean hasBias = true;
    protected ConvolutionMode convolutionMode = ConvolutionMode.Truncate; //Default to truncate here - default for 0.6.0 and earlier networks on JSON deserialization
    protected int dilation[] = new int[]{1,1};
    protected int[] kernelSize; // Square filter
    protected int[] stride; // Default is 2. Down-sample by a factor of 2
    protected int[] padding;

    /** The "PREFER_FASTEST" mode will pick the fastest algorithm for the specified parameters
     * from the {@link FwdAlgo}, {@link BwdFilterAlgo}, and {@link BwdDataAlgo} lists, but they
     * may be very memory intensive, so if weird errors occur when using cuDNN, please try the
     * "NO_WORKSPACE" mode. Alternatively, it is possible to specify the algorithm manually by
     * setting the "USER_SPECIFIED" mode, but this is not recommended.
     *
     * Note: Currently only supported with cuDNN.
     */
    public enum AlgoMode {
        NO_WORKSPACE, PREFER_FASTEST, USER_SPECIFIED
    }

    /** The forward algorithm to use when {@link AlgoMode} is set to "USER_SPECIFIED".
     *
     * Note: Currently only supported with cuDNN.
     */
    public enum FwdAlgo {
        IMPLICIT_GEMM, IMPLICIT_PRECOMP_GEMM, GEMM, DIRECT, FFT, FFT_TILING, WINOGRAD, WINOGRAD_NONFUSED, COUNT
    }

    /** The backward filter algorithm to use when {@link AlgoMode} is set to "USER_SPECIFIED".
     *
     * Note: Currently only supported with cuDNN.
     */
    public enum BwdFilterAlgo {
        ALGO_0, ALGO_1, FFT, ALGO_3, WINOGRAD, WINOGRAD_NONFUSED, FFT_TILING, COUNT
    }

    /** The backward data algorithm to use when {@link AlgoMode} is set to "USER_SPECIFIED".
     *
     * Note: Currently only supported with cuDNN.
     */
    public enum BwdDataAlgo {
        ALGO_0, ALGO_1, FFT, FFT_TILING, WINOGRAD, WINOGRAD_NONFUSED, COUNT
    }

    /** Defaults to "PREFER_FASTEST", but "NO_WORKSPACE" uses less memory. */
    protected AlgoMode cudnnAlgoMode = AlgoMode.PREFER_FASTEST;
    protected FwdAlgo cudnnFwdAlgo;
    protected BwdFilterAlgo cudnnBwdFilterAlgo;
    protected BwdDataAlgo cudnnBwdDataAlgo;

    /**
     * ConvolutionLayer
     * nIn in the input layer is the number of channels
     * nOut is the number of filters to be used in the net or in other words the depth
     * The builder specifies the filter/kernel size, the stride and padding
     * The pooling layer takes the kernel size
     */
    protected ConvolutionLayer(BaseConvBuilder<?> builder) {
        super(builder);
        this.hasBias = builder.hasBias;
        this.convolutionMode = builder.convolutionMode;
        this.dilation = builder.dilation;
        if (builder.kernelSize.length != 2)
            throw new IllegalArgumentException("Kernel size of should be rows x columns (a 2d array)");
        this.kernelSize = builder.kernelSize;
        if (builder.stride.length != 2)
            throw new IllegalArgumentException("Stride should include stride for rows and columns (a 2d array)");
        this.stride = builder.stride;
        if (builder.padding.length != 2)
            throw new IllegalArgumentException("Padding should include padding for rows and columns (a 2d array)");
        this.padding = builder.padding;
        this.cudnnAlgoMode = builder.cudnnAlgoMode;
        this.cudnnFwdAlgo = builder.cudnnFwdAlgo;
        this.cudnnBwdFilterAlgo = builder.cudnnBwdFilterAlgo;
        this.cudnnBwdDataAlgo = builder.cudnnBwdDataAlgo;

        initializeConstraints(builder);
    }

    public boolean hasBias(){
        return hasBias;
    }

    @Override
    public ConvolutionLayer clone() {
        ConvolutionLayer clone = (ConvolutionLayer) super.clone();
        if (clone.kernelSize != null)
            clone.kernelSize = clone.kernelSize.clone();
        if (clone.stride != null)
            clone.stride = clone.stride.clone();
        if (clone.padding != null)
            clone.padding = clone.padding.clone();
        return clone;
    }

    @Override
    public Layer instantiate(NeuralNetConfiguration conf, Collection<IterationListener> iterationListeners,
                    int layerIndex, INDArray layerParamsView, boolean initializeParams) {
        LayerValidation.assertNInNOutSet("ConvolutionLayer", getLayerName(), layerIndex, getNIn(), getNOut());

        org.deeplearning4j.nn.layers.convolution.ConvolutionLayer ret =
                        new org.deeplearning4j.nn.layers.convolution.ConvolutionLayer(conf);
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
        return ConvolutionParamInitializer.getInstance();
    }

    @Override
    public InputType getOutputType(int layerIndex, InputType inputType) {
        if (inputType == null || inputType.getType() != InputType.Type.CNN) {
            throw new IllegalStateException("Invalid input for Convolution layer (layer name=\"" + getLayerName()
                            + "\"): Expected CNN input, got " + inputType);
        }

        return InputTypeUtil.getOutputTypeCnnLayers(inputType, kernelSize, stride, padding, dilation,
                convolutionMode, nOut, layerIndex, getLayerName(), ConvolutionLayer.class);
    }

    @Override
    public void setNIn(InputType inputType, boolean override) {
        if (inputType == null || inputType.getType() != InputType.Type.CNN) {
            throw new IllegalStateException("Invalid input for Convolution layer (layer name=\"" + getLayerName()
                            + "\"): Expected CNN input, got " + inputType);
        }

        if (nIn <= 0 || override) {
            InputType.InputTypeConvolutional c = (InputType.InputTypeConvolutional) inputType;
            this.nIn = c.getDepth();
        }
    }

    @Override
    public InputPreProcessor getPreProcessorForInputType(InputType inputType) {
        if (inputType == null) {
            throw new IllegalStateException("Invalid input for Convolution layer (layer name=\"" + getLayerName()
                            + "\"): input is null");
        }

        return InputTypeUtil.getPreProcessorForInputTypeCnnLayers(inputType, getLayerName());
    }

    @Override
    public double getL1ByParam(String paramName) {
        switch (paramName) {
            case ConvolutionParamInitializer.WEIGHT_KEY:
                return l1;
            case ConvolutionParamInitializer.BIAS_KEY:
                return l1Bias;
            default:
                throw new IllegalArgumentException("Unknown parameter name: \"" + paramName + "\"");
        }
    }

    @Override
    public double getL2ByParam(String paramName) {
        switch (paramName) {
            case ConvolutionParamInitializer.WEIGHT_KEY:
                return l2;
            case ConvolutionParamInitializer.BIAS_KEY:
                return l2Bias;
            default:
                throw new IllegalArgumentException("Unknown parameter name: \"" + paramName + "\"");
        }
    }

    @Override
    public LayerMemoryReport getMemoryReport(InputType inputType) {
        int paramSize = initializer().numParams(this);
        int updaterStateSize = (int) getIUpdater().stateSize(paramSize);

        InputType.InputTypeConvolutional c = (InputType.InputTypeConvolutional) inputType;
        InputType.InputTypeConvolutional outputType = (InputType.InputTypeConvolutional) getOutputType(-1, inputType);

        //TODO convolution helper memory use... (CuDNN etc)

        //During forward pass: im2col array, mmul (result activations), in-place broadcast add
        int im2colSizePerEx =
                        c.getDepth() * outputType.getHeight() * outputType.getWidth() * kernelSize[0] * kernelSize[1];

        //During training: have im2col array, in-place gradient calculation, then epsilons...
        //But: im2col array may be cached...
        Map<CacheMode, Long> trainWorkingMemoryPerEx = new HashMap<>();
        Map<CacheMode, Long> cachedPerEx = new HashMap<>();

        //During backprop: im2col array for forward pass (possibly cached) + the epsilon6d array required to calculate
        // the 4d epsilons (equal size to input)
        //Note that the eps6d array is same size as im2col
        for (CacheMode cm : CacheMode.values()) {
            long trainWorkingSizePerEx;
            long cacheMemSizePerEx = 0;
            if (cm == CacheMode.NONE) {
                trainWorkingSizePerEx = 2 * im2colSizePerEx;
            } else {
                //im2col is cached, but epsNext2d/eps6d is not
                cacheMemSizePerEx = im2colSizePerEx;
                trainWorkingSizePerEx = im2colSizePerEx;
            }

            if (getIDropout() != null) {
                //Dup on the input before dropout, but only for training
                trainWorkingSizePerEx += inputType.arrayElementsPerExample();
            }

            trainWorkingMemoryPerEx.put(cm, trainWorkingSizePerEx);
            cachedPerEx.put(cm, cacheMemSizePerEx);
        }


        return new LayerMemoryReport.Builder(layerName, ConvolutionLayer.class, inputType, outputType)
                        .standardMemory(paramSize, updaterStateSize)
                        //im2col caching -> only variable size caching
                        .workingMemory(0, im2colSizePerEx, MemoryReport.CACHE_MODE_ALL_ZEROS, trainWorkingMemoryPerEx)
                        .cacheMemory(MemoryReport.CACHE_MODE_ALL_ZEROS, cachedPerEx).build();

    }

    public static class Builder extends BaseConvBuilder<Builder> {

        public Builder(int[] kernelSize, int[] stride, int[] padding) {
            super(kernelSize, stride, padding);
        }

        public Builder(int[] kernelSize, int[] stride) {
            super(kernelSize, stride);
        }

        public Builder(int... kernelSize) {
            super(kernelSize);
        }

        public Builder() {
            super();
        }

        /**
         * Size of the convolution
         * rows/columns
         * @param kernelSize the height and width of the
         *                   kernel
         * @return
         */
        public Builder kernelSize(int... kernelSize) {
            this.kernelSize = kernelSize;
            return this;
        }

        public Builder stride(int... stride) {
            this.stride = stride;
            return this;
        }

        public Builder padding(int... padding) {
            this.padding = padding;
            return this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public ConvolutionLayer build() {
            ConvolutionUtils.validateConvolutionModePadding(convolutionMode, padding);
            ConvolutionUtils.validateCnnKernelStridePadding(kernelSize, stride, padding);

            return new ConvolutionLayer(this);
        }
    }

    protected static abstract class BaseConvBuilder<T extends BaseConvBuilder<T>> extends FeedForwardLayer.Builder<T> {
        protected boolean hasBias = true;
        protected ConvolutionMode convolutionMode = null;
        protected int[] dilation = new int[]{1, 1};
        public int[] kernelSize = new int[] {5, 5};
        protected int[] stride = new int[] {1, 1};
        protected int[] padding = new int[] {0, 0};
        protected AlgoMode cudnnAlgoMode = AlgoMode.PREFER_FASTEST;
        protected FwdAlgo cudnnFwdAlgo;
        protected BwdFilterAlgo cudnnBwdFilterAlgo;
        protected BwdDataAlgo cudnnBwdDataAlgo;


        protected BaseConvBuilder(int[] kernelSize, int[] stride, int[] padding) {
            this.kernelSize = kernelSize;
            this.stride = stride;
            this.padding = padding;
        }

        protected BaseConvBuilder(int[] kernelSize, int[] stride) {
            this.kernelSize = kernelSize;
            this.stride = stride;
        }

        protected BaseConvBuilder(int... kernelSize) {
            this.kernelSize = kernelSize;
        }

        protected BaseConvBuilder() {}

        /**
         * If true (default): include bias parameters in the model. False: no bias.
         *
         * @param hasBias If true: include bias parameters in this model
         */
        public T hasBias(boolean hasBias){
            this.hasBias = hasBias;
            return (T)this;
        }

        /**
         * Set the convolution mode for the Convolution layer.
         * See {@link ConvolutionMode} for more details
         *
         * @param convolutionMode    Convolution mode for layer
         */
        public T convolutionMode(ConvolutionMode convolutionMode) {
            this.convolutionMode = convolutionMode;
            return (T) this;
        }

        /**
         * Kernel dilation. Default: {1, 1}, which is standard convolutions. Used for implementing dilated convolutions,
         * which are also known as atrous convolutions.
         *
         * For more details, see:
         * <a href="https://arxiv.org/abs/1511.07122">Yu and Koltun (2014)</a> and
         * <a href="https://arxiv.org/abs/1412.7062">Chen et al. (2014)</a>, as well as
         * <a href="http://deeplearning.net/software/theano/tutorial/conv_arithmetic.html#dilated-convolutions">
         *     http://deeplearning.net/software/theano/tutorial/conv_arithmetic.html#dilated-convolutions</a><br>
         *
         * @param dilation Dilation for kernel
         */
        public T dilation(int... dilation){
            this.dilation = dilation;
            return (T) this;
        }

        /** Defaults to "PREFER_FASTEST", but "NO_WORKSPACE" uses less memory. */
        public T cudnnAlgoMode(AlgoMode cudnnAlgoMode) {
            this.cudnnAlgoMode = cudnnAlgoMode;
            return (T) this;
        }

        public T cudnnFwdMode(FwdAlgo cudnnFwdAlgo) {
            this.cudnnFwdAlgo = cudnnFwdAlgo;
            return (T) this;
        }

        public T cudnnBwdFilterMode(BwdFilterAlgo cudnnBwdFilterAlgo) {
            this.cudnnBwdFilterAlgo = cudnnBwdFilterAlgo;
            return (T) this;
        }

        public T cudnnBwdDataMode(BwdDataAlgo cudnnBwdDataAlgo) {
            this.cudnnBwdDataAlgo = cudnnBwdDataAlgo;
            return (T) this;
        }
    }
}
