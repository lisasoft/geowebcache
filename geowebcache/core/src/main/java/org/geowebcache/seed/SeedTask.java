/**
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * @author Marius Suta / The Open Planning Project 2008
 * @author Arne Kepp / The Open Planning Project 2009 
 */
package org.geowebcache.seed;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.geowebcache.GeoWebCacheException;
import org.geowebcache.conveyor.ConveyorTile;
import org.geowebcache.filter.request.RequestFilter;
import org.geowebcache.layer.TileLayer;
import org.geowebcache.layer.wms.WMSLayer;
import org.geowebcache.storage.JobLogObject;
import org.geowebcache.storage.StorageBroker;
import org.geowebcache.storage.TileRange;
import org.geowebcache.storage.TileRangeIterator;

public class SeedTask extends GWCTask {
    private static Log log = LogFactory.getLog(org.geowebcache.seed.SeedTask.class);

    private final TileRangeIterator trIter;

    private final TileLayer tl;

    private boolean reseed;

    private boolean doFilterUpdate;

    private StorageBroker storageBroker;

    private int tileFailureRetryCount;

    private long tileFailureRetryWaitTime;

    private long totalFailuresBeforeAborting;

    private AtomicLong sharedFailureCounter;

    private int minTimeBetweenRequests;
    
    private ThroughputTracker throughputTracker = null;
    
    private long seedStartTime;
    
    /**
     * Constructs a SeedTask
     * @param sb Used to store the tiles and meta information
     * @param trIter Iterable range of tiles to seed
     * @param tl Layer to seed
     * @param reseed If true, existing cached tiles will be overwritten with the latest
     * @param doFilterUpdate
     * @param priority thread priority for this task
     * @param maxThroughput Maximum number of requests per second
     * @param jobId
     */
    public SeedTask(StorageBroker sb, TileRangeIterator trIter, TileLayer tl, boolean reseed,
            boolean doFilterUpdate, PRIORITY priority, float maxThroughput, long jobId, long spawnedBy) {
        this.storageBroker = sb;
        this.trIter = trIter;
        this.tl = tl;
        this.reseed = reseed;
        this.priority = priority;
        this.doFilterUpdate = doFilterUpdate;
        this.jobId = jobId;
        this.spawnedBy = spawnedBy;

        tileFailureRetryCount = 0;
        tileFailureRetryWaitTime = 100;
        totalFailuresBeforeAborting = 10000;
        sharedFailureCounter = new AtomicLong();

        if(maxThroughput > 0) {
            minTimeBetweenRequests = (int)(1000 / maxThroughput);
        } else {
            minTimeBetweenRequests = 0;
        }
        
        throughputTracker = new ThroughputTracker(5); // minimal throughput tracking by default
        seedStartTime = -1;

        if (reseed) {
            super.taskType = GWCTask.TYPE.RESEED;
        } else {
            super.taskType = GWCTask.TYPE.SEED;
        }
        super.layerName = tl.getName();

        super.state = GWCTask.STATE.READY;
    }

    @Override
    protected void doActionInternal() throws GeoWebCacheException, InterruptedException {
        super.state = GWCTask.STATE.RUNNING;

        Thread.currentThread().setPriority(priority.getThreadPriority());

        checkInterrupted();

        // approximate thread creation time
        final long START_TIME = System.currentTimeMillis();

        final String layerName = tl.getName();
        log.info(Thread.currentThread().getName() + " begins seeding layer : " + layerName);

        TileRange tr = trIter.getTileRange();

        checkInterrupted();
        super.tilesTotal = new SeedEstimator().tileCount(tr);

        final int metaTilingFactorX = tl.getMetaTilingFactors()[0];
        final int metaTilingFactorY = tl.getMetaTilingFactors()[1];

        final boolean tryCache = !reseed;

        checkInterrupted();
        long[] gridLoc = trIter.nextMetaGridLocation(new long[3]);

        long seedCalls = 0;
        while (gridLoc != null && this.terminate == false) {
            seedStartTime = System.currentTimeMillis();

            checkInterrupted();
            Map<String, String> fullParameters = tr.getParameters();

            ConveyorTile tile = new ConveyorTile(storageBroker, layerName, tr.getGridSetId(), gridLoc,
                    tr.getMimeType(), fullParameters, null, null);

            for (int fetchAttempt = 0; fetchAttempt <= tileFailureRetryCount; fetchAttempt++) {
                try {
                    checkInterrupted();
                    tl.seedTile(tile, tryCache);
                    break;// success, let it go
                } catch (Exception e) {
                    // if GWC_SEED_RETRY_COUNT was not set then none of the settings have effect, in
                    // order to keep backwards compatibility with the old behaviour
                    if (tileFailureRetryCount == 0) {
                        if (e instanceof GeoWebCacheException) {
                            throw (GeoWebCacheException) e;
                        }
                        throw new GeoWebCacheException(e);
                    }

                    long sharedFailureCount = sharedFailureCounter.incrementAndGet();
                    if (sharedFailureCount >= totalFailuresBeforeAborting) {
                        log.info("Aborting seed thread " + Thread.currentThread().getName()
                                + ". Error count reached configured maximum of "
                                + totalFailuresBeforeAborting);
                        super.state = GWCTask.STATE.DEAD;
                        addLog(JobLogObject.createErrorLog(jobId, "Thread Aborted Seeding", "A thread of the job has aborted seeding due to too many failures."));
                        return;
                    }
                    String logMsg = "Seed failed at " + tile.toString() + " after "
                            + (fetchAttempt + 1) + " of " + (tileFailureRetryCount + 1)
                            + " attempts.";
                    if (fetchAttempt < tileFailureRetryCount) {
                        log.debug(logMsg);
                        if (tileFailureRetryWaitTime > 0) {
                            log.trace("Waiting " + tileFailureRetryWaitTime
                                    + " before trying again");
                            Thread.sleep(tileFailureRetryCount);
                        }
                    } else {
                        logMsg += " Skipping and continuing with next tile. Original error: " + e.getMessage();
                        log.info(logMsg);
                        addLog(JobLogObject.createWarnLog(jobId, "Seed Failed", logMsg));
                    }
                }
            }

            if (log.isTraceEnabled()) {
                log.trace(Thread.currentThread().getName() + " seeded " + Arrays.toString(gridLoc));
            }

            // final long totalTilesCompleted = trIter.getTilesProcessed();
            // note: computing the # of tiles processed by this thread instead of by the whole group
            // also reduces thread contention as the trIter methods are synchronized and profiler
            // shows 16 threads block on synchronization about 40% the time
            final long tilesCompletedByThisThread = seedCalls * metaTilingFactorX
                    * metaTilingFactorY;

            updateStatusInfo(tl, tilesCompletedByThisThread, START_TIME);

            checkInterrupted();
            
            seedCalls++;
            gridLoc = trIter.nextMetaGridLocation(gridLoc);

            checkThrottling();
        }

        if (this.terminate) {
            String logMsg = "Thread " + Thread.currentThread().getName() + " was terminated after "
                    + this.tilesDone + " tiles";
            log.info(logMsg);
            addLog(JobLogObject.createInfoLog(jobId, "Seeding Terminated", logMsg));
            super.state = GWCTask.STATE.KILLED;
        } else {
            String logMsg = Thread.currentThread().getName() + " completed (re)seeding layer "
                    + layerName + " after " + this.tilesDone + " tiles and " + this.timeSpent
                    + " seconds.";
            log.info(logMsg);
            addLog(JobLogObject.createInfoLog(jobId, "Seeding Completed", logMsg));
            super.state = GWCTask.STATE.DONE;
        }

        checkInterrupted();
        if (threadOffset == 0 && doFilterUpdate) {
            runFilterUpdates(tr.getGridSetId());
        }
    }

    protected void doAbnormalExit(Throwable t) {
        String logMsg = "Thread " + Thread.currentThread().getName() + " was terminated after "
                + this.tilesDone + " tiles due to the following exception:\n";
        logMsg += t.getClass().getName() + ": " + t.getMessage();
        addLog(JobLogObject.createErrorLog(jobId, "Seeding Terminated Abnormally", logMsg));
        super.state = GWCTask.STATE.DEAD;
    }

    private void checkThrottling() throws InterruptedException {
        if(seedStartTime != -1) {
            int sample = (int)(System.currentTimeMillis() - seedStartTime);
            // There is a 0.02 of a second buffer here as a rough "factor out the overhead" and "if
            // it's close don't sleep the thread for almost no time" reasons
            if(minTimeBetweenRequests > 0 && minTimeBetweenRequests > (sample + 20)) {
                try {
                    Thread.sleep(minTimeBetweenRequests - sample);
                } catch (InterruptedException e) {
                    checkInterrupted();
                }
                sample = (int)(System.currentTimeMillis() - seedStartTime);
            }
            throughputTracker.addSample(sample);
        }
    }

    /**
     * Helper method to report status of thread progress.
     * 
     * @param layer
     * @param zoomStart
     * @param zoomStop
     * @param level
     * @param gridBounds
     * @return
     */
    private void updateStatusInfo(TileLayer layer, long tilesCount, long start_time) {

        // working on tile
        tilesDone = tilesCount;

        // estimated time of completion in seconds, use a moving average over the last
        timeSpent = (int) (System.currentTimeMillis() - start_time) / 1000;

        int threadCount = sharedThreadCount.get();
        long timeTotal = new SeedEstimator().totalTimeEstimate(timeSpent, tilesDone, tilesTotal, threadCount);

        timeRemaining = (int) (timeTotal - timeSpent);
    }

    /**
     * Updates any request filters
     */
    private void runFilterUpdates(String gridSetId) {
        // We will assume that all filters that can be updated should be updated
        List<RequestFilter> reqFilters = tl.getRequestFilters();
        if (reqFilters != null && !reqFilters.isEmpty()) {
            Iterator<RequestFilter> iter = reqFilters.iterator();
            while (iter.hasNext()) {
                RequestFilter reqFilter = iter.next();
                if (reqFilter.update(tl, gridSetId)) {
                    log.info("Updated request filter " + reqFilter.getName());
                } else {
                    log.debug("Request filter " + reqFilter.getName()
                            + " returned false on update.");
                }
            }
        }
    }

    public void setFailurePolicy(int tileFailureRetryCount, long tileFailureRetryWaitTime,
            long totalFailuresBeforeAborting, AtomicLong sharedFailureCounter) {
        this.tileFailureRetryCount = tileFailureRetryCount;
        this.tileFailureRetryWaitTime = tileFailureRetryWaitTime;
        this.totalFailuresBeforeAborting = totalFailuresBeforeAborting;
        this.sharedFailureCounter = sharedFailureCounter;
    }
    
    public void setThrottlingPolicy(int sampleSize) {
        throughputTracker = new ThroughputTracker(sampleSize);
    }

    @Override
    protected void dispose() {
        if (tl instanceof WMSLayer) {
            ((WMSLayer) tl).cleanUpThreadLocals();
        }
    }
    
    public long getSharedFailureCounter() {
        return(sharedFailureCounter.get());
    }

    /**
     * Number of requests this seed task is handling per second.
     * @return current throughput in actions (in this case requests) per second
     */
    public float getThroughput() {
        return throughputTracker.getThroughput();
    }
}
