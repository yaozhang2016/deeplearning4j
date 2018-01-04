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
package org.deeplearning4j.nn.modelimport.keras.layers.normalization;

import org.deeplearning4j.nn.conf.layers.BatchNormalization;
import org.deeplearning4j.nn.modelimport.keras.config.Keras1LayerConfiguration;
import org.deeplearning4j.nn.modelimport.keras.config.Keras2LayerConfiguration;
import org.deeplearning4j.nn.modelimport.keras.config.KerasLayerConfiguration;
import org.junit.Test;
import org.nd4j.linalg.learning.config.Nesterovs;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * @author Max Pumperla
 */
public class KerasBatchNormalizationTest {
    private final String LAYER_NAME = "batch_norm_layer";

    private Integer keras1 = 1;
    private Integer keras2 = 2;
    private Keras1LayerConfiguration conf1 = new Keras1LayerConfiguration();
    private Keras2LayerConfiguration conf2 = new Keras2LayerConfiguration();


    @Test
    public void testBatchnormLayer() throws Exception {
        buildBatchNormalizationLayer(conf1, keras1);
        buildBatchNormalizationLayer(conf2, keras2);
    }


    public void buildBatchNormalizationLayer(KerasLayerConfiguration conf, Integer kerasVersion) throws Exception {
        double epsilon = 1E-5;
        double momentum = 0.99;

        KerasBatchNormalization batchNormalization = new KerasBatchNormalization(kerasVersion);

        Map<String, Object> layerConfig = new HashMap<String, Object>();
        layerConfig.put(conf.getLAYER_FIELD_CLASS_NAME(), conf.getLAYER_CLASS_NAME_BATCHNORMALIZATION());
        Map<String, Object> config = new HashMap<String, Object>();
        config.put(conf.getLAYER_FIELD_NAME(), LAYER_NAME);
        config.put(batchNormalization.getLAYER_FIELD_EPSILON(), epsilon);
        config.put(batchNormalization.getLAYER_FIELD_MOMENTUM(), momentum);
        config.put(batchNormalization.getLAYER_FIELD_GAMMA_REGULARIZER(), null);
        config.put(batchNormalization.getLAYER_FIELD_BETA_REGULARIZER(), null);
        config.put(batchNormalization.getLAYER_FIELD_MODE(), 0);
        config.put(batchNormalization.getLAYER_FIELD_AXIS(), 3);
        layerConfig.put(conf.getLAYER_FIELD_CONFIG(), config);
        layerConfig.put(conf.getLAYER_FIELD_KERAS_VERSION(), kerasVersion);

        BatchNormalization layer = new KerasBatchNormalization(layerConfig).getBatchNormalizationLayer();
        assertEquals(LAYER_NAME, layer.getLayerName());
        assertEquals(epsilon, layer.getEps(), 0.0);
    }
}
