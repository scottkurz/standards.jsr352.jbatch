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

import java.io.Serializable;

import javax.batch.runtime.BatchStatus;

/**
 * @author skurz
 */
public interface IStepStatus {
	
	public void setBatchStatus(BatchStatus batchStatus);
	public BatchStatus getBatchStatus();

	public long getStepExecutionId();
	public int getStartCount();
	public void incrementStartCount();
	public void setExitStatus(String exitStatus);
	public String getExitStatus();
	public void setPersistentUserData(Serializable persistentUserData);
	public Serializable getPersistentUserData();
    public Integer getNumPartitions();
	public void setNumPartitions(Integer numPartitions);
	public void setStepExecutionId(long stepExecutionId);
	public long getLastRunStepExecutionId();
	public void setLastRunStepExecutionId(long stepExecutionId);
}
