package org.deeplearning4j.nn.conf.dropout;

import lombok.Data;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.impl.transforms.arithmetic.MulOp;
import org.nd4j.linalg.api.ops.impl.transforms.arithmetic.OldMulOp;
import org.nd4j.linalg.api.ops.random.impl.GaussianDistribution;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.schedule.ISchedule;
import org.nd4j.shade.jackson.annotation.JsonProperty;

/**
 * Gaussian dropout. This is a multiplicative Gaussian noise (mean 1) on the input activations.<br>
 * <br>
 * Each input activation x is independently set to:<br>
 * x <- x * y, where y ~ N(1, stdev = sqrt((1-rate)/rate))<br>
 * Dropout schedules (i.e., varying probability p as a function of iteration/epoch) are also supported.<br>
 * <br>
 * Note 1: As per all IDropout instances, GaussianDropout is applied at training time only - and is automatically not
 * applied at test time (for evaluation, etc)<br>
 * Note 2: Frequently, dropout is not applied to (or, has higher retain probability for) input (first layer)
 * layers. Dropout is also often not applied to output layers.<br>
 * <br>
 * See: "Multiplicative Gaussian Noise" in Srivastava et al. 2014: Dropout: A Simple Way to Prevent Neural Networks from
 * Overfitting <a href="http://www.cs.toronto.edu/~rsalakhu/papers/srivastava14a.pdf">http://www.cs.toronto.edu/~rsalakhu/papers/srivastava14a.pdf</a>
 *
 * @author Alex Black
 */
@Data
public class GaussianDropout implements IDropout {

    private final double rate;
    private final ISchedule rateSchedule;

    /**
     * @param rate Rate parameter, see {@link GaussianDropout}
     */
    public GaussianDropout(double rate){
        this(rate, null);
    }

    /**
     * @param rateSchedule Schedule for rate parameter, see {@link GaussianDropout}
     */
    public GaussianDropout(ISchedule rateSchedule){
        this(Double.NaN, rateSchedule);
    }

    protected GaussianDropout(@JsonProperty("rate") double rate, @JsonProperty("rateSchedule") ISchedule rateSchedule){
        this.rate = rate;
        this.rateSchedule = rateSchedule;
    }

    @Override
    public INDArray applyDropout(INDArray inputActivations, int iteration, int epoch, boolean inPlace) {
        double r;
        if(rateSchedule != null){
            r = rateSchedule.valueAt(iteration, epoch);
        } else {
            r = rate;
        }

        double stdev = Math.sqrt(r / (1.0 - r));

        INDArray noise = Nd4j.createUninitialized(inputActivations.shape(), inputActivations.ordering());
        Nd4j.getExecutioner().exec(new GaussianDistribution(noise, 1.0, stdev));

        if(inPlace){
            return inputActivations.muli(noise);
        } else {
            INDArray result = Nd4j.createUninitialized(inputActivations.shape(), inputActivations.ordering());
            return Nd4j.getExecutioner().execAndReturn(new OldMulOp(inputActivations, noise, result));
        }
    }

    @Override
    public GaussianDropout clone() {
        return new GaussianDropout(rate, rateSchedule == null ? null : rateSchedule.clone());
    }
}
