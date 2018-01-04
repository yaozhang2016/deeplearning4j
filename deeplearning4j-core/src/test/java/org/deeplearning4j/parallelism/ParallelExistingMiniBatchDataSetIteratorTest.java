package org.deeplearning4j.parallelism;

import lombok.extern.slf4j.Slf4j;
import org.datavec.api.util.ClassPathResource;
import org.deeplearning4j.datasets.iterator.callbacks.DataSetDeserializer;
import org.deeplearning4j.datasets.iterator.parallel.FileSplitParallelDataSetIterator;
import org.junit.Before;
import org.junit.Test;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.primitives.Pair;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * @author raver119@gmail.com
 */
@Slf4j
public class ParallelExistingMiniBatchDataSetIteratorTest {

    private static File rootFolder;

    @Before
    public void setUp() {
        if (rootFolder == null) {
            try {
                rootFolder = new ClassPathResource("/datasets/mnist").getFile();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }


    @Test
    public void testNewSimpleLoop1() throws Exception {
        FileSplitParallelDataSetIterator fspdsi = new FileSplitParallelDataSetIterator(rootFolder, "mnist-train-%d.bin",
                        new DataSetDeserializer());

        List<Pair<Long, Long>> pairs = new ArrayList<>();


        long time1 = System.nanoTime();
        int cnt = 0;
        while (fspdsi.hasNext()) {
            DataSet ds = fspdsi.next();
            long time2 = System.nanoTime();
            pairs.add(new Pair<Long, Long>(time2 - time1, 0L));
            assertNotNull(ds);

            // imitating processing here
            Thread.sleep(10);

            cnt++;
            time1 = System.nanoTime();
        }

        assertEquals(26, cnt);

        for (Pair<Long, Long> times : pairs) {
            log.info("Parallel: {} ns; Simple: {} ns", times.getFirst(), times.getSecond());
        }
    }


    /*
    @Test
    public void testSimpleLoop1() throws Exception {
        ParallelExistingMiniBatchDataSetIterator iterator = new ParallelExistingMiniBatchDataSetIterator(rootFolder,"mnist-train-%d.bin", 4);
        ExistingMiniBatchDataSetIterator test = new ExistingMiniBatchDataSetIterator(rootFolder,"mnist-train-%d.bin");
    
    
        List<Pair<Long, Long>> pairs = new ArrayList<>();
    
        int cnt = 0;
        long time1 = System.nanoTime();
        while (iterator.hasNext()) {
            DataSet ds = iterator.next();
            long time2 = System.nanoTime();
            assertNotNull(ds);
            assertEquals(64, ds.numExamples());
            pairs.add(new Pair<Long, Long>(time2 - time1, 0L));
            cnt++;
            time1 = System.nanoTime();
        }
        assertEquals(26, cnt);
    
        cnt = 0;
        time1 = System.nanoTime();
        while (test.hasNext()) {
            DataSet ds = test.next();
            long time2 = System.nanoTime();
            assertNotNull(ds);
            assertEquals(64, ds.numExamples());
            pairs.get(cnt).setSecond(time2 - time1);
            cnt++;
            time1 = System.nanoTime();
        }
    
        assertEquals(26, cnt);
    
        for (Pair<Long, Long> times: pairs) {
            log.info("Parallel: {} ns; Simple: {} ns", times.getFirst(), times.getSecond());
        }
    }
    
    @Test
    public void testReset1() throws Exception {
        ParallelExistingMiniBatchDataSetIterator iterator = new ParallelExistingMiniBatchDataSetIterator(rootFolder,"mnist-train-%d.bin", 8);
    
        int cnt = 0;
        long time1 = System.nanoTime();
        while (iterator.hasNext()) {
            DataSet ds = iterator.next();
            long time2 = System.nanoTime();
            assertNotNull(ds);
            assertEquals(64, ds.numExamples());
            cnt++;
    
            if (cnt == 10)
                iterator.reset();
    
            time1 = System.nanoTime();
        }
        assertEquals(36, cnt);
    }
    
    @Test
    public void testWithAdsi1() throws Exception {
        ParallelExistingMiniBatchDataSetIterator iterator = new ParallelExistingMiniBatchDataSetIterator(rootFolder,"mnist-train-%d.bin", 8);
        AsyncDataSetIterator adsi = new AsyncDataSetIterator(iterator, 8, true);
    
        int cnt = 0;
        long time1 = System.nanoTime();
        while (adsi.hasNext()) {
            DataSet ds = adsi.next();
            long time2 = System.nanoTime();
            assertNotNull(ds);
            assertEquals(64, ds.numExamples());
            cnt++;
    
            if (cnt == 10)
                adsi.reset();
    
            time1 = System.nanoTime();
        }
        assertEquals(36, cnt);
    }
    */
}
