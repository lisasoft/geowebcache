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
 */
package org.geowebcache.seed;

import java.util.Iterator;
import java.util.Map.Entry;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.geowebcache.GeoWebCacheException;
import org.geowebcache.job.JobScheduler;
import org.geowebcache.storage.JobLogObject;
import org.geowebcache.storage.JobObject;
import org.geowebcache.storage.JobStore;
import org.geowebcache.storage.StorageException;

public class ScheduledJobInitiator implements Runnable {
    private static Log log = LogFactory.getLog(org.geowebcache.seed.ScheduledJobInitiator.class);

    protected JobObject job = null;
    protected TileBreeder seeder = null;
    protected JobStore jobStore = null;

    public ScheduledJobInitiator(JobObject job, TileBreeder seeder, JobStore jobStore) {
        this.job = job;
        this.seeder = seeder;
        this.jobStore = jobStore;
    }
    
    public void run() {
        JobObject job_to_run = null;
        if(job.isRunOnce()) {
            log.info("Starting scheduled run-once job: " + job.getJobId());
            // remove run-once job from schedule.
            JobScheduler.deschedule(this.job.getJobId());
            job.addLog(JobLogObject.createInfoLog(job.getJobId(), "Once Off Job Scheduled", "This once-off job is now scheduled to start."));
            job_to_run = job;
        } else {
            if(spawnedJobAlreadyRunning()) {
                log.warn("The repeating job " + job.getJobId() + " is now scheduled to start but a job spawned by this schedule is still running.");
                job.addLog(JobLogObject.createWarnLog(job.getJobId(), "Couldn't Spawn Job", "This repeating job is now scheduled to start, but a job spawned by this schedule is still running. Will not spawn a new job at this time."));
                try {
                    jobStore.put(job);
                } catch (StorageException e) {
                    log.error("Got an exception while trying to log that a repeating job spawned a new job.", e);
                }
            } else {
                log.info("The repeating job " + job.getJobId() + " is scheduled to begin now.");
                job.addLog(JobLogObject.createInfoLog(job.getJobId(), "Spawned New Job", "This repeating job is now scheduled to start. Will spawn a new job to run."));
                try {
                    jobStore.put(job);
                } catch (StorageException e) {
                    log.error("Got an exception while trying to log that a repeating job spawned a new job.", e);
                }
    
                // Clone the existing job and run it. The original job already scheduled stays in the system.
                // To do this we just need to clear the ID. Jobs are persisted on execution and a job with 
                // no ID will be treated as a new job according to the store.
                job_to_run = JobObject.createJobObject(job);
                job_to_run.setJobId(-1);
                job_to_run.setSpawnedBy(job.getJobId());
                job_to_run.setSchedule(null);
            }
        }
        
        try {
            if(job_to_run != null) {
                seeder.executeJob(job_to_run);
            }
        } catch(GeoWebCacheException gwce) {
            log.error("Couldn't start scheduled job: " + gwce.getMessage(), gwce);
        }
    }

    private boolean spawnedJobAlreadyRunning() {
        Iterator<Entry<Long, GWCTask>> iter = seeder.getRunningTasksIterator();
        
        while(iter.hasNext()) {
            GWCTask task = iter.next().getValue();
            
            if(task.spawnedBy == this.job.getJobId()) {
                return true;
            }
        }
        
        return false;
    }
}
