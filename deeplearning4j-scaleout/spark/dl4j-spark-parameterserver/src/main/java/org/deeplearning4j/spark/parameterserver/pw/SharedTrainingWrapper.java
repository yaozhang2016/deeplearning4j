package org.deeplearning4j.spark.parameterserver.pw;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.Loader;
import org.deeplearning4j.exception.DL4JInvalidConfigException;
import org.deeplearning4j.nn.api.Model;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.optimize.listeners.SleepyTrainingListener;
import org.deeplearning4j.optimize.solvers.accumulation.EncodedGradientsAccumulator;
import org.deeplearning4j.optimize.solvers.accumulation.MessageHandler;
import org.deeplearning4j.parallelism.ParallelWrapper;
import org.deeplearning4j.spark.parameterserver.conf.SharedTrainingConfiguration;
import org.deeplearning4j.spark.parameterserver.iterators.VirtualDataSetIterator;
import org.deeplearning4j.spark.parameterserver.iterators.VirtualIterator;
import org.deeplearning4j.spark.parameterserver.iterators.VirtualMultiDataSetIterator;
import org.deeplearning4j.spark.parameterserver.networking.SilentTrainingDriver;
import org.deeplearning4j.spark.parameterserver.networking.WiredEncodingHandler;
import org.deeplearning4j.spark.parameterserver.networking.messages.SilentIntroductoryMessage;
import org.deeplearning4j.spark.parameterserver.training.SharedTrainingResult;
import org.deeplearning4j.spark.parameterserver.training.SharedTrainingWorker;
import org.deeplearning4j.spark.parameterserver.util.BlockingObserver;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.MultiDataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.parameterserver.distributed.VoidParameterServer;
import org.nd4j.parameterserver.distributed.conf.VoidConfiguration;
import org.nd4j.parameterserver.distributed.enums.TransportType;
import org.nd4j.parameterserver.distributed.transport.MulticastTransport;
import org.nd4j.parameterserver.distributed.transport.RoutedTransport;
import org.nd4j.parameterserver.distributed.transport.Transport;
import org.nd4j.parameterserver.distributed.util.NetworkOrganizer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This class maintains ParallelWrapper instance in Spark environment, and provides primitives for inter-executor
 * communication during training over partitions.
 *
 * @author raver119@gmail.com
 */
@Slf4j
public class SharedTrainingWrapper {
    public static SharedTrainingWrapper INSTANCE = new SharedTrainingWrapper();
    protected ParallelWrapper wrapper;
    protected VirtualDataSetIterator iteratorDS;
    protected VirtualMultiDataSetIterator iteratorMDS;

    protected List<Iterator<DataSet>> iteratorsDS;
    protected List<Iterator<MultiDataSet>> iteratorsMDS;


    protected AtomicBoolean isFirst = new AtomicBoolean(false);

    protected ThreadLocal<BlockingObserver> observer = new ThreadLocal<>();
    protected EncodedGradientsAccumulator accumulator;
    protected Model originalModel;

    protected SilentTrainingDriver driver;

    protected SharedTrainingWrapper() {
        init();
    }

    protected void init() {
        // instantiate some stuff here
        iteratorsDS = new CopyOnWriteArrayList<>();
        iteratorsMDS = new CopyOnWriteArrayList<>();

        // now we're creating DataSetIterators, to feed ParallelWrapper
        iteratorDS = new VirtualDataSetIterator(iteratorsDS);
    }

    public static SharedTrainingWrapper getInstance() {
        return INSTANCE;
    }

    /**
     * This method registers given Iterable<DataSet> in VirtualDataSetIterator
     *
     * @param iterator
     */
    public void attachDS(Iterator<DataSet> iterator) {
        log.info("Attaching thread...");

        // we're creating our Observable wrapper
        VirtualIterator<DataSet> wrapped = new VirtualIterator<>(iterator);

        // and creating Observer which will be used to monitor progress within iterator
        BlockingObserver obs = new BlockingObserver();
        wrapped.addObserver(obs);

        // putting that "somewhere"
        iteratorsDS.add(wrapped);

        // storing observer into ThreadLocal, since we're going to use that later
        observer.set(obs);
    }

    /**
     * This method registers given Iterable<MultiDataSet> in VirtualMultiDataSetIterator
     *
     * @param iterator
     */
    public void attachMDS(Iterator<MultiDataSet> iterator) {
        log.info("Attaching thread...");

        // we're creating our Observable wrapper
        VirtualIterator<MultiDataSet> wrapped = new VirtualIterator<>(iterator);

        // and creating Observer which will be used to monitor progress within iterator
        BlockingObserver obs = new BlockingObserver();
        wrapped.addObserver(obs);

        // putting that "somewhere"
        iteratorsMDS.add(wrapped);

        // storing observer into ThreadLocal, since we're going to use that later
        observer.set(obs);
    }

    public SharedTrainingResult run(SharedTrainingWorker worker) {
        /*
            first call instantiates pw, messenger etc, and gets in charge here.
         */
        if (isFirst.compareAndSet(false, true)) {
            SharedTrainingConfiguration trainingConfiguration = worker.getBroadcastConfiguration().getValue();
            VoidConfiguration voidConfiguration = worker.getBroadcastConfiguration().getValue().getVoidConfiguration();

            Model model = null;

            /*
                    Plan is simple here: if there's defined field in SharedTrainingConfiguration - use that.
                    If no - try to guess something
                 */
            int numDevices = Nd4j.getAffinityManager().getNumberOfDevices();

            int numCores = Loader.totalCores();

            /**
             * Logic here is simple:
             * 1) If user had specified number of workers per node - use that value
             * 2) If not, and there's > 1 devices in system (as in Multi-GPU system) - use numberOfDevices as number of workers
             * 3) otherwise, let's assume that's regular multi-core node, so we'll use 1..6 workers, depending on number of cores/4
             */
            int numWorkers = trainingConfiguration.getNumberOfWorkersPerNode() > 0
                            ? trainingConfiguration.getNumberOfWorkersPerNode()
                            : numDevices > 1 ? numDevices : Math.min(6, Math.max(1, numCores / 4));

            if (numDevices > 1 && numWorkers > numDevices)
                log.warn("WARNING! Using more workers then number of available computational devices!");



            // now we're attaching VoidParameterServer to GradientsAccumulator, but doing that only once
            if (wrapper == null) {
                log.info("Starting ParallelWrapper at thread {}", Thread.currentThread().getId());

                model = worker.getInitialModel();
                if (model == null)
                    model = worker.getInitialModelGraph();

                if (model == null)
                    throw new DL4JInvalidConfigException("No model was defined for training");

                MessageHandler handler = new WiredEncodingHandler(trainingConfiguration.getThreshold(),
                                trainingConfiguration.getMinThreshold(), trainingConfiguration.getThresholdStep(),
                                trainingConfiguration.getStepTrigger(), trainingConfiguration.getStepDelay(),
                                trainingConfiguration.getShakeFrequency());

                // this accumulator will provide sharing gradients over network, via WiredEncodedHandler. But we create it only once
                if (accumulator == null) {
                    /**
                     *  We know, that updates are guaranteed to have MAX size of params / 16. So, here we go.
                     *  I.e. for model with 100m params, that's 400m of floats (or 800m of doubles)
                     *  The worst case for us is bitmap encoding, that takes 2 bits to encode each gradient value
                     *
                     *  so, for float in worst case we'll have (100m / 16) int elements. So, our buffer size will be 6.25m * queueSize * 4 bytes per int
                     */

                    int queueSize = numWorkers * 2;

                    int bufferSize = trainingConfiguration.getBufferSize() > 0 ? trainingConfiguration.getBufferSize()
                                    : EncodedGradientsAccumulator.getOptimalBufferSize(model, numWorkers, 2);

                    accumulator = new EncodedGradientsAccumulator.Builder(numWorkers).messageHandler(handler)
                                    .encodingThreshold(trainingConfiguration.getThreshold())
                                    .memoryParameters(bufferSize, queueSize).build();

                    // FIXME: implement support for Custom transport implementation
                    Transport transport =
                                    voidConfiguration.getTransportType() == TransportType.ROUTED ? new RoutedTransport()
                                                    : voidConfiguration.getTransportType() == TransportType.BROADCAST
                                                                    ? new MulticastTransport() : null;

                    if (transport == null)
                        throw new DL4JInvalidConfigException(
                                        "No Transport implementation was defined for this training session!");

                    // let's check for spark local edge case
                    if (!VoidParameterServer.getInstance().isInit()) {
                        // all nodes that are NOT master - enforced to be Clients
                        voidConfiguration.setForcedRole(null);

                        // TODO: tbd: let's allow one of executor nodes to be silent worker maybe? or this going to be too expensive?
                    }

                    driver = new SilentTrainingDriver(accumulator);
                    VoidParameterServer.getInstance().init(voidConfiguration, transport, driver);

                    // we're saving reference to original model
                    originalModel = model;

                    // we should introduce ourselves to controller
                    // FIXME: if localIP is null - use original ip discovery available in VoidParameterServer
                    String localIP = System.getenv("SPARK_PUBLIC_DNS");

                    // picking IP address based on network mask
                    if (localIP == null && voidConfiguration.getNetworkMask() != null) {
                        NetworkOrganizer organizer = new NetworkOrganizer(voidConfiguration.getNetworkMask());
                        localIP = organizer.getMatchingAddress();
                    }

                    // last resort here...
                    if (localIP == null)
                        localIP = System.getenv("DL4J_VOID_IP");

                    // set it to localhost, and hope for BroadcastTransport used
                    if (localIP == null) {
                        localIP = "127.0.0.1";
                        log.warn("Can't get IP address to start VoidParameterServer client. Using localhost instead");
                    }

                    // FIXME: do we need port here, in case of Multicast/Broadcast Transport?
                    SilentIntroductoryMessage sim =
                                    new SilentIntroductoryMessage(localIP, voidConfiguration.getUnicastPort());

                    // we're sending this message to all shards, though it's just one Shard by design here - Spark Master
                    VoidParameterServer.getInstance().sendMessageToAllShards(sim);

                    // after initialization finished, we're ok to actually start training
                }

                // if we're going to extend iteratation for debugging purposes - let's do that here
                if (trainingConfiguration.getDebugLongerIterations() > 0) {
                    log.warn("Adding SleepyListener: {} ms", trainingConfiguration.getDebugLongerIterations());
                    model.addListeners(SleepyTrainingListener.builder()
                                    .timerIteration(trainingConfiguration.getDebugLongerIterations()).build());
                }

                // we're launching PW only if number of workers is more then 1
                if (numWorkers > 1) {
                    log.info("Params at PW: {}", originalModel.params().meanNumber().doubleValue());

                    wrapper = new ParallelWrapper.Builder<>(originalModel).workers(numWorkers)
                                    .workspaceMode(trainingConfiguration.getWorkspaceMode())
                                    .trainingMode(ParallelWrapper.TrainingMode.CUSTOM).gradientsAccumulator(accumulator)
                                    .prefetchBuffer(trainingConfiguration.getPrefetchSize()).build();
                } else {
                    log.info("Using standalone model instead...");

                    // since there'll be only one consumer, we don't need complex sync logic anymore
                    accumulator.fallbackToSingleConsumerMode(true);
                    accumulator.touch();

                    // ok. attaching accumulator to model
                    if (model instanceof ComputationGraph) {
                        ((ComputationGraph) originalModel).getConfiguration()
                                        .setTrainingWorkspaceMode(trainingConfiguration.getWorkspaceMode());
                        ((ComputationGraph) originalModel).setGradientsAccumulator(accumulator);
                    } else if (model instanceof MultiLayerNetwork) {
                        ((MultiLayerNetwork) originalModel).getLayerWiseConfigurations()
                                        .setTrainingWorkspaceMode(trainingConfiguration.getWorkspaceMode());
                        ((MultiLayerNetwork) originalModel).setGradientsAccumulator(accumulator);
                    }
                }
            }

            // TODO: optionally we might be waiting until we have >1 splits delivered


            driver.bypassMode(false);

            // now we're just calling for fit
            if (wrapper != null) {
                if (iteratorDS != null)
                    wrapper.fit(iteratorDS);
                else if (iteratorMDS != null)
                    wrapper.fit(iteratorMDS);
                else
                    throw new DL4JInvalidConfigException("No iterators were defined for training");
            } else {
                // if wrapper is null, we're fitting standalone model then
                if (iteratorDS != null) {
                    if (model instanceof ComputationGraph) {
                        ((ComputationGraph) originalModel).fit(iteratorDS);
                    } else if (model instanceof MultiLayerNetwork) {
                        ((MultiLayerNetwork) originalModel).fit(iteratorDS);
                    }
                } else if (iteratorMDS != null) {
                    ((ComputationGraph) originalModel).fit(iteratorMDS);
                } else
                    throw new DL4JInvalidConfigException("No iterators were defined for training");
            }


            // conditionally shutdown & reset ParallelWrapper
            if (trainingConfiguration.isEpochReset()) {
                wrapper.shutdown();
                wrapper = null;
            }

            // reset iterators too
            init();

            // and accumulator, to reset its states
            accumulator.reset();

            // current TrainingDriver won't be receiving any updates beyond this point
            driver.bypassMode(true);


            isFirst.set(false);

            log.info("Master thread done...");

            INDArray updaterState = null;
            if (model instanceof ComputationGraph) {
                updaterState = ((ComputationGraph) originalModel).getUpdater().getUpdaterStateViewArray();
            } else if (model instanceof MultiLayerNetwork) {
                updaterState = ((MultiLayerNetwork) originalModel).getUpdater().getStateViewArray();
            }

            // FIXME: fill stats here
            return SharedTrainingResult.builder().aggregationsCount(1).scoreSum(originalModel.score())
                            .updaterStateArray(updaterState).listenerMetaData(new ArrayList<>())
                            .listenerStaticInfo(new ArrayList<>()).listenerUpdates(new ArrayList<>()).build();
        } else {
            // blocking call right here, all non-master threads will be blocked here
            try {
                observer.get().waitTillDone();
                //observer.get().wait();

                log.info("Feeder thread done...");

                //  nothing to do here, just give away empty result
                return new SharedTrainingResult();
            } catch (InterruptedException e) {
                // FIXME: we don't really need to throw it again, it's here only for debugging purposes
                throw new RuntimeException(e);
            }
        }
    }

    public void passDataSet(DataSet dataSet) {
        // we're going to save this dataset into VirtualDataSetIterator
    }

    public void passDataSet(MultiDataSet dataSet) {
        // we're going to save this dataset into VirtualMultiDataSetIterator
    }


    public void blockUntilFinished() throws InterruptedException {
        if (observer.get() != null)
            observer.get().wait();
        else
            throw new IllegalStateException("This method can't be called before iterators initialization");
    }
}
