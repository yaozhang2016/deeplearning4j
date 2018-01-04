package org.deeplearning4j.parallelism;

import org.deeplearning4j.datasets.iterator.parallel.MultiBoolean;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * @author raver119@gmail.com
 */
public class MultiBooleanTest {

    @Test
    public void testBoolean1() throws Exception {
        MultiBoolean bool = new MultiBoolean(5);

        assertTrue(bool.allFalse());
        assertFalse(bool.allTrue());
    }


    @Test
    public void testBoolean2() throws Exception {
        MultiBoolean bool = new MultiBoolean(5);

        bool.set(true, 2);

        assertFalse(bool.allFalse());
        assertFalse(bool.allTrue());
    }

    @Test
    public void testBoolean3() throws Exception {
        MultiBoolean bool = new MultiBoolean(5);

        bool.set(true, 0);
        bool.set(true, 1);
        bool.set(true, 2);


        bool.set(true, 3);

        assertFalse(bool.allTrue());

        bool.set(true, 4);

        assertFalse(bool.allFalse());
        assertTrue(bool.allTrue());

        bool.set(false, 2);

        assertFalse(bool.allTrue());

        bool.set(true, 2);

        assertTrue(bool.allTrue());
    }

    @Test
    public void testBoolean4() throws Exception {
        MultiBoolean bool = new MultiBoolean(5, true);


        assertTrue(bool.get(1));

        bool.set(false, 1);

        assertFalse(bool.get(1));
    }


    @Test
    public void testBoolean5() throws Exception {
        MultiBoolean bool = new MultiBoolean(5, true, true);

        for (int i = 0; i < 5; i++) {
            bool.set(false, i);
        }

        for (int i = 0; i < 5; i++) {
            bool.set(true, i);
        }

        assertTrue(bool.allFalse());
    }
}
