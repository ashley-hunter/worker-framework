/*
 * Copyright 2015-2017 Hewlett Packard Enterprise Development LP.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hpe.caf.worker.datastore.cs;


import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;
import com.google.common.io.FileBackedOutputStream;
import com.google.common.io.Files;
import com.hpe.caf.api.HealthResult;
import com.hpe.caf.api.HealthStatus;
import com.hpe.caf.api.worker.DataStoreException;
import com.hpe.caf.api.worker.DataStoreMetricsReporter;
import com.hpe.caf.api.worker.ManagedDataStore;
import com.hpe.caf.storage.common.crypto.WrappedKey;
import com.hpe.caf.storage.sdk.StorageClient;
import com.hpe.caf.storage.sdk.exceptions.StorageClientException;
import com.hpe.caf.storage.sdk.exceptions.StorageServiceConnectException;
import com.hpe.caf.storage.sdk.exceptions.StorageServiceException;
import com.hpe.caf.storage.sdk.model.AssetMetadata;
import com.hpe.caf.storage.sdk.model.StorageServiceInfo;
import com.hpe.caf.storage.sdk.model.StorageServiceStatus;
import com.hpe.caf.storage.sdk.model.requests.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * ManagedDataStore implementation for the CAF Storage Service.
 */
public class StorageServiceDataStore implements ManagedDataStore, TokenRefreshListener
{
    @FunctionalInterface
    private interface StorageClientFunction<T, R>  {
        R apply(T t) throws StorageServiceConnectException, StorageServiceException, StorageClientException, IOException;
    }

    /**
     * The Storage Service "file type" for Worker assets.
     */
    private final AtomicInteger errors = new AtomicInteger(0);
    private final AtomicInteger numRx = new AtomicInteger(0);
    private final AtomicInteger numTx = new AtomicInteger(0);
    private final AtomicInteger numDx = new AtomicInteger(0);

    private final DataStoreMetricsReporter metrics = new StorageServiceDataStoreMetricsReporter();
    private final StorageClient storageClient;
    private String accessToken = null;
    private static final Logger LOG = LoggerFactory.getLogger(StorageServiceDataStore.class);
    private final KeycloakClient keycloakClient;

    /**
     * Byte size at which incoming streams are buffered to disk before sending to the Storage Service.
     */
    private static final int FILE_THRESHOLD = 1024 * 1024;

    private static final String DELEGATION_TICKET_NAMED_PARAMETER = "delegationTicket";

    public StorageServiceDataStore(final StorageServiceDataStoreConfiguration storageServiceDataStoreConfiguration, final KeycloakClient keycloak)
    {
        StorageServiceClientCallback callBack = new StorageServiceClientCallback(storageServiceDataStoreConfiguration);

        // Listen for access token refresh changes.
        callBack.addTokenRefreshListener(this);

        storageClient = new StorageClient(storageServiceDataStoreConfiguration.getServerName(),
                String.valueOf(storageServiceDataStoreConfiguration.getPort()),
                null,
                callBack);

        keycloakClient = keycloak;

    }

    @Override
    public void tokenRefreshed(String token) {
        //  Cache refreshed token.
        accessToken = token;
    }

    @Override
    public DataStoreMetricsReporter getMetrics()
    {
        return metrics;
    }


    @Override
    public void shutdown()
    {
        // nothing to do
    }

    private <T> T  callStorageService(StorageClientFunction<StorageClient, T> call)
            throws StorageServiceConnectException, IOException, StorageServiceException, StorageClientException {
        return callStorageService(call, 2);
    }

    private <T> T  callStorageService(StorageClientFunction<StorageClient, T> call, int retryCount)
            throws StorageServiceConnectException, StorageClientException, IOException, StorageServiceException {
        for (int i = 1; ; i++) {
            try {
                LOG.debug("About to run caf storage request.");
                if (accessToken == null && keycloakClient != null) {
                    accessToken = keycloakClient.getAccessToken();
                }
                T result = call.apply(storageClient);
                LOG.debug("Received caf storage response.");
                return result;
            }
            catch (StorageServiceException e) {
                if (i >= retryCount || e.getHTTPStatus() != 401 || keycloakClient == null) {
                    throw e;
                }
                accessToken = keycloakClient.getAccessToken();
            }
        }
    }

    public void delete(String reference) throws DataStoreException {
        LOG.debug("Received delete request for {}", reference);
        numDx.incrementAndGet();

        //  Parse incoming reference. Extract container/asset identifiers as well as any delegation ticket provided.
        ReferenceComponents refComponents = ReferenceComponents.parseReference(reference);
        reference = refComponents.getReference();
        String delegationTicket = refComponents.getNamedValue(DELEGATION_TICKET_NAMED_PARAMETER);

        CafStoreReference ref = new CafStoreReference(reference);

        try {
            DeleteAssetRequest deleteAssetRequest = new DeleteAssetRequest(accessToken, ref.getContainer(), ref.getAsset());

            //  If delegation ticket has been provided then set it as part of the delete request.
            if (delegationTicket != null) {
                deleteAssetRequest.setDelegationTicket(delegationTicket);
            }

            callStorageService(c ->  {
                c.deleteAsset(deleteAssetRequest);
                return null; // Added return to satisfy functional interface
            });
        } catch (StorageServiceConnectException | StorageClientException | StorageServiceException | IOException e) {
            errors.incrementAndGet();
            throw new DataStoreException("Failed to delete asset data for reference " + reference, e);
        }
    }

    @Override
    public InputStream retrieve(String reference)
        throws DataStoreException
    {
        LOG.debug("Received retrieve request for {}", reference);
        numRx.incrementAndGet();

        //  Parse incoming reference. Extract container/asset identifiers as well as any delegation ticket provided.
        ReferenceComponents refComponents = ReferenceComponents.parseReference(reference);
        reference = refComponents.getReference();
        String delegationTicket = refComponents.getNamedValue(DELEGATION_TICKET_NAMED_PARAMETER);

        CafStoreReference ref = new CafStoreReference(reference);

        try {
            GetAssetContainerEncryptionKeyRequest getAssetContainerEncryptionKeyRequest = new GetAssetContainerEncryptionKeyRequest(accessToken, ref.getContainer());
            if(delegationTicket != null){
                getAssetContainerEncryptionKeyRequest.setDelegationTicket(delegationTicket);
            }

            WrappedKey wrappedKey = callStorageService(c -> c.getAssetContainerEncryptionKey(getAssetContainerEncryptionKeyRequest));

            DownloadAssetRequest downloadAssetRequest = new DownloadAssetRequest(accessToken, ref.getContainer(), ref.getAsset(), wrappedKey);

            //  If delegation ticket has been provided then set it as part of the download asset request.
            if (delegationTicket != null) {
                downloadAssetRequest.setDelegationTicket(delegationTicket);
            }

            return callStorageService(c -> c.downloadAsset(downloadAssetRequest)).getDecryptedStream();
        } catch (StorageClientException | StorageServiceException | StorageServiceConnectException | IOException e) {
            errors.incrementAndGet();
            throw new DataStoreException("Failed to retrieve data from reference " + reference, e);
        }
    }


    @Override
    public long size(String reference)
        throws DataStoreException
    {
        LOG.debug("Received size request for {}", reference);

        //  Parse incoming reference. Extract container/asset identifiers as well as any delegation ticket provided.
        ReferenceComponents refComponents = ReferenceComponents.parseReference(reference);
        reference = refComponents.getReference();
        String delegationTicket = refComponents.getNamedValue(DELEGATION_TICKET_NAMED_PARAMETER);

        CafStoreReference ref = new CafStoreReference(reference);
        try {
            GetAssetMetadataRequest getAssetMetadataRequest = new GetAssetMetadataRequest(accessToken, ref.getContainer(), ref.getAsset());
            if (delegationTicket != null) {
                getAssetMetadataRequest.setDelegationTicket(delegationTicket);
            }

            return callStorageService(c -> c.getAssetMetadata(getAssetMetadataRequest)).getSize();
        } catch (IOException | StorageClientException |  StorageServiceException | StorageServiceConnectException e) {
            errors.incrementAndGet();
            throw new DataStoreException("Failed to get data size for reference " + reference, e);
        }
    }


    @Override
    public String store(InputStream inputStream, String partialReference)
        throws DataStoreException
    {
        try ( FileBackedOutputStream fileBackedOutputStream = new FileBackedOutputStream(FILE_THRESHOLD, true) ) {
            try {
                ByteStreams.copy(inputStream, fileBackedOutputStream);
                return store(fileBackedOutputStream.asByteSource(), partialReference);
            } finally {
                fileBackedOutputStream.reset();
            }
        } catch (IOException ex) {
            errors.incrementAndGet();
            throw new DataStoreException("Could not store input stream.", ex);
        }
    }


    @Override
    public String store(byte[] bytes, String partialReference)
        throws DataStoreException
    {
        return store(ByteSource.wrap(bytes), partialReference);
    }


    @Override
    public String store(Path path, String partialReference)
        throws DataStoreException
    {
        return store(Files.asByteSource(path.toFile()), partialReference);
    }


    @Override
    public HealthResult healthCheck()
    {
        try {
            LOG.debug("Received healthcheck request for storage service.");
            StorageServiceInfo status = callStorageService(c -> c.getStorageServiceStatus());
            LOG.debug("Storage service healthcheck status received. Storage service version: " + status.getVersion() + ", status: " + status.getStatus());
            if (status.getStatus() != StorageServiceStatus.HEALTHY) {
                return new HealthResult(HealthStatus.UNHEALTHY, "Storage service returned " + status.getStatus() + " status.");
            }

        } catch (StorageServiceException e) {
            LOG.warn("Health check failed", e);
            return new HealthResult(HealthStatus.UNHEALTHY, "Error from Storage service: " + e.getResponseErrorMessage());
        } catch (StorageServiceConnectException | StorageClientException e) {
            LOG.warn("Health check failed", e);
            return new HealthResult(HealthStatus.UNHEALTHY, "Failed to connect to Storage service");
        } catch (IOException e) {
            LOG.warn("Health check failed", e);
            return new HealthResult(HealthStatus.UNHEALTHY, "Failed to request access token: " + e.getMessage());
        }
        return HealthResult.RESULT_HEALTHY;
    }

    private String store(ByteSource byteSource, String partialReference)
            throws DataStoreException {

        LOG.debug("Received store request for {}", partialReference);
        numTx.incrementAndGet();

        //  Parse incoming partial reference. Extract container identifier and delegation ticket if provided.
        ReferenceComponents refComponents = ReferenceComponents.parseReference(partialReference);
        String containerId = refComponents.getReference();
        String delegationTicket = refComponents.getNamedValue(DELEGATION_TICKET_NAMED_PARAMETER);

        try (InputStream inputStream = byteSource.openBufferedStream()) {

            final GetAssetContainerEncryptionKeyRequest encryptionRequest = new GetAssetContainerEncryptionKeyRequest(accessToken, containerId);
            if (delegationTicket != null) {
                encryptionRequest.setDelegationTicket(delegationTicket);
            }

            WrappedKey wrappedKey = callStorageService(c -> c.getAssetContainerEncryptionKey(encryptionRequest));

            UploadAssetRequest uploadRequest = new UploadAssetRequest(accessToken, containerId, UUID.randomUUID().toString(), wrappedKey, inputStream);

            //  If delegation ticket has been provided then set it as part of the upload request.
            if (delegationTicket != null) {
                uploadRequest.setDelegationTicket(delegationTicket);
            }

            AssetMetadata assetMetadata =
                    callStorageService(c -> c.uploadAsset(uploadRequest,null));

            String returnValue = null;
            if (delegationTicket != null) {
                //  Return the delegation ticket as part of the CafStoreReference value.
                String encodedDelegationTicket = "?delegationTicket=" + URLEncoder.encode(delegationTicket, StandardCharsets.UTF_8.toString());
                returnValue = new CafStoreReference(assetMetadata.getContainerId(), assetMetadata.getAssetId()).toString() + encodedDelegationTicket;
            } else {
                returnValue = new CafStoreReference(assetMetadata.getContainerId(), assetMetadata.getAssetId()).toString();
            }

            return returnValue;
        } catch (IOException e) {
            errors.incrementAndGet();
            throw new DataStoreException("Failed to open buffered stream.", e);
        } catch (StorageClientException | StorageServiceException | StorageServiceConnectException e) {
            throw new DataStoreException("Failed to store data", e);
        }
    }


    private class StorageServiceDataStoreMetricsReporter implements DataStoreMetricsReporter
    {
        @Override
        public int getDeleteRequests() {
            return numDx.get();
        }

        @Override
        public int getStoreRequests()
        {
            return numTx.get();
        }


        @Override
        public int getRetrieveRequests()
        {
            return numRx.get();
        }


        @Override
        public int getErrors()
        {
            return errors.get();
        }
    }
}
