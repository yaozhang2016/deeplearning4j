package org.deeplearning4j.datasets.iterator.impl;

import org.datavec.image.loader.CifarLoader;
import org.datavec.image.transform.ImageTransform;
import org.deeplearning4j.datasets.datavec.RecordReaderDataSetIterator;
import org.nd4j.linalg.dataset.DataSet;

import java.util.List;

/**
 * CifarDataSetIterator is an iterator for Cifar10 dataset explicitly
 *
 * There is a special preProcessor used to normalize the dataset based on Sergey Zagoruyko example
 * https://github.com/szagoruyko/cifar.torch
 */

public class CifarDataSetIterator extends RecordReaderDataSetIterator {
    protected static final int HEIGHT = 32;
    protected static final int WIDTH = 32;
    protected static final int CHANNELS = 3;

    protected final CifarLoader loader;
    protected final int numExamples;
    protected final boolean useSpecialPreProcessCifar;
    protected final boolean train;
    protected final ImageTransform imageTransform;
    protected int exampleCount = 0;
    protected boolean overshot = false;


    /**
     * Loads images with given  batchSize & numExamples returned by the generator.
     */
    public CifarDataSetIterator(int batchSize, int numExamples) {
        this(batchSize, numExamples, true);
    }

    /**
     * Loads images with given  batchSize, numExamples, & version returned by the generator.
     */
    public CifarDataSetIterator(int batchSize, int numExamples, boolean train) {
        this(batchSize, numExamples, new int[] {HEIGHT, WIDTH, CHANNELS}, CifarLoader.NUM_LABELS, null,
                        CifarLoader.DEFAULT_USE_SPECIAL_PREPROC, train);
    }

    /**
     * Loads images with given  batchSize & imgDim returned by the generator.
     */
    public CifarDataSetIterator(int batchSize, int[] imgDim) {
        this(batchSize, CifarLoader.NUM_TRAIN_IMAGES, imgDim);
    }

    /**
     * Loads images with given  batchSize, numExamples, & imgDim returned by the generator.
     */
    public CifarDataSetIterator(int batchSize, int numExamples, int[] imgDim) {
        this(batchSize, numExamples, imgDim, true);
    }

    /**
     * Loads images with given  batchSize, numExamples, imgDim & version returned by the generator.
     */
    public CifarDataSetIterator(int batchSize, int numExamples, int[] imgDim, boolean train) {
        this(batchSize, numExamples, imgDim, CifarLoader.DEFAULT_USE_SPECIAL_PREPROC, train);
    }

    /**
     * Loads images with given  batchSize, numExamples, imgDim & version returned by the generator.
     */
    public CifarDataSetIterator(int batchSize, int numExamples, int[] imgDim, boolean useSpecialPreProcessCifar,
                    boolean train) {
        this(batchSize, numExamples, imgDim, CifarLoader.NUM_LABELS, null, useSpecialPreProcessCifar, train);
    }

    /**
     * Create Cifar data specific iterator
     *
     * @param batchSize      the batch size of the examples
     * @param imgDim         an array of height, width and channels
     * @param numExamples    the overall number of examples
     * @param imageTransform the transformation to apply to the images
     * @param useSpecialPreProcessCifar use Zagoruyko's preprocess for Cifar
     * @param train          true if use training set and false for test
     */
    public CifarDataSetIterator(int batchSize, int numExamples, int[] imgDim, int numPossibleLables,
                    ImageTransform imageTransform, boolean useSpecialPreProcessCifar, boolean train) {
        super(null, batchSize, 1, numExamples);
        this.loader = new CifarLoader(imgDim[0], imgDim[1], imgDim[2], imageTransform, train,
                        useSpecialPreProcessCifar);
        int totalExamples = train ? CifarLoader.NUM_TRAIN_IMAGES : CifarLoader.NUM_TEST_IMAGES;
        this.numExamples = numExamples > totalExamples ? totalExamples : numExamples;
        this.numPossibleLabels = numPossibleLables;
        this.imageTransform = imageTransform;
        this.useSpecialPreProcessCifar = useSpecialPreProcessCifar;
        this.train = train;
    }

    @Override
    public DataSet next(int batchSize) {
        if (useCurrent) {
            useCurrent = false;
            return last;
        }
        DataSet result;
        if (useSpecialPreProcessCifar) {
            result = loader.next(batchSize, exampleCount);
        } else
            result = loader.next(batchSize);
        exampleCount += batchSize;
        batchNum++;

        if ((result.getFeatureMatrix() == null || result == new DataSet())
                        || (maxNumBatches > -1 && batchNum >= maxNumBatches)) {
            overshot = true;
            return last;
        }

        if (preProcessor != null)
            preProcessor.preProcess(result);
        last = result;
        if (loader.getLabels() != null)
            result.setLabelNames(loader.getLabels());
        return result;
    }

    @Override
    public boolean hasNext() {
        return exampleCount < numExamples && (maxNumBatches == -1 || batchNum < maxNumBatches) && !overshot;
    }

    @Override
    public int totalExamples() {
        return numExamples;
    }

    @Override
    public void reset() {
        exampleCount = 0;
        overshot = false;
        batchNum = 0;
        loader.reset();
    }

    @Override
    public boolean resetSupported() {
        return true;
    }

    @Override
    public List<String> getLabels() {
        return loader.getLabels();
    }

    @Override
    public boolean asyncSupported() {
        return true;
    }
}
