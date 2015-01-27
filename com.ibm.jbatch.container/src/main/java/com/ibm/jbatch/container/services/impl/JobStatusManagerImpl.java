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
package com.ibm.jbatch.container.services.impl;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.batch.runtime.BatchStatus;

import com.ibm.jbatch.container.exception.BatchContainerServiceException;
import com.ibm.jbatch.container.exception.PersistenceException;
import com.ibm.jbatch.container.services.IJobStatus;
import com.ibm.jbatch.container.services.IJobStatusManagerService;
import com.ibm.jbatch.container.services.IPersistenceManagerService;
import com.ibm.jbatch.container.services.IStepStatus;
import com.ibm.jbatch.container.servicesmanager.ServicesManager;
import com.ibm.jbatch.container.servicesmanager.ServicesManagerImpl;
import com.ibm.jbatch.container.status.StepStatus;
import com.ibm.jbatch.spi.services.IBatchConfig;

public class JobStatusManagerImpl implements IJobStatusManagerService {

    private static final String CLASSNAME = JobStatusManagerImpl.class.getName();
    private static Logger logger = Logger.getLogger(JobStatusManagerImpl.class.getPackage().getName());
    private IPersistenceManagerService _persistenceManager;    

    @Override
    public void shutdown() throws BatchContainerServiceException {
        // TODO Auto-generated method stub
    }

    @Override
    public IJobStatus createJobStatus(long jobInstanceId) throws BatchContainerServiceException {
        return _persistenceManager.createJobStatus(jobInstanceId);
    }

    @Override
    public IJobStatus getJobStatus(long jobInstanceId) throws BatchContainerServiceException {
        return _persistenceManager.getJobStatus(jobInstanceId);
    }
    
    @Override
    public void updateJobStatus(IJobStatus jobStatus) {
        persistJobStatus(jobStatus.getJobInstanceId(), jobStatus);
    }
    
    @Override
    public IJobStatus getJobStatusFromExecutionId(long executionId) throws BatchContainerServiceException {
    	IJobStatus retVal = null;
    	logger.fine("For executionId: " + executionId);
    	try {
    		retVal = _persistenceManager.getJobStatusFromExecution(executionId);
    	} catch (PersistenceException e) {
    		logger.warning("Did not find job instance status for executionId: " + executionId);
    		throw e;
    	}
    	logger.fine("Returning : " + retVal);
    	return retVal;
    }

    @Override
    public void updateJobBatchStatus(long jobInstanceId, BatchStatus batchStatus) throws BatchContainerServiceException {
        IJobStatus js = getJobStatus(jobInstanceId);
        if (js == null) {
            throw new IllegalStateException("Couldn't find entry to update for id = " + jobInstanceId);
        }
        if (BatchStatus.ABANDONED.equals(js.getBatchStatus())) {
        	logger.fine("Don't update batch status for id = " + jobInstanceId + " since it is already ABANDONED"); 
        }
        js.setBatchStatus(batchStatus);
        persistJobStatus(jobInstanceId, js);
    }

    @Override
    public void updateJobExecutionStatus(long jobInstanceId, BatchStatus batchStatus, String exitStatus) throws BatchContainerServiceException {
        IJobStatus js = getJobStatus(jobInstanceId);
        if (js == null) {
            throw new IllegalStateException("Couldn't find entry to update for id = " + jobInstanceId);
        }
        js.setBatchStatus(batchStatus);
        js.setExitStatus(exitStatus);
        persistJobStatus(jobInstanceId, js);
    }

    @Override
    public void updateJobCurrentStep(long jobInstanceId, String currentStepName) throws BatchContainerServiceException {
        IJobStatus js = getJobStatus(jobInstanceId);
        if (js == null) {
            throw new IllegalStateException("Couldn't find entry to update for id = " + jobInstanceId);
        }
        js.setCurrentStepId(currentStepName);
        persistJobStatus(jobInstanceId, js);        
    }


    @Override
    public void updateJobStatusWithNewExecution(long jobInstanceId, long newExecutionId) throws BatchContainerServiceException {
        IJobStatus js = getJobStatus(jobInstanceId);
        if (js == null) {
            throw new IllegalStateException("Couldn't find entry to update for id = " + jobInstanceId);
        }
        js.setRestartOn(null);
        js.setLatestExecutionId(newExecutionId);
        js.setBatchStatus(BatchStatus.STARTING);
        persistJobStatus(jobInstanceId, js);                
    }

    private void persistJobStatus(long jobInstanceId, IJobStatus newJobStatus) throws BatchContainerServiceException {       
        _persistenceManager.updateJobStatus(jobInstanceId, newJobStatus);
    }

    @Override
    public IStepStatus createStepStatus(long stepExecutionId) throws BatchContainerServiceException {        
        return _persistenceManager.createStepStatus(stepExecutionId);
    }

    @Override
    /*
     * @return - StepStatus or null if one is unknown
     */
    public IStepStatus getStepStatus(long jobInstanceId, String stepId) throws BatchContainerServiceException {
        String method = "getStepStatus";
        logger.entering(CLASSNAME, method, new Object[] {jobInstanceId, stepId});

        IStepStatus stepStatus = _persistenceManager.getStepStatus(jobInstanceId, stepId);

        logger.exiting(CLASSNAME, method, stepStatus==null ? "<null>" : stepStatus);
        return stepStatus;
    }

    @Override 
    public void updateStepStatus(long stepExecutionId, IStepStatus newStepStatus) {
        String method = "updateStepStatus";
        logger.entering(CLASSNAME, method, new Object[] {stepExecutionId, newStepStatus});
        _persistenceManager.updateStepStatus(stepExecutionId, newStepStatus);
        logger.exiting(CLASSNAME, method);
    }

    @Override
    public void init(IBatchConfig batchConfig)
    throws BatchContainerServiceException {
        String method = "init";
        if(logger.isLoggable(Level.FINER)) { logger.entering(CLASSNAME, method);}

        ServicesManager sm = ServicesManagerImpl.getInstance();

        _persistenceManager = sm.getPersistenceManagerService();

        if(logger.isLoggable(Level.FINER)) { logger.exiting(CLASSNAME, method);}
    }

    @Override
    /*
     * Inefficient, since we've already updated the status to stopped.. would be better to have a single update.
     */
    public void updateJobStatusFromJSLStop(long jobInstanceId, String restartOn) throws BatchContainerServiceException {       
        IJobStatus js = getJobStatus(jobInstanceId);        
        if (js == null) {
            throw new IllegalStateException("Couldn't find entry to update for id = " + jobInstanceId);
        }
        js.setRestartOn(restartOn);
        persistJobStatus(jobInstanceId, js);   
    }
}
