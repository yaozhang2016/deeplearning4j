/*-
 *
 *  * Copyright 2016 Skymind,Inc.
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
package org.deeplearning4j.nn.layers.normalization;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.Pointer;
import org.deeplearning4j.nn.gradient.DefaultGradient;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.nn.layers.BaseCudnnHelper;
import org.deeplearning4j.nn.params.BatchNormalizationParamInitializer;
import org.nd4j.jita.allocator.Allocator;
import org.nd4j.jita.allocator.impl.AtomicAllocator;
import org.nd4j.jita.conf.CudaEnvironment;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.executioner.GridExecutioner;
import org.nd4j.linalg.api.shape.Shape;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.jcublas.context.CudaContext;
import org.nd4j.linalg.primitives.Pair;

import static org.bytedeco.javacpp.cuda.CUstream_st;
import static org.bytedeco.javacpp.cudnn.*;

/**
 * cuDNN-based helper for the batch normalization layer.
 *
 * @author saudet
 */
@Slf4j
public class CudnnBatchNormalizationHelper extends BaseCudnnHelper implements BatchNormalizationHelper {

    private static class CudnnBatchNormalizationContext extends CudnnContext {

        private static class Deallocator extends CudnnBatchNormalizationContext implements Pointer.Deallocator {
            Deallocator(CudnnBatchNormalizationContext c) {
                super(c);
            }

            @Override
            public void deallocate() {
                destroyHandles();
            }
        }

        private cudnnTensorStruct srcTensorDesc = new cudnnTensorStruct(), dstTensorDesc = new cudnnTensorStruct(),
                        deltaTensorDesc = new cudnnTensorStruct(), gammaBetaTensorDesc = new cudnnTensorStruct();

        public CudnnBatchNormalizationContext() {
            createHandles();
            deallocator(new Deallocator(this));
        }

        public CudnnBatchNormalizationContext(CudnnBatchNormalizationContext c) {
            super(c);
            srcTensorDesc = new cudnnTensorStruct(c.srcTensorDesc);
            dstTensorDesc = new cudnnTensorStruct(c.dstTensorDesc);
            deltaTensorDesc = new cudnnTensorStruct(c.deltaTensorDesc);
            gammaBetaTensorDesc = new cudnnTensorStruct(c.gammaBetaTensorDesc);
        }

        @Override
        protected void createHandles() {
            super.createHandles();
            checkCudnn(cudnnCreateTensorDescriptor(srcTensorDesc));
            checkCudnn(cudnnCreateTensorDescriptor(dstTensorDesc));
            checkCudnn(cudnnCreateTensorDescriptor(deltaTensorDesc));
            checkCudnn(cudnnCreateTensorDescriptor(gammaBetaTensorDesc));
        }

        @Override
        protected void destroyHandles() {
            checkCudnn(cudnnDestroyTensorDescriptor(srcTensorDesc));
            checkCudnn(cudnnDestroyTensorDescriptor(dstTensorDesc));
            checkCudnn(cudnnDestroyTensorDescriptor(deltaTensorDesc));
            checkCudnn(cudnnDestroyTensorDescriptor(gammaBetaTensorDesc));
            super.destroyHandles();
        }
    }

    protected final int batchNormMode = CUDNN_BATCHNORM_SPATIAL; // would need to increase rank of gamma and beta for CUDNN_BATCHNORM_PER_ACTIVATION

    private CudnnBatchNormalizationContext cudnnContext = new CudnnBatchNormalizationContext();
    private DataCache meanCache = new DataCache();
    private DataCache varCache = new DataCache();

    public boolean checkSupported(double eps) {
        boolean supported = checkSupported();
        if (eps < CUDNN_BN_MIN_EPSILON) {
            supported = false;
            log.warn("Not supported: eps < CUDNN_BN_MIN_EPSILON (" + eps + " < " + CUDNN_BN_MIN_EPSILON + ")");
        }
        return supported;
    }

    @Override
    public Pair<Gradient, INDArray> backpropGradient(INDArray input, INDArray epsilon, int[] shape, INDArray gamma,
                    INDArray dGammaView, INDArray dBetaView, double eps) {
        int miniBatch = input.size(0);
        int depth = input.size(1);
        int inH = input.size(2);
        int inW = input.size(3);

        Gradient retGradient = new DefaultGradient();

        if (!Shape.strideDescendingCAscendingF(epsilon)) {
            // apparently not supported by cuDNN
            epsilon = epsilon.dup();
        }

        int[] srcStride = input.stride();
        int[] deltaStride = epsilon.stride();

        if (Nd4j.getExecutioner() instanceof GridExecutioner)
            ((GridExecutioner) Nd4j.getExecutioner()).flushQueue();

        checkCudnn(cudnnSetTensor4dDescriptorEx(cudnnContext.srcTensorDesc, dataType, miniBatch, depth, inH, inW,
                        srcStride[0], srcStride[1], srcStride[2], srcStride[3]));
        checkCudnn(cudnnSetTensor4dDescriptorEx(cudnnContext.deltaTensorDesc, dataType, miniBatch, depth, inH, inW,
                        deltaStride[0], deltaStride[1], deltaStride[2], deltaStride[3]));

        INDArray nextEpsilon = Nd4j.createUninitialized(new int[] {miniBatch, depth, inH, inW}, 'c');
        int[] dstStride = nextEpsilon.stride();
        checkCudnn(cudnnSetTensor4dDescriptorEx(cudnnContext.dstTensorDesc, dataType, miniBatch, depth, inH, inW,
                        dstStride[0], dstStride[1], dstStride[2], dstStride[3]));
        int[] gammaStride = gamma.stride();
        checkCudnn(cudnnSetTensor4dDescriptor(cudnnContext.gammaBetaTensorDesc, tensorFormat, dataType, shape[0],
                        shape[1], shape.length > 2 ? shape[2] : 1, shape.length > 3 ? shape[3] : 1));

        Allocator allocator = AtomicAllocator.getInstance();
        CudaContext context = allocator.getFlowController().prepareActionAllWrite(input, epsilon, nextEpsilon, gamma,
                        dGammaView, dBetaView);
        Pointer srcData = allocator.getPointer(input, context);
        Pointer epsData = allocator.getPointer(epsilon, context);
        Pointer dstData = allocator.getPointer(nextEpsilon, context);
        Pointer gammaData = allocator.getPointer(gamma, context);
        Pointer dGammaData = allocator.getPointer(dGammaView, context);
        Pointer dBetaData = allocator.getPointer(dBetaView, context);

        checkCudnn(cudnnSetStream(cudnnContext, new CUstream_st(context.getOldStream())));
        checkCudnn(cudnnBatchNormalizationBackward(cudnnContext, batchNormMode, alpha, beta, alpha, alpha,
                        cudnnContext.srcTensorDesc, srcData, cudnnContext.deltaTensorDesc, epsData,
                        cudnnContext.dstTensorDesc, dstData, cudnnContext.gammaBetaTensorDesc, gammaData, dGammaData,
                        dBetaData, eps, meanCache, varCache));

        allocator.getFlowController().registerActionAllWrite(context, input, epsilon, nextEpsilon, gamma, dGammaView,
                        dBetaView);

        retGradient.setGradientFor(BatchNormalizationParamInitializer.GAMMA, dGammaView);
        retGradient.setGradientFor(BatchNormalizationParamInitializer.BETA, dBetaView);

        if (CudaEnvironment.getInstance().getConfiguration().isDebug())
            context.syncOldStream();

        return new Pair<>(retGradient, nextEpsilon);
    }


    @Override
    public INDArray preOutput(INDArray x, boolean training, int[] shape, INDArray gamma, INDArray beta, INDArray mean,
                    INDArray var, double decay, double eps) {
        int miniBatch = x.size(0);
        int inDepth = x.size(1);
        int inH = x.size(2);
        int inW = x.size(3);

        int[] srcStride = x.stride();
        checkCudnn(cudnnSetTensor4dDescriptorEx(cudnnContext.srcTensorDesc, dataType, miniBatch, inDepth, inH, inW,
                        srcStride[0], srcStride[1], srcStride[2], srcStride[3]));

        INDArray activations = Nd4j.createUninitialized(new int[] {miniBatch, inDepth, inH, inW}, 'c');
        int[] dstStride = activations.stride();
        checkCudnn(cudnnSetTensor4dDescriptorEx(cudnnContext.dstTensorDesc, dataType, miniBatch, inDepth, inH, inW,
                        dstStride[0], dstStride[1], dstStride[2], dstStride[3]));
        int[] gammaStride = gamma.stride();
        checkCudnn(cudnnSetTensor4dDescriptor(cudnnContext.gammaBetaTensorDesc, tensorFormat, dataType, shape[0],
                        shape[1], shape.length > 2 ? shape[2] : 1, shape.length > 3 ? shape[3] : 1));

        Allocator allocator = AtomicAllocator.getInstance();
        CudaContext context =
                        allocator.getFlowController().prepareActionAllWrite(x, activations, gamma, beta, mean, var);
        Pointer srcData = allocator.getPointer(x, context);
        Pointer dstData = allocator.getPointer(activations, context);
        Pointer gammaData = allocator.getPointer(gamma, context);
        Pointer betaData = allocator.getPointer(beta, context);
        Pointer meanData = allocator.getPointer(mean, context);
        Pointer varData = allocator.getPointer(var, context);

        if (Nd4j.getExecutioner() instanceof GridExecutioner)
            ((GridExecutioner) Nd4j.getExecutioner()).flushQueue();

        checkCudnn(cudnnSetStream(cudnnContext, new CUstream_st(context.getOldStream())));
        if (training) {
            if (meanCache.capacity() < mean.data().length() * mean.data().getElementSize()) {
                meanCache.deallocate();
                meanCache = new DataCache(mean.data().length() * mean.data().getElementSize());
            }
            if (varCache.capacity() < var.data().length() * mean.data().getElementSize()) {
                varCache.deallocate();
                varCache = new DataCache(var.data().length() * mean.data().getElementSize());
            }
            checkCudnn(cudnnBatchNormalizationForwardTraining(cudnnContext, batchNormMode, this.alpha, this.beta,
                            cudnnContext.srcTensorDesc, srcData, cudnnContext.dstTensorDesc, dstData,
                            cudnnContext.gammaBetaTensorDesc, gammaData, betaData, decay, meanData, varData, eps,
                            meanCache, varCache));
        } else {
            checkCudnn(cudnnBatchNormalizationForwardInference(cudnnContext, batchNormMode, this.alpha, this.beta,
                            cudnnContext.srcTensorDesc, srcData, cudnnContext.dstTensorDesc, dstData,
                            cudnnContext.gammaBetaTensorDesc, gammaData, betaData, meanData, varData, eps));
        }

        allocator.getFlowController().registerActionAllWrite(context, x, activations, gamma, beta, mean, var);

        if (CudaEnvironment.getInstance().getConfiguration().isDebug())
            context.syncOldStream();

        return activations;
    }

}
