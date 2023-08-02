package gov.cdc.dex.router

import com.azure.identity.DefaultAzureCredentialBuilder
import com.azure.security.keyvault.secrets.SecretClient
import com.azure.security.keyvault.secrets.SecretClientBuilder
import com.azure.storage.blob.BlobServiceClientBuilder
import com.google.gson.Gson
import com.microsoft.azure.functions.ExecutionContext
import com.microsoft.azure.functions.annotation.*
import gov.cdc.dex.router.dtos.EventSchema
import gov.cdc.dex.router.dtos.RouteConfig
import java.sql.*

class RouteIngestedFile {
    
    @FunctionName("RouteIngestedFile")
    fun run(
        @EventHubTrigger(name = "message", eventHubName = "%AzureEventHubName%", consumerGroup = "%AzureEventHubConsumerGroup%",
            connection = "AzureEventHubConnectionString", cardinality = Cardinality.ONE) message: String, context: ExecutionContext
    ) {
        try {
            context.logger.info("Function triggered for message $message")
            val eventContent = Gson().fromJson(message, Array<EventSchema>::class.java).first()

            //Define source
            val sourceUrl = eventContent.data.url
            val fileName = sourceUrl.substringAfter(System.getenv("BlobIngestContainerName") + "/")
            context.logger.info("FileName: $fileName")

            val endpoint = "https://${System.getenv("BlobIngestStorageAccountName")}.blob.core.windows.net"
            //val blobServiceClient = BlobServiceClientBuilder().endpoint(endpoint).credential(azureCredential).buildClient()
            val blobServiceClient = BlobServiceClientBuilder().endpoint(endpoint).connectionString(System.getenv("BlobIngestConnectionString")).buildClient()
            val blobContainerClient = blobServiceClient.getBlobContainerClient(System.getenv("BlobIngestContainerName"))
            val sourceBlob = blobContainerClient.getBlobClient(fileName)
            context.logger.info("Source Blob URL: " + sourceBlob.blobUrl)

            //Get message type from the metadata
            val sourceMetadata = sourceBlob.properties.metadata
            val messageType = sourceMetadata.getOrDefault("message_type", "?")
            context.logger.info("Message Type: $messageType")

            //Get ConnectionString from Key Vault
            val azureCredential = DefaultAzureCredentialBuilder().build()
            val vaultURL = System.getenv("ConfigVaultURL")
            val secretClient: SecretClient = SecretClientBuilder()
                .vaultUrl(vaultURL)
                .credential(azureCredential)
                .buildClient()
            val configConnString = secretClient.getSecret("ConfigSQLDBConnection").value
            context.logger.info("Connection retrieved from Key Vault")

            //Retrieve Configuration from SQL Database
            try {
                Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver")
            } catch (e: ClassNotFoundException) {
                context.logger.severe("SQL JDBC Driver not found.")
            }
            var connection: Connection? = null
            var stmt: CallableStatement? = null
            var rs: ResultSet? = null
            var configJson: String? = null
            try {
                connection = DriverManager.getConnection(configConnString)
                stmt = connection.prepareCall("{call GetRouterConfigByUseCase(?)}")
                stmt.setString(1, "UploadAPI")
                rs = stmt.executeQuery()
                while(rs.next()) {
                    configJson = rs.getString("ConfigurationJSON")
                    context.logger.info("Configuration retrieved from database")
                }
            } catch (e: Exception) {
                context.logger.severe("Exception: ${e.message}")
            } finally {
                rs?.close()
                stmt?.close()
                connection?.close()
            }

            // Load configs
            if(configJson.isNullOrEmpty()){
                context.logger.severe("Config file is missing or empty, routing cannot continue.")
            }
            val routeConfigs = Gson().fromJson(configJson, Array<RouteConfig>::class.java).toList()

            // Find associated config
            var routeConfig = routeConfigs.firstOrNull { it.messageTypes.contains(messageType) }
            if (routeConfig == null) {
                context.logger.warning("No routing configured for files with a $messageType message type. Will route to misc folder.")
                routeConfig = routeConfigs.firstOrNull { it.fileType == "?" }
                if(routeConfig == null){
                    context.logger.severe("Config file does not define misc folder.")
                    return
                }
            }

            // Define destination
            val destinationFileName = fileName.split("/").last()
            val destinationBlobName = "${routeConfig.stagingLocations.destinationContainer}/${destinationFileName}"
            val destinationEndpoint = "https://${System.getenv("BlobDestinationStorageAccountName")}.blob.core.windows.net"
            //val destinationBlobServiceClient = BlobServiceClientBuilder().endpoint(destinationEndpoint).credential(azureCredential).buildClient()
            val destinationBlobServiceClient = BlobServiceClientBuilder().endpoint(destinationEndpoint).connectionString(System.getenv("BlobDestinationConnectionString")).buildClient()
            val destinationContainerClient = destinationBlobServiceClient.getBlobContainerClient(System.getenv("BlobDestinationContainerName"))
            val destinationBlob = destinationContainerClient.getBlobClient(destinationBlobName)
            context.logger.info("Destination Blob URL: " + destinationBlob.blobUrl)

            //Stream the blob and upload it to the destination, then close the stream
            val sourceBlobInputStream = sourceBlob.openInputStream()
            destinationBlob.upload(sourceBlobInputStream, sourceBlob.properties.blobSize, true)
            sourceBlobInputStream.close()

            //Set metadata
            sourceMetadata["system_provider"] = "DEX-ROUTING"
            destinationBlob.setMetadata(sourceMetadata)

            context.logger.info("Blob $sourceUrl has been routed to $destinationBlobName")
        } catch (e: Exception) {
            context.logger.severe(e.message)
        }
    }
}
