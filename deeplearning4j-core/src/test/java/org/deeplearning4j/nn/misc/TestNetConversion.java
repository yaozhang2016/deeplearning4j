package org.deeplearning4j.nn.misc;

import org.deeplearning4j.nn.conf.ConvolutionMode;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.inputs.InputType;
import org.deeplearning4j.nn.conf.layers.*;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.junit.Test;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.config.Sgd;
import org.nd4j.linalg.lossfunctions.LossFunctions;

import static org.junit.Assert.assertEquals;

public class TestNetConversion {

    @Test
    public void testMlnToCompGraph() {
        Nd4j.getRandom().setSeed(12345);

        for( int i=0; i<3; i++ ){
            MultiLayerNetwork n;
            switch (i){
                case 0:
                    n = getNet1(false);
                    break;
                case 1:
                    n = getNet1(true);
                    break;
                case 2:
                    n = getNet2();
                    break;
                default:
                    throw new RuntimeException();
            }
            INDArray in = (i <= 1 ? Nd4j.rand(new int[]{8, 3, 10, 10}) : Nd4j.rand(new int[]{8, 5, 10}));
            INDArray labels = (i <= 1 ? Nd4j.rand(new int[]{8, 10}) : Nd4j.rand(new int[]{8, 10, 10}));

            ComputationGraph cg = n.toComputationGraph();

            INDArray out1 = n.output(in);
            INDArray out2 = cg.outputSingle(in);
            assertEquals(out1, out2);


            n.setInput(in);
            n.setLabels(labels);

            cg.setInputs(in);
            cg.setLabels(labels);

            n.computeGradientAndScore();
            cg.computeGradientAndScore();

            assertEquals(n.score(), cg.score(), 1e-6);

            assertEquals(n.gradient().gradient(), cg.gradient().gradient());

            n.fit(in, labels);
            cg.fit(new INDArray[]{in}, new INDArray[]{labels});

            assertEquals(n.params(), cg.params());
        }
    }

    private MultiLayerNetwork getNet1(boolean train) {

        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                .convolutionMode(ConvolutionMode.Same)
                .activation(Activation.TANH)
                .weightInit(WeightInit.XAVIER)
                .updater(new Sgd(0.1))
                .list()
                .layer(new ConvolutionLayer.Builder().nIn(3).nOut(5).kernelSize(2, 2).stride(1, 1).build())
                .layer(new SubsamplingLayer.Builder().kernelSize(2, 2).stride(1, 1).build())
                .layer(new DenseLayer.Builder().nOut(32).build())
                .layer(new OutputLayer.Builder().nOut(10).lossFunction(LossFunctions.LossFunction.MSE).build())
                .setInputType(InputType.convolutional(10, 10, 3))
                .build();

        MultiLayerNetwork net = new MultiLayerNetwork(conf);
        net.init();

        if(train) {
            for (int i = 0; i < 3; i++) {
                INDArray f = Nd4j.rand(new int[]{8, 3, 10, 10});
                INDArray l = Nd4j.rand(8, 10);

                net.fit(f, l);
            }
        }

        return net;
    }

    private MultiLayerNetwork getNet2() {

        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                .convolutionMode(ConvolutionMode.Same)
                .activation(Activation.TANH)
                .weightInit(WeightInit.XAVIER)
                .updater(new Sgd(0.1))
                .list()
                .layer(new GravesLSTM.Builder().nOut(8).build())
                .layer(new LSTM.Builder().nOut(8).build())
                .layer(new RnnOutputLayer.Builder().nOut(10).lossFunction(LossFunctions.LossFunction.MSE).build())
                .setInputType(InputType.recurrent(5))
                .build();

        MultiLayerNetwork net = new MultiLayerNetwork(conf);
        net.init();

        for (int i = 0; i < 3; i++) {
            INDArray f = Nd4j.rand(new int[]{8, 5, 10});
            INDArray l = Nd4j.rand(new int[]{8, 10, 10});

            net.fit(f, l);
        }

        return net;

    }

}
