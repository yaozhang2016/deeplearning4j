/*-
 *
 *  * Copyright 2017 Skymind,Inc.
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
package org.deeplearning4j.nn.modelimport.keras.layers.core;

import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.modelimport.keras.config.Keras1LayerConfiguration;
import org.deeplearning4j.nn.modelimport.keras.config.Keras2LayerConfiguration;
import org.deeplearning4j.nn.modelimport.keras.config.KerasLayerConfiguration;
import org.deeplearning4j.nn.modelimport.keras.exceptions.InvalidKerasConfigurationException;
import org.deeplearning4j.nn.modelimport.keras.exceptions.UnsupportedKerasConfigurationException;
import org.deeplearning4j.nn.modelimport.keras.preprocessors.ReshapePreprocessor;
import org.junit.Assert;
import org.junit.Test;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * @author Max Pumperla
 */
public class KerasReshapeTest {

    String LAYER_NAME = "reshape";

    private Integer keras1 = 1;
    private Integer keras2 = 2;
    private Keras1LayerConfiguration conf1 = new Keras1LayerConfiguration();
    private Keras2LayerConfiguration conf2 = new Keras2LayerConfiguration();


    @Test
    public void testReshapeLayer() throws Exception {
        buildLReshapeLayer(conf1, keras1);
        buildLReshapeLayer(conf2, keras2);
    }

    @Test
    public void testReshapeDynamicMinibatch() throws Exception {
        testDynamicMinibatches(conf1, keras1);
        testDynamicMinibatches(conf2, keras2);
    }

    private void buildLReshapeLayer(KerasLayerConfiguration conf, Integer kerasVersion) throws Exception {
        int[] targetShape = new int[]{10, 5};
        List<Integer> targetShapeList = new ArrayList<>();
        targetShapeList.add(targetShape[0]);
        targetShapeList.add(targetShape[1]);
        ReshapePreprocessor preProcessor = getReshapePreProcesser(conf, kerasVersion, targetShapeList);
        assertEquals(preProcessor.getTargetShape()[0], targetShape[0]);
        assertEquals(preProcessor.getTargetShape()[1], targetShape[1]);
    }

    private ReshapePreprocessor getReshapePreProcesser(KerasLayerConfiguration conf, Integer kerasVersion,
            List<Integer> targetShapeList)
            throws InvalidKerasConfigurationException, UnsupportedKerasConfigurationException {
        Map<String, Object> layerConfig = new HashMap<>();
        layerConfig.put(conf.getLAYER_FIELD_CLASS_NAME(), conf.getLAYER_CLASS_NAME_RESHAPE());
        Map<String, Object> config = new HashMap<>();
        String LAYER_FIELD_TARGET_SHAPE = "target_shape";
        config.put(LAYER_FIELD_TARGET_SHAPE, targetShapeList);
        config.put(conf.getLAYER_FIELD_NAME(), LAYER_NAME);
        layerConfig.put(conf.getLAYER_FIELD_CONFIG(), config);
        layerConfig.put(conf.getLAYER_FIELD_KERAS_VERSION(), kerasVersion);
        InputType inputType = InputType.InputTypeFeedForward.feedForward(20);
        ReshapePreprocessor preProcessor =
                (ReshapePreprocessor) new KerasReshape(layerConfig).getInputPreprocessor(inputType);
        return preProcessor;
    }

    private void testDynamicMinibatches(KerasLayerConfiguration conf, Integer kerasVersion) throws InvalidKerasConfigurationException, UnsupportedKerasConfigurationException {
        List<Integer> targetShape = Arrays.asList(20);
        ReshapePreprocessor preproceser = getReshapePreProcesser(conf, kerasVersion, targetShape);
        INDArray r1 = preproceser.preProcess(Nd4j.zeros(10, 20), 10);
        INDArray r2 = preproceser.preProcess(Nd4j.zeros(5, 20), 5);
        Assert.assertArrayEquals(r2.shape(), new int[] {5, 20});
        Assert.assertArrayEquals(r1.shape(), new int[] {10, 20});
    }
}
