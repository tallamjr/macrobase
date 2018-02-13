package edu.stanford.futuredata.macrobase.analysis.summary.aplinear;

import edu.stanford.futuredata.macrobase.analysis.summary.util.*;
import edu.stanford.futuredata.macrobase.analysis.summary.util.qualitymetrics.QualityMetric;
import edu.stanford.futuredata.macrobase.util.MacrobaseInternalError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.roaringbitmap.RoaringBitmap;

import java.util.*;
import java.util.concurrent.CountDownLatch;

/**
 * Class for handling the generic, algorithmic aspects of apriori explanation.
 * This class assumes that subgroups posses "aggregates" such as count and outlier_count
 * which can be combined additively. Then, we use APriori to find the subgroups which
 * are the most interesting as defined by "quality metrics" on these aggregates.
 */
public class APrioriLinear {
    private Logger log = LoggerFactory.getLogger("APrioriLinear");

    // **Parameters**
    private QualityMetric[] qualityMetrics;
    private double[] thresholds;

    // **Cached values**
    // Singleton viable sets for quick lookup
    private boolean[] singleNextArray;
    // Sets that have high enough support but not high qualityMetrics, need to be explored
    private HashMap<Integer, HashSet<IntSet>> setNext;
    // Aggregate values for all of the sets we saved
    private HashMap<Integer, Map<IntSet, double []>> savedAggregates;

    public APrioriLinear(
            List<QualityMetric> qualityMetrics,
            List<Double> thresholds
    ) {
        this.qualityMetrics = qualityMetrics.toArray(new QualityMetric[0]);
        this.thresholds = new double[thresholds.size()];
        for (int i = 0; i < thresholds.size(); i++) {
            this.thresholds[i] = thresholds.get(i);
        }
        this.setNext = new HashMap<>(3);
        this.savedAggregates = new HashMap<>(3);
    }

    public List<APLExplanationResult> explain(
            final int[][] attributes,
            double[][] aggregateColumns,
            int cardinality,
            int numThreads,
            HashMap<Integer, RoaringBitmap>[][] bitmap,
            ArrayList<Integer>[] outlierList
    ) {
        final int numAggregates = aggregateColumns.length;
        final int numRows = aggregateColumns[0].length;
        final int numColumns = attributes[0].length;

        // Maximum order of explanations.
        final boolean useIntSetAsArray;
        // 2097151 is 2^21 - 1, the largest value that can fit in a length-three IntSetAsLong.
        // If the cardinality is greater than that, don't use them.
        if (cardinality >= 2097151) {
            log.warn("Cardinality is extremely high.  Candidate generation will be slow.");
            useIntSetAsArray = true;
        } else{
            useIntSetAsArray = false;
        }

        // Shard the dataset by rows for the threads, but store it by column for fast processing
        final int[][][] byThreadAttributesTranspose =
                new int[numThreads][numColumns][(numRows + numThreads)/numThreads];
        final HashMap<Integer, RoaringBitmap>[][][] byThreadBitmap = new HashMap[numThreads][numColumns][2];
        for (int i = 0; i < numThreads; i++)
            for (int j = 0; j < numColumns; j++)
                for (int k = 0; k < 2; k++)
                    byThreadBitmap[i][j][k] = new HashMap<>();
        for (int threadNum = 0; threadNum < numThreads; threadNum++) {
            final int startIndex = (numRows * threadNum) / numThreads;
            final int endIndex = (numRows * (threadNum + 1)) / numThreads;
            for(int i = 0; i < numColumns; i++) {
                for (int j = startIndex; j < endIndex; j++) {
                    byThreadAttributesTranspose[threadNum][i][j - startIndex] = attributes[j][i];
                }
                for (int j = 0; j < 2; j++) {
                    for (HashMap.Entry<Integer, RoaringBitmap> entry : bitmap[i][j].entrySet()) {
                        RoaringBitmap rr = new RoaringBitmap();
                        rr.add((long) startIndex, (long) endIndex);
                        rr.and(entry.getValue());
                        if (rr.getCardinality() > 0) {
                            byThreadBitmap[threadNum][i][j].put(entry.getKey(), rr);
                        }
                    }
                }
            }
        }

        // Quality metrics are initialized with global aggregates to
        // allow them to determine the appropriate relative thresholds
        double[] globalAggregates = new double[numAggregates];
        for (int j = 0; j < numAggregates; j++) {
            globalAggregates[j] = 0;
            double[] curColumn = aggregateColumns[j];
            for (int i = 0; i < numRows; i++) {
                globalAggregates[j] += curColumn[i];
            }
        }
        for (QualityMetric q : qualityMetrics) {
            q.initialize(globalAggregates);
        }

        // Row store for more convenient access
        final double[][] aRows = new double[numRows][numAggregates];
        for (int i = 0; i < numRows; i++) {
            for (int j = 0; j < numAggregates; j++) {
                aRows[i][j] = aggregateColumns[j][i];
            }
        }
        for (int curOrder = 1; curOrder <= 3; curOrder++) {
            long startTime = System.currentTimeMillis();
            final int curOrderFinal = curOrder;
            // Initialize per-thread hashmaps.
            final ArrayList<FastFixedHashTable> threadSetAggregates = new ArrayList<>(numThreads);
            for (int i = 0; i < numThreads; i++) {
                threadSetAggregates.add(new FastFixedHashTable(cardinality, numAggregates, useIntSetAsArray));
            }
            // Shard the dataset by row into threads and generate candidates.
            final CountDownLatch doneSignal = new CountDownLatch(numThreads);
            for (int threadNum = 0; threadNum < numThreads; threadNum++) {
                final int curThreadNum = threadNum;
                final int startIndex = (numRows * threadNum) / numThreads;
                final int endIndex = (numRows * (threadNum + 1)) / numThreads;
                final FastFixedHashTable thisThreadSetAggregates = threadSetAggregates.get(threadNum);
                // Do candidate generation in a lambda.
                Runnable APrioriLinearRunnable = () -> {
                    IntSet curCandidate;
                    if (!useIntSetAsArray)
                        curCandidate = new IntSetAsLong(0);
                    else
                        curCandidate = new IntSetAsArray(0);
                    if (curOrderFinal == 1) {
                        /*for (int colNum = 0; colNum < numColumns; colNum++) {
                            int[] curColumnAttributes = byThreadAttributesTranspose[curThreadNum][colNum];
                            for (int rowNum = startIndex; rowNum < endIndex; rowNum++) {
                                // Require that all order-one candidates have minimum support.
                                if (curColumnAttributes[rowNum - startIndex] == AttributeEncoder.noSupport)
                                    continue;
                                // Cascade to arrays if necessary, but otherwise pack attributes into longs.
                                if (useIntSetAsArray) {
                                    curCandidate = new IntSetAsArray(curColumnAttributes[rowNum - startIndex]);
                                } else {
                                    ((IntSetAsLong) curCandidate).value = curColumnAttributes[rowNum - startIndex];
                                }
                                double[] candidateVal = thisThreadSetAggregates.get(curCandidate);
                                if (candidateVal == null) {
                                    thisThreadSetAggregates.put(curCandidate,
                                            Arrays.copyOf(aRows[rowNum], numAggregates));
                                } else {
                                    for (int a = 0; a < numAggregates; a++) {
                                        candidateVal[a] += aRows[rowNum][a];
                                    }
                                }
                            }
                        }*/
                        for (int colNum = 0; colNum < numColumns; colNum++) {
                            for (Integer curOutlierCandidate : outlierList[colNum]) {
                                // Require that all order-one candidates have minimum support.
                                if (curOutlierCandidate == AttributeEncoder.noSupport)
                                    continue;

                                int outlierCount = 0, inlierCount = 0;
                                if (byThreadBitmap[curThreadNum][colNum][1].containsKey(curOutlierCandidate))
                                    outlierCount = byThreadBitmap[curThreadNum][colNum][1].get(curOutlierCandidate).getCardinality();
                                if (byThreadBitmap[curThreadNum][colNum][0].containsKey(curOutlierCandidate))
                                    inlierCount = byThreadBitmap[curThreadNum][colNum][0].get(curOutlierCandidate).getCardinality();

                                // Cascade to arrays if necessary, but otherwise pack attributes into longs.
                                if (useIntSetAsArray) {
                                    curCandidate = new IntSetAsArray(curOutlierCandidate);
                                } else {
                                    ((IntSetAsLong) curCandidate).value = curOutlierCandidate;
                                }

                                double[] candidateVal = thisThreadSetAggregates.get(curCandidate);
                                if (candidateVal == null) {
                                    thisThreadSetAggregates.put(curCandidate,
                                            new double[]{outlierCount, outlierCount + inlierCount});
                                } else {
                                    candidateVal[0] += outlierCount;
                                    candidateVal[1] += outlierCount + inlierCount;
                                }
                            }
                        }
                    } else if (curOrderFinal == 2) {
                        /*for (int colNumOne = 0; colNumOne < numColumns; colNumOne++) {
                            int[] curColumnOneAttributes = byThreadAttributesTranspose[curThreadNum][colNumOne];
                            for (int colNumTwo = colNumOne + 1; colNumTwo < numColumns; colNumTwo++) {
                                int[] curColumnTwoAttributes = byThreadAttributesTranspose[curThreadNum][colNumTwo];
                                for (int rowNum = startIndex; rowNum < endIndex; rowNum++) {
                                    int rowNumInCol = rowNum - startIndex;
                                    // Only examine a pair if both its members have minimum support.
                                    if (curColumnOneAttributes[rowNumInCol] == AttributeEncoder.noSupport
                                            || curColumnTwoAttributes[rowNumInCol] == AttributeEncoder.noSupport
                                            || !singleNextArray[curColumnOneAttributes[rowNumInCol]]
                                            || !singleNextArray[curColumnTwoAttributes[rowNumInCol]])
                                        continue;
                                    // Cascade to arrays if necessary, but otherwise pack attributes into longs.
                                    if (useIntSetAsArray) {
                                        curCandidate = new IntSetAsArray(curColumnOneAttributes[rowNumInCol],
                                                curColumnTwoAttributes[rowNumInCol]);
                                    } else {
                                        ((IntSetAsLong) curCandidate).value = IntSetAsLong.twoIntToLong(curColumnOneAttributes[rowNumInCol],
                                                curColumnTwoAttributes[rowNumInCol]);
                                    }
                                    double[] candidateVal = thisThreadSetAggregates.get(curCandidate);
                                    if (candidateVal == null) {
                                        thisThreadSetAggregates.put(curCandidate,
                                                Arrays.copyOf(aRows[rowNum], numAggregates));
                                    } else {
                                        for (int a = 0; a < numAggregates; a++) {
                                            candidateVal[a] += aRows[rowNum][a];
                                        }
                                    }
                                }
                            }
                        }*/
                        for (int colNumOne = 0; colNumOne < numColumns; colNumOne++) {
                            for (Integer curCandidateOne : outlierList[colNumOne]) {
                                if (curCandidateOne == AttributeEncoder.noSupport || !singleNextArray[curCandidateOne])
                                    continue;

                                for (int colNumTwo = colNumOne + 1; colNumTwo < numColumns; colNumTwo++) {
                                    for (Integer curCandidateTwo : outlierList[colNumTwo]) {
                                        if (curCandidateTwo == AttributeEncoder.noSupport || !singleNextArray[curCandidateTwo])
                                            continue;

                                        int outlierCount = 0, inlierCount = 0;
                                        if (byThreadBitmap[curThreadNum][colNumOne][1].containsKey(curCandidateOne) &&
                                                byThreadBitmap[curThreadNum][colNumTwo][1].containsKey(curCandidateTwo))
                                            outlierCount = RoaringBitmap.andCardinality(byThreadBitmap[curThreadNum][colNumOne][1].get(curCandidateOne),
                                                    byThreadBitmap[curThreadNum][colNumTwo][1].get(curCandidateTwo));
                                        if (byThreadBitmap[curThreadNum][colNumOne][0].containsKey(curCandidateOne) &&
                                                byThreadBitmap[curThreadNum][colNumTwo][0].containsKey(curCandidateTwo))
                                            inlierCount = RoaringBitmap.andCardinality(byThreadBitmap[curThreadNum][colNumOne][0].get(curCandidateOne),
                                                    byThreadBitmap[curThreadNum][colNumTwo][0].get(curCandidateTwo));

                                        // Cascade to arrays if necessary, but otherwise pack attributes into longs.
                                        if (useIntSetAsArray) {
                                            curCandidate = new IntSetAsArray(curCandidateOne, curCandidateTwo);
                                        } else {
                                            ((IntSetAsLong) curCandidate).value = IntSetAsLong.twoIntToLong(curCandidateOne, curCandidateTwo);
                                        }

                                        double[] candidateVal = thisThreadSetAggregates.get(curCandidate);
                                        if (candidateVal == null) {
                                            thisThreadSetAggregates.put(curCandidate,
                                                    new double[]{outlierCount, outlierCount + inlierCount});
                                        } else {
                                            candidateVal[0] += outlierCount;
                                            candidateVal[1] += outlierCount + inlierCount;
                                        }
                                    }
                                }
                            }
                        }
                    } else if (curOrderFinal == 3) {
                        /*for (int colNumOne = 0; colNumOne < numColumns; colNumOne++) {
                            int[] curColumnOneAttributes = byThreadAttributesTranspose[curThreadNum][colNumOne % numColumns];
                            for (int colNumTwo = colNumOne + 1; colNumTwo < numColumns; colNumTwo++) {
                                int[] curColumnTwoAttributes = byThreadAttributesTranspose[curThreadNum][colNumTwo % numColumns];
                                for (int colnumThree = colNumTwo + 1; colnumThree < numColumns; colnumThree++) {
                                    int[] curColumnThreeAttributes = byThreadAttributesTranspose[curThreadNum][colnumThree % numColumns];
                                    for (int rowNum = startIndex; rowNum < endIndex; rowNum++) {
                                        int rowNumInCol = rowNum - startIndex;
                                        // Only construct a triple if all its singleton members have minimum support.
                                        if (curColumnOneAttributes[rowNumInCol] == AttributeEncoder.noSupport
                                                || curColumnTwoAttributes[rowNumInCol] == AttributeEncoder.noSupport
                                                || curColumnThreeAttributes[rowNumInCol] == AttributeEncoder.noSupport
                                                || !singleNextArray[curColumnThreeAttributes[rowNumInCol]]
                                                || !singleNextArray[curColumnOneAttributes[rowNumInCol]]
                                                || !singleNextArray[curColumnTwoAttributes[rowNumInCol]])
                                            continue;
                                        // Cascade to arrays if necessary, but otherwise pack attributes into longs.
                                        if (useIntSetAsArray) {
                                            curCandidate = new IntSetAsArray(
                                                    curColumnOneAttributes[rowNumInCol],
                                                    curColumnTwoAttributes[rowNumInCol],
                                                    curColumnThreeAttributes[rowNumInCol]);
                                        } else {
                                            ((IntSetAsLong) curCandidate).value = IntSetAsLong.threeIntToLong(
                                                    curColumnOneAttributes[rowNumInCol],
                                                    curColumnTwoAttributes[rowNumInCol],
                                                    curColumnThreeAttributes[rowNumInCol]);
                                        }
                                        double[] candidateVal = thisThreadSetAggregates.get(curCandidate);
                                        if (candidateVal == null) {
                                            thisThreadSetAggregates.put(curCandidate,
                                                    Arrays.copyOf(aRows[rowNum], numAggregates));
                                        } else {
                                            for (int a = 0; a < numAggregates; a++) {
                                                candidateVal[a] += aRows[rowNum][a];
                                            }
                                        }
                                    }
                                }
                            }
                        }*/
                        for (int colNumOne = 0; colNumOne < numColumns; colNumOne++) {
                            for (Integer curCandidateOne : outlierList[colNumOne]) {
                                if (curCandidateOne == AttributeEncoder.noSupport || !singleNextArray[curCandidateOne])
                                    continue;

                                for (int colNumTwo = colNumOne + 1; colNumTwo < numColumns; colNumTwo++) {
                                    for (Integer curCandidateTwo : outlierList[colNumTwo]) {
                                        if (curCandidateTwo == AttributeEncoder.noSupport || !singleNextArray[curCandidateTwo])
                                            continue;

                                        for (int colNumThree = colNumTwo + 1; colNumThree < numColumns; colNumThree++) {
                                            for (Integer curCandidateThree : outlierList[colNumThree]) {
                                                if (curCandidateThree == AttributeEncoder.noSupport || !singleNextArray[curCandidateThree])
                                                    continue;

                                                int outlierCount = 0, inlierCount = 0;
                                                if (byThreadBitmap[curThreadNum][colNumOne][1].containsKey(curCandidateOne) &&
                                                        byThreadBitmap[curThreadNum][colNumTwo][1].containsKey(curCandidateTwo) &&
                                                        byThreadBitmap[curThreadNum][colNumThree][1].containsKey(curCandidateThree))
                                                    outlierCount = RoaringBitmap.andCardinality(
                                                            RoaringBitmap.and(byThreadBitmap[curThreadNum][colNumOne][1].get(curCandidateOne),
                                                                    byThreadBitmap[curThreadNum][colNumTwo][1].get(curCandidateTwo)),
                                                            byThreadBitmap[curThreadNum][colNumThree][1].get(curCandidateThree));
                                                if (byThreadBitmap[curThreadNum][colNumOne][0].containsKey(curCandidateOne) &&
                                                        byThreadBitmap[curThreadNum][colNumTwo][0].containsKey(curCandidateTwo) &&
                                                        byThreadBitmap[curThreadNum][colNumThree][0].containsKey(curCandidateThree))
                                                    inlierCount = RoaringBitmap.andCardinality(
                                                            RoaringBitmap.and(byThreadBitmap[curThreadNum][colNumOne][0].get(curCandidateOne),
                                                                    byThreadBitmap[curThreadNum][colNumTwo][0].get(curCandidateTwo)),
                                                            byThreadBitmap[curThreadNum][colNumThree][0].get(curCandidateThree));

                                                // Cascade to arrays if necessary, but otherwise pack attributes into longs.
                                                if (useIntSetAsArray) {
                                                    curCandidate = new IntSetAsArray(
                                                            curCandidateOne,
                                                            curCandidateTwo,
                                                            curCandidateThree);
                                                } else {
                                                    ((IntSetAsLong) curCandidate).value = IntSetAsLong.threeIntToLong(
                                                            curCandidateOne,
                                                            curCandidateTwo,
                                                            curCandidateThree);
                                                }

                                                double[] candidateVal = thisThreadSetAggregates.get(curCandidate);
                                                if (candidateVal == null) {
                                                    thisThreadSetAggregates.put(curCandidate,
                                                            new double[]{outlierCount, outlierCount + inlierCount});
                                                } else {
                                                    candidateVal[0] += outlierCount;
                                                    candidateVal[1] += outlierCount + inlierCount;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        throw new MacrobaseInternalError("High Order not supported");
                    }
                    log.debug("Time spent in Thread {} in order {}:  {} ms",
                            curThreadNum, curOrderFinal, System.currentTimeMillis() - startTime);
                    doneSignal.countDown();
                };
                // Run numThreads lambdas in separate threads
                Thread APrioriLinearThread = new Thread(APrioriLinearRunnable);
                APrioriLinearThread.start();
            }
            // Wait for all threads to finish running.
            try {
                doneSignal.await();
            } catch (InterruptedException ex) {ex.printStackTrace();}


            Map<IntSet, double []> setAggregates = new HashMap<>();
            // Collect the aggregates stored in the per-thread HashMaps.
            for (FastFixedHashTable set : threadSetAggregates) {
                if (useIntSetAsArray) {
                    for (IntSet curCandidateKey : set.keySet()) {
                        double[] curCandidateValue = set.get(curCandidateKey);
                        double[] candidateVal = setAggregates.get(curCandidateKey);
                        if (candidateVal == null) {
                            setAggregates.put(curCandidateKey, Arrays.copyOf(curCandidateValue, numAggregates));
                        } else {
                            for (int a = 0; a < numAggregates; a++) {
                                candidateVal[a] += curCandidateValue[a];
                            }
                        }
                    }
                } else {
                    for (long curCandidateKeyLong : set.keySetLong()) {
                        IntSetAsLong curCandidateKeyIntSetAsLong = new IntSetAsLong(curCandidateKeyLong);
                        IntSet curCandidateKey = new IntSetAsArray(curCandidateKeyIntSetAsLong);
                        double[] curCandidateValue = set.get(curCandidateKeyIntSetAsLong);
                        double[] candidateVal = setAggregates.get(curCandidateKey);
                        if (candidateVal == null) {
                            setAggregates.put(curCandidateKey, Arrays.copyOf(curCandidateValue, numAggregates));
                        } else {
                            for (int a = 0; a < numAggregates; a++) {
                                candidateVal[a] += curCandidateValue[a];
                            }
                        }
                    }
                }
            }

            // Prune all the collected aggregates
            HashSet<IntSet> curOrderNext = new HashSet<>();
            HashSet<IntSet> curOrderSaved = new HashSet<>();
            for (IntSet curCandidate: setAggregates.keySet()) {
                if (curOrder == 1 && curCandidate.getFirst() == AttributeEncoder.noSupport) {
                    continue;
                }
                double[] curAggregates = setAggregates.get(curCandidate);
                boolean canPassThreshold = true;
                boolean isPastThreshold = true;
                for (int i = 0; i < qualityMetrics.length; i++) {
                    QualityMetric q = qualityMetrics[i];
                    double t = thresholds[i];
                    canPassThreshold &= q.maxSubgroupValue(curAggregates) >= t;
                    isPastThreshold &= q.value(curAggregates) >= t;
                }
                if (canPassThreshold) {
                    // if a set is already past the threshold on all metrics,
                    // save it and no need for further exploration
                    if (isPastThreshold && !(curOrder == 3 && !validateCandidate(curCandidate, setNext.get(2)))) {
                        curOrderSaved.add(curCandidate);
                    }
                    else {
                        // otherwise if a set still has potentially good subsets,
                        // save it for further examination
                        curOrderNext.add(curCandidate);
                    }
                }
            }

            // Save aggregates that pass all qualityMetrics to return later, store aggregates
            // that have minimum support for higher-order exploration.
            Map<IntSet, double []> curSavedAggregates = new HashMap<>(curOrderSaved.size());
            for (IntSet curSaved : curOrderSaved) {
                curSavedAggregates.put(curSaved, setAggregates.get(curSaved));
            }
            savedAggregates.put(curOrder, curSavedAggregates);
            setNext.put(curOrder, curOrderNext);
            if (curOrder == 1) {
                singleNextArray = new boolean[cardinality];
                for (IntSet i : curOrderNext) {
                    singleNextArray[i.getFirst()] = true;
                }
            }
        }

        List<APLExplanationResult> results = new ArrayList<>();
        for (int curOrder: savedAggregates.keySet()) {
            Map<IntSet, double []> curOrderSavedAggregates = savedAggregates.get(curOrder);
            for (IntSet curSet : curOrderSavedAggregates.keySet()) {
                double[] aggregates = curOrderSavedAggregates.get(curSet);
                double[] metrics = new double[qualityMetrics.length];
                for (int i = 0; i < metrics.length; i++) {
                    metrics[i] = qualityMetrics[i].value(aggregates);
                }
                results.add(
                        new APLExplanationResult(qualityMetrics, curSet, aggregates, metrics)
                );
            }
        }
        return results;
    }

    /**
     * Check if all subsets of an order-3 candidate are order-2 candidates.
     * @param o2Candidates All candidates of order 2 with minimum support.
     * @param curCandidate An order-3 candidate
     * @return Boolean
     */
    private boolean validateCandidate(IntSet curCandidate,
                                      HashSet<IntSet> o2Candidates) {
            IntSet subPair;
            subPair = new IntSetAsArray(
                    curCandidate.getFirst(),
                    curCandidate.getSecond());
            if (o2Candidates.contains(subPair)) {
                subPair = new IntSetAsArray(
                        curCandidate.getSecond(),
                        curCandidate.getThird());
                if (o2Candidates.contains(subPair)) {
                    subPair = new IntSetAsArray(
                            curCandidate.getFirst(),
                            curCandidate.getThird());
                    if (o2Candidates.contains(subPair)) {
                        return true;
                    }
                }
            }
        return false;
    }
}
