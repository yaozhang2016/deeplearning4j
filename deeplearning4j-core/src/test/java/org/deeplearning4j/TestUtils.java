package org.deeplearning4j;

import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.util.ModelSerializer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

import static org.junit.Assert.assertEquals;

public class TestUtils {

    public static MultiLayerNetwork testModelSerialization(MultiLayerNetwork net){

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ModelSerializer.writeModel(net, baos, true);
            byte[] bytes = baos.toByteArray();

            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            MultiLayerNetwork restored = ModelSerializer.restoreMultiLayerNetwork(bais, true);

            assertEquals(net.getLayerWiseConfigurations(), restored.getLayerWiseConfigurations());
            assertEquals(net.params(), restored.params());

            return restored;
        } catch (IOException e){
            //Should never happen
            throw new RuntimeException(e);
        }
    }

    public static ComputationGraph testModelSerialization(ComputationGraph net){

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ModelSerializer.writeModel(net, baos, true);
            byte[] bytes = baos.toByteArray();

            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            ComputationGraph restored = ModelSerializer.restoreComputationGraph(bais, true);

            assertEquals(net.getConfiguration(), restored.getConfiguration());
            assertEquals(net.params(), restored.params());

            return restored;
        } catch (IOException e){
            //Should never happen
            throw new RuntimeException(e);
        }
    }

    public static INDArray randomOneHotTimeSeries(int minibatch, int outSize, int tsLength){
        return randomOneHotTimeSeries(minibatch, outSize, tsLength, new Random());
    }

    public static INDArray randomOneHotTimeSeries(int minibatch, int outSize, int tsLength, long rngSeed){
        return randomOneHotTimeSeries(minibatch, outSize, tsLength, new Random(rngSeed));
    }

    public static INDArray randomOneHotTimeSeries(int minibatch, int outSize, int tsLength, Random rng){
        INDArray out = Nd4j.create(new int[]{minibatch, outSize, tsLength}, 'f');
        for( int i=0; i<minibatch; i++ ){
            for( int j=0; j<tsLength; j++ ){
                out.putScalar(i, rng.nextInt(outSize), j, 1.0);
            }
        }
        return out;
    }
}
