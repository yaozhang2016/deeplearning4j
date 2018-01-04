package org.deeplearning4j.nn.layers.convolution;

import org.deeplearning4j.datasets.iterator.impl.MnistDataSetIterator;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.conf.GradientNormalization;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.Upsampling2D;
import org.deeplearning4j.nn.gradient.Gradient;
import org.junit.Test;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.primitives.Pair;

import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * @author Max Pumperla
 */
public class Upsampling2DTest {

    private int nExamples = 1;
    private int depth = 20;
    private int nChannelsIn = 1;
    private int inputWidth = 28;
    private int inputHeight = 28;

    private int size = 2;
    private int outputWidth = inputWidth * size;
    private int outputHeight = inputHeight * size;

    private INDArray epsilon = Nd4j.ones(nExamples, depth, outputHeight, outputWidth);


    @Test
    public void testUpsampling() throws Exception {

        double[] outArray = new double[] {1., 1., 2., 2., 1., 1., 2., 2., 3., 3., 4., 4., 3., 3., 4., 4.};
        INDArray containedExpectedOut = Nd4j.create(outArray, new int[] {1, 1, 4, 4});
        INDArray containedInput = getContainedData();
        INDArray input = getData();
        Layer layer = getUpsamplingLayer();

        INDArray containedOutput = layer.activate(containedInput);
        assertTrue(Arrays.equals(containedExpectedOut.shape(), containedOutput.shape()));
        assertEquals(containedExpectedOut, containedOutput);

        INDArray output = layer.activate(input);
        assertTrue(Arrays.equals(new int[] {nExamples, nChannelsIn, outputWidth, outputHeight},
                        output.shape()));
        assertEquals(nChannelsIn, output.size(1), 1e-4);
    }


    @Test
    public void testUpsampling2DBackprop() throws Exception {
        INDArray expectedContainedEpsilonInput =
                        Nd4j.create(new double[] {1., 1., 1., 1., 1., 1., 1., 1., 1., 1., 1., 1., 1., 1., 1., 1.},
                                new int[] {1, 1, 4, 4});

        INDArray expectedContainedEpsilonResult = Nd4j.create(new double[] {4., 4., 4., 4.},
                        new int[] {1, 1, 2, 2});

        INDArray input = getContainedData();

        Layer layer = getUpsamplingLayer();
        layer.activate(input);

        Pair<Gradient, INDArray> containedOutput = layer.backpropGradient(expectedContainedEpsilonInput);

        assertEquals(expectedContainedEpsilonResult, containedOutput.getSecond());
        assertEquals(null, containedOutput.getFirst().getGradientFor("W"));
        assertEquals(expectedContainedEpsilonResult.shape().length, containedOutput.getSecond().shape().length);

        INDArray input2 = getData();
        layer.activate(input2);
        int depth = input2.size(1);

        epsilon = Nd4j.ones(5, depth, outputHeight, outputWidth);

        Pair<Gradient, INDArray> out = layer.backpropGradient(epsilon);
        assertEquals(input.shape().length, out.getSecond().shape().length);
        assertEquals(depth, out.getSecond().size(1));
    }


    private Layer getUpsamplingLayer() {
        NeuralNetConfiguration conf = new NeuralNetConfiguration.Builder()
                        .gradientNormalization(GradientNormalization.RenormalizeL2PerLayer).seed(123)
                        .layer(new Upsampling2D.Builder(size).build()).build();
        return conf.getLayer().instantiate(conf, null, 0, null, true);
    }

    public INDArray getData() throws Exception {
        DataSetIterator data = new MnistDataSetIterator(5, 5);
        DataSet mnist = data.next();
        nExamples = mnist.numExamples();
        return mnist.getFeatureMatrix().reshape(nExamples, nChannelsIn, inputWidth, inputHeight);
    }

    private INDArray getContainedData() {
        INDArray ret = Nd4j.create
                (new double[] {1., 2., 3., 4.},
                        new int[] {1, 1, 2, 2});
        return ret;
    }

}
