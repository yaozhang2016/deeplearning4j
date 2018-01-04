package org.deeplearning4j.datasets.iterator;

import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.datasets.iterator.impl.EmnistDataSetIterator;
import org.junit.Test;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;

import static org.junit.Assert.*;

/**
 * Created by Alex on 28/07/2017.
 */
@Slf4j
public class TestEmnistDataSetIterator {

    @Test
    public void testEmnistDataSetIterator() throws Exception {

        //        EmnistFetcher fetcher = new EmnistFetcher(EmnistDataSetIterator.Set.COMPLETE);
        //        File baseEmnistDir = fetcher.getFILE_DIR();
        //        if(baseEmnistDir.exists()){
        //            FileUtils.deleteDirectory(baseEmnistDir);
        //        }
        //        assertFalse(baseEmnistDir.exists());


        int batchSize = 128;

        for (EmnistDataSetIterator.Set s : EmnistDataSetIterator.Set.values()) {
            boolean isBalanced = EmnistDataSetIterator.isBalanced(s);
            int numLabels = EmnistDataSetIterator.numLabels(s);
            INDArray labelCounts = null;
            for (boolean train : new boolean[] {true, false}) {
                if (isBalanced && train) {
                    labelCounts = Nd4j.create(numLabels);
                } else {
                    labelCounts = null;
                }

                log.info("Starting test: {}, {}", s, (train ? "train" : "test"));
                EmnistDataSetIterator iter = new EmnistDataSetIterator(s, batchSize, train, 12345);

                assertTrue(iter.asyncSupported());
                assertTrue(iter.resetSupported());

                int expNumExamples;
                if (train) {
                    expNumExamples = EmnistDataSetIterator.numExamplesTrain(s);
                } else {
                    expNumExamples = EmnistDataSetIterator.numExamplesTest(s);
                }

                assertEquals(expNumExamples, iter.numExamples());



                assertEquals(numLabels, iter.getLabels().size());
                assertEquals(numLabels, iter.getLabelsArrays().length);

                char[] labelArr = iter.getLabelsArrays();
                for (char c : labelArr) {
                    boolean isExpected = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
                    assertTrue(isExpected);
                }

                int totalCount = 0;
                while (iter.hasNext()) {
                    DataSet ds = iter.next();
                    assertNotNull(ds.getFeatures());
                    assertNotNull(ds.getLabels());
                    assertEquals(ds.getFeatures().size(0), ds.getLabels().size(0));

                    totalCount += ds.getFeatures().size(0);

                    assertEquals(784, ds.getFeatures().size(1));
                    assertEquals(numLabels, ds.getLabels().size(1));

                    if (isBalanced && train) {
                        labelCounts.addi(ds.getLabels().sum(0));
                    }
                }

                assertEquals(expNumExamples, totalCount);

                if (isBalanced && train) {
                    int min = labelCounts.minNumber().intValue();
                    int max = labelCounts.maxNumber().intValue();
                    int exp = expNumExamples / numLabels;

                    assertTrue(min > 0);
                    assertEquals(exp, min);
                    assertEquals(exp, max);
                }
            }
        }
    }
}
