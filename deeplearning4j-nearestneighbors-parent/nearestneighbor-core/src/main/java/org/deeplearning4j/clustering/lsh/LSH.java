package org.deeplearning4j.clustering.lsh;

import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.BaseAccumulation;

/**
 * This interface gathers the minimal elements for an LSH implementation
 *
 * See chapter 3 of :
 * _Mining Massive Datasets_, Anand Rajaraman and Jeffrey Ullman
 * http://www.mmds.org/
 *
 */
public interface LSH {

    /**
     * Returns an instance of the distance measure associated to the LSH family of this implementation.
     * Beware, hashing families and their amplification constructs are distance-specific.
     */
    public String getDistanceMeasure();

    /**
     * Returns the size of a hash compared against in one hashing bucket, corresponding to an AND construction
     *
     * denoting hashLength by h,
     * amplifies a (d1, d2, p1, p2) hash family into a
     *                   (d1, d2, p1^h, p2^h)-sensitive one (match probability is decreasing with h)
     *
     * @return the length of the hash in the AND construction used by this index
     */
    public int getHashLength();

    /**
     *
     * denoting numTables by n,
     * amplifies a (d1, d2, p1, p2) hash family into a
     *                   (d1, d2, (1-p1^n), (1-p2^n))-sensitive one (match probability is increasing with n)
     *
     * @return the # of hash tables in the OR construction used by this index
     */
    public int getNumTables();

    /**
     * @return The dimension of the index vectors and queries
     */
    public int getInDimension();

    /**
     * Populates the index with data vectors.
     * @param data the vectors to index
     */
    public void makeIndex(INDArray data);

    /**
     * Returns the set of all vectors that could approximately be considered negihbors of the query,
     * without selection on the basis of distance or number of neighbors.
     * @param query a  vector to find neighbors for
     * @return its approximate neighbors, unfiltered
     */
    public INDArray bucket(INDArray query);

    /**
     * Returns the approximate neighbors within a distance bound.
     * @param query a vector to find neighbors for
     * @param maxRange the maximum distance between results and the query
     * @return approximate neighbors within the distance bounds
     */
    public INDArray search(INDArray query, double maxRange);

    /**
     * Returns the approximate neighbors within a k-closest bound
     * @param query a vector to find neighbors for
     * @param k the maximum number of closest neighbors to return
     * @return at most k neighbors of the query, ordered by increasing distance
     */
    public INDArray search(INDArray query, int k);
}
