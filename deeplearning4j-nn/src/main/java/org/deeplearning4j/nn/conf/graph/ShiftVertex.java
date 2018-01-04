/*-
 *
 *  * Copyright 2016 Skymind,Inc.
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

package org.deeplearning4j.nn.conf.graph;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.inputs.InvalidInputTypeException;
import org.deeplearning4j.nn.conf.memory.LayerMemoryReport;
import org.deeplearning4j.nn.conf.memory.MemoryReport;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.shade.jackson.annotation.JsonProperty;

/**
 * A ShiftVertex is used to shift the activations of a single layer<br>
 * One could use it to add a bias or as part of some other calculation.
 * For example, Highway Layers need them in two places. One, it's often
 * useful to have the gate weights have a large negative bias. (Of course
 * for this, we could just initialize the biases that way.)
 * But, _also_ it needs to do this:
 * (1-sigmoid(weight * input + bias)) (*) input + sigmoid(weight * input + bias) (*) activation(w2 * input + bias) ((*) is hadamard product)
 * So, here, we could have
 * 1. a DenseLayer that does the sigmoid
 * 2. a ScaleVertex(-1) and
 * 3. a ShiftVertex(1)
 * to accomplish that.
 *
 * @author Binesh Bannerjee (binesh_binesh@hotmail.com, @bnsh on gitter)
 */
@Data
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = false)
public class ShiftVertex extends GraphVertex {

    public ShiftVertex(@JsonProperty("shiftFactor") double shiftFactor) {
        this.shiftFactor = shiftFactor;
    }

    protected double shiftFactor = 0.0; // Shift by zero if it's not specified.

    @Override
    public ShiftVertex clone() {
        return new ShiftVertex(shiftFactor);
    }

    @Override
    public int numParams(boolean backprop) {
        return 0;
    }

    @Override
    public int minVertexInputs() {
        return 1;
    }

    @Override
    public int maxVertexInputs() {
        return 1;
    }

    @Override
    public org.deeplearning4j.nn.graph.vertex.GraphVertex instantiate(ComputationGraph graph, String name, int idx,
                    INDArray paramsView, boolean initializeParams) {

        return new org.deeplearning4j.nn.graph.vertex.impl.ShiftVertex(graph, name, idx, shiftFactor);
    }

    @Override
    public InputType getOutputType(int layerIndex, InputType... vertexInputs) throws InvalidInputTypeException {
        if (vertexInputs.length == 1)
            return vertexInputs[0];
        InputType first = vertexInputs[0];

        return first; //Same output shape/size as
    }

    @Override
    public MemoryReport getMemoryReport(InputType... inputTypes) {
        //Do one dup on the forward pass (output activations). Accounted for in output activations.
        InputType outputType = getOutputType(-1, inputTypes);
        return new LayerMemoryReport.Builder(null, ShiftVertex.class, inputTypes[0], outputType).standardMemory(0, 0) //No params
                        .workingMemory(0, 0, 0, 0).cacheMemory(0, 0) //No caching
                        .build();
    }
}
