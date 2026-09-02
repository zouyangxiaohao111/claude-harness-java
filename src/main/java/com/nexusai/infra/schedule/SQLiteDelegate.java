package com.nexusai.infra.schedule;

import org.quartz.impl.jdbcjobstore.StdJDBCDelegate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Quartz DriverDelegate for SQLite.
 *
 * <p>SQLite's JDBC driver doesn't implement {@code ResultSet.getBlob()}, so the
 * default {@link StdJDBCDelegate} (which calls {@code getBlob} in
 * {@code getObjectFromBlob} / {@code getJobDataFromBlob}) fails with
 * {@code SQLFeatureNotSupportedException: not implemented by SQLite JDBC driver}.
 *
 * <p>SQLite stores BLOB as a raw byte stream accessible via
 * {@link ResultSet#getBytes(String)} — we override the two methods that
 * touch BLOB columns to use {@code getBytes} instead.
 *
 * <p>Configure via {@code spring.quartz.properties.org.quartz.jobStore.driverDelegateClass=
 * com.nexusai.schedule.SQLiteDelegate}.
 */
public class SQLiteDelegate extends StdJDBCDelegate {

    @Override
    protected Object getObjectFromBlob(ResultSet rs, String colName) throws ClassNotFoundException, IOException, SQLException {
        Object o = null;
        // SQLite: read the BLOB as raw bytes (getBlob throws SQLFeatureNotSupportedException)
        byte[] bytes = rs.getBytes(colName);
        if (bytes != null && bytes.length != 0) {
            try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
                o = ois.readObject();
            }
        }
        return o;
    }

    @Override
    protected Object getJobDataFromBlob(ResultSet rs, String colName) throws ClassNotFoundException, IOException, SQLException {
        // Same logic as getObjectFromBlob for our use case (JobDataMap).
        return getObjectFromBlob(rs, colName);
    }

    // setBytes is inherited from StdJDBCDelegate and uses setBytes() on the
    // PreparedStatement, which SQLite JDBC supports natively. No override needed.
}
