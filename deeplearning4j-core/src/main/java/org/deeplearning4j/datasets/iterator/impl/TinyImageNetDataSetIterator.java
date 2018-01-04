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

package org.deeplearning4j.datasets.iterator.impl;

import lombok.Getter;
import org.datavec.image.transform.ImageTransform;
import org.deeplearning4j.datasets.datavec.RecordReaderDataSetIterator;
import org.deeplearning4j.datasets.fetchers.DataSetType;
import org.deeplearning4j.datasets.fetchers.TinyImageNetFetcher;
import org.nd4j.linalg.dataset.api.DataSetPreProcessor;

/**
 * Tiny ImageNet is a subset of the ImageNet database. TinyImageNet is the default course challenge for CS321n
 * at Stanford University.
 *
 * Tiny ImageNet has 200 classes, each consisting of 500 training images.
 *
 * See: <a href="http://cs231n.stanford.edu/">http://cs231n.stanford.edu/</a> and
 * <a href="https://tiny-imagenet.herokuapp.com/">https://tiny-imagenet.herokuapp.com/</a>
 *
 * @author Justin Long (crockpotveggies)
 */
public class TinyImageNetDataSetIterator extends RecordReaderDataSetIterator {

    @Getter
    protected DataSetPreProcessor preProcessor;

    public TinyImageNetDataSetIterator(int batchSize) {
        this(batchSize, new int[]{}, DataSetType.TRAIN, null, 123);
    }

    public TinyImageNetDataSetIterator(int batchSize, DataSetType set) {
        this(batchSize, new int[]{}, set, null, 123);
    }

    /**
     * Get the Tiny ImageNet iterator with specified train/test set and custom transform.
     *
     * @param batchSize Size of each patch
     * @param imgDim Dimensions of desired output
     * @param set Train, test, or validation
     * @param imageTransform Additional image transform for output
     * @param rngSeed random number generator seed to use when shuffling examples
     */
    public TinyImageNetDataSetIterator(int batchSize, int[] imgDim, DataSetType set,
                                       ImageTransform imageTransform, long rngSeed) {
        super(new TinyImageNetFetcher().getRecordReader(rngSeed, imgDim, set, imageTransform), batchSize, 1, TinyImageNetFetcher.NUM_LABELS);
    }
}
