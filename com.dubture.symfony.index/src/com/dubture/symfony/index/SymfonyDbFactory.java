/*******************************************************************************
 * This file is part of the Symfony eclipse plugin.
 * 
 * (c) Robert Gruendler <r.gruendler@gmail.com>
 * 
 * For the full copyright and license information, please view the LICENSE
 * file that was distributed with this source code.
 ******************************************************************************/
package com.dubture.symfony.index;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.jobs.ILock;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.IPreferencesService;
import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.tools.DeleteDbFiles;

import com.dubture.symfony.index.dao.IParameterDao;
import com.dubture.symfony.index.dao.IResourceDao;
import com.dubture.symfony.index.dao.IRouteDao;
import com.dubture.symfony.index.dao.IServiceDao;
import com.dubture.symfony.index.dao.ITransUnitDao;
import com.dubture.symfony.index.dao.sql.ParameterDao;
import com.dubture.symfony.index.dao.sql.ResourceDao;
import com.dubture.symfony.index.dao.sql.RouteDao;
import com.dubture.symfony.index.dao.sql.ServiceDao;
import com.dubture.symfony.index.dao.sql.TransUnitDao;
import com.dubture.symfony.index.log.Logger;
import com.dubture.symfony.index.preferences.SymfonyIndexPreferences;

/**
 * 
 * Database Factory for the SQL Index.
 * 
 * 
 * @author "Robert Gruendler <r.gruendler@gmail.com>"
 *
 */
public class SymfonyDbFactory  {

	private static ILock instanceLock = Job.getJobManager().newLock();	
	private static final String DB_NAME = "symfonymodel"; //$NON-NLS-1$
	private static final String DB_USER = ""; //$NON-NLS-1$
	private static final String DB_PASS = ""; //$NON-NLS-1$
	private JdbcConnectionPool pool;
	private static SymfonyDbFactory instance = null;
	
	private ServiceDao serviceDao = new ServiceDao();
	private ParameterDao parameterDao = new ParameterDao();
	private RouteDao routeDao = new RouteDao();
	private ResourceDao resourceDao = new ResourceDao();
	private TransUnitDao transUnitDao = new TransUnitDao();

	public static SymfonyDbFactory getInstance() {

		if (instance == null) {
			try {
				instanceLock.acquire();
				instance = new SymfonyDbFactory();
				/*
				 * Explicitly register shutdown handler, so it
				 * would be disposed only if class was loaded.
				 * 
				 * We don't want static initialization code to
				 * be executed during framework shutdown.
				 */
				SymfonyIndex
				.addShutdownListener(new IShutdownListener() {
					public void shutdown() {
						if (instance != null) {
							try {
								instance.dispose();
							} catch (SQLException e) {
								Logger.logException(e);
							}
							instance = null;
						}
					}
				});

			} catch (Exception e) {
				Logger.logException(e);
			} finally {
				instanceLock.release();
			}
		}

		return instance;
	}


	private SymfonyDbFactory() throws Exception {
		
		IPath dbPath = SymfonyIndex.getDefault().getStateLocation();
		String connString = getConnectionString(dbPath);
		pool = JdbcConnectionPool.create(connString, DB_USER, DB_PASS);
		pool.setMaxConnections(100);
		Schema schema = new Schema();
		boolean initializeSchema = false;

		int tries = 2; // Tries for opening database
		Connection connection = null;
		do {
			try {
				connection = pool.getConnection();
				try {
					Statement statement = connection.createStatement();
					try {
						statement
						.executeQuery("SELECT COUNT(*) FROM SERVICES WHERE 1=0;");
						initializeSchema = !schema.isCompatible();

					} catch (SQLException e) {
						// Basic table doesn't exist
						initializeSchema = true;
					} finally {
						statement.close();
					}

					if (initializeSchema) {
						connection.close();
						pool.dispose();
						// Destroy schema by removing DB (if exists)
						DeleteDbFiles.execute(dbPath.toOSString(), DB_NAME,
								true);

						pool = JdbcConnectionPool.create(connString, DB_USER,
								DB_PASS);
						pool.setMaxConnections(100);
						connection = pool.getConnection();
						schema.initialize(connection);
					}
				} finally {
					if (connection != null) {
						connection.close();
					}
				}
			} catch (SQLException e) {
				Logger.logException(e);
				// remove corrupted DB
				try {
					DeleteDbFiles.execute(dbPath.toOSString(), DB_NAME, true);
				} catch (Exception e1) {
					Logger.logException(e1);
					throw e1;
				}
			}
		} while (connection == null && --tries > 0);
		
	}

	/**
	 * Generates connection string using user preferences
	 * 
	 * @param dbPath
	 *            Path to the database files
	 * @return
	 */
	private String getConnectionString(IPath dbPath) {

		IPreferencesService preferencesService = Platform
				.getPreferencesService();

		StringBuilder buf = new StringBuilder("jdbc:h2:").append(dbPath.append(
				DB_NAME).toOSString());

		buf.append(";UNDO_LOG=0");
		buf.append(";LOCK_MODE=").append(
				preferencesService.getInt(SymfonyIndex.PLUGIN_ID,
						SymfonyIndexPreferences.DB_LOCK_MODE, 0, null));

		buf.append(";CACHE_TYPE=").append(
				preferencesService.getString(SymfonyIndex.PLUGIN_ID,
						SymfonyIndexPreferences.DB_CACHE_TYPE, null, null));

		buf.append(";CACHE_SIZE=").append(
				preferencesService.getInt(SymfonyIndex.PLUGIN_ID,
						SymfonyIndexPreferences.DB_CACHE_SIZE, 0, null));
		
		buf.append(";QUERY_CACHE_SIZE=").append(
				preferencesService.getInt(SymfonyIndex.PLUGIN_ID,
						SymfonyIndexPreferences.DB_QUERY_CACHE_SIZE, 0, null));

		buf.append(";LARGE_RESULT_BUFFER_SIZE=").append(
				preferencesService
						.getInt(SymfonyIndex.PLUGIN_ID,
								SymfonyIndexPreferences.DB_LARGE_RESULT_BUFFER_SIZE,
								0, null));
		
		buf.append(";FILE_LOCK=NO");

		return buf.toString();
	}


	public Connection createConnection() throws SQLException {
		return pool == null ? null : pool.getConnection();
	}

	public void dispose() throws SQLException {
		if (pool != null) {
			pool.dispose();
			pool = null;
		}
	}

	public IServiceDao getServiceDao() throws SQLException {
		return serviceDao;
	}

	public IRouteDao getRouteDao() throws SQLException {
		return routeDao;
	}
	
	public ITransUnitDao getTransDao() throws SQLException {
		return transUnitDao;
	}

	public IResourceDao getResourceDao() throws SQLException {
		return resourceDao;
	}
	
	public IParameterDao getParamDao() throws SQLException {
		return parameterDao;
	}
}
