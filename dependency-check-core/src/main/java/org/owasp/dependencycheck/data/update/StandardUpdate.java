/*
 * This file is part of dependency-check-core.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (c) 2012 Jeremy Long. All Rights Reserved.
 */
package org.owasp.dependencycheck.data.update;

import java.net.MalformedURLException;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.owasp.dependencycheck.data.nvdcve.CveDB;
import org.owasp.dependencycheck.data.nvdcve.DatabaseException;
import org.owasp.dependencycheck.data.nvdcve.DatabaseProperties;
import static org.owasp.dependencycheck.data.nvdcve.DatabaseProperties.MODIFIED;
import org.owasp.dependencycheck.data.update.exception.InvalidDataException;
import org.owasp.dependencycheck.data.update.exception.UpdateException;
import org.owasp.dependencycheck.data.update.task.DownloadTask;
import org.owasp.dependencycheck.data.update.task.ProcessTask;
import org.owasp.dependencycheck.utils.DateUtil;
import org.owasp.dependencycheck.utils.DownloadFailedException;
import org.owasp.dependencycheck.utils.InvalidSettingException;
import org.owasp.dependencycheck.utils.Settings;

/**
 * Class responsible for updating the NVDCVE data store.
 *
 * @author Jeremy Long
 */
public class StandardUpdate {

    /**
     * Static logger.
     */
    private static final Logger LOGGER = Logger.getLogger(StandardUpdate.class.getName());
    /**
     * The max thread pool size to use when downloading files.
     */
    public static final int MAX_THREAD_POOL_SIZE = Settings.getInt(Settings.KEYS.MAX_DOWNLOAD_THREAD_POOL_SIZE, 3);
    /**
     * Information about the timestamps and URLs for data that needs to be updated.
     */
    private DatabaseProperties properties;
    /**
     * A collection of updateable NVD CVE items.
     */
    private UpdateableNvdCve updateable;
    /**
     * Reference to the Cve Database.
     */
    private CveDB cveDB = null;

    /**
     * Gets whether or not an update is needed.
     *
     * @return true or false depending on whether an update is needed
     */
    public boolean isUpdateNeeded() {
        return updateable.isUpdateNeeded();
    }

    /**
     * Constructs a new Standard Update Task.
     *
     * @throws MalformedURLException thrown if a configured URL is malformed
     * @throws DownloadFailedException thrown if a timestamp cannot be checked on a configured URL
     * @throws UpdateException thrown if there is an exception generating the update task
     */
    public StandardUpdate() throws MalformedURLException, DownloadFailedException, UpdateException {
        openDataStores();
        properties = cveDB.getDatabaseProperties();
        updateable = updatesNeeded();
    }

    /**
     * <p>
     * Downloads the latest NVD CVE XML file from the web and imports it into the current CVE Database.</p>
     *
     * @throws UpdateException is thrown if there is an error updating the database
     */
    public void update() throws UpdateException {
        int maxUpdates = 0;
        try {
            for (NvdCveInfo cve : updateable) {
                if (cve.getNeedsUpdate()) {
                    maxUpdates += 1;
                }
            }
            if (maxUpdates <= 0) {
                return;
            }
            if (maxUpdates > 3) {
                LOGGER.log(Level.INFO,
                        "NVD CVE requires several updates; this could take a couple of minutes.");
            }
            if (maxUpdates > 0) {
                openDataStores();
            }

            final int poolSize = (MAX_THREAD_POOL_SIZE < maxUpdates) ? MAX_THREAD_POOL_SIZE : maxUpdates;

            final ExecutorService downloadExecutors = Executors.newFixedThreadPool(poolSize);
            final ExecutorService processExecutor = Executors.newSingleThreadExecutor();
            final Set<Future<Future<ProcessTask>>> downloadFutures = new HashSet<Future<Future<ProcessTask>>>(maxUpdates);
            for (NvdCveInfo cve : updateable) {
                if (cve.getNeedsUpdate()) {
                    final DownloadTask call = new DownloadTask(cve, processExecutor, cveDB, Settings.getInstance());
                    downloadFutures.add(downloadExecutors.submit(call));
                }
            }
            downloadExecutors.shutdown();

            //next, move the future future processTasks to just future processTasks
            final Set<Future<ProcessTask>> processFutures = new HashSet<Future<ProcessTask>>(maxUpdates);
            for (Future<Future<ProcessTask>> future : downloadFutures) {
                Future<ProcessTask> task = null;
                try {
                    task = future.get();
                } catch (InterruptedException ex) {
                    downloadExecutors.shutdownNow();
                    processExecutor.shutdownNow();

                    LOGGER.log(Level.FINE, "Thread was interrupted during download", ex);
                    throw new UpdateException("The download was interrupted", ex);
                } catch (ExecutionException ex) {
                    downloadExecutors.shutdownNow();
                    processExecutor.shutdownNow();

                    LOGGER.log(Level.FINE, "Thread was interrupted during download execution", ex);
                    throw new UpdateException("The execution of the download was interrupted", ex);
                }
                if (task == null) {
                    downloadExecutors.shutdownNow();
                    processExecutor.shutdownNow();
                    LOGGER.log(Level.FINE, "Thread was interrupted during download");
                    throw new UpdateException("The download was interrupted; unable to complete the update");
                } else {
                    processFutures.add(task);
                }
            }

            for (Future<ProcessTask> future : processFutures) {
                try {
                    final ProcessTask task = future.get();
                    if (task.getException() != null) {
                        throw task.getException();
                    }
                } catch (InterruptedException ex) {
                    processExecutor.shutdownNow();
                    LOGGER.log(Level.FINE, "Thread was interrupted during processing", ex);
                    throw new UpdateException(ex);
                } catch (ExecutionException ex) {
                    processExecutor.shutdownNow();
                    LOGGER.log(Level.FINE, "Execution Exception during process", ex);
                    throw new UpdateException(ex);
                } finally {
                    processExecutor.shutdown();
                }
            }

            if (maxUpdates >= 1) { //ensure the modified file date gets written (we may not have actually updated it)
                properties.save(updateable.get(MODIFIED));
                LOGGER.log(Level.INFO, "Begin database maintenance.");
                cveDB.cleanupDatabase();
                LOGGER.log(Level.INFO, "End database maintenance.");
            }
        } finally {
            closeDataStores();
        }
    }

    /**
     * Determines if the index needs to be updated. This is done by fetching the NVD CVE meta data and checking the last
     * update date. If the data needs to be refreshed this method will return the NvdCveUrl for the files that need to
     * be updated.
     *
     * @return the collection of files that need to be updated
     * @throws MalformedURLException is thrown if the URL for the NVD CVE Meta data is incorrect
     * @throws DownloadFailedException is thrown if there is an error. downloading the NVD CVE download data file
     * @throws UpdateException Is thrown if there is an issue with the last updated properties file
     */
    protected final UpdateableNvdCve updatesNeeded() throws MalformedURLException, DownloadFailedException, UpdateException {
        UpdateableNvdCve updates = null;
        try {
            updates = retrieveCurrentTimestampsFromWeb();
        } catch (InvalidDataException ex) {
            final String msg = "Unable to retrieve valid timestamp from nvd cve downloads page";
            LOGGER.log(Level.FINE, msg, ex);
            throw new DownloadFailedException(msg, ex);
        } catch (InvalidSettingException ex) {
            LOGGER.log(Level.FINE, "Invalid setting found when retrieving timestamps", ex);
            throw new DownloadFailedException("Invalid settings", ex);
        }

        if (updates == null) {
            throw new DownloadFailedException("Unable to retrieve the timestamps of the currently published NVD CVE data");
        }
        if (!properties.isEmpty()) {
            try {
                final long lastUpdated = Long.parseLong(properties.getProperty(DatabaseProperties.LAST_UPDATED, "0"));
                final Date now = new Date();
                final int days = Settings.getInt(Settings.KEYS.CVE_MODIFIED_VALID_FOR_DAYS, 7);
                if (lastUpdated == updates.getTimeStamp(MODIFIED)) {
                    updates.clear(); //we don't need to update anything.
                } else if (DateUtil.withinDateRange(lastUpdated, now.getTime(), days)) {
                    for (NvdCveInfo entry : updates) {
                        if (MODIFIED.equals(entry.getId())) {
                            entry.setNeedsUpdate(true);
                        } else {
                            entry.setNeedsUpdate(false);
                        }
                    }
                } else { //we figure out which of the several XML files need to be downloaded.
                    for (NvdCveInfo entry : updates) {
                        if (MODIFIED.equals(entry.getId())) {
                            entry.setNeedsUpdate(true);
                        } else {
                            long currentTimestamp = 0;
                            try {
                                currentTimestamp = Long.parseLong(properties.getProperty(DatabaseProperties.LAST_UPDATED_BASE + entry.getId(), "0"));
                            } catch (NumberFormatException ex) {
                                final String msg = String.format("Error parsing '%s' '%s' from nvdcve.lastupdated",
                                        DatabaseProperties.LAST_UPDATED_BASE, entry.getId());
                                LOGGER.log(Level.FINE, msg, ex);
                            }
                            if (currentTimestamp == entry.getTimestamp()) {
                                entry.setNeedsUpdate(false);
                            }
                        }
                    }
                }
            } catch (NumberFormatException ex) {
                final String msg = "An invalid schema version or timestamp exists in the data.properties file.";
                LOGGER.log(Level.WARNING, msg);
                LOGGER.log(Level.FINE, "", ex);
            }
        }
        return updates;
    }

    /**
     * Retrieves the timestamps from the NVD CVE meta data file.
     *
     * @return the timestamp from the currently published nvdcve downloads page
     * @throws MalformedURLException thrown if the URL for the NVD CCE Meta data is incorrect.
     * @throws DownloadFailedException thrown if there is an error downloading the nvd cve meta data file
     * @throws InvalidDataException thrown if there is an exception parsing the timestamps
     * @throws InvalidSettingException thrown if the settings are invalid
     */
    private UpdateableNvdCve retrieveCurrentTimestampsFromWeb()
            throws MalformedURLException, DownloadFailedException, InvalidDataException, InvalidSettingException {

        final UpdateableNvdCve updates = new UpdateableNvdCve();
        updates.add(MODIFIED, Settings.getString(Settings.KEYS.CVE_MODIFIED_20_URL),
                Settings.getString(Settings.KEYS.CVE_MODIFIED_12_URL),
                false);

        final int start = Settings.getInt(Settings.KEYS.CVE_START_YEAR);
        final int end = Calendar.getInstance().get(Calendar.YEAR);
        final String baseUrl20 = Settings.getString(Settings.KEYS.CVE_SCHEMA_2_0);
        final String baseUrl12 = Settings.getString(Settings.KEYS.CVE_SCHEMA_1_2);
        for (int i = start; i <= end; i++) {
            updates.add(Integer.toString(i), String.format(baseUrl20, i),
                    String.format(baseUrl12, i),
                    true);
        }

        return updates;
    }

    /**
     * Closes the CVE and CPE data stores.
     */
    protected void closeDataStores() {
        if (cveDB != null) {
            try {
                cveDB.close();
            } catch (Throwable ignore) {
                LOGGER.log(Level.FINEST, "Error closing the cveDB", ignore);
            }
        }
    }

    /**
     * Opens the CVE and CPE data stores.
     *
     * @throws UpdateException thrown if a data store cannot be opened
     */
    protected final void openDataStores() throws UpdateException {
        if (cveDB != null) {
            return;
        }
        try {
            cveDB = new CveDB();
            cveDB.open();
        } catch (DatabaseException ex) {
            closeDataStores();
            LOGGER.log(Level.FINE, "Database Exception opening databases", ex);
            throw new UpdateException("Error updating the CPE/CVE data, please see the log file for more details.");
        }
    }
}
