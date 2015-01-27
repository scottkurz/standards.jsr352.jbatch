/*
 * Copyright 2015 International Business Machines Corp.
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
package com.ibm.jbatch.container.services;

import javax.batch.runtime.BatchStatus;
import javax.batch.runtime.JobInstance;

import com.ibm.jbatch.container.jobinstance.JobInstanceImpl;

/**
 * @author skurz
 *
 */
public interface IJobStatus {

	public abstract long getJobInstanceId();

	public abstract void setJobInstance(JobInstance jobInstance);

	public abstract JobInstance getJobInstance();

	public abstract String getCurrentStepId();

	public abstract void setCurrentStepId(String currentStepId);

	public abstract BatchStatus getBatchStatus();

	public abstract void setBatchStatus(BatchStatus batchStatus);

	public abstract String getRestartOn();

	public abstract void setRestartOn(String restartOn);

	public abstract void setExitStatus(String exitStatus);
	public abstract String getExitStatus();

	public abstract void setLatestExecutionId(long newExecutionId);
	public abstract long getLatestExecutionId();

	public abstract String getJobXML();

}