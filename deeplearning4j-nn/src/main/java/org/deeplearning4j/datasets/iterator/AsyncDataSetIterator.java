package org.deeplearning4j.datasets.iterator;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.datasets.iterator.callbacks.DataSetCallback;
import org.deeplearning4j.datasets.iterator.callbacks.DefaultCallback;
import org.nd4j.linalg.api.memory.MemoryWorkspace;
import org.nd4j.linalg.api.memory.conf.WorkspaceConfiguration;
import org.nd4j.linalg.api.memory.enums.AllocationPolicy;
import org.nd4j.linalg.api.memory.enums.LearningPolicy;
import org.nd4j.linalg.api.memory.enums.ResetPolicy;
import org.nd4j.linalg.api.memory.enums.SpillPolicy;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.DataSetPreProcessor;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.factory.Nd4j;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Async prefetching iterator wrapper for MultiDataSetIterator implementations
 *
 * @author raver119@gmail.com
 */
@Slf4j
public class AsyncDataSetIterator implements DataSetIterator {
    protected DataSetIterator backedIterator;

    protected DataSet terminator = new DataSet();
    protected DataSet nextElement = null;
    protected BlockingQueue<DataSet> buffer;
    protected AsyncPrefetchThread thread;
    protected AtomicBoolean shouldWork = new AtomicBoolean(true);
    protected volatile RuntimeException throwable = null;
    protected boolean useWorkspace = true;
    protected int prefetchSize;
    protected String workspaceId;
    protected Integer deviceId;
    protected AtomicBoolean hasDepleted = new AtomicBoolean(false);

    protected DataSetCallback callback;

    protected AsyncDataSetIterator() {
        //
    }

    public AsyncDataSetIterator(DataSetIterator baseIterator) {
        this(baseIterator, 8);
    }

    public AsyncDataSetIterator(DataSetIterator iterator, int queueSize, BlockingQueue<DataSet> queue) {
        this(iterator, queueSize, queue, true);
    }

    public AsyncDataSetIterator(DataSetIterator baseIterator, int queueSize) {
        this(baseIterator, queueSize, new LinkedBlockingQueue<DataSet>(queueSize));
    }

    public AsyncDataSetIterator(DataSetIterator baseIterator, int queueSize, boolean useWorkspace) {
        this(baseIterator, queueSize, new LinkedBlockingQueue<DataSet>(queueSize), useWorkspace);
    }

    public AsyncDataSetIterator(DataSetIterator baseIterator, int queueSize, boolean useWorkspace, Integer deviceId) {
        this(baseIterator, queueSize, new LinkedBlockingQueue<DataSet>(queueSize), useWorkspace, new DefaultCallback(),
                        deviceId);
    }

    public AsyncDataSetIterator(DataSetIterator baseIterator, int queueSize, boolean useWorkspace,
                    DataSetCallback callback) {
        this(baseIterator, queueSize, new LinkedBlockingQueue<DataSet>(queueSize), useWorkspace, callback);
    }

    public AsyncDataSetIterator(DataSetIterator iterator, int queueSize, BlockingQueue<DataSet> queue,
                    boolean useWorkspace) {
        this(iterator, queueSize, queue, useWorkspace, new DefaultCallback());
    }

    public AsyncDataSetIterator(DataSetIterator iterator, int queueSize, BlockingQueue<DataSet> queue,
                    boolean useWorkspace, DataSetCallback callback) {
        this(iterator, queueSize, queue, useWorkspace, callback, Nd4j.getAffinityManager().getDeviceForCurrentThread());
    }

    public AsyncDataSetIterator(DataSetIterator iterator, int queueSize, BlockingQueue<DataSet> queue,
                    boolean useWorkspace, DataSetCallback callback, Integer deviceId) {
        if (queueSize < 2)
            queueSize = 2;

        this.deviceId = deviceId;
        this.callback = callback;
        this.useWorkspace = useWorkspace;
        this.buffer = queue;
        this.prefetchSize = queueSize;
        this.backedIterator = iterator;
        this.workspaceId = "ADSI_ITER-" + java.util.UUID.randomUUID().toString();

        if (iterator.resetSupported() && !iterator.hasNext())
            this.backedIterator.reset();

        this.thread = new AsyncPrefetchThread(buffer, iterator, terminator, null);

        /**
         * We want to ensure, that background thread will have the same thread->device affinity, as master thread
         */

        Nd4j.getAffinityManager().attachThreadToDevice(thread, deviceId);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Like the standard next method but allows a
     * customizable number of examples returned
     *
     * @param num the number of examples
     * @return the next data applyTransformToDestination
     */
    @Override
    public DataSet next(int num) {
        throw new UnsupportedOperationException();
    }

    /**
     * Total examples in the iterator
     *
     * @return
     */
    @Override
    public int totalExamples() {
        return backedIterator.totalExamples();
    }

    /**
     * Input columns for the dataset
     *
     * @return
     */
    @Override
    public int inputColumns() {
        return backedIterator.inputColumns();
    }

    /**
     * The number of labels for the dataset
     *
     * @return
     */
    @Override
    public int totalOutcomes() {
        return backedIterator.totalOutcomes();
    }

    /**
     * Is resetting supported by this DataSetIterator? Many DataSetIterators do support resetting,
     * but some don't
     *
     * @return true if reset method is supported; false otherwise
     */
    @Override
    public boolean resetSupported() {
        return backedIterator.resetSupported();
    }

    /**
     * Does this DataSetIterator support asynchronous prefetching of multiple DataSet objects?
     * Most DataSetIterators do, but in some cases it may not make sense to wrap this iterator in an
     * iterator that does asynchronous prefetching. For example, it would not make sense to use asynchronous
     * prefetching for the following types of iterators:
     * (a) Iterators that store their full contents in memory already
     * (b) Iterators that re-use features/labels arrays (as future next() calls will overwrite past contents)
     * (c) Iterators that already implement some level of asynchronous prefetching
     * (d) Iterators that may return different data depending on when the next() method is called
     *
     * @return true if asynchronous prefetching from this iterator is OK; false if asynchronous prefetching should not
     * be used with this iterator
     */
    @Override
    public boolean asyncSupported() {
        return false;
    }

    protected void externalCall() {
        // for spark
    }

    /**
     * Resets the iterator back to the beginning
     */
    @Override
    public void reset() {
        buffer.clear();

        if (thread != null)
            thread.interrupt();
        try {
            // Shutdown() should be a synchronous operation since the iterator is reset after shutdown() is
            // called in AsyncLabelAwareIterator.reset().
            if (thread != null)
                thread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        this.thread.shutdown();
        buffer.clear();


        backedIterator.reset();
        shouldWork.set(true);
        this.thread = new AsyncPrefetchThread(buffer, backedIterator, terminator, null);

        /**
         * We want to ensure, that background thread will have the same thread->device affinity, as master thread
         */
        Nd4j.getAffinityManager().attachThreadToDevice(thread, deviceId);

        thread.setDaemon(true);
        thread.start();
        hasDepleted.set(false);

        nextElement = null;
    }

    /**
     * This method will terminate background thread AND will destroy attached workspace (if any)
     *
     * PLEASE NOTE: After shutdown() call, this instance can't be used anymore
     */
    public void shutdown() {
        buffer.clear();

        if (thread != null)
            thread.interrupt();
        try {
            // Shutdown() should be a synchronous operation since the iterator is reset after shutdown() is
            // called in AsyncLabelAwareIterator.reset().
            if (thread != null)
                thread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        this.thread.shutdown();
        buffer.clear();
    }

    /**
     * Batch size
     *
     * @return
     */
    @Override
    public int batch() {
        return backedIterator.batch();
    }

    /**
     * The current cursor if applicable
     *
     * @return
     */
    @Override
    public int cursor() {
        return backedIterator.cursor();
    }

    /**
     * Total number of examples in the dataset
     *
     * @return
     */
    @Override
    public int numExamples() {
        return backedIterator.numExamples();
    }

    /**
     * Set a pre processor
     *
     * @param preProcessor a pre processor to set
     */
    @Override
    public void setPreProcessor(DataSetPreProcessor preProcessor) {
        backedIterator.setPreProcessor(preProcessor);
    }

    /**
     * Returns preprocessors, if defined
     *
     * @return
     */
    @Override
    public DataSetPreProcessor getPreProcessor() {
        return backedIterator.getPreProcessor();
    }

    /**
     * Get dataset iterator record reader labels
     */
    @Override
    public List<String> getLabels() {
        return backedIterator.getLabels();
    }

    /**
     * Returns {@code true} if the iteration has more elements.
     * (In other words, returns {@code true} if {@link #next} would
     * return an element rather than throwing an exception.)
     *
     * @return {@code true} if the iteration has more elements
     */
    @Override
    public boolean hasNext() {
        if (throwable != null)
            throw throwable;

        try {
            if (hasDepleted.get())
                return false;

            if (nextElement != null && nextElement != terminator) {
                return true;
            } else if (nextElement == terminator)
                return false;


            nextElement = buffer.take();

            if (nextElement == terminator) {
                hasDepleted.set(true);
                return false;
            }

            return true;
        } catch (Exception e) {
            log.error("Premature end of loop!");
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the next element in the iteration.
     *
     * @return the next element in the iteration
     */
    @Override
    public DataSet next() {
        if (throwable != null)
            throw throwable;

        if (hasDepleted.get())
            return null;

        DataSet temp = nextElement;
        nextElement = null;
        return temp;
    }

    /**
     * Removes from the underlying collection the last element returned
     * by this iterator (optional operation).  This method can be called
     * only once per call to {@link #next}.  The behavior of an iterator
     * is unspecified if the underlying collection is modified while the
     * iteration is in progress in any way other than by calling this
     * method.
     *
     * @throws UnsupportedOperationException if the {@code remove}
     *                                       operation is not supported by this iterator
     * @throws IllegalStateException         if the {@code next} method has not
     *                                       yet been called, or the {@code remove} method has already
     *                                       been called after the last call to the {@code next}
     *                                       method
     * @implSpec The default implementation throws an instance of
     * {@link UnsupportedOperationException} and performs no other action.
     */
    @Override
    public void remove() {

    }

    protected class AsyncPrefetchThread extends Thread implements Runnable {
        private BlockingQueue<DataSet> queue;
        private DataSetIterator iterator;
        private DataSet terminator;
        private AtomicBoolean isShutdown = new AtomicBoolean(false);
        private WorkspaceConfiguration configuration = WorkspaceConfiguration.builder().minSize(10 * 1024L * 1024L)
                        .overallocationLimit(prefetchSize + 1).policyReset(ResetPolicy.ENDOFBUFFER_REACHED)
                        .policyLearning(LearningPolicy.FIRST_LOOP).policyAllocation(AllocationPolicy.OVERALLOCATE)
                        .policySpill(SpillPolicy.REALLOCATE).build();

        private MemoryWorkspace workspace;


        protected AsyncPrefetchThread(@NonNull BlockingQueue<DataSet> queue, @NonNull DataSetIterator iterator,
                        @NonNull DataSet terminator, MemoryWorkspace workspace) {
            this.queue = queue;
            this.iterator = iterator;
            this.terminator = terminator;

            this.setDaemon(true);
            this.setName("ADSI prefetch thread");
        }

        @Override
        public void run() {
            externalCall();
            try {
                if (useWorkspace)
                    workspace = Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(configuration, workspaceId);

                while (iterator.hasNext() && shouldWork.get()) {
                    DataSet smth = null;

                    if (useWorkspace) {
                        try (MemoryWorkspace ws = workspace.notifyScopeEntered()) {
                            smth = iterator.next();

                            if (callback != null)
                                callback.call(smth);
                        }
                    } else {
                        smth = iterator.next();

                        if (callback != null)
                            callback.call(smth);
                    }

                    // we want to ensure underlying iterator finished dataset creation
                    Nd4j.getExecutioner().commit();

                    if (smth != null)
                        queue.put(smth);

                }
                queue.put(terminator);
            } catch (InterruptedException e) {
                // do nothing
                shouldWork.set(false);
            } catch (RuntimeException e) {
                throwable = e;
                throw new RuntimeException(e);
            } catch (Exception e) {
                throwable = new RuntimeException(e);
                throw new RuntimeException(e);
            } finally {
                //log.info("Trying destroy...");
                //if (useWorkspace)
                //Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread(workspaceId).destroyWorkspace();
                isShutdown.set(true);
            }
        }

        public void shutdown() {
            while (!isShutdown.get())
                LockSupport.parkNanos(100L);

            if (workspace != null) {
                log.debug("Manually destroying ADSI workspace");
                workspace.destroyWorkspace(true);
            }
        }
    }
}
