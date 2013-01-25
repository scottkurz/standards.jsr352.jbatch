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
package jsr352.tck.tests.jslxml;

import static jsr352.tck.utils.AssertionUtils.assertWithMessage;

import java.net.URL;
import java.util.Properties;
import java.util.logging.Logger;

import javax.batch.runtime.JobExecution;


import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import jsr352.tck.utils.IOHelper;
import jsr352.tck.utils.JobOperatorBridge;

public class ContextAndListenerTests {

    private final static Logger logger = Logger.getLogger(ContextAndListenerTests.class.getName());
    private static JobOperatorBridge jobOp = null;

    
    public static void setup(String[] args, Properties props) throws Exception {
        jobOp = new JobOperatorBridge();
    }
    
    @BeforeClass
    public static void setUp() throws Exception {
        jobOp = new JobOperatorBridge();
    }

    /*
	 * @testName: testOneArtifactIsJobAndStepListener
	 * @assertion: FIXME
	 * @test_Strategy: FIXME
	 */
    @Test 
    public void testOneArtifactIsJobAndStepListener() throws Exception {

        String expectedStr = "BeforeJob" + 
                                 "BeforeStep" + "AfterStep" +
                                 "BeforeStep" + "BeforeStep" + 
                                 "AfterStep" + "AfterStep" +
                             "AfterJob";
        URL jobXMLURL = this.getClass().getResource("/oneArtifactIsJobAndStepListener.xml");
        String jobXML = IOHelper.readJobXML(jobXMLURL.getFile());

        JobExecution execution1 = jobOp.startJobAndWaitForResult(jobXML, null);
        assertWithMessage("Testing batch status", "COMPLETED", execution1.getStatus());
        assertWithMessage("Testing exit status", expectedStr, execution1.getExitStatus());
    }

    @AfterClass
    public static void cleanup() throws Exception {
        jobOp.destroy();
    }
}
