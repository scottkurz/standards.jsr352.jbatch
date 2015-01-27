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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.batch.operations.NoSuchJobExecutionException;
import javax.batch.runtime.BatchStatus;
import javax.batch.runtime.JobExecution;
import javax.batch.runtime.JobInstance;
import javax.batch.runtime.Metric;
import javax.batch.runtime.StepExecution;
import javax.batch.runtime.context.StepContext;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import com.ibm.jbatch.container.context.impl.MetricImpl;
import com.ibm.jbatch.container.exception.BatchContainerServiceException;
import com.ibm.jbatch.container.exception.PersistenceException;
import com.ibm.jbatch.container.impl.PartitionedStepBuilder;
import com.ibm.jbatch.container.jobinstance.JobInstanceImpl;
import com.ibm.jbatch.container.jobinstance.JobOperatorJobExecution;
import com.ibm.jbatch.container.jobinstance.RuntimeFlowInSplitExecution;
import com.ibm.jbatch.container.jobinstance.RuntimeJobExecution;
import com.ibm.jbatch.container.jobinstance.StepExecutionImpl;
import com.ibm.jbatch.container.persistence.CheckpointData;
import com.ibm.jbatch.container.persistence.CheckpointData.Type;
import com.ibm.jbatch.container.services.CheckpointDataKey;
import com.ibm.jbatch.container.services.CheckpointDataPair;
import com.ibm.jbatch.container.services.IJobStatus;
import com.ibm.jbatch.container.services.IPersistenceManagerService;
import com.ibm.jbatch.container.services.IStepStatus;
import com.ibm.jbatch.container.status.JobStatus;
import com.ibm.jbatch.container.status.StepStatus;
import com.ibm.jbatch.container.util.TCCLObjectInputStream;
import com.ibm.jbatch.spi.services.IBatchConfig;

public class JDBCPersistenceManagerImpl implements IPersistenceManagerService, JDBCPersistenceManagerSQLConstants {

	private class NoSuchEntryException extends Exception { }

	private static final String CLASSNAME = JDBCPersistenceManagerImpl.class.getName();

	private final static Logger logger = Logger.getLogger(CLASSNAME);

	private IBatchConfig batchConfig = null;

	protected DataSource dataSource = null;
	protected String jndiName = null;

	protected String driver = ""; 
	protected String schema = "";
	protected String url = ""; 
	protected String userId = "";
	protected String pwd = "";

	/* (non-Javadoc)
	 * @see com.ibm.jbatch.container.services.impl.AbstractPersistenceManagerImpl#init(com.ibm.jbatch.container.IBatchConfig)
	 */
	@Override
	public void init(IBatchConfig batchConfig) throws BatchContainerServiceException {
		logger.config("Entering CLASSNAME.init(), batchConfig =" + batchConfig);

		this.batchConfig = batchConfig;

		schema = batchConfig.getDatabaseConfigurationBean().getSchema();

		if (!batchConfig.isJ2seMode()) {
			jndiName = batchConfig.getDatabaseConfigurationBean().getJndiName();

			logger.config("JNDI name = " + jndiName);

			if (jndiName == null || jndiName.equals("")) {
				throw new BatchContainerServiceException("JNDI name is not defined.");
			}

			try {
				Context ctx = new InitialContext();
				dataSource = (DataSource) ctx.lookup(jndiName);

			} catch (NamingException e) {
				logger.severe("Lookup failed for JNDI name: " + jndiName + 
						".  One cause of this could be that the batch runtime is incorrectly configured to EE mode when it should be in SE mode.");
				throw new BatchContainerServiceException(e);
			}

		} else {
			driver = batchConfig.getDatabaseConfigurationBean().getJdbcDriver();
			url = batchConfig.getDatabaseConfigurationBean().getJdbcUrl();
			userId = batchConfig.getDatabaseConfigurationBean().getDbUser();
			pwd = batchConfig.getDatabaseConfigurationBean().getDbPassword();

			logger.config("driver: " + driver + ", url: " + url);
		}

		try {
			// only auto-create on Derby
			if(isDerby()) {	
				if(!isSchemaValid()) {
					createSchema();
				}
				checkAllTables();
			}
		} catch (SQLException e) {
			logger.severe(e.getLocalizedMessage());
			throw new BatchContainerServiceException(e);
		}

		logger.config("Exiting CLASSNAME.init()");
	}

	/**
	 * Checks if the default schema JBATCH or the schema defined in batch-config exists.
	 * 
	 * @return true if the schema exists, false otherwise.
	 * @throws SQLException
	 */
	private boolean isSchemaValid() throws SQLException {
		logger.entering(CLASSNAME, "isSchemaValid");
		Connection conn = getConnectionToDefaultSchema();
		DatabaseMetaData dbmd = conn.getMetaData();
		ResultSet rs = dbmd.getSchemas();
		while(rs.next()) {
			if (schema.equalsIgnoreCase(rs.getString("TABLE_SCHEM")) ) {
				cleanupConnection(conn, rs, null);
				logger.exiting(CLASSNAME, "isSchemaValid", true);
				return true;
			}
		}
		cleanupConnection(conn, rs, null);
		logger.exiting(CLASSNAME, "isSchemaValid", false);
		return false;
	}

	private boolean isDerby() throws SQLException {
		logger.entering(CLASSNAME, "isDerby");
		Connection conn = getConnectionToDefaultSchema();
		DatabaseMetaData dbmd = conn.getMetaData();
		boolean derby = dbmd.getDatabaseProductName().toLowerCase().indexOf("derby") > 0;
		logger.exiting(CLASSNAME, "isDerby", derby);
		return derby;
	}

	/**
	 * Creates the default schema JBATCH or the schema defined in batch-config.
	 * 
	 * @throws SQLException
	 */
	private void createSchema() throws SQLException {
		logger.entering(CLASSNAME, "createSchema");
		Connection conn = getConnectionToDefaultSchema();

		logger.log(Level.INFO, schema + " schema does not exists. Trying to create it.");
		PreparedStatement ps = null;
		ps = conn.prepareStatement("CREATE SCHEMA " + schema);
		ps.execute();

		cleanupConnection(conn, null, ps);
		logger.exiting(CLASSNAME, "createSchema");
	}

	/**
	 * Checks if all the runtime batch table exists. If not, it creates them.
	 *  
	 * @throws SQLException
	 */
	private void checkAllTables() throws SQLException {
		logger.entering(CLASSNAME, "checkAllTables");

		createIfNotExists(CHECKPOINTDATA_TABLE, CREATE_TAB_CHECKPOINTDATA);
		executeStatement(CREATE_CHECKPOINTDATA_INDEX);
		createIfNotExists(JOBINSTANCEDATA_TABLE, CREATE_TAB_JOBINSTANCEDATA);

		createIfNotExists(EXECUTIONINSTANCEDATA_TABLE,
				CREATE_TAB_EXECUTIONINSTANCEDATA);
		createIfNotExists(STEPEXECUTIONINSTANCEDATA_TABLE,
				CREATE_TAB_STEPEXECUTIONINSTANCEDATA);

		createIfNotExists(JOBSTATUS_TABLE, CREATE_TAB_JOBSTATUS);
		createIfNotExists(STEPSTATUS_TABLE, CREATE_TAB_STEPSTATUS);	

		logger.exiting(CLASSNAME, "checkAllTables");
	}

	/**
	 * Creates tableName using the createTableStatement DDL.
	 * 
	 * @param tableName
	 * @param createTableStatement
	 * @throws SQLException
	 */
	private void createIfNotExists(String tableName, String createTableStatement) throws SQLException {
		logger.entering(CLASSNAME, "createIfNotExists", new Object[] {tableName, createTableStatement});

		Connection conn = getConnection();
		DatabaseMetaData dbmd = conn.getMetaData();
		ResultSet rs = dbmd.getTables(null, schema, tableName, null);
		PreparedStatement ps = null;
		if(!rs.next()) {
			logger.log(Level.INFO, tableName + " table does not exists. Trying to create it.");
			ps = conn.prepareStatement(createTableStatement);
			ps.executeUpdate();
		}

		cleanupConnection(conn, rs, ps);
		logger.exiting(CLASSNAME, "createIfNotExists");
	}

	/**
	 * Executes the provided SQL statement
	 * 
	 * @param statement
	 * @throws SQLException
	 */
	private void executeStatement(String statement) throws SQLException {
		logger.entering(CLASSNAME, "executeStatement", statement);

		Connection conn = getConnection();
		PreparedStatement ps = null;

		ps = conn.prepareStatement(statement);
		ps.executeUpdate();

		cleanupConnection(conn, ps);
		logger.exiting(CLASSNAME, "executeStatement");
	}

	private String getCheckpointKeyString(CheckpointDataKey checkpointDataKey, Type type) {
		return checkpointDataKey.getJobInstanceId() + "," +
				checkpointDataKey.getStepName() + "," + type.toString();
	}
		
	/* (non-Javadoc)
	 * @see com.ibm.jbatch.container.services.impl.AbstractPersistenceManagerImpl#createCheckpointData(com.ibm.ws.batch.container.checkpoint.CheckpointDataKey, com.ibm.ws.batch.container.checkpoint.CheckpointData)
	 */
	@Override
	public void createCheckpointData(CheckpointDataKey key) {
		logger.entering(CLASSNAME, "createCheckpointData", key);
		
		CheckpointData readerChkpt = 
				new CheckpointData(key.getJobInstanceId(), key.getStepName(), Type.READER);
		CheckpointData writerChkpt = 
				new CheckpointData(key.getJobInstanceId(), key.getStepName(), Type.WRITER);

		String readerKeyString = getCheckpointKeyString(key, Type.READER);
		String writerKeyString = getCheckpointKeyString(key, Type.WRITER);

		insertCheckpointData(readerKeyString, readerChkpt);
		insertCheckpointData(writerKeyString, writerChkpt);
		
		logger.exiting(CLASSNAME, "createCheckpointData");
	}
	
	private void insertCheckpointData(String keyString, CheckpointData checkpointData) {
		Connection conn = null;
		PreparedStatement statement = null;
		ByteArrayOutputStream baos = null;
		ObjectOutputStream oout = null;

		logger.finer("Inserting checkpoint data for key: " + keyString);

		byte[] b;
		try {
			conn = getConnection();
			statement = conn.prepareStatement(INSERT_CHECKPOINTDATA);
			
			b = serializeObject(checkpointData);

			statement.setString(1, keyString);
			statement.setBytes(2, b);
			statement.executeUpdate();
		} catch (SQLException e) {
			throw new PersistenceException(e);
		} catch (IOException e) {
			throw new PersistenceException(e);
		} finally {
			if (baos != null) {
				try {
					baos.close();
				} catch (IOException e) {
					throw new PersistenceException(e);
				}
			}
			if (oout != null) {
				try {
					oout.close();
				} catch (IOException e) {
					throw new PersistenceException(e);
				}
			}
			cleanupConnection(conn, null, statement);
		}
		
		logger.exiting(CLASSNAME, "createCheckpointData");
	}

	/* (non-Javadoc)
	 * @see com.ibm.jbatch.container.services.impl.AbstractPersistenceManagerImpl#getCheckpointData(com.ibm.ws.batch.container.checkpoint.CheckpointDataKey)
	 */
	@Override
	public CheckpointDataPair getCheckpointData(CheckpointDataKey key) {
		logger.entering(CLASSNAME, "getCheckpointData", key==null ? "<null>" : key);
		
		Serializable readerCheckpointData = null;
		Serializable writerCheckpointData = null;

		String readerKeyString = getCheckpointKeyString(key, Type.READER);
		String writerKeyString = getCheckpointKeyString(key, Type.WRITER);
		
		try {
			readerCheckpointData = queryCheckpointData(readerKeyString);
			writerCheckpointData = queryCheckpointData(writerKeyString);
		} catch (NoSuchEntryException e) {
			logger.finer("No entry found for: " + readerKeyString);
			return null;
		}
		
		CheckpointDataPair checkpointDataPair = new CheckpointDataPairImpl();
		checkpointDataPair.setReaderCheckpoint(readerCheckpointData);
		checkpointDataPair.setWriterCheckpoint(writerCheckpointData);
		
		logger.exiting(CLASSNAME, "getCheckpointData");

		return checkpointDataPair;
	}
		
	private Serializable queryCheckpointData(String keyString) throws NoSuchEntryException {

		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;
		ObjectInputStream objectIn = null;
		CheckpointData data = null;

		logger.finer("Querying checkpoint data for key: " + keyString);

		try {
			conn = getConnection();
			statement = conn.prepareStatement(SELECT_CHECKPOINTDATA);
			statement.setString(1, keyString);
			rs = statement.executeQuery();
			if (rs.next()) {
				byte[] buf = rs.getBytes("obj");
				data = (CheckpointData)deserializeObject(buf);
			} else {
				throw new NoSuchEntryException();
			}
		} catch (SQLException e) {
			throw new PersistenceException(e);
		} catch (IOException e) {
			throw new PersistenceException(e);
		} catch (ClassNotFoundException e) {
			throw new PersistenceException(e);
		} finally {
			if (objectIn != null) {
				try {
					objectIn.close();
				} catch (IOException e) {
					throw new PersistenceException(e);
				}
			}
			cleanupConnection(conn, rs, statement);
		}
		
		// Note 'data' must be non-null at this point, though 'checkpointInfo' of null is a valid value.
		Serializable checkpointInfo = data.getDeserializedRestartToken();
		
		logger.exiting(CLASSNAME, "getCheckpointData", checkpointInfo==null ? "<null>" : checkpointInfo);
		return checkpointInfo;
	}


	/**
	 * 
	 */
	public void updateCheckpointData(CheckpointDataKey key, CheckpointDataPair checkpointDataPair) {
		logger.entering(CLASSNAME, "updateCheckpointData", new Object[] {key, checkpointDataPair});
		
		updateCheckpointData(key, checkpointDataPair.getReaderCheckpoint(), Type.READER);
		updateCheckpointData(key, checkpointDataPair.getWriterCheckpoint(), Type.WRITER);
		
		logger.exiting(CLASSNAME, "updateCheckpointData");
	}
	
	private void updateCheckpointData(CheckpointDataKey key, Serializable checkpointInfo, Type type) {
		
		Connection conn = null;
		PreparedStatement statement = null;
		ByteArrayOutputStream baos = null;
		ObjectOutputStream oout = null;
		byte[] b;

		String keyString = getCheckpointKeyString(key, type);
		logger.finer("Updating checkpoint data for key: " + keyString);

		try {
			conn = getConnection();
			statement = conn.prepareStatement(UPDATE_CHECKPOINTDATA);

			CheckpointData checkpointData =
				new CheckpointData(key.getJobInstanceId(), key.getStepName(), type);

			byte[] checkpointInfoBytes = serializeObject(checkpointInfo);
			checkpointData.setRestartToken(checkpointInfoBytes);

			b = serializeObject(checkpointData);

			statement.setBytes(1, b);
			statement.setString(2, keyString);
			statement.executeUpdate();
		} catch (SQLException e) {
			throw new PersistenceException(e);
		} catch (IOException e) {
			throw new PersistenceException(e);
		} finally {
			if (baos != null) {
				try {
					baos.close();
				} catch (IOException e) {
					throw new PersistenceException(e);
				}
			}
			if (oout != null) {
				try {
					oout.close();
				} catch (IOException e) {
					throw new PersistenceException(e);
				}
			}
			cleanupConnection(conn, null, statement);
		}
		logger.exiting(CLASSNAME, "updateCheckpointData");
	}


	/**
	 * @return the database connection and sets it to the default schema JBATCH or the schema defined in batch-config.
	 * 
	 * @throws SQLException
	 */
	protected Connection getConnection() throws SQLException {
		logger.finest("Entering: " + CLASSNAME + ".getConnection");
		Connection connection = null;

		if(!batchConfig.isJ2seMode()) {
			logger.finest("J2EE mode, getting connection from data source");
			connection = dataSource.getConnection();
			logger.finest("autocommit="+connection.getAutoCommit());
		} else {
			try {
				Class.forName(driver);
			} catch (ClassNotFoundException e) {
				throw new PersistenceException(e);
			}
			logger.finest("JSE mode, getting connection from " + url);
			connection = DriverManager.getConnection(url, userId, pwd);
			logger.finest("autocommit="+connection.getAutoCommit());
		}
		setSchemaOnConnection(connection);

		logger.finest("Exiting: " + CLASSNAME + ".getConnection() with conn =" + connection);
		return connection;
	}

	/**
	 * @return the database connection. The schema is set to whatever default its used by the underlying database.
	 * @throws SQLException
	 */
	protected Connection getConnectionToDefaultSchema() throws SQLException {
		logger.finest("Entering getConnectionToDefaultSchema");
		Connection connection = null;

		if(!batchConfig.isJ2seMode()) {
			logger.finest("J2EE mode, getting connection from data source");
			try {
				connection = dataSource.getConnection();
			} catch(SQLException e) {
				logException("FAILED GETTING DATABASE CONNECTION", e);
				throw new PersistenceException(e);
			}
			logger.finest("autocommit="+connection.getAutoCommit());
		} else {
			try {
				Class.forName(driver);
			} catch (ClassNotFoundException e) {
				logException("ClassNotFoundException: Cannot load driver class: " + driver, e);
				throw new PersistenceException(e);
			}
			logger.finest("JSE mode, getting connection from " + url);
			try {
				connection = DriverManager.getConnection(url, userId, pwd);
			} catch (SQLException e) {
				logException("FAILED GETTING DATABASE CONNECTION.  FOR EMBEDDED DERBY CHECK FOR OTHER USER LOCKING THE CURRENT DATABASE (Try using a different database instance).", e); 
				throw new PersistenceException(e);
			}
			logger.finest("autocommit="+connection.getAutoCommit());
		}
		logger.finest("Exiting from getConnectionToDefaultSchema, conn= " +connection);
		return connection;
	}

	private void logException(String msg, Exception e) {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		e.printStackTrace(pw);

		logger.log(Level.SEVERE, msg +  "; Exception stack trace: " + sw);
	}

	/**
	 * Set the default schema JBATCH or the schema defined in batch-config on the connection object.
	 * 
	 * @param connection
	 * @throws SQLException
	 */
	private void setSchemaOnConnection(Connection connection) throws SQLException {
		logger.finest("Entering " + CLASSNAME +".setSchemaOnConnection()");

		if (!"Oracle".equals(connection.getMetaData().getDatabaseProductName())) {
			PreparedStatement ps = null;
			ps = connection.prepareStatement("SET SCHEMA ?");
			ps.setString(1, schema);
			ps.executeUpdate(); 
			ps.close();
		}

		logger.finest("Exiting " + CLASSNAME +".setSchemaOnConnection()");
	}

	/**
	 * closes connection, result set and statement
	 * 
	 * @param conn - connection object to close
	 * @param rs - result set object to close
	 * @param statement - statement object to close
	 */
	private void cleanupConnection(Connection conn, ResultSet rs, PreparedStatement statement) {

		logger.logp(Level.FINEST, CLASSNAME, "cleanupConnection", "Entering",  new Object[] {conn, rs==null ? "<null>" : rs, statement==null ? "<null>" : statement});

		if (statement != null) {
			try {
				statement.close();
			} catch (SQLException e) {
				throw new PersistenceException(e);
			}
		}

		if (rs != null) {
			try {
				rs.close();
			} catch (SQLException e) {
				throw new PersistenceException(e);
			}
		}

		if (conn != null) {
			try {
				conn.close();
			} catch (SQLException e) {
				throw new PersistenceException(e);
			} finally {
				try {
					conn.close();
				} catch (SQLException e) {
					throw new PersistenceException(e);
				}
			}
		}
		logger.logp(Level.FINEST, CLASSNAME, "cleanupConnection", "Exiting");
	}

	/**
	 * closes connection and statement
	 * 
	 * @param conn - connection object to close
	 * @param statement - statement object to close
	 */
	private void cleanupConnection(Connection conn, PreparedStatement statement) {

		logger.logp(Level.FINEST, CLASSNAME, "cleanupConnection", "Entering",  new Object[] {conn, statement});

		if (statement != null) {
			try {
				statement.close();
			} catch (SQLException e) {
				throw new PersistenceException(e);
			}
		}

		if (conn != null) {
			try {
				conn.close();
			} catch (SQLException e) {
				throw new PersistenceException(e);
			} finally {
				try {
					conn.close();
				} catch (SQLException e) {
					throw new PersistenceException(e);
				}
			}
		}
		logger.logp(Level.FINEST, CLASSNAME, "cleanupConnection", "Exiting");
	}


	@Override
	public int jobOperatorGetJobInstanceCount(String jobName, String appTag) {
		
		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;
		int count;

		try {
			conn = getConnection();
			statement = conn.prepareStatement("select count(jobinstanceid) as jobinstancecount from jobinstancedata where name = ? and apptag = ?");
			statement.setString(1, jobName);
			statement.setString(2, appTag);
			rs = statement.executeQuery();
			rs.next();
			count = rs.getInt("jobinstancecount");

		} catch (SQLException e) {
			throw new PersistenceException(e);
		}
		finally {
			cleanupConnection(conn, rs, statement);
		}
		return count;
	}
	
	@Override
	public int jobOperatorGetJobInstanceCount(String jobName) {

		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;
		int count;

		try {
			conn = getConnection();
			statement = conn.prepareStatement(SELECT_JOBINSTANCEDATA_COUNT);
			statement.setString(1, jobName);
			rs = statement.executeQuery();
			rs.next();
			count = rs.getInt("jobinstancecount");

		} catch (SQLException e) {
			throw new PersistenceException(e);
		}
		finally {
			cleanupConnection(conn, rs, statement);
		}
		return count;
	}


	@Override
	public List<Long> jobOperatorGetJobInstanceIds(String jobName, String appTag, int start, int count) {
		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;
		List<Long> data = new ArrayList<Long>();

		try {
			conn = getConnection();
			statement = conn.prepareStatement("select jobinstanceid from jobinstancedata where name = ? and apptag = ? order by jobinstanceid desc");
			statement.setObject(1, jobName);
			statement.setObject(2, appTag);
			rs = statement.executeQuery();
			while (rs.next()) {
				long id = rs.getLong("jobinstanceid");
				data.add(id);
			}
		} catch (SQLException e) {
			throw new PersistenceException(e);
		}
		finally {
			cleanupConnection(conn, rs, statement);
		}

		if (data.size() > 0){
			try {
				return data.subList(start, start+count);
			}
			catch (IndexOutOfBoundsException oobEx){
				return data.subList(start, data.size());
			}
		}
		else return data;
	}
	
	@Override
	public List<Long> jobOperatorGetJobInstanceIds(String jobName, int start, int count) {

		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;
		List<Long> data = new ArrayList<Long>();

		try {
			conn = getConnection();
			statement = conn.prepareStatement(SELECT_JOBINSTANCEDATA_IDS);
			statement.setObject(1, jobName);
			rs = statement.executeQuery();
			while (rs.next()) {
				long id = rs.getLong("jobinstanceid");
				data.add(id);
			}
		} catch (SQLException e) {
			throw new PersistenceException(e);
		}
		finally {
			cleanupConnection(conn, rs, statement);
		}

		if (data.size() > 0){
			try {
				return data.subList(start, start+count);
			}
			catch (IndexOutOfBoundsException oobEx){
				return data.subList(start, data.size());
			}
		}
		else return data;
	}

	@Override
	public Map<Long, String> jobOperatorGetExternalJobInstanceData() {

		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;
		HashMap<Long, String> data = new HashMap<Long,String>();

		try {
			conn = getConnection();

			// Filter out 'subjob' parallel execution entries which start with the special character
			final String filter = "not like '" + PartitionedStepBuilder.JOB_ID_SEPARATOR + "%'";

			statement = conn.prepareStatement("select distinct jobinstanceid, name from jobinstancedata where name " + filter );
			rs = statement.executeQuery();
			while (rs.next()) {
				long id = rs.getLong("jobinstanceid");
				String name = rs.getString("name");
				data.put(id, name);
			}
		} catch (SQLException e) {
			throw new PersistenceException(e);
		}
		finally {
			cleanupConnection(conn, rs, statement);
		}

		return data;
	}

	@Override
	public Timestamp jobOperatorQueryJobExecutionTimestamp(long key, TimestampType timestampType) {



		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;
		Timestamp createTimestamp = null;
		Timestamp endTimestamp = null;
		Timestamp updateTimestamp = null;
		Timestamp startTimestamp = null;

		try {
			conn = getConnection();
			statement = conn.prepareStatement("select createtime, endtime, updatetime, starttime from executioninstancedata where jobexecid = ?");
			statement.setObject(1, key);
			rs = statement.executeQuery();
			while (rs.next()) {
				createTimestamp = rs.getTimestamp(1);
				endTimestamp = rs.getTimestamp(2);
				updateTimestamp = rs.getTimestamp(3);
				startTimestamp = rs.getTimestamp(4);
			}
		} catch (SQLException e) {
			throw new PersistenceException(e);
		}
		finally {
			cleanupConnection(conn, rs, statement);
		}

		if (timestampType.equals(TimestampType.CREATE)) {
			return createTimestamp;
		} else if (timestampType.equals(TimestampType.END)) {
			return endTimestamp;
		} else if (timestampType.equals(TimestampType.LAST_UPDATED)) {
			return updateTimestamp;
		} else if (timestampType.equals(TimestampType.STARTED)) {
			return startTimestamp;
		} else {
			throw new IllegalArgumentException("Unexpected enum value.");
		}
	}

	@Override
	public String jobOperatorQueryJobExecutionBatchStatus(long key) {

		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;
		String status = null;

		try {
			conn = getConnection();
			statement = conn.prepareStatement("select batchstatus from executioninstancedata where jobexecid = ?");
			statement.setLong(1, key);
			rs = statement.executeQuery();
			while (rs.next()) {
				status = rs.getString(1);
			}
		} catch (SQLException e) {
			throw new PersistenceException(e);
		} finally {
			cleanupConnection(conn, rs, statement);
		}

		return status;
	}


	@Override
	public String jobOperatorQueryJobExecutionExitStatus(long key) {

		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;
		String status = null;

		try {
			conn = getConnection();
			statement = conn.prepareStatement("select exitstatus from executioninstancedata where jobexecid = ?");
			statement.setLong(1, key);
			rs = statement.executeQuery();
			while (rs.next()) {
				status = rs.getString(1);
			}
		} catch (SQLException e) {
			throw new PersistenceException(e);
		} finally {
			cleanupConnection(conn, rs, statement);
		}

		return status;
	}

	@Override
	public long jobOperatorQueryJobExecutionJobInstanceId(long executionID) throws NoSuchJobExecutionException {

		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;
		long jobinstanceID = 0;

		try {
			conn = getConnection();
			statement = conn.prepareStatement("select jobinstanceid from executioninstancedata where jobexecid = ?");
			statement.setLong(1, executionID);
			rs = statement.executeQuery();
			if (rs.next()) {
				jobinstanceID = rs.getLong("jobinstanceid");
			} else {
				String msg = "Did not find job instance associated with executionID =" + executionID;
				logger.fine(msg);
				throw new NoSuchJobExecutionException(msg);
			}
		} catch (SQLException e) {
			throw new PersistenceException(e);
		}
		finally {
			cleanupConnection(conn, rs, statement);
		}

		return jobinstanceID;
	}

	@Override
	public Properties getParameters(long executionId) throws NoSuchJobExecutionException {
		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;
		Properties props = null;
		ObjectInputStream objectIn = null;

		try {
			conn = getConnection();
			statement = conn.prepareStatement("select parameters from executioninstancedata where jobexecid = ?"); 
			statement.setLong(1, executionId);
			rs = statement.executeQuery();
			
			if (rs.next()) {
				// get the object based data
				byte[] buf = rs.getBytes("parameters");
				props = (Properties)deserializeObject(buf);
			} else {
				String msg = "Did not find table entry for executionID =" + executionId;
				logger.fine(msg);
				throw new NoSuchJobExecutionException(msg);
			}

		} catch (SQLException e) {
			throw new PersistenceException(e);
		} catch (IOException e) {
			throw new PersistenceException(e);
		} catch (ClassNotFoundException e) {
			throw new PersistenceException(e);
		} finally {
			if (objectIn != null) {
				try {
					objectIn.close();
				} catch (IOException e) {
					throw new PersistenceException(e);
				}
			}
			cleanupConnection(conn, rs, statement);
		}

		return props;

	}


	public Map<String, StepExecution> getMostRecentStepExecutionsForJobInstance(long instanceId) {

		Map<String, StepExecution> data = new HashMap<String, StepExecution>();

		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;

		long jobexecid = 0;
		long stepexecid = 0;
		String stepname = null;
		String batchstatus = null;
		String exitstatus = null;
		Exception ex = null;
		long readCount =0;
		long writeCount = 0;
		long commitCount = 0;
		long rollbackCount = 0;
		long readSkipCount = 0;
		long processSkipCount = 0;
		long filterCount = 0;
		long writeSkipCount = 0;
		Timestamp startTS = null;
		Timestamp endTS = null;
		StepExecutionImpl stepEx = null;
		ObjectInputStream objectIn = null;

		try {
			conn = getConnection();
			statement = conn.prepareStatement("select A.* from stepexecutioninstancedata A inner join executioninstancedata B on A.jobexecid = B.jobexecid where B.jobinstanceid = ? order by A.stepexecid desc"); 
			statement.setLong(1, instanceId);
			rs = statement.executeQuery();
			while (rs.next()) {
				stepname = rs.getString("stepname");
				if (data.containsKey(stepname)) {
					continue;
				} else {

					jobexecid = rs.getLong("jobexecid");
					batchstatus = rs.getString("batchstatus");
					exitstatus = rs.getString("exitstatus");
					readCount = rs.getLong("readcount");
					writeCount = rs.getLong("writecount");
					commitCount = rs.getLong("commitcount");
					rollbackCount = rs.getLong("rollbackcount");
					readSkipCount = rs.getLong("readskipcount");
					processSkipCount = rs.getLong("processskipcount");
					filterCount = rs.getLong("filtercount");
					writeSkipCount = rs.getLong("writeSkipCount");
					startTS = rs.getTimestamp("startTime");
					endTS = rs.getTimestamp("endTime");
					// get the object based data
					Serializable persistentData = null;
					byte[] pDataBytes = rs.getBytes("persistentData");
					if (pDataBytes != null) {
						objectIn = new TCCLObjectInputStream(new ByteArrayInputStream(pDataBytes));
						persistentData = (Serializable)objectIn.readObject();
					}

					stepEx = new StepExecutionImpl(jobexecid, stepexecid);

					stepEx.setBatchStatus(BatchStatus.valueOf(batchstatus));
					stepEx.setExitStatus(exitstatus);
					stepEx.setStepName(stepname);
					stepEx.setReadCount(readCount);
					stepEx.setWriteCount(writeCount);
					stepEx.setCommitCount(commitCount);
					stepEx.setRollbackCount(rollbackCount);
					stepEx.setReadSkipCount(readSkipCount);
					stepEx.setProcessSkipCount(processSkipCount);
					stepEx.setFilterCount(filterCount);
					stepEx.setWriteSkipCount(writeSkipCount);
					stepEx.setStartTime(startTS);
					stepEx.setEndTime(endTS);	
					stepEx.setPersistentUserData(persistentData);

					data.put(stepname, stepEx);
				}
			}
		} catch (SQLException e) {
			throw new PersistenceException(e);
		} catch (IOException e) {
			throw new PersistenceException(e);
		} catch (ClassNotFoundException e) {
			throw new PersistenceException(e);
		} finally {
			cleanupConnection(conn, rs, statement);
		}

		return data;
	}


	@Override
	public List<StepExecution> getStepExecutionsForJobExecution(long execid) {
		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;

		long jobexecid = 0;
		long stepexecid = 0;
		String stepname = null;
		String batchstatus = null;
		String exitstatus = null;
		Exception ex = null;
		long readCount =0;
		long writeCount = 0;
		long commitCount = 0;
		long rollbackCount = 0;
		long readSkipCount = 0;
		long processSkipCount = 0;
		long filterCount = 0;
		long writeSkipCount = 0;
		Timestamp startTS = null;
		Timestamp endTS = null;
		StepExecutionImpl stepEx = null;
		ObjectInputStream objectIn = null;

		List<StepExecution> data = new ArrayList<StepExecution>();

		try {
			conn = getConnection();
			statement = conn.prepareStatement("select * from stepexecutioninstancedata where jobexecid = ?"); 
			statement.setLong(1, execid);
			rs = statement.executeQuery();
			while (rs.next()) {
				jobexecid = rs.getLong("jobexecid");
				stepexecid = rs.getLong("stepexecid");
				stepname = rs.getString("stepname");
				batchstatus = rs.getString("batchstatus");
				exitstatus = rs.getString("exitstatus");
				readCount = rs.getLong("readcount");
				writeCount = rs.getLong("writecount");
				commitCount = rs.getLong("commitcount");
				rollbackCount = rs.getLong("rollbackcount");
				readSkipCount = rs.getLong("readskipcount");
				processSkipCount = rs.getLong("processskipcount");
				filterCount = rs.getLong("filtercount");
				writeSkipCount = rs.getLong("writeSkipCount");
				startTS = rs.getTimestamp("startTime");
				endTS = rs.getTimestamp("endTime");
				// get the object based data
				Serializable persistentData = null;
				byte[] pDataBytes = rs.getBytes("persistentData");
				if (pDataBytes != null) {
					objectIn = new TCCLObjectInputStream(new ByteArrayInputStream(pDataBytes));
					persistentData = (Serializable)objectIn.readObject();
				}

				stepEx = new StepExecutionImpl(jobexecid, stepexecid);

				stepEx.setBatchStatus(BatchStatus.valueOf(batchstatus));
				stepEx.setExitStatus(exitstatus);
				stepEx.setStepName(stepname);
				stepEx.setReadCount(readCount);
				stepEx.setWriteCount(writeCount);
				stepEx.setCommitCount(commitCount);
				stepEx.setRollbackCount(rollbackCount);
				stepEx.setReadSkipCount(readSkipCount);
				stepEx.setProcessSkipCount(processSkipCount);
				stepEx.setFilterCount(filterCount);
				stepEx.setWriteSkipCount(writeSkipCount);
				stepEx.setStartTime(startTS);
				stepEx.setEndTime(endTS);	
				stepEx.setPersistentUserData(persistentData);
				
				logger.fine("BatchStatus: " + batchstatus + " | StepName: " + stepname + " | JobExecID: " + jobexecid + " | StepExecID: " + stepexecid);
				
				data.add(stepEx);
			}
		} catch (SQLException e) {
			throw new PersistenceException(e);
		} catch (IOException e) {
			throw new PersistenceException(e);
		} catch (ClassNotFoundException e) {
			throw new PersistenceException(e);
		} finally {
			cleanupConnection(conn, rs, statement);
		}

		return data;
	}

	
    @Override
    public StepExecution getStepExecutionByStepExecutionId(long stepExecId) {
        Connection conn = null;
        PreparedStatement statement = null;
        ResultSet rs = null;

        long jobexecid = 0;
        long stepexecid = 0;
        String stepname = null;
        String batchstatus = null;
        String exitstatus = null;
        Exception ex = null;
        long readCount = 0;
        long writeCount = 0;
        long commitCount = 0;
        long rollbackCount = 0;
        long readSkipCount = 0;
        long processSkipCount = 0;
        long filterCount = 0;
        long writeSkipCount = 0;
        Timestamp startTS = null;
        Timestamp endTS = null;
        StepExecutionImpl stepEx = null;
        ObjectInputStream objectIn = null;

        try {
            conn = getConnection();
            statement = conn.prepareStatement("select * from stepexecutioninstancedata where stepexecid = ?");
            statement.setLong(1, stepExecId);
            rs = statement.executeQuery();
            while (rs.next()) {
                jobexecid = rs.getLong("jobexecid");
                stepexecid = rs.getLong("stepexecid");
                stepname = rs.getString("stepname");
                batchstatus = rs.getString("batchstatus");
                exitstatus = rs.getString("exitstatus");
                readCount = rs.getLong("readcount");
                writeCount = rs.getLong("writecount");
                commitCount = rs.getLong("commitcount");
                rollbackCount = rs.getLong("rollbackcount");
                readSkipCount = rs.getLong("readskipcount");
                processSkipCount = rs.getLong("processskipcount");
                filterCount = rs.getLong("filtercount");
                writeSkipCount = rs.getLong("writeSkipCount");
                startTS = rs.getTimestamp("startTime");
                endTS = rs.getTimestamp("endTime");
                // get the object based data
                Serializable persistentData = null;
                byte[] pDataBytes = rs.getBytes("persistentData");
                if (pDataBytes != null) {
                    objectIn = new TCCLObjectInputStream(new ByteArrayInputStream(pDataBytes));
                    persistentData = (Serializable) objectIn.readObject();
                }

                stepEx = new StepExecutionImpl(jobexecid, stepexecid);

                stepEx.setBatchStatus(BatchStatus.valueOf(batchstatus));
                stepEx.setExitStatus(exitstatus);
                stepEx.setStepName(stepname);
                stepEx.setReadCount(readCount);
                stepEx.setWriteCount(writeCount);
                stepEx.setCommitCount(commitCount);
                stepEx.setRollbackCount(rollbackCount);
                stepEx.setReadSkipCount(readSkipCount);
                stepEx.setProcessSkipCount(processSkipCount);
                stepEx.setFilterCount(filterCount);
                stepEx.setWriteSkipCount(writeSkipCount);
                stepEx.setStartTime(startTS);
                stepEx.setEndTime(endTS);
                stepEx.setPersistentUserData(persistentData);

                logger.fine("stepExecution BatchStatus: " + batchstatus + " StepName: " + stepname);
            }
        } catch (SQLException e) {
            throw new PersistenceException(e);
        } catch (IOException e) {
            throw new PersistenceException(e);
        } catch (ClassNotFoundException e) {
            throw new PersistenceException(e);
        } finally {
            cleanupConnection(conn, rs, statement);
        }

        return stepEx;
    }

	@Override
	public void updateBatchStatusOnly(long key, BatchStatus batchStatus, Date updatets) {
		Connection conn = null;
		PreparedStatement statement = null;
		ByteArrayOutputStream baos = null;
		ObjectOutputStream oout = null;
		byte[] b;

		try {
			conn = getConnection();
			statement = conn.prepareStatement("update executioninstancedata set batchstatus = ?, updatetime = ? where jobexecid = ?");
			statement.setString(1, batchStatus.name());
			statement.setTimestamp(2, getTimestamp(updatets));
			statement.setLong(3, key);
			statement.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
			throw new PersistenceException(e);
		} finally {
			if (baos != null) {
				try {
					baos.close();
				} catch (IOException e) {
					throw new PersistenceException(e);
				}
			}
			if (oout != null) {
				try {
					oout.close();
				} catch (IOException e) {
					throw new PersistenceException(e);
				}
			}
			cleanupConnection(conn, null, statement);
		}
	}

	@Override
	public void updateWithFinalExecutionStatusesAndTimestamps(long key,
			BatchStatus batchStatus, String exitStatus, Date updatets) {
		// TODO Auto-generated method stub
		Connection conn = null;
		PreparedStatement statement = null;
		ByteArrayOutputStream baos = null;
		ObjectOutputStream oout = null;
		byte[] b;

		try {
			conn = getConnection();
			statement = conn.prepareStatement("update executioninstancedata set batchstatus = ?, exitstatus = ?, endtime = ?, updatetime = ? where jobexecid = ?");

			statement.setString(1, batchStatus.name());
			statement.setString(2, exitStatus);
			Timestamp updateTimeStamp = getTimestamp(updatets);
			statement.setTimestamp(3, updateTimeStamp);
			statement.setTimestamp(4, updateTimeStamp);
			statement.setLong(5, key);

			statement.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
			throw new PersistenceException(e);
		} finally {
			if (baos != null) {
				try {
					baos.close();
				} catch (IOException e) {
					throw new PersistenceException(e);
				}
			}
			if (oout != null) {
				try {
					oout.close();
				} catch (IOException e) {
					throw new PersistenceException(e);
				}
			}
			cleanupConnection(conn, null, statement);
		}

	}

	@Override
	public void markJobStarted(long key, Date startTS) {

		Connection conn = null;
		PreparedStatement statement = null;
		ByteArrayOutputStream baos = null;
		ObjectOutputStream oout = null;

		try {
			conn = getConnection();
			statement = conn.prepareStatement("update executioninstancedata set batchstatus = ?, starttime = ?, updatetime = ? where jobexecid = ?");

			statement.setString(1, BatchStatus.STARTED.name());
			Timestamp startTimestamp = getTimestamp(startTS);
			statement.setTimestamp(2, startTimestamp);
			statement.setTimestamp(3, startTimestamp);
			statement.setLong(4, key);

			statement.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
			throw new PersistenceException(e);
		} finally {
			if (baos != null) {
				try {
					baos.close();
				} catch (IOException e) {
					throw new PersistenceException(e);
				}
			}
			if (oout != null) {
				try {
					oout.close();
				} catch (IOException e) {
					throw new PersistenceException(e);
				}
			}
			cleanupConnection(conn, null, statement);
		}
	}


	@Override
	public JobExecution jobOperatorGetJobExecution(long jobExecutionId) {
		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;
		JobExecution jobEx = null;
		ObjectInputStream objectIn = null;

		try {
			conn = getConnection();
			statement = conn.prepareStatement("select A.jobexecid, A.createtime, A.starttime, A.endtime, A.updatetime, A.parameters, A.jobinstanceid, A.batchstatus, A.exitstatus, B.name from executioninstancedata A inner join jobinstancedata B on A.jobinstanceid = B.jobinstanceid where jobexecid = ?"); 
			statement.setLong(1, jobExecutionId);
			rs = statement.executeQuery();

			jobEx = (rs.next()) ?  readJobExecutionRecord(rs) : null;
		} catch (SQLException e) {
			throw new PersistenceException(e);
		} catch (IOException e) {
			throw new PersistenceException(e);
		} catch (ClassNotFoundException e) {
			throw new PersistenceException(e);
		} finally {
			if (objectIn != null) {
				try {
					objectIn.close();
				} catch (IOException e) {
					throw new PersistenceException(e);
				}
			}
			cleanupConnection(conn, rs, statement);
		}
		return jobEx;
	}

	@Override
	public List<JobExecution> jobOperatorGetJobExecutions(long jobInstanceId) {
		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;
		List<JobExecution> data = new ArrayList<JobExecution>();
		ObjectInputStream objectIn = null;

		try {
			conn = getConnection();
			statement = conn.prepareStatement("select A.jobexecid, A.jobinstanceid, A.createtime, A.starttime, A.endtime, A.updatetime, A.parameters, A.batchstatus, A.exitstatus, B.name from executioninstancedata A inner join jobinstancedata B ON A.jobinstanceid = B.jobinstanceid where A.jobinstanceid = ?"); 
			statement.setLong(1, jobInstanceId);
			rs = statement.executeQuery();
			while (rs.next()) {
				data.add(readJobExecutionRecord(rs));
			}
		} catch (SQLException e) {
			throw new PersistenceException(e);
		} catch (IOException e) {
			throw new PersistenceException(e);
		} catch (ClassNotFoundException e) {
			throw new PersistenceException(e);
		} finally {
			if (objectIn != null) {
				try {
					objectIn.close();
				} catch (IOException e) {
					throw new PersistenceException(e);
				}
			}
			cleanupConnection(conn, rs, statement);
		}
		return data;
	}

	private JobExecution readJobExecutionRecord(ResultSet rs) throws SQLException, IOException, ClassNotFoundException {
		if (rs == null) {
			return null;
		}
			
	    JobOperatorJobExecution retMe = 
	    		new JobOperatorJobExecution(rs.getLong("jobexecid"), rs.getLong("jobinstanceid"));

	    retMe.setCreateTime(rs.getTimestamp("createtime"));
	    retMe.setStartTime(rs.getTimestamp("starttime"));
	    retMe.setEndTime(rs.getTimestamp("endtime"));
	    retMe.setLastUpdateTime(rs.getTimestamp("updatetime"));

	    retMe.setJobParameters((Properties)deserializeObject(rs.getBytes("parameters")));

	    retMe.setBatchStatus(rs.getString("batchstatus"));
	    retMe.setExitStatus(rs.getString("exitstatus"));
	    retMe.setJobName(rs.getString("name"));
	    
	    return retMe;
	}

	@Override
	public Set<Long> jobOperatorGetRunningExecutions(String jobName){
		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;
		Set<Long> executionIds = new HashSet<Long>();

		try {
			conn = getConnection();
			statement = conn.prepareStatement("SELECT A.jobexecid FROM executioninstancedata A INNER JOIN jobinstancedata B ON A.jobinstanceid = B.jobinstanceid WHERE A.batchstatus IN (?,?,?) AND B.name = ?"); 
			statement.setString(1, BatchStatus.STARTED.name());
			statement.setString(2, BatchStatus.STARTING.name());
			statement.setString(3, BatchStatus.STOPPING.name());
			statement.setString(4, jobName);
			rs = statement.executeQuery();
			while (rs.next()) {
				executionIds.add(rs.getLong("jobexecid"));
			}

		} catch (SQLException e) {
			throw new PersistenceException(e);
		} finally {
			cleanupConnection(conn, rs, statement);
		}
		return executionIds;		
	}

	@Override
	public String getJobCurrentTag(long jobInstanceId) {
		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;
		String apptag = null;

		try {
			conn = getConnection();
			statement = conn.prepareStatement(SELECT_JOBINSTANCEDATA_APPTAG); 
			statement.setLong(1, jobInstanceId);
			rs = statement.executeQuery();
			while (rs.next()) {
				apptag = rs.getString(APPTAG);
			}

		} catch (SQLException e) {
			throw new PersistenceException(e);
		} finally {
			cleanupConnection(conn, rs, statement);
		}

		return apptag;
	}

	@Override
	public void purge(String apptag) {

		logger.entering(CLASSNAME, "purge", apptag);
		String deleteJobs = "DELETE FROM jobinstancedata WHERE apptag = ?";
		String deleteJobExecutions = "DELETE FROM executioninstancedata " 
				+ "WHERE jobexecid IN (" 
				+ "SELECT B.jobexecid FROM jobinstancedata A INNER JOIN executioninstancedata B " 
				+ "ON A.jobinstanceid = B.jobinstanceid " 
				+ "WHERE A.apptag = ?)";
		String deleteStepExecutions = "DELETE FROM stepexecutioninstancedata " 
				+ "WHERE stepexecid IN ("
				+ "SELECT C.stepexecid FROM jobinstancedata A INNER JOIN executioninstancedata B "
				+ "ON A.jobinstanceid = B.jobinstanceid INNER JOIN stepexecutioninstancedata C "
				+ "ON B.jobexecid = C.jobexecid "
				+ "WHERE A.apptag = ?)";

		Connection conn = null;
		PreparedStatement statement = null;
		try {
			conn = getConnection();
			statement = conn.prepareStatement(deleteStepExecutions);
			statement.setString(1, apptag);
			statement.executeUpdate();

			statement = conn.prepareStatement(deleteJobExecutions);
			statement.setString(1, apptag);
			statement.executeUpdate();

			statement = conn.prepareStatement(deleteJobs);
			statement.setString(1, apptag);
			statement.executeUpdate();

		} catch (SQLException e) {
			throw new PersistenceException(e);
		} finally {

			cleanupConnection(conn, null, statement);
		}
		logger.exiting(CLASSNAME, "purge");

	}

	@Override
	public JobStatus getJobStatusFromExecution(long executionId) {

		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;
		JobStatus retVal = null;

		try {
			conn = getConnection();
			statement = conn.prepareStatement("select A.obj from jobstatus A inner join " + 
					"executioninstancedata B on A.id = B.jobinstanceid where B.jobexecid = ?");
			statement.setLong(1, executionId);
			rs = statement.executeQuery();
			byte[] buf = null;
			if (rs.next()) {
				buf = rs.getBytes("obj");
			}
			retVal = (JobStatus)deserializeObject(buf);
		} catch (Exception e) {
			throw new PersistenceException(e);
		} finally {
			cleanupConnection(conn, rs, statement);
		}
		logger.exiting(CLASSNAME, "executeQuery");
		return retVal;	
	}

	public long getJobInstanceIdByExecutionId(long executionId) throws NoSuchJobExecutionException {
		long instanceId= 0;

		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;

		try {
			conn = getConnection();
			statement = conn.prepareStatement("select jobinstanceid from executioninstancedata where jobexecid = ?");
			statement.setObject(1, executionId);
			rs = statement.executeQuery();
			if (rs.next()) {
				instanceId = rs.getLong("jobinstanceid");
			} else {
				String msg = "Did not find job instance associated with executionID =" + executionId;
				logger.fine(msg);
				throw new NoSuchJobExecutionException(msg);
			}
		} catch (SQLException e) {
			throw new PersistenceException(e);
		} finally {
			cleanupConnection(conn, rs, statement);
		}

		return instanceId;
	}

	/**
	 * This method is used to serialized an object saved into a table BLOB field.
	 *  
	 * @param theObject the object to be serialized
	 * @return a object byte array
	 * @throws IOException
	 */
	private byte[] serializeObject(Serializable theObject) throws IOException {

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ObjectOutputStream oout = new ObjectOutputStream(baos);
		oout.writeObject(theObject);
		byte[] data = baos.toByteArray();
		baos.close();
		oout.close();

		return data;
	}

	/**
	 * This method is used to de-serialized a table BLOB field to its original object form.
	 * 
	 * @param buffer the byte array save a BLOB
	 * @return the object saved as byte array
	 * @throws IOException
	 * @throws ClassNotFoundException
	 */
	private Serializable deserializeObject(byte[] buffer) throws IOException, ClassNotFoundException {

		Serializable theObject = null;
		ObjectInputStream objectIn = null;

		if (buffer != null) {
			objectIn = new ObjectInputStream(new ByteArrayInputStream(buffer));
			theObject = (Serializable)objectIn.readObject();
			objectIn.close();
		}
		return theObject;
	}

	/* (non-Javadoc)
	 * @see com.ibm.jbatch.container.services.IPersistenceManagerService#createJobInstance(java.lang.String, java.lang.String, java.lang.String, java.util.Properties)
	 */
	@Override
	public JobInstance createSubJobInstance(String name, String apptag) {
		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;
		JobInstanceImpl jobInstance = null;

		try {
			conn = getConnection();
			statement = conn.prepareStatement("INSERT INTO jobinstancedata (name, apptag) VALUES(?, ?)", new String[] { "JOBINSTANCEID" } );
			statement.setString(1, name);
			statement.setString(2, apptag); 
			statement.executeUpdate();
			rs = statement.getGeneratedKeys();
			if(rs.next()) {
				long jobInstanceID = rs.getLong(1);
				jobInstance = new JobInstanceImpl(jobInstanceID);
				jobInstance.setJobName(name);
			}
		} catch (SQLException e) {
			throw new PersistenceException(e);
		} finally {
			cleanupConnection(conn, rs, statement);
		}
		return jobInstance;
	}

	/* (non-Javadoc)
	 * @see com.ibm.jbatch.container.services.IPersistenceManagerService#createJobInstance(java.lang.String, java.lang.String, java.lang.String, java.util.Properties)
	 */
	@Override
	public JobInstance createJobInstance(String name, String apptag, String jobXml) {
		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;
		JobInstanceImpl jobInstance = null;

		try {
			conn = getConnection();
			statement = conn.prepareStatement("INSERT INTO jobinstancedata (name, apptag) VALUES(?, ?)", new String[] { "JOBINSTANCEID" } );
			statement.setString(1, name);
			statement.setString(2, apptag);
			statement.executeUpdate();
			rs = statement.getGeneratedKeys();
			if(rs.next()) {
				long jobInstanceID = rs.getLong(1);
				jobInstance = new JobInstanceImpl(jobInstanceID, jobXml);
				jobInstance.setJobName(name);
			}
		} catch (SQLException e) {
			throw new PersistenceException(e);
		} finally {
			cleanupConnection(conn, rs, statement);
		}
		return jobInstance;
	}


	@Override
	public long createJobExecution(JobInstance jobInstance, Properties jobParameters, BatchStatus batchStatus, Date createTime) {
		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;
		long newJobExecutionId = 0L;
		try {
			conn = getConnection();
			statement = conn.prepareStatement("INSERT INTO executioninstancedata (jobinstanceid, createtime, updatetime, batchstatus, parameters) VALUES(?, ?, ?, ?, ?)", new String[] { "JOBEXECID" });
			statement.setLong(1, jobInstance.getInstanceId());
			Timestamp createTimeTS = getTimestamp(createTime);
			statement.setTimestamp(2, createTimeTS);
			statement.setTimestamp(3, createTimeTS);
			statement.setString(4, batchStatus.name());
			statement.setObject(5, serializeObject(jobParameters));
			statement.executeUpdate();
			rs = statement.getGeneratedKeys();
			if(rs.next()) {
				newJobExecutionId = rs.getLong(1);
			}
		} catch (SQLException e) {
			throw new PersistenceException(e);
		} catch (IOException e) {
			throw new PersistenceException(e);
		} finally {
			cleanupConnection(conn, rs, statement);
		}
		return newJobExecutionId;
	}

	/*
	@Override
	public RuntimeFlowInSplitExecution createFlowInSplitExecution(JobInstance jobInstance, BatchStatus batchStatus) {
		Timestamp now = new Timestamp(System.currentTimeMillis());
		long newExecutionId = createRuntimeJobExecutionEntry(jobInstance, null, batchStatus, now);
		RuntimeFlowInSplitExecution flowExecution = new RuntimeFlowInSplitExecution(jobInstance, newExecutionId);
		flowExecution.setBatchStatus(batchStatus.name());
		flowExecution.setCreateTime(now);
		flowExecution.setLastUpdateTime(now);
		return flowExecution;
	}
	*/

	/* (non-Javadoc)
	 * @see com.ibm.jbatch.container.services.IPersistenceManagerService#createStepExecution(long, com.ibm.jbatch.container.context.impl.StepContextImpl)
	 */
	@Override
	public long createStepExecution(long rootJobExecId, StepContext stepContext) {
		String batchStatus = stepContext.getBatchStatus() == null ? BatchStatus.STARTING.name() : stepContext.getBatchStatus().name();
		String exitStatus = stepContext.getExitStatus();
		String stepName = stepContext.getStepName();
		if (logger.isLoggable(Level.FINE)) {
			logger.fine("batchStatus: " + batchStatus + " | stepName: " + stepName);
		}
		long readCount = 0;
		long writeCount = 0;
		long commitCount = 0;
		long rollbackCount = 0;
		long readSkipCount = 0;
		long processSkipCount = 0;
		long filterCount = 0;
		long writeSkipCount = 0;
		long now = System.currentTimeMillis();
		Timestamp startTime = new Timestamp(now);
		Timestamp endTime = null;

		Metric[] metrics = stepContext.getMetrics();
		for (int i = 0; i < metrics.length; i++) {
			if (metrics[i].getType().equals(MetricImpl.MetricType.READ_COUNT)) {
				readCount = metrics[i].getValue();
			} else if (metrics[i].getType().equals(MetricImpl.MetricType.WRITE_COUNT)) {
				writeCount = metrics[i].getValue();	
			} else if (metrics[i].getType().equals(MetricImpl.MetricType.PROCESS_SKIP_COUNT)) {
				processSkipCount = metrics[i].getValue();	
			} else if (metrics[i].getType().equals(MetricImpl.MetricType.COMMIT_COUNT)) {
				commitCount = metrics[i].getValue();
			} else if (metrics[i].getType().equals(MetricImpl.MetricType.ROLLBACK_COUNT)) {
				rollbackCount = metrics[i].getValue();	
			} else if (metrics[i].getType().equals(MetricImpl.MetricType.READ_SKIP_COUNT)) {
				readSkipCount = metrics[i].getValue();
			} else if (metrics[i].getType().equals(MetricImpl.MetricType.FILTER_COUNT)) {
				filterCount = metrics[i].getValue();	
			} else if (metrics[i].getType().equals(MetricImpl.MetricType.WRITE_SKIP_COUNT)) {
				writeSkipCount = metrics[i].getValue();	
			}		
		}
		Serializable persistentData = stepContext.getPersistentUserData();

		long stepExecutionId = createStepExecution(rootJobExecId, batchStatus, exitStatus, stepName, readCount, 
				writeCount, commitCount, rollbackCount, readSkipCount, processSkipCount, filterCount, writeSkipCount, startTime,
				endTime, persistentData);

		return stepExecutionId;
	}


	private long createStepExecution(long rootJobExecId,  String batchStatus, String exitStatus, String stepName, long readCount, 
			long writeCount, long commitCount, long rollbackCount, long readSkipCount, long processSkipCount, long filterCount,
			long writeSkipCount, Timestamp startTime, Timestamp endTime, Serializable persistentData) {

		logger.entering(CLASSNAME, "createStepExecution", new Object[] {rootJobExecId, batchStatus, exitStatus==null ? "<null>" : exitStatus, stepName, readCount, 
				writeCount, commitCount, rollbackCount, readSkipCount, processSkipCount, filterCount, writeSkipCount, startTime == null ? "<null>" : startTime,
						endTime==null ? "<null>" :endTime , persistentData==null ? "<null>" : persistentData});

		long stepExecutionId = -1;

		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;
		StepExecutionImpl stepExecution = null;
		String query = "INSERT INTO stepexecutioninstancedata (jobexecid, batchstatus, exitstatus, stepname, readcount," 
				+ "writecount, commitcount, rollbackcount, readskipcount, processskipcount, filtercount, writeskipcount, starttime,"
				+ "endtime, persistentdata) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

		try {
			conn = getConnection();
			statement = conn.prepareStatement(query, new String[] { "STEPEXECID" });
			statement.setLong(1, rootJobExecId);
			statement.setString(2, batchStatus);
			statement.setString(3, exitStatus);
			statement.setString(4, stepName);
			statement.setLong(5, readCount);
			statement.setLong(6, writeCount);
			statement.setLong(7, commitCount);
			statement.setLong(8, rollbackCount);
			statement.setLong(9, readSkipCount);
			statement.setLong(10, processSkipCount);
			statement.setLong(11, filterCount);
			statement.setLong(12, writeSkipCount);
			statement.setTimestamp(13, startTime);
			statement.setTimestamp(14, endTime);
			statement.setObject(15, serializeObject(persistentData));

			statement.executeUpdate();
			rs = statement.getGeneratedKeys();
			if(rs.next()) {
				stepExecutionId = rs.getLong(1);
			} else {
				throw new PersistenceException("Failed to create step execution entry");
			}
		} catch (SQLException e) {
			throw new PersistenceException(e);
		} catch (IOException e) {
			throw new PersistenceException(e);
		} finally {
			cleanupConnection(conn, null, statement);
		}

		logger.exiting(CLASSNAME, "createStepExecution", "stepExecutionId = " + stepExecutionId);
		return stepExecutionId;
	}
		
	@Override
	public void updateStepExecutionOnEnd(long internalStepExecutionId, StepContext stepContext, Date endTime) {
		updateStepExecution(internalStepExecutionId, stepContext, endTime);
	}

	/* (non-Javadoc)
	 * @see com.ibm.jbatch.container.services.IPersistenceManagerService#updateStepExecution(com.ibm.jbatch.container.context.impl.StepContextImpl)
	 */
	@Override
	public void updateStepExecution(long internalStepExecutionId, StepContext stepContext) {
		updateStepExecution(internalStepExecutionId, stepContext, null);
	}

	private void updateStepExecution(long internalStepExecutionId, StepContext stepContext, Date endTime) {
		
		long readCount = 0;
		long writeCount = 0;
		long commitCount = 0;
		long rollbackCount = 0;
		long readSkipCount = 0;
		long processSkipCount = 0;
		long filterCount = 0;
		long writeSkipCount = 0;
		
		Metric[] metrics = stepContext.getMetrics();

		for (int i = 0; i < metrics.length; i++) {
			if (metrics[i].getType().equals(MetricImpl.MetricType.READ_COUNT)) {
				readCount = metrics[i].getValue();
			} else if (metrics[i].getType().equals(MetricImpl.MetricType.WRITE_COUNT)) {
				writeCount = metrics[i].getValue();	
			} else if (metrics[i].getType().equals(MetricImpl.MetricType.PROCESS_SKIP_COUNT)) {
				processSkipCount = metrics[i].getValue();	
			} else if (metrics[i].getType().equals(MetricImpl.MetricType.COMMIT_COUNT)) {
				commitCount = metrics[i].getValue();
			} else if (metrics[i].getType().equals(MetricImpl.MetricType.ROLLBACK_COUNT)) {
				rollbackCount = metrics[i].getValue();	
			} else if (metrics[i].getType().equals(MetricImpl.MetricType.READ_SKIP_COUNT)) {
				readSkipCount = metrics[i].getValue();
			} else if (metrics[i].getType().equals(MetricImpl.MetricType.FILTER_COUNT)) {
				filterCount = metrics[i].getValue();	
			} else if (metrics[i].getType().equals(MetricImpl.MetricType.WRITE_SKIP_COUNT)) {
				writeSkipCount = metrics[i].getValue();	
			}
		}
		
		updateStepExecutionWithMetrics(internalStepExecutionId, stepContext,  readCount, 
				writeCount, commitCount, rollbackCount, readSkipCount, processSkipCount, filterCount,
				writeSkipCount, endTime);
	}
	
	/**
	 * Obviously would be nice if the code writing this special format were in the same place as this
	 * code reading it.
	 * 
	 * Assumes format like:
	 * 
	 * JOBINSTANCEDATA
	 * (jobinstanceid name, ...)
	 * 
	 * 1197,"partitionMetrics","NOTSET"
	 * 1198,":1197:step1:0","NOTSET"
	 * 1199,":1197:step1:1","NOTSET"
	 * 1200,":1197:step2:0","NOTSET"
	 * 
	 * @param rootJobExecutionId JobExecution id of the top-level job
	 * @param stepName Step name of the top-level stepName
	 */
	private String getPartitionLevelJobInstanceWildCard(long rootJobExecutionId, String stepName) {
		
		long jobInstanceId = getJobInstanceIdByExecutionId(rootJobExecutionId);
		
		StringBuilder sb = new StringBuilder(":");
		sb.append(Long.toString(jobInstanceId));
		sb.append(":");
		sb.append(stepName);
		sb.append(":%");
		
		return sb.toString();
	}

	@Override
	public void updateWithFinalPartitionAggregateStepExecution(long internalStepExecutionId, StepContext stepContext, long rootJobExecutionId, Date endTS) {

		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;
		
		long readCount =0;
		long writeCount = 0;
		long commitCount = 0;
		long rollbackCount = 0;
		long readSkipCount = 0;
		long processSkipCount = 0;
		long filterCount = 0;
		long writeSkipCount = 0;
		
		try {
			conn = getConnection();
			statement = conn.prepareStatement("select SUM(STEPEX.readcount) readcount, SUM(STEPEX.writecount) writecount, SUM(STEPEX.commitcount) commitcount,  SUM(STEPEX.rollbackcount) rollbackcount," +
					" SUM(STEPEX.readskipcount) readskipcount, SUM(STEPEX.processskipcount) processskipcount, SUM(STEPEX.filtercount) filtercount, SUM(STEPEX.writeSkipCount) writeSkipCount" + 
					" from stepexecutioninstancedata STEPEX inner join executioninstancedata JOBEX" + 
					" on STEPEX.jobexecid = JOBEX.jobexecid" +
					" where JOBEX.jobinstanceid IN" +
					" (select jobinstanceid from JOBINSTANCEDATA where name like ?)");

			statement.setString(1, getPartitionLevelJobInstanceWildCard(rootJobExecutionId, stepContext.getStepName()));
			rs = statement.executeQuery();
			if(rs.next()) {
				readCount = rs.getLong("readcount");
				writeCount = rs.getLong("writecount");
				commitCount = rs.getLong("commitcount");
				rollbackCount = rs.getLong("rollbackcount");
				readSkipCount = rs.getLong("readskipcount");
				processSkipCount = rs.getLong("processskipcount");
				filterCount = rs.getLong("filtercount");
				writeSkipCount = rs.getLong("writeSkipCount");
			}
		} catch (SQLException e) {
			throw new PersistenceException(e);
		} finally {
			cleanupConnection(conn, rs, statement);
		}

		updateStepExecutionWithMetrics(internalStepExecutionId, stepContext,  readCount, 
				writeCount, commitCount, rollbackCount, readSkipCount, processSkipCount, filterCount,
				writeSkipCount, endTS);
	}

	private void updateStepExecutionWithMetrics(long stepExecutionId, StepContext stepContext, long readCount, 
			long writeCount, long commitCount, long rollbackCount, long readSkipCount, long processSkipCount, long filterCount,
			long writeSkipCount, Date endTime) {

		String batchStatus = stepContext.getBatchStatus() == null ? BatchStatus.STARTING.name() : stepContext.getBatchStatus().name();
		String exitStatus = stepContext.getExitStatus();
		String stepName = stepContext.getStepName();
		if (logger.isLoggable(Level.FINE)) {
			logger.fine("batchStatus: " + batchStatus + " | stepName: " + stepName + " | stepExecID: " + stepContext.getStepExecutionId());
		}

		Serializable persistentData = stepContext.getPersistentUserData();

		if (logger.isLoggable(Level.FINER)) {
			logger.log(Level.FINER, "About to update StepExecution with: ", new Object[] {stepExecutionId, batchStatus, exitStatus==null ? "<null>" : exitStatus, stepName, readCount, 
				writeCount, commitCount, rollbackCount, readSkipCount, processSkipCount, filterCount, writeSkipCount, 
						endTime==null ? "<null>" : endTime, persistentData==null ? "<null>" : persistentData});
		}

		Connection conn = null;
		PreparedStatement statement = null;
		String query = "UPDATE stepexecutioninstancedata SET batchstatus = ?, exitstatus = ?, stepname = ?,  readcount = ?," 
				+ "writecount = ?, commitcount = ?, rollbackcount = ?, readskipcount = ?, processskipcount = ?, filtercount = ?, writeskipcount = ?,"
				+ " endtime = ?, persistentdata = ? WHERE stepexecid = ?";

		try {
			conn = getConnection();
			statement = conn.prepareStatement(query);
			statement.setString(1, batchStatus);
			statement.setString(2, exitStatus);
			statement.setString(3, stepName);
			statement.setLong(4, readCount);
			statement.setLong(5, writeCount);
			statement.setLong(6, commitCount);
			statement.setLong(7, rollbackCount);
			statement.setLong(8, readSkipCount);
			statement.setLong(9, processSkipCount);
			statement.setLong(10, filterCount);
			statement.setLong(11, writeSkipCount);
			statement.setTimestamp(12, getTimestamp(endTime));
			statement.setObject(13, serializeObject(persistentData));
			statement.setLong(14, stepExecutionId); 
			statement.executeUpdate();
		} catch (SQLException e) {
			throw new PersistenceException(e);
		} catch (IOException e) {
			throw new PersistenceException(e);
		} finally {
			cleanupConnection(conn, null, statement);
		}
	}

	/* (non-Javadoc)
	 * @see com.ibm.jbatch.container.services.IPersistenceManagerService#createJobStatus(long)
	 */
	@Override
	public IJobStatus createJobStatus(long jobInstanceId) {
		logger.entering(CLASSNAME, "createJobStatus", jobInstanceId);
		Connection conn = null;
		PreparedStatement statement = null;
		JobStatus jobStatus = new JobStatus(jobInstanceId);
		try {
			conn = getConnection();
			statement = conn.prepareStatement("INSERT INTO jobstatus (id, obj) VALUES(?, ?)");
			statement.setLong(1, jobInstanceId);
			statement.setBytes(2, serializeObject(jobStatus));
			statement.executeUpdate();

		} catch (SQLException e) {
			throw new PersistenceException(e);
		} catch (IOException e) {
			throw new PersistenceException(e);
		} finally {
			cleanupConnection(conn, null, statement);
		}
		logger.exiting(CLASSNAME, "createJobStatus");
		return jobStatus;
	}

	/* (non-Javadoc)
	 * @see com.ibm.jbatch.container.services.IPersistenceManagerService#getJobStatus(long)
	 */
	@Override
	public IJobStatus getJobStatus(long instanceId) {
		logger.entering(CLASSNAME, "getJobStatus", instanceId);
		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;
		String query = "SELECT obj FROM jobstatus WHERE id = ?";
		JobStatus jobStatus = null;

		try {
			conn = getConnection();
			statement = conn.prepareStatement(query);
			statement.setLong(1, instanceId);
			rs = statement.executeQuery();
			if(rs.next()) {
				jobStatus = (JobStatus)deserializeObject(rs.getBytes(1));
			}
		} catch (SQLException e) {
			throw new PersistenceException(e);
		} catch (IOException e) {
			throw new PersistenceException(e);
		} catch (ClassNotFoundException e) {
			throw new PersistenceException(e);
		} finally {
			cleanupConnection(conn, rs, statement);
		}
		logger.exiting(CLASSNAME, "getJobStatus", jobStatus);
		return jobStatus;
	}

	/* (non-Javadoc)
	 * @see com.ibm.jbatch.container.services.IPersistenceManagerService#updateJobStatus(long, com.ibm.jbatch.container.status.JobStatus)
	 */
	@Override
	public void updateJobStatus(long instanceId, IJobStatus jobStatus) {
		logger.entering(CLASSNAME, "updateJobStatus", new Object[] {instanceId, jobStatus});
		if (logger.isLoggable(Level.FINE)) {
			logger.fine("Updating Job Status to: " + jobStatus.getBatchStatus());
		}
		Connection conn = null;
		PreparedStatement statement = null;
		try {
			conn = getConnection();
			statement = conn.prepareStatement("UPDATE jobstatus SET obj = ? WHERE id = ?");
			// Convert to our own persistence-ready format
			JobStatus jobStatusImpl = new JobStatus(jobStatus);
			statement.setBytes(1, serializeObject(jobStatusImpl));
			statement.setLong(2, instanceId);
			statement.executeUpdate();
		} catch (SQLException e) {
			throw new PersistenceException(e);
		} catch (IOException e) {
			throw new PersistenceException(e);
		} finally {
			cleanupConnection(conn, null, statement);
		}
		logger.exiting(CLASSNAME, "updateJobStatus");
	}	

	/* (non-Javadoc)
	 * @see com.ibm.jbatch.container.services.IPersistenceManagerService#createStepStatus(long)
	 */
	@Override
	public StepStatus createStepStatus(long stepExecId) {
		logger.entering(CLASSNAME, "createStepStatus", stepExecId);
		Connection conn = null;
		PreparedStatement statement = null;
		StepStatus stepStatus = new StepStatus(stepExecId);
		try {
			conn = getConnection();
			statement = conn.prepareStatement("INSERT INTO stepstatus (id, obj) VALUES(?, ?)");
			statement.setLong(1, stepExecId);
			statement.setBytes(2, serializeObject(stepStatus));
			statement.executeUpdate();
		} catch (SQLException e) {
			throw new PersistenceException(e);
		} catch (IOException e) {
			throw new PersistenceException(e);
		} finally {
			cleanupConnection(conn, null, statement);
		}
		logger.exiting(CLASSNAME, "createStepStatus");
		return stepStatus;
	}

	/* (non-Javadoc)
	 * @see com.ibm.jbatch.container.services.IPersistenceManagerService#getStepStatus(long, java.lang.String)
	 */
	@Override
	public IStepStatus getStepStatus(long instanceId, String stepName) {
		logger.entering(CLASSNAME, "getStepStatus", new Object[] {instanceId, stepName});
		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;
		String query = "SELECT obj FROM stepstatus WHERE id IN ("
				+ "SELECT B.stepexecid FROM executioninstancedata A INNER JOIN stepexecutioninstancedata B ON A.jobexecid = B.jobexecid " 
				+ "WHERE A.jobinstanceid = ? and B.stepname = ?)";
		StepStatus stepStatus = null;

		try {
			conn = getConnection();
			statement = conn.prepareStatement(query);
			statement.setLong(1, instanceId);
			statement.setString(2, stepName);
			rs = statement.executeQuery();
			if(rs.next()) {
				// Deserialize into our own format.
				stepStatus = (StepStatus)deserializeObject(rs.getBytes(1));
			}
		} catch (SQLException e) {
			throw new PersistenceException(e);
		} catch (IOException e) {
			throw new PersistenceException(e);
		} catch (ClassNotFoundException e) {
			throw new PersistenceException(e);
		} finally {
			cleanupConnection(conn, rs, statement);
		}
		logger.exiting(CLASSNAME, "getStepStatus", stepStatus==null ? "<null>" : stepStatus);
		return stepStatus;
	}

	/* (non-Javadoc)
	 * @see com.ibm.jbatch.container.services.IPersistenceManagerService#updateStepStatus(long, com.ibm.jbatch.container.status.StepStatus)
	 */
	@Override
	public void updateStepStatus(long stepExecutionId, IStepStatus stepStatus) {
		logger.entering(CLASSNAME, "updateStepStatus", new Object[] {stepExecutionId, stepStatus});

		if (logger.isLoggable(Level.FINE)) {
			logger.fine("Updating StepStatus to: " + stepStatus.getBatchStatus());
		}
		Connection conn = null;
		PreparedStatement statement = null;
		try {
			conn = getConnection();
			statement = conn.prepareStatement("UPDATE stepstatus SET obj = ? WHERE id = ?");
			// Convert to our own persistence-ready format
			StepStatus stepStatusImpl = new StepStatus(stepStatus);
			statement.setBytes(1, serializeObject(stepStatusImpl));
			statement.setLong(2, stepExecutionId);
			statement.executeUpdate();
		} catch (SQLException e) {
			throw new PersistenceException(e);
		} catch (IOException e) {
			throw new PersistenceException(e);
		} finally {
			cleanupConnection(conn, null, statement);
		}
		logger.exiting(CLASSNAME, "updateStepStatus");
	}

	/* (non-Javadoc)
	 * @see com.ibm.jbatch.container.services.IPersistenceManagerService#getTagName(long)
	 */
	@Override
	public String getTagName(long jobExecutionId) {
		logger.entering(CLASSNAME, "getTagName", jobExecutionId);
		String apptag = null;
		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;
		String query = "SELECT A.apptag FROM jobinstancedata A INNER JOIN executioninstancedata B ON A.jobinstanceid = B.jobinstanceid"
				+ " WHERE B.jobexecid = ?";
		try {
			conn = getConnection();
			statement = conn.prepareStatement(query);
			statement.setLong(1, jobExecutionId);
			rs = statement.executeQuery();
			if(rs.next()) {
				apptag = rs.getString(1);
			}
		} catch (SQLException e) {
			throw new PersistenceException(e);
		} finally {
			cleanupConnection(conn, rs, statement);
		}
		logger.exiting(CLASSNAME, "getTagName");
		return apptag;
	}

	@Override
	public long getMostRecentExecutionId(long jobInstanceId) {
		logger.entering(CLASSNAME, "getMostRecentExecutionId", jobInstanceId);
		long mostRecentId = -1;
		Connection conn = null;
		PreparedStatement statement = null;
		ResultSet rs = null;
		String query = "SELECT jobexecid FROM executioninstancedata WHERE jobinstanceid = ? ORDER BY createtime DESC";

		try {
			conn = getConnection();
			statement = conn.prepareStatement(query);
			statement.setLong(1, jobInstanceId);
			rs = statement.executeQuery();
			if(rs.next()) {
				mostRecentId = rs.getLong(1);
			}
		} catch (SQLException e) {
			throw new PersistenceException(e);
		} finally {
			cleanupConnection(conn, rs, statement);
		}
		logger.exiting(CLASSNAME, "getMostRecentExecutionId");
		return mostRecentId;
	}

	private Timestamp getTimestamp(Date date) {
		if (date == null) {
			return null;
		} else {
			return new Timestamp(date.getTime());
		}
	}
	
	@Override
	public void shutdown() throws BatchContainerServiceException {
		// TODO Auto-generated method stub

	}


}
