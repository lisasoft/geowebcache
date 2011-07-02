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
 * @author Arne Kepp / The Open Planning Project 2009
 *  
 */
package org.geowebcache.storage.jdbc.taskstore;

import static org.geowebcache.storage.jdbc.JDBCUtils.close;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.geowebcache.grid.BoundingBox;
import org.geowebcache.grid.SRS;
import org.geowebcache.seed.GWCTask.PRIORITY;
import org.geowebcache.seed.GWCTask.STATE;
import org.geowebcache.seed.GWCTask.TYPE;
import org.geowebcache.storage.DefaultStorageFinder;
import org.geowebcache.storage.StorageException;
import org.geowebcache.storage.TaskObject;
import org.h2.jdbcx.JdbcConnectionPool;

/**
 * Wrapper class for the JDBC object, used by JDBCTaskBackend
 * 
 * Performs mundane tasks such as
 * <ul>
 * <li>initialize the database</li>
 * <li>create tables</li>
 * <li>create iterators</li>
 * </ul>
 * 
 */
class JDBCTaskWrapper {
    private static Log log = LogFactory
            .getLog(org.geowebcache.storage.jdbc.taskstore.JDBCTaskWrapper.class);

    /** Database version, for automatic updates */
    static int DB_VERSION = 120;

    /** Connection information */
    final String jdbcString;

    final String username;

    final String password;

    final String driverClass;

    /**
     * H2 would close all the file handles to the database once you closed the last connection.
     * Reopening from scratch can take almost a second, so keeping one connection around in the
     * background ensures that this doesn't happen.
     * <p>
     * It _really_ makes a difference if connection pooling is disabled!
     * </p>
     */
    private Connection persistentConnection;

    boolean closing = false;

    private boolean useConnectionPooling;

    private int maxConnections;

    private JdbcConnectionPool connPool;

    protected JDBCTaskWrapper(String driverClass, String jdbcString, String username,
            String password, boolean useConnectionPooling, int maxConnections)
            throws StorageException, SQLException {
        this.jdbcString = jdbcString;
        this.username = username;
        this.password = password;
        this.driverClass = driverClass;
        this.useConnectionPooling = useConnectionPooling;
        this.maxConnections = maxConnections;
        try {
            Class.forName(driverClass);
        } catch (ClassNotFoundException cnfe) {
            throw new StorageException("Class not found: " + cnfe.getMessage());
        }

        if (!useConnectionPooling) {
            persistentConnection = getConnection();
        }

        checkTables();
    }

    public JDBCTaskWrapper(DefaultStorageFinder defStoreFind, boolean useConnectionPooling,
            int maxConnections) throws StorageException, SQLException {
        String envStrUsername;
        String envStrPassword;
        String envStrJdbcUrl;
        String envStrDriver;
        envStrUsername = defStoreFind.findEnvVar(DefaultStorageFinder.GWC_TASKSTORE_USERNAME);
        envStrPassword = defStoreFind.findEnvVar(DefaultStorageFinder.GWC_TASKSTORE_PASSWORD);
        envStrJdbcUrl = defStoreFind.findEnvVar(DefaultStorageFinder.GWC_TASKSTORE_JDBC_URL);
        envStrDriver = defStoreFind.findEnvVar(DefaultStorageFinder.GWC_TASKSTORE_DRIVER_CLASS);
        this.useConnectionPooling = useConnectionPooling;
        this.maxConnections = maxConnections;
        if (envStrUsername != null) {
            username = envStrUsername;
        } else {
            this.username = "sa";
        }

        if (envStrPassword != null) {
            this.password = envStrPassword;
        } else {
            this.password = "";
        }

        if (envStrDriver != null) {
            this.driverClass = envStrDriver;
        } else {
            this.driverClass = "org.h2.Driver";
        }

        if (envStrJdbcUrl != null) {
            this.jdbcString = envStrJdbcUrl;
        } else {
            String path = defStoreFind.getDefaultPath() + File.separator + "task_jdbc_h2";
            File dir = new File(path);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new StorageException("Unable to create " + dir.getAbsolutePath()
                        + " for H2 database.");
            }
            this.jdbcString = "jdbc:h2:file:" + path + File.separator + "gwc_taskstore"
                    + ";TRACE_LEVEL_FILE=0;AUTO_SERVER=TRUE";
        }

        try {
            Class.forName(driverClass);
        } catch (ClassNotFoundException cnfe) {
            throw new StorageException("Class not found: " + cnfe.getMessage());
        }

        if (!useConnectionPooling) {
            persistentConnection = getConnection();
        }

        checkTables();
    }

    protected Connection getConnection() throws SQLException {
        if (closing) {
            throw new IllegalStateException(getClass().getSimpleName() + " is being shut down");
        }
        Connection conn;
        if (useConnectionPooling) {
            if (connPool == null) {
                connPool = JdbcConnectionPool.create(jdbcString, username, password == null ? ""
                        : password);
                connPool.setMaxConnections(maxConnections);
            }
            conn = connPool.getConnection();
        } else {
            conn = DriverManager.getConnection(jdbcString, username, password);
        }
        conn.setAutoCommit(true);
        return conn;
    }

    private void checkTables() throws StorageException, SQLException {
        final Connection conn = getConnection();
        try {

            /** Easy ones */
            checkTasksTable(conn);
            
//            condCreate(conn, "LOGS",
//                    "ID BIGINT AUTO_INCREMENT PRIMARY KEY, VALUE VARCHAR(126) UNIQUE", "VALUE",
//                    null);

            int fromVersion = getDbVersion(conn);
            log.info("TaskStore database is version " + fromVersion);

            if (fromVersion != DB_VERSION) {
                if (fromVersion < DB_VERSION) {
                    runDbUpgrade(conn, fromVersion);
                } else {
                    log.error("Taskstore database is newer than the running version of GWC. Proceeding with undefined results.");
                }
            }
        } finally {
            close(conn);
        }
    }

    /**
     * Checks / creates the "variables" table and verifies that the db_version variable is.
     * 
     * @param conn
     * @throws SQLException
     * @throws StorageException
     *             if the database is newer than the software
     */
    protected int getDbVersion(Connection conn) throws SQLException, StorageException {

        condCreate(conn, "VARIABLES", "KEY VARCHAR(32), VALUE VARCHAR(128)", "KEY", null);

        Statement st = null;
        ResultSet rs = null;

        try {
            st = conn.createStatement();
            rs = st.executeQuery("SELECT VALUE FROM VARIABLES WHERE KEY LIKE 'db_version'");

            if (rs.first()) {
                // Check what version of the database this is
                String db_versionStr = rs.getString("value");
                int cur_db_version = Integer.parseInt(db_versionStr);
                return cur_db_version;
            } else {
                // This is a new database, insert current value
                st.execute("INSERT INTO VARIABLES (KEY,VALUE) " + " VALUES ('db_version',"
                        + JDBCTaskWrapper.DB_VERSION + ")");

                return JDBCTaskWrapper.DB_VERSION;
            }
        } finally {
            close(rs);
            close(st);
        }
    }

    private void checkTasksTable(Connection conn) throws SQLException {
        
        condCreate(conn, "TASKS", "task_id BIGINT AUTO_INCREMENT PRIMARY KEY, " + 
                "layer_name VARCHAR(254), " + 
                "state VARCHAR(10), " + 
                "time_spent BIGINT, " + 
                "time_remaining BIGINT, " + 
                "tiles_done BIGINT, " + 
                "tiles_total BIGINT, " + 
                "bounds VARCHAR(254), " + 
                "girdset_id VARCHAR(254), " + 
                "srs INT, " + 
                "thread_count INT, " + 
                "zoom_start INT, " + 
                "zoom_stop INT, " + 
                "format VARCHAR(32), " + 
                "task_type VARCHAR(10), " + 
                "max_throughput INT, " + 
                "priority VARCHAR(10), " + 
                "schedule VARCHAR(10), " + 
                "time_first_start TIMESTAMP, " + 
                "time_latest_start TIMESTAMP ", 
                "layer_name", 
                null);
    }

    private void condCreate(Connection conn, String tableName, String columns, String indexColumns,
            String index2Columns) throws SQLException {
        Statement st = null;

        try {
            st = conn.createStatement();

            st.execute("CREATE TABLE IF NOT EXISTS " + tableName + " (" + columns + ")");

            st.execute("CREATE INDEX IF NOT EXISTS " + "IDX_" + tableName + " ON " + tableName
                    + " (" + indexColumns + ")");

            if (index2Columns != null) {
                st.execute("CREATE INDEX IF NOT EXISTS " + "IDX2_" + tableName + " ON " + tableName
                        + " (" + index2Columns + ")");
            }
        } finally {
            close(st);
        }

    }

    private void runDbUpgrade(Connection conn, int fromVersion) {
        log.info("Upgrading  H2 database from " + fromVersion + " to " + JDBCTaskWrapper.DB_VERSION);

        boolean earlier = false;

        // no changes yet, so no upgrades
    }

    protected void deleteTask(TaskObject stObj) throws SQLException {
        final Connection conn = getConnection();
        try {
            deleteTask(conn, stObj);
        } finally {
            close(conn);
        }
    }

    protected void deleteTask(Connection conn, TaskObject stObj) throws SQLException {

        String query = "DELETE FROM TASKS WHERE TASK_ID = ?";

        PreparedStatement prep = conn.prepareStatement(query);
        try {
            prep.setLong(1, stObj.getId());

            prep.execute();
        } finally {
            close(prep);
        }
    }

    protected boolean getTask(TaskObject stObj) throws SQLException {
        String query = "SELECT * FROM TASKS WHERE TASK_ID = ? LIMIT 1 ";

        final Connection conn = getConnection();

        PreparedStatement prep = null;
        
        try {
            prep = conn.prepareStatement(query);
            prep.setLong(1, stObj.getId());

            ResultSet rs = prep.executeQuery();
            try {
                if (rs.first()) {
                    // stObj.setId(rs.getLong("task_id"));
                    stObj.setLayerName(rs.getString("layer_name"));
                    stObj.setState(STATE.valueOf(rs.getString("state")));
                    stObj.setTimeSpent(rs.getLong("time_spent"));
                    stObj.setTimeRemaining(rs.getLong("time_remaining"));
                    stObj.setTilesDone(rs.getLong("tiles_done"));
                    stObj.setTilesTotal(rs.getLong("tiles_total"));
                    
                    stObj.setBounds(new BoundingBox(rs.getString("bounds")));
                    stObj.setGridSetId(rs.getString("gridset_id"));
                    stObj.setSrs(SRS.getSRS(rs.getInt("srs")));

                    stObj.setThreadCount(rs.getInt("thread_count"));
                    stObj.setZoomStart(rs.getInt("zoom_start"));
                    stObj.setZoomStop(rs.getInt("zoom_stop"));
                    stObj.setFormat(rs.getString("format"));
                    stObj.setTaskType(TYPE.valueOf(rs.getString("task_type")));
                    stObj.setMaxThroughput(rs.getInt("max_throughput"));
                    stObj.setPriority(PRIORITY.valueOf(rs.getString("priority")));
                    stObj.setSchedule(rs.getString("schedule"));
                    
                    stObj.setTimeFirstStart(rs.getTimestamp("time_first_start"));
                    stObj.setTimeLatestStart(rs.getTimestamp("time_latest_start"));

                    return true;
                } else {
                    return false;
                }
            } finally {
                close(rs);
            }
        } finally {
            close(prep);
            close(conn);
        }
    }

    public void putTask(TaskObject stObj) throws SQLException, StorageException {

        // TODO more fields etc
        String query = "MERGE INTO " + 
                "TASKS(TASK_ID, layer_name, state, time_spent, time_remaining, tiles_done, " + 
                "tiles_total, bounds, gridset_id, srs, thread_count, zoom_start, zoom_stop, " + 
                "format, task_type, max_throughput, priority, schedule, time_first_start, " + 
                "time_latest_start) " + 
                "KEY(TASK_ID) " + 
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

        final Connection conn = getConnection();

        try {
            Long insertId;
            PreparedStatement prep = conn.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
            try {
                prep.setLong(1, stObj.getId());

                prep.setString(2, stObj.getLayerName());
                prep.setString(3, stObj.getState().toString());
                prep.setLong(4, stObj.getTimeSpent());
                prep.setLong(5, stObj.getTimeRemaining());
                prep.setLong(6, stObj.getTilesDone());
                prep.setLong(7, stObj.getTilesTotal());
                
                prep.setString(8, stObj.getBounds().toString());
                prep.setString(9, stObj.getGridSetId());
                prep.setInt(10, stObj.getSrs().getNumber());

                prep.setInt(11, stObj.getThreadCount());
                prep.setInt(12, stObj.getZoomStart());
                prep.setInt(13, stObj.getZoomStop());
                prep.setString(14, stObj.getFormat());
                prep.setString(15, stObj.getTaskType().toString());
                prep.setInt(16, stObj.getMaxThroughput());
                prep.setString(17, stObj.getPriority().toString());
                prep.setString(18, stObj.getSchedule());
                
                prep.setTimestamp(19, stObj.getTimeFirstStart());
                prep.setTimestamp(20, stObj.getTimeLatestStart());
                
                insertId = wrappedInsert(prep);
            } finally {
                close(prep);
            }
            if (insertId == null) {
                log.error("Did not receive a id for " + query);
            } else {
                stObj.setId(insertId.longValue());
            }

        } finally {
            conn.close();
        }

    }

    protected Long wrappedInsert(PreparedStatement st) throws SQLException {
        ResultSet rs = null;

        try {
            st.executeUpdate();
            rs = st.getGeneratedKeys();

            if (rs.next()) {
                return Long.valueOf(rs.getLong(1));
            }

            return null;

        } finally {
            close(rs);
        }
    }

    public void destroy() {
        Connection conn = null;
        try {
            conn = getConnection();
            this.closing = true;
            try {
                conn.createStatement().execute("SHUTDOWN");
            } catch (SQLException se) {
                log.warn("SHUTDOWN call to JDBC resulted in: " + se.getMessage());
            }
        } catch (SQLException e) {
            log.error("Couldn't obtain JDBC Connection to perform database shut down", e);
        } finally {
            if (conn != null) {
                // should be already closed after SHUTDOWN
                boolean closed = false;
                try {
                    closed = conn.isClosed();
                } catch (SQLException e) {
                    log.error(e);
                }
                if (!closed) {
                    close(conn);
                }
            }
        }

        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.gc();
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.gc();
    }

    public void deleteTask(long taskId) throws SQLException {

        String query = "DELETE FROM TASKS WHERE TASK_ID = ?";

        final Connection conn = getConnection();
        try {
            PreparedStatement prep = conn.prepareStatement(query);
            try {
                prep.setLong(1, taskId);
                prep.execute();
            } finally {
                close(prep);
            }
        } finally {
            close(conn);
        }
    }
}