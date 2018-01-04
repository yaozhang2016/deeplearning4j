package org.deeplearning4j.nearestneighbor.server;

import org.deeplearning4j.clustering.sptree.DataPoint;
import org.deeplearning4j.clustering.vptree.VPTree;
import org.deeplearning4j.clustering.vptree.VPTreeFillSearch;
import org.deeplearning4j.nearestneighbor.client.NearestNeighborsClient;
import org.deeplearning4j.nearestneighbor.model.NearestNeighborRequest;
import org.deeplearning4j.nearestneighbor.model.NearstNeighborsResults;
import org.junit.Test;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.serde.binary.BinarySerde;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

/**
 * Created by agibsonccc on 4/27/17.
 */
public class NearestNeighborTest {

    @Test
    public void testNearestNeighbor() {
        INDArray arr = Nd4j.create(new double[][] {{1, 2, 3, 4}, {1, 2, 3, 5}, {3, 4, 5, 6}});

        VPTree vpTree = new VPTree(arr, false);
        NearestNeighborRequest request = new NearestNeighborRequest();
        request.setK(2);
        request.setInputIndex(0);
        NearestNeighbor nearestNeighbor = NearestNeighbor.builder().tree(vpTree).points(arr).record(request).build();
        assertEquals(1, nearestNeighbor.search().get(0).getIndex());
    }



    public static int getAvailablePort() {
        try {
            ServerSocket socket = new ServerSocket(0);
            try {
                return socket.getLocalPort();
            } finally {
                socket.close();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot find available port: " + e.getMessage(), e);
        }
    }

    @Test
    public void testServer() throws Exception {
        int localPort = getAvailablePort();
        Nd4j.getRandom().setSeed(7);
        INDArray rand = Nd4j.randn(10, 5);
        File writeToTmp = new File(System.getProperty("java.io.tmpdir"), "ndarray" + UUID.randomUUID().toString());
        writeToTmp.deleteOnExit();
        BinarySerde.writeArrayToDisk(rand, writeToTmp);
        NearestNeighborsServer server = new NearestNeighborsServer();
        server.runMain("--ndarrayPath", writeToTmp.getAbsolutePath(), "--nearestNeighborsPort",
                        String.valueOf(localPort));

        NearestNeighborsClient client = new NearestNeighborsClient("http://localhost:" + localPort);
        NearstNeighborsResults result = client.knnNew(5, rand.getRow(0));
        assertEquals(5, result.getResults().size());
        server.stop();
    }



    @Test
    public void testFullSearch() throws Exception {
        int numRows = 1000;
        int numCols = 100;
        int numNeighbors = 42;
        INDArray points = Nd4j.rand(numRows, numCols);
        VPTree tree = new VPTree(points);
        INDArray query = Nd4j.rand(new int[] {1, numCols});
        VPTreeFillSearch fillSearch = new VPTreeFillSearch(tree, numNeighbors, query);
        fillSearch.search();
        List<DataPoint> results = fillSearch.getResults();
        List<Double> distances = fillSearch.getDistances();
        assertEquals(numNeighbors, distances.size());
        assertEquals(numNeighbors, results.size());
    }

}
