package org.deeplearning4j.nn.transferlearning;

import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.ComputationGraphConfiguration;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.constraint.UnitNormConstraint;
import org.deeplearning4j.nn.conf.distribution.NormalDistribution;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.layers.*;
import org.deeplearning4j.nn.conf.layers.misc.FrozenLayer;
import org.deeplearning4j.nn.conf.weightnoise.DropConnect;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.weights.WeightInit;
import org.junit.Test;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.config.Adam;
import org.nd4j.linalg.learning.config.Nesterovs;
import org.nd4j.linalg.learning.config.RmsProp;
import org.nd4j.linalg.learning.config.Sgd;
import org.nd4j.linalg.lossfunctions.LossFunctions;

import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Created by susaneraly on 2/17/17.
 */
public class TransferLearningCompGraphTest {

    @Test
    public void simpleFineTune() {

        long rng = 12345L;
        DataSet randomData = new DataSet(Nd4j.rand(10, 4), Nd4j.rand(10, 3));
        //original conf
        ComputationGraphConfiguration confToChange = new NeuralNetConfiguration.Builder().seed(rng)
                        .optimizationAlgo(OptimizationAlgorithm.LBFGS).updater(new Nesterovs(0.01, 0.99))
                        .graphBuilder().addInputs("layer0In").setInputTypes(InputType.feedForward(4))
                        .addLayer("layer0", new DenseLayer.Builder().nIn(4).nOut(3).build(), "layer0In")
                        .addLayer("layer1",
                                        new org.deeplearning4j.nn.conf.layers.OutputLayer.Builder(
                                                        LossFunctions.LossFunction.MCXENT)
                                                                        .activation(Activation.SOFTMAX).nIn(3).nOut(3)
                                                                        .build(),
                                        "layer0")
                        .setOutputs("layer1").build();

        //conf with learning parameters changed
        ComputationGraphConfiguration expectedConf = new NeuralNetConfiguration.Builder().seed(rng)
                        .updater(new RmsProp(0.2))
                        .graphBuilder().addInputs("layer0In")
                        .setInputTypes(InputType.feedForward(4))
                        .addLayer("layer0", new DenseLayer.Builder().nIn(4).nOut(3).build(), "layer0In")
                        .addLayer("layer1",
                                        new org.deeplearning4j.nn.conf.layers.OutputLayer.Builder(
                                                        LossFunctions.LossFunction.MCXENT)
                                                                        .activation(Activation.SOFTMAX).nIn(3).nOut(3)
                                                                        .build(),
                                        "layer0")
                        .setOutputs("layer1").build();
        ComputationGraph expectedModel = new ComputationGraph(expectedConf);
        expectedModel.init();

        ComputationGraph modelToFineTune = new ComputationGraph(expectedConf);
        modelToFineTune.init();
        modelToFineTune.setParams(expectedModel.params());
        //model after applying changes with transfer learning
        ComputationGraph modelNow =
                        new TransferLearning.GraphBuilder(modelToFineTune)
                                        .fineTuneConfiguration(new FineTuneConfiguration.Builder().seed(rng)
                                                        .updater(new RmsProp(0.2)).build())
                                        .build();

        //Check json
        assertEquals(expectedConf.toJson(), modelNow.getConfiguration().toJson());

        //Check params after fit
        modelNow.fit(randomData);
        expectedModel.fit(randomData);
        assertEquals(modelNow.score(), expectedModel.score(), 1e-8);
        assertEquals(modelNow.params(), expectedModel.params());
    }

    @Test
    public void testNoutChanges() {
        DataSet randomData = new DataSet(Nd4j.rand(10, 4), Nd4j.rand(10, 2));

        NeuralNetConfiguration.Builder overallConf = new NeuralNetConfiguration.Builder().updater(new Sgd(0.1))
                        .activation(Activation.IDENTITY);
        FineTuneConfiguration fineTuneConfiguration = new FineTuneConfiguration.Builder().updater(new Sgd(0.1))
                        .activation(Activation.IDENTITY).build();

        ComputationGraph modelToFineTune = new ComputationGraph(overallConf.graphBuilder().addInputs("layer0In")
                        .addLayer("layer0", new DenseLayer.Builder().nIn(4).nOut(5).build(), "layer0In")
                        .addLayer("layer1", new DenseLayer.Builder().nIn(3).nOut(2).build(), "layer0")
                        .addLayer("layer2", new DenseLayer.Builder().nIn(2).nOut(3).build(), "layer1")
                        .addLayer("layer3",
                                        new org.deeplearning4j.nn.conf.layers.OutputLayer.Builder(
                                                        LossFunctions.LossFunction.MCXENT)
                                                                        .activation(Activation.SOFTMAX).nIn(3).nOut(3)
                                                                        .build(),
                                        "layer2")
                        .setOutputs("layer3").build());
        modelToFineTune.init();
        ComputationGraph modelNow = new TransferLearning.GraphBuilder(modelToFineTune)
                        .fineTuneConfiguration(fineTuneConfiguration).nOutReplace("layer3", 2, WeightInit.XAVIER)
                        .nOutReplace("layer0", 3, new NormalDistribution(1, 1e-1), WeightInit.XAVIER)
                        //.setOutputs("layer3")
                        .build();

        BaseLayer bl0 = ((BaseLayer) modelNow.getLayer("layer0").conf().getLayer());
        BaseLayer bl1 = ((BaseLayer) modelNow.getLayer("layer1").conf().getLayer());
        BaseLayer bl3 = ((BaseLayer) modelNow.getLayer("layer3").conf().getLayer());
        assertEquals(bl0.getWeightInit(), WeightInit.DISTRIBUTION);
        assertEquals(bl0.getDist(), new NormalDistribution(1, 1e-1));
        assertEquals(bl1.getWeightInit(), WeightInit.XAVIER);
        assertEquals(bl1.getDist(), null);
        assertEquals(bl1.getWeightInit(), WeightInit.XAVIER);

        ComputationGraph modelExpectedArch = new ComputationGraph(overallConf.graphBuilder().addInputs("layer0In")
                        .addLayer("layer0", new DenseLayer.Builder().nIn(4).nOut(3).build(), "layer0In")
                        .addLayer("layer1", new DenseLayer.Builder().nIn(3).nOut(2).build(), "layer0")
                        .addLayer("layer2", new DenseLayer.Builder().nIn(2).nOut(3).build(), "layer1")
                        .addLayer("layer3",
                                        new org.deeplearning4j.nn.conf.layers.OutputLayer.Builder(
                                                        LossFunctions.LossFunction.MCXENT)
                                                                        .activation(Activation.SOFTMAX).nIn(3).nOut(2)
                                                                        .build(),
                                        "layer2")
                        .setOutputs("layer3").build());

        modelExpectedArch.init();

        //modelNow should have the same architecture as modelExpectedArch
        assertArrayEquals(modelExpectedArch.params().shape(), modelNow.params().shape());
        assertArrayEquals(modelExpectedArch.getLayer("layer0").params().shape(),
                        modelNow.getLayer("layer0").params().shape());
        assertArrayEquals(modelExpectedArch.getLayer("layer1").params().shape(),
                        modelNow.getLayer("layer1").params().shape());
        assertArrayEquals(modelExpectedArch.getLayer("layer2").params().shape(),
                        modelNow.getLayer("layer2").params().shape());
        assertArrayEquals(modelExpectedArch.getLayer("layer3").params().shape(),
                        modelNow.getLayer("layer3").params().shape());

        modelNow.setParams(modelExpectedArch.params());
        //fit should give the same results
        modelExpectedArch.fit(randomData);
        modelNow.fit(randomData);
        assertEquals(modelExpectedArch.score(), modelNow.score(), 1e-8);
        assertEquals(modelExpectedArch.params(), modelNow.params());
    }

    @Test
    public void testRemoveAndAdd() {
        DataSet randomData = new DataSet(Nd4j.rand(10, 4), Nd4j.rand(10, 3));

        NeuralNetConfiguration.Builder overallConf = new NeuralNetConfiguration.Builder().updater(new Sgd(0.1))
                        .activation(Activation.IDENTITY);
        FineTuneConfiguration fineTuneConfiguration = new FineTuneConfiguration.Builder().updater(new Sgd(0.1))
                        .activation(Activation.IDENTITY).build();

        ComputationGraph modelToFineTune = new ComputationGraph(overallConf.graphBuilder().addInputs("layer0In")
                        .addLayer("layer0", new DenseLayer.Builder().nIn(4).nOut(5).build(), "layer0In")
                        .addLayer("layer1", new DenseLayer.Builder().nIn(5).nOut(2).build(), "layer0")
                        .addLayer("layer2", new DenseLayer.Builder().nIn(2).nOut(3).build(), "layer1")
                        .addLayer("layer3",
                                        new org.deeplearning4j.nn.conf.layers.OutputLayer.Builder(
                                                        LossFunctions.LossFunction.MCXENT)
                                                                        .activation(Activation.SOFTMAX).nIn(3).nOut(3)
                                                                        .build(),
                                        "layer2")
                        .setOutputs("layer3").build());
        modelToFineTune.init();

        ComputationGraph modelNow = new TransferLearning.GraphBuilder(modelToFineTune)
                        .fineTuneConfiguration(fineTuneConfiguration)
                        .nOutReplace("layer0", 7, WeightInit.XAVIER, WeightInit.XAVIER)
                        .nOutReplace("layer2", 5, WeightInit.XAVIER).removeVertexKeepConnections("layer3")
                        .addLayer("layer3",
                                        new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT).nIn(5).nOut(3)
                                                        .activation(Activation.SOFTMAX).build(),
                                        "layer2")
                        //.setOutputs("layer3")
                        .build();

        ComputationGraph modelExpectedArch = new ComputationGraph(overallConf.graphBuilder().addInputs("layer0In")
                        .addLayer("layer0", new DenseLayer.Builder().nIn(4).nOut(7).build(), "layer0In")
                        .addLayer("layer1", new DenseLayer.Builder().nIn(7).nOut(2).build(), "layer0")
                        .addLayer("layer2", new DenseLayer.Builder().nIn(2).nOut(5).build(), "layer1")
                        .addLayer("layer3",
                                        new org.deeplearning4j.nn.conf.layers.OutputLayer.Builder(
                                                        LossFunctions.LossFunction.MCXENT)
                                                                        .activation(Activation.SOFTMAX).nIn(5).nOut(3)
                                                                        .build(),
                                        "layer2")
                        .setOutputs("layer3").build());

        modelExpectedArch.init();

        //modelNow should have the same architecture as modelExpectedArch
        assertArrayEquals(modelExpectedArch.params().shape(), modelNow.params().shape());
        assertArrayEquals(modelExpectedArch.getLayer("layer0").params().shape(),
                        modelNow.getLayer("layer0").params().shape());
        assertArrayEquals(modelExpectedArch.getLayer("layer1").params().shape(),
                        modelNow.getLayer("layer1").params().shape());
        assertArrayEquals(modelExpectedArch.getLayer("layer2").params().shape(),
                        modelNow.getLayer("layer2").params().shape());
        assertArrayEquals(modelExpectedArch.getLayer("layer3").params().shape(),
                        modelNow.getLayer("layer3").params().shape());

        modelNow.setParams(modelExpectedArch.params());
        //fit should give the same results
        modelExpectedArch.fit(randomData);
        modelNow.fit(randomData);
        assertEquals(modelExpectedArch.score(), modelNow.score(), 1e-8);
        assertEquals(modelExpectedArch.params(), modelNow.params());
    }

    @Test
    public void testAllWithCNN() {

        DataSet randomData = new DataSet(Nd4j.rand(10, 28 * 28 * 3).reshape(10, 3, 28, 28), Nd4j.rand(10, 10));
        ComputationGraph modelToFineTune =
                        new ComputationGraph(
                                new NeuralNetConfiguration.Builder().seed(123)
                                        .weightInit(WeightInit.XAVIER)
                                        .updater(new Nesterovs(0.01, 0.9)).graphBuilder()
                                        .addInputs("layer0In")
                                        .setInputTypes(InputType.convolutionalFlat(28, 28,
                                                3))
                                        .addLayer("layer0",
                                                new ConvolutionLayer.Builder(5, 5).nIn(3)
                                                        .stride(1, 1).nOut(20)
                                                        .activation(Activation.IDENTITY)
                                                        .build(),
                                                "layer0In")
                                        .addLayer("layer1",
                                                new SubsamplingLayer.Builder(
                                                        SubsamplingLayer.PoolingType.MAX)
                                                        .kernelSize(2, 2)
                                                        .stride(2, 2)
                                                        .build(),
                                                "layer0")
                                        .addLayer("layer2",
                                                new ConvolutionLayer.Builder(5, 5).stride(1, 1)
                                                        .nOut(50)
                                                        .activation(Activation.IDENTITY)
                                                        .build(),
                                                "layer1")
                                        .addLayer("layer3",
                                                new SubsamplingLayer.Builder(
                                                        SubsamplingLayer.PoolingType.MAX)
                                                        .kernelSize(2, 2)
                                                        .stride(2, 2)
                                                        .build(),
                                                "layer2")
                                        .addLayer("layer4",
                                                new DenseLayer.Builder()
                                                        .activation(Activation.RELU)
                                                        .nOut(500).build(),
                                                "layer3")
                                        .addLayer("layer5",
                                                new DenseLayer.Builder()
                                                        .activation(Activation.RELU)
                                                        .nOut(250).build(),
                                                "layer4")
                                        .addLayer("layer6",
                                                new OutputLayer.Builder(
                                                        LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD)
                                                        .nOut(100)
                                                        .activation(Activation.SOFTMAX)
                                                        .build(),
                                                "layer5")
                                        .setOutputs("layer5").backprop(true).pretrain(false).build());
        modelToFineTune.init();

        //this will override the learning configuration set in the model
        NeuralNetConfiguration.Builder overallConf = new NeuralNetConfiguration.Builder().seed(456).updater(new Sgd(0.001));
        FineTuneConfiguration fineTuneConfiguration = new FineTuneConfiguration.Builder().seed(456).updater(new Sgd(0.001))
                        .build();

        ComputationGraph modelNow =
                new TransferLearning.GraphBuilder(modelToFineTune).fineTuneConfiguration(fineTuneConfiguration)
                        .setFeatureExtractor("layer1").nOutReplace("layer4", 600, WeightInit.XAVIER)
                        .removeVertexAndConnections("layer5").removeVertexAndConnections("layer6")
                        .setInputs("layer0In").setInputTypes(InputType.convolutionalFlat(28, 28, 3))
                        .addLayer("layer5",
                                new DenseLayer.Builder().activation(Activation.RELU).nIn(600)
                                        .nOut(300).build(),
                                "layer4")
                        .addLayer("layer6",
                                new DenseLayer.Builder().activation(Activation.RELU).nIn(300)
                                        .nOut(150).build(),
                                "layer5")
                        .addLayer("layer7",
                                new DenseLayer.Builder().activation(Activation.RELU).nIn(150)
                                        .nOut(50).build(),
                                "layer6")
                        .addLayer("layer8",
                                new OutputLayer.Builder(
                                        LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD)
                                        .activation(Activation.SOFTMAX)
                                        .nIn(50).nOut(10).build(),
                                "layer7")
                        .setOutputs("layer8").build();

        ComputationGraph modelExpectedArch =
                        new ComputationGraph(overallConf.graphBuilder().addInputs("layer0In")
                                .setInputTypes(InputType.convolutionalFlat(28,28, 3))
                                .addLayer("layer0",
                                        new FrozenLayer(new ConvolutionLayer.Builder(5, 5).nIn(3)
                                                .stride(1, 1).nOut(20)
                                                .activation(Activation.IDENTITY).build()),
                                        "layer0In")
                                .addLayer("layer1",
                                        new FrozenLayer(new SubsamplingLayer.Builder(
                                                SubsamplingLayer.PoolingType.MAX)
                                                .kernelSize(2, 2).stride(2, 2)
                                                .build()),
                                        "layer0")
                                .addLayer("layer2",
                                        new ConvolutionLayer.Builder(5, 5).stride(1, 1).nOut(50)
                                                .activation(Activation.IDENTITY).build(),
                                        "layer1")
                                .addLayer("layer3",
                                        new SubsamplingLayer.Builder(SubsamplingLayer.PoolingType.MAX)
                                                .kernelSize(2, 2).stride(2, 2).build(),
                                        "layer2")
                                .addLayer("layer4",
                                        new DenseLayer.Builder().activation(Activation.RELU).nOut(600)
                                                .build(),
                                        "layer3")
                                .addLayer("layer5",
                                        new DenseLayer.Builder().activation(Activation.RELU).nOut(300)
                                                .build(),
                                        "layer4")
                                .addLayer("layer6",
                                        new DenseLayer.Builder().activation(Activation.RELU).nOut(150)
                                                .build(),
                                        "layer5")
                                .addLayer("layer7",
                                        new DenseLayer.Builder().activation(Activation.RELU).nOut(50)
                                                .build(),
                                        "layer6")
                                .addLayer("layer8",
                                        new OutputLayer.Builder(
                                                LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD)
                                                .nOut(10)
                                                .activation(Activation.SOFTMAX)
                                                .build(),
                                        "layer7")
                                .setOutputs("layer8").backprop(true).pretrain(false).build());
        modelExpectedArch.init();
        modelExpectedArch.getVertex("layer0").setLayerAsFrozen();
        modelExpectedArch.getVertex("layer1").setLayerAsFrozen();

        assertEquals(modelExpectedArch.getConfiguration().toJson(), modelNow.getConfiguration().toJson());

        modelNow.setParams(modelExpectedArch.params());
        int i = 0;
        while (i < 5) {
            modelExpectedArch.fit(randomData);
            modelNow.fit(randomData);
            i++;
        }
        assertEquals(modelExpectedArch.params(), modelNow.params());

    }


    @Test
    public void testTransferGlobalPool() {

        ComputationGraphConfiguration conf = new NeuralNetConfiguration.Builder().seed(12345).updater(new Adam(0.1))
                .weightInit(WeightInit.XAVIER)
                        .graphBuilder().addInputs("in")
                        .addLayer("blstm1",new GravesBidirectionalLSTM.Builder().nIn(10).nOut(10)
                                                        .activation(Activation.TANH).build(),
                                        "in")
                        .addLayer("pool", new GlobalPoolingLayer.Builder().build(), "blstm1")
                        .addLayer("dense", new DenseLayer.Builder().nIn(10).nOut(10).build(), "pool")
                        .addLayer("out", new OutputLayer.Builder().nIn(10).nOut(10).activation(Activation.IDENTITY)
                                        .lossFunction(LossFunctions.LossFunction.MSE).build(), "dense")
                        .setOutputs("out").build();

        ComputationGraph g = new ComputationGraph(conf);
        g.init();

        FineTuneConfiguration fineTuneConfiguration =
                        new FineTuneConfiguration.Builder().seed(12345).updater(new Sgd(0.01)).build();

        ComputationGraph graph = new TransferLearning.GraphBuilder(g).fineTuneConfiguration(fineTuneConfiguration)
                        .removeVertexKeepConnections("out").setFeatureExtractor("dense")
                        .addLayer("out", new OutputLayer.Builder().updater(new Adam(0.1))
                                .weightInit(WeightInit.XAVIER)
                                        .activation(Activation.SOFTMAX).lossFunction(LossFunctions.LossFunction.MCXENT)
                                        .nIn(10).nOut(5).build(), "dense")
                        .build();

        ComputationGraphConfiguration confExpected = new NeuralNetConfiguration.Builder().seed(12345)
                        .updater(new Sgd(0.01))
                        .weightInit(WeightInit.XAVIER)
                        .graphBuilder().addInputs("in")
                        .addLayer("blstm1",
                                        new FrozenLayer(new GravesBidirectionalLSTM.Builder().nIn(10).nOut(10)
                                                        .activation(Activation.TANH).build()),
                                        "in")
                        .addLayer("pool", new FrozenLayer(new GlobalPoolingLayer.Builder().build()), "blstm1")
                        .addLayer("dense", new FrozenLayer(new DenseLayer.Builder().nIn(10).nOut(10).build()), "pool")
                        .addLayer("out", new OutputLayer.Builder().nIn(10).nOut(5).activation(Activation.SOFTMAX)
                                        .updater(new Adam(0.1))
                                        .lossFunction(LossFunctions.LossFunction.MCXENT).build(), "dense")
                        .setOutputs("out").build();

        ComputationGraph modelExpected = new ComputationGraph(confExpected);
        modelExpected.init();


//        assertEquals(confExpected, graph.getConfiguration());
        assertEquals(confExpected.toJson(), graph.getConfiguration().toJson());
    }


    @Test
    public void testObjectOverrides(){
        //https://github.com/deeplearning4j/deeplearning4j/issues/4368
        ComputationGraphConfiguration conf = new NeuralNetConfiguration.Builder()
                .dropOut(0.5)
                .weightNoise(new DropConnect(0.5))
                .l2(0.5)
                .constrainWeights(new UnitNormConstraint())
                .graphBuilder()
                .addInputs("in")
                .addLayer("layer", new DenseLayer.Builder().nIn(10).nOut(10).build(), "in")
                .setOutputs("layer")
                .build();

        ComputationGraph orig = new ComputationGraph(conf);
        orig.init();

        FineTuneConfiguration ftc = new FineTuneConfiguration.Builder()
                .dropOut(0)
                .weightNoise(null)
                .constraints(null)
                .l2(0.0)
                .build();

        ComputationGraph transfer = new TransferLearning.GraphBuilder(orig)
                .fineTuneConfiguration(ftc)
                .build();

        DenseLayer l = (DenseLayer) transfer.getLayer(0).conf().getLayer();

        assertNull(l.getIDropout());
        assertNull(l.getWeightNoise());
        assertNull(l.getConstraints());
        assertEquals(0.0, l.getL2(), 0.0);
    }
}
