/*
 * Copyright 2012 International Business Machines Corp.
 * 
 * See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership. Licensed under the Apache License, 
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/
package com.ibm.jbatch.container.status;
import java.io.Serializable;

import javax.batch.runtime.BatchStatus;
import javax.batch.runtime.JobInstance;

import com.ibm.jbatch.container.jobinstance.JobInstanceImpl;
import com.ibm.jbatch.container.services.IJobStatus;

public final class JobStatus implements Serializable, Cloneable, IJobStatus {

    private static final long serialVersionUID = 1L;

    // Changing this shouldn't affect the serialization/deserialization since we were always
    // writing this same JobInstanceImpl previously.
    private JobInstanceImpl jobInstance;

    private long jobInstanceId;

    private String currentStepId;

    private BatchStatus batchStatus;  // Might be nice to know.

    private String exitStatus;

    // Assume this will be needed.
    private long latestExecutionId;

    // How many times the status has been updated.

    //TODO - reset to 0?
    //private int updateCount;

    // TODO - Maybe a job operator would use this?
    //private int restartCount;

    private String restartOn;

    public JobStatus(long jobInstanceId) {
        this.jobInstanceId = jobInstanceId;
        this.batchStatus = BatchStatus.STARTING;
    }
    
    /**
	 * @param jobStatus
	 */
	public JobStatus(IJobStatus jobStatus) {
		// When the object was instantiated from our own persistence service, we know we can cast this
		// first field to JobInstanceImpl.
		this.jobInstance = (JobInstanceImpl)jobStatus.getJobInstance();
		this.jobInstanceId = jobStatus.getJobInstanceId();
		this.currentStepId = jobStatus.getCurrentStepId();
		this.batchStatus = jobStatus.getBatchStatus();
		this.exitStatus = jobStatus.getExitStatus();
		this.latestExecutionId = jobStatus.getLatestExecutionId();
		this.restartOn = jobStatus.getRestartOn();
	}

	/* (non-Javadoc)
	 * @see com.ibm.jbatch.container.status.IJobStatus#getJobInstanceId()
	 */
    @Override
	public long getJobInstanceId() {
        return this.jobInstanceId;
    }

    /* (non-Javadoc)
	 * @see com.ibm.jbatch.container.status.IJobStatus#setJobInstance(javax.batch.runtime.JobInstance)
	 */
    @Override
	public void setJobInstance(JobInstance jobInstance) {
		// When the object was instantiated from our own persistence service, we know we can cast this
		// first field to JobInstanceImpl.
        this.jobInstance = (JobInstanceImpl)jobInstance;
    }
    
    /* (non-Javadoc)
	 * @see com.ibm.jbatch.container.status.IJobStatus#getJobInstance()
	 */
    @Override
	public JobInstanceImpl getJobInstance() {
        return (JobInstanceImpl)jobInstance;
    }

    /* (non-Javadoc)
	 * @see com.ibm.jbatch.container.status.IJobStatus#getCurrentStepId()
	 */
    @Override
	public String getCurrentStepId() {
        return currentStepId;
    }

    /* (non-Javadoc)
	 * @see com.ibm.jbatch.container.status.IJobStatus#setCurrentStepId(java.lang.String)
	 */
    @Override
	public void setCurrentStepId(String currentStepId) {
        this.currentStepId = currentStepId;
    }

    /* (non-Javadoc)
	 * @see com.ibm.jbatch.container.status.IJobStatus#getBatchStatus()
	 */
    @Override
	public BatchStatus getBatchStatus() {
        return batchStatus;
    }

    /* (non-Javadoc)
	 * @see com.ibm.jbatch.container.status.IJobStatus#setBatchStatus(javax.batch.runtime.BatchStatus)
	 */
    @Override
	public void setBatchStatus(BatchStatus batchStatus) {
        this.batchStatus = batchStatus;
    }

    public long getLatestExecutionId() {
        return latestExecutionId;
    }

    public void setLatestExecutionId(long latestExecutionId) {
        this.latestExecutionId = latestExecutionId;
    }

    @Override
    public String toString() {        
        
        StringBuffer buf = new StringBuffer();
        buf.append(",currentStepId: " + currentStepId);
        buf.append(",batchStatus: " + batchStatus);
        buf.append(",latestExecutionId: " + latestExecutionId);
        buf.append(",restartOn: " + restartOn);
        return buf.toString();
    }

    public void setExitStatus(String exitStatus) {
        this.exitStatus = exitStatus;
    }

    public String getExitStatus() {
        return exitStatus;
    }

    /* (non-Javadoc)
	 * @see com.ibm.jbatch.container.status.IJobStatus#getRestartOn()
	 */
    @Override
	public String getRestartOn() {
        return restartOn;
    }

    /* (non-Javadoc)
	 * @see com.ibm.jbatch.container.status.IJobStatus#setRestartOn(java.lang.String)
	 */
    @Override
	public void setRestartOn(String restartOn) {
        this.restartOn = restartOn;
    }

	/* (non-Javadoc)
	 * @see com.ibm.jbatch.container.services.IJobStatus#getJobXML()
	 */
	@Override
	public String getJobXML() {
		return jobInstance.getJobXML();
	}
}
