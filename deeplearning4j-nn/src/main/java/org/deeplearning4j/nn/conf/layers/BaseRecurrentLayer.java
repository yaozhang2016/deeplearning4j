package org.deeplearning4j.nn.conf.layers;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.deeplearning4j.nn.api.layers.LayerConstraint;
import org.deeplearning4j.nn.conf.InputPreProcessor;
import org.deeplearning4j.nn.conf.inputs.InputType;

import java.util.Arrays;
import java.util.List;

@Data
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public abstract class BaseRecurrentLayer extends FeedForwardLayer {

    protected BaseRecurrentLayer(Builder builder) {
        super(builder);
    }

    @Override
    public InputType getOutputType(int layerIndex, InputType inputType) {
        if (inputType == null || inputType.getType() != InputType.Type.RNN) {
            throw new IllegalStateException("Invalid input for RNN layer (layer index = " + layerIndex
                            + ", layer name = \"" + getLayerName() + "\"): expect RNN input type with size > 0. Got: "
                            + inputType);
        }

        InputType.InputTypeRecurrent itr = (InputType.InputTypeRecurrent) inputType;

        return InputType.recurrent(nOut, itr.getTimeSeriesLength());
    }

    @Override
    public void setNIn(InputType inputType, boolean override) {
        if (inputType == null || inputType.getType() != InputType.Type.RNN) {
            throw new IllegalStateException("Invalid input for RNN layer (layer name = \"" + getLayerName()
                            + "\"): expect RNN input type with size > 0. Got: " + inputType);
        }

        if (nIn <= 0 || override) {
            InputType.InputTypeRecurrent r = (InputType.InputTypeRecurrent) inputType;
            this.nIn = r.getSize();
        }
    }

    @Override
    public InputPreProcessor getPreProcessorForInputType(InputType inputType) {
        return InputTypeUtil.getPreprocessorForInputTypeRnnLayers(inputType, getLayerName());
    }


    @NoArgsConstructor
    public static abstract class Builder<T extends Builder<T>> extends FeedForwardLayer.Builder<T> {
        protected List<LayerConstraint> recurrentConstraints;
        protected List<LayerConstraint> inputWeightConstraints;

        /**
         * Set constraints to be applied to the RNN recurrent weight parameters of this layer. Default: no constraints.<br>
         * Constraints can be used to enforce certain conditions (non-negativity of parameters, max-norm regularization,
         * etc). These constraints are applied at each iteration, after the parameters have been updated.
         *
         * @param constraints Constraints to apply to the recurrent weight parameters of this layer
         */
        public T constrainRecurrent(LayerConstraint... constraints) {
            this.recurrentConstraints = Arrays.asList(constraints);
            return (T) this;
        }

        /**
         * Set constraints to be applied to the RNN input weight parameters of this layer. Default: no constraints.<br>
         * Constraints can be used to enforce certain conditions (non-negativity of parameters, max-norm regularization,
         * etc). These constraints are applied at each iteration, after the parameters have been updated.
         *
         * @param constraints Constraints to apply to the input weight parameters of this layer
         */
        public T constrainInputWeights(LayerConstraint... constraints) {
            this.inputWeightConstraints = Arrays.asList(constraints);
            return (T) this;
        }
    }

}
