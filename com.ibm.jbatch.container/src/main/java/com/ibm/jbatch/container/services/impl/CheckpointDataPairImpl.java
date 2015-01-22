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
package com.ibm.jbatch.container.services.impl;

import java.io.Serializable;

import com.ibm.jbatch.container.services.CheckpointDataPair;

/**
 * @author skurz
 *
 */
public class CheckpointDataPairImpl implements CheckpointDataPair {

	private Serializable readerCheckpointInfo;
	private Serializable writerCheckpointInfo;

	public CheckpointDataPairImpl() {
		super();
	}

	@Override
	public Serializable getReaderCheckpoint() {
		return readerCheckpointInfo;
	}

	@Override
	public void setReaderCheckpoint(Serializable obj) {
		this.readerCheckpointInfo = obj;
	}

	@Override
	public Serializable getWriterCheckpoint() {
		return writerCheckpointInfo;
	}

	@Override
	public void setWriterCheckpoint(Serializable obj) {
		this.writerCheckpointInfo = obj;
	}
}
