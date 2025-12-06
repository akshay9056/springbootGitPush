package com.avangrid.gui.avangrid_backend.repository;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.io.InputStream;

@Repository
@Slf4j
public class AzureBlobRepository {

    @Value("${azure.storage.account-name}")
    private String storageAccountName;

    @Value("${azure.storage.container-name}")
    private String containerName;

    @Value("${azure.client-id}")
    private String clientId;

    @Value("${azure.client-secret}")
    private String clientSecret;

    @Value("${azure.tenant-id}")
    private String tenantId;

    // Lazy initialization - thread safe for Spring singletons
    private volatile BlobServiceClient blobServiceClient;
    private volatile BlobContainerClient containerClient;

    private BlobServiceClient createBlobServiceClient() {
        ClientSecretCredential credential = new ClientSecretCredentialBuilder()
                .clientId(clientId)
                .clientSecret(clientSecret)
                .tenantId(tenantId)
                .build();

        String endpoint = String.format("https://%s.blob.core.windows.net", storageAccountName);

        return new BlobServiceClientBuilder()
                .endpoint(endpoint)
                .credential(credential)
                .buildClient();
    }

    private BlobServiceClient getBlobServiceClient() {
        if (blobServiceClient == null) {
            synchronized (this) {
                if (blobServiceClient == null) {
                    blobServiceClient = createBlobServiceClient();
                }
            }
        }
        return blobServiceClient;
    }

    private BlobContainerClient getContainerClient() {
        if (containerClient == null) {
            synchronized (this) {
                if (containerClient == null) {
                    containerClient = getBlobServiceClient().getBlobContainerClient(containerName);
                }
            }
        }
        return containerClient;
    }

    public List<String> listBlobs(String prefix) {

        if (Objects.isNull(prefix)) {
            log.warn("Blob prefix is null");
            return Collections.emptyList();
        }

        List<String> blobNames = new ArrayList<>();

        try {
            for (BlobItem blobItem : getContainerClient().listBlobsByHierarchy(prefix)) {
                blobNames.add(blobItem.getName());
            }
        } catch (Exception e) {
            log.error("Failed to list blobs for prefix: {}", prefix, e);
        }

        return blobNames;
    }

    public byte[] getBlobContent(String blobName) {
        try {
            BlobClient blobClient = getContainerClient().getBlobClient(blobName);
            return blobClient.downloadContent().toBytes();
        } catch (Exception e) {
            log.error("Failed to download blob: {}", blobName, e);
            return new byte[0];
        }
    }

    public boolean isContainerAvailable() {
        try {
            return getContainerClient().exists();
        } catch (Exception e) {
            log.error("Azure Blob connection check failed", e);
            return false;
        }
    }

    public InputStream getBlobStream(String blobName) {
        BlobClient blobClient = getContainerClient().getBlobClient(blobName);
        return blobClient.openInputStream();
    }
}
