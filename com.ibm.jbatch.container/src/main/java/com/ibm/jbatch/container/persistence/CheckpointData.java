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
package com.ibm.jbatch.container.persistence;
import java.io.ByteArrayInputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;

import com.ibm.jbatch.container.exception.BatchContainerRuntimeException;
import com.ibm.jbatch.container.services.CheckpointDataKey;
import com.ibm.jbatch.container.util.TCCLObjectInputStream;

/**
 * 
 */
public final class CheckpointData implements Serializable {

	public enum Type {READER, WRITER};

	private static final long serialVersionUID = 1L;
	private long _jobInstanceId;

	/**
	 * Enum -constrained values but we won't change at this point
	 * See: {@link CheckpointDataKey.Type}
	 */
	private String _batchDataStreamName;
	private String _stepName;
	private byte[] _restartToken;
	

	// Remove "NOTSET" support.  While this would have allowed us to distinguish between an "empty" entry and one
	// set with a 'null' checkpointInfo ('null' being a valid value), this also requires extra logic on the deserialization path
	// to consider.  I don't see the value in being able to distinguish this.
	// 
	// In the past the "NOTSET" was never serialized.  We didn't create the entry until the first checkpoint, at which time
	// we would use the value of 'null' if it existed.   So we don't have to worry about supporting it on deserialize.
	public CheckpointData (long jobInstanceId, String stepname, Type type) {
		if(stepname != null && type != null) {
			_jobInstanceId = jobInstanceId;
			_batchDataStreamName = type.toString();
			_stepName = stepname;
		} else {
			throw new RuntimeException("Invalid parameters to CheckpointData jobInstanceId: " + _jobInstanceId + 
					" Type: " + _batchDataStreamName + " stepName: " + stepname);
		}
	}

	public void setRestartToken(byte[] token) {
		_restartToken = token;
	}

	public String toString() {
		String restartString = null;
		try {
			restartString = new String(this._restartToken, "UTF8");
		} catch (UnsupportedEncodingException e) {
			restartString = "<bytes not UTF-8>";
		}
		return " jobInstanceId: " + _jobInstanceId + " stepId: " + this._stepName + " bdsName: " + this._batchDataStreamName +
				" restartToken: [UTF8-bytes: " + restartString;
	}

	public Serializable getDeserializedRestartToken() {

		if (_restartToken == null) {
			return null;
		}

		Serializable retVal = null;
		ByteArrayInputStream bais = new ByteArrayInputStream(_restartToken);
		TCCLObjectInputStream ois = null;
		try {
			ois = new TCCLObjectInputStream(bais);
			retVal = (Serializable)ois.readObject();
			ois.close();
		} catch (Exception ex) {
			// is this what I should be throwing here?
			throw new BatchContainerRuntimeException("Problem deserializing restart token for jobInstanceId: " + _jobInstanceId + " stepId: " + this._stepName + " bdsName: " + this._batchDataStreamName, ex);
		}
		return retVal;
	}
}

