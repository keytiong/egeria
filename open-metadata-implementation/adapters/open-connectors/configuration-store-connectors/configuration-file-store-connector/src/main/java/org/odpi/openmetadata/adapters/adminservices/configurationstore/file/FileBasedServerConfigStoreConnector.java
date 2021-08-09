/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright Contributors to the ODPi Egeria project. */
package org.odpi.openmetadata.adapters.adminservices.configurationstore.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.odpi.openmetadata.adminservices.store.OMAGServerConfigStoreRetrieveAll;
import org.odpi.openmetadata.frameworks.connectors.ffdc.ConnectorCheckedException;
import org.odpi.openmetadata.frameworks.connectors.ffdc.OCFRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.odpi.openmetadata.adminservices.store.OMAGServerConfigStoreConnectorBase;
import org.odpi.openmetadata.frameworks.connectors.properties.EndpointProperties;
import org.odpi.openmetadata.adminservices.configuration.properties.OMAGServerConfig;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.text.ParseException;
import java.util.HashSet;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * FileBasedServerConfigStoreConnector provides a connector that manages a configuration document for an OMAG Server in a file
 */
public class FileBasedServerConfigStoreConnector extends OMAGServerConfigStoreConnectorBase implements OMAGServerConfigStoreRetrieveAll
{
    /*
     * This is the insert string using in the file name template
     */
    private static final String INSERT_FOR_FILENAME_TEMPLATE = "{0}";
    /*
     * This is the name of the configuration file that is used if there is no file name in the connection.
     */
    private static final String DEFAULT_FILENAME_TEMPLATE    = "./data/servers/" +INSERT_FOR_FILENAME_TEMPLATE + "/config/" +INSERT_FOR_FILENAME_TEMPLATE + ".config";


    /*
     * Variables used in writing to the file.
     */
    private String           configStoreName  = null;

    /*
     * Variables used for logging and debug.
     */
    private static final Logger log = LoggerFactory.getLogger(FileBasedServerConfigStoreConnector.class);


    /**
     * Default constructor
     */
    public FileBasedServerConfigStoreConnector()
    {
    }


    /**
     * Set up the name of the file store
     *
     * @throws ConnectorCheckedException something went wrong
     */
    @Override
    public void start() throws ConnectorCheckedException
    {
        super.start();

        String configStoreTemplateName = getStoreTemplateName();

        configStoreName = super.getStoreName(configStoreTemplateName, serverName);
    }

    /**
     * Get the store template name
     * @return the store template name
     */
    private String getStoreTemplateName()
    {
        EndpointProperties endpoint = connectionProperties.getEndpoint();

        String configStoreTemplateName = null;
        if (endpoint != null)
        {
            configStoreTemplateName = endpoint.getAddress();
        }
        if (configStoreTemplateName == null)
        {
            configStoreTemplateName = DEFAULT_FILENAME_TEMPLATE;
        }
        return configStoreTemplateName;
    }



    /**
     * Save the server configuration.
     *
     * @param omagServerConfig - configuration properties to save
     */
    @Override
    public void saveServerConfig(OMAGServerConfig omagServerConfig)
    {
        File    configStoreFile = new File(configStoreName);

        try
        {
            log.debug("Writing server config store properties: " + omagServerConfig);

            if (omagServerConfig == null)
            {
                configStoreFile.delete();
            }
            else
            {
                ObjectMapper objectMapper = new ObjectMapper();

                String configStoreFileContents = objectMapper.writeValueAsString(omagServerConfig);

                FileUtils.writeStringToFile(configStoreFile, configStoreFileContents, (String)null,false);
            }
        }
        catch (IOException   ioException)
        {
            log.debug("Unusable Server config Store :(", ioException);
        }
    }


    /**
     * Retrieve the configuration saved from a previous run of the server.
     *
     * @return server configuration
     */
    @Override
    public OMAGServerConfig  retrieveServerConfig()
    {
        File             configStoreFile     = new File(configStoreName);
        OMAGServerConfig newConfigProperties;

        try
        {
            log.debug("Retrieving server configuration properties");

            String configStoreFileContents = FileUtils.readFileToString(configStoreFile, "UTF-8");

            ObjectMapper objectMapper = new ObjectMapper();

            newConfigProperties = objectMapper.readValue(configStoreFileContents, OMAGServerConfig.class);
        }
        catch (IOException ioException)
        {
            /*
             * The config file is not found, create a new one ...
             */
            log.debug("New server config Store", ioException);

            newConfigProperties = null;
        }

        return newConfigProperties;
    }


    /**
     * Remove the server configuration.
     */
    @Override
    public void removeServerConfig()
    {
        File    configStoreFile = new File(configStoreName);

        configStoreFile.delete();
    }


    /**
     * Retrieve all the stored server configurations
     *
     * @return the set of server configurations present in this OMAG Server Config store
     */
    @Override
    public Set<OMAGServerConfig> retrieveAllServerConfigs() {
        final String methodName = "retrieveAllServerConfigs";
        Set<OMAGServerConfig> omagServerConfigSet = new HashSet<>();
        String templateString = getStoreTemplateName();
        Set<String> fileNames = getFileNames(templateString, methodName);
        for (String fileName : fileNames) {
            configStoreName = fileName;
            OMAGServerConfig config = retrieveServerConfig();
            omagServerConfigSet.add(config);
        }

        return omagServerConfigSet;
    }

    /**
     * Get filenames from the file system that match the store template.
     * Only supports 1 or 2 inserts in the template and they need to be in different url segments.
     * When a file is matched on the file system, the match for the insert is the serverName.
     *
     * @param methodName callers name for diagnostics
     * @return set of filenames fro the file System that match the template
     */
    protected Set<String> getFileNames(String templateString, String methodName) {
        if (isTemplateValid(templateString)  ) {
            // no inserts .... bad template supplied - error
            throw new OCFRuntimeException(DocStoreErrorCode.CONFIG_RETRIEVE_ALL_ERROR_INVALID_TEMPLATE.getMessageDefinition(templateString),
                                          this.getClass().getName(),
                                          methodName);
        }

        Set<String> fileNames = new HashSet<>();

        int firstIndex = templateString.indexOf(INSERT_FOR_FILENAME_TEMPLATE);
        int secondIndex = -1;
        if (firstIndex != -1 && templateString.length() > firstIndex + 3) {
            secondIndex = templateString.substring(firstIndex + 3).indexOf(INSERT_FOR_FILENAME_TEMPLATE);
        }
        try {
            if (firstIndex != -1 && secondIndex == -1) {
                // only one insert
                String firstPartOfTemplate = templateString.substring(0, firstIndex);
                String secondPartOfTemplate = templateString.substring(firstIndex + 3);

                Stream<Path> list = Files.list(Paths.get(firstPartOfTemplate));
                Set<String> serverNames = list.map(x -> x.getFileName().toString())
                        .collect(Collectors.toSet());
                for (String serverName : serverNames) {
                    String fileName = firstPartOfTemplate + serverName + secondPartOfTemplate;
                    fileNames.add(fileName);
                }

            } else {
                secondIndex = firstIndex + 3 + secondIndex;
                // 2 inserts - the first must be a folder name. hopefully the file name is not pathological with 2 inserts in the same segment.
                String firstPartOfTemplate = templateString.substring(0, firstIndex);
                String secondPartOfTemplate = templateString.substring(firstIndex + 3, secondIndex);
                String thirdPartOfTemplate = templateString.substring(secondIndex + 3);
                // take the serverName from the first insert and then look for its presence in the second insert position.
                Stream<Path> list = Files.list(Paths.get(firstPartOfTemplate));
                Set<String> serverNames = list.map(x -> x.getFileName().toString())
                        .collect(Collectors.toSet());
                for (String serverName : serverNames) {
                    String fileName = firstPartOfTemplate + serverName + secondPartOfTemplate + serverName + thirdPartOfTemplate;
                    fileNames.add(fileName);
                }
            }
        } catch (IOException e) {
            throw new OCFRuntimeException(DocStoreErrorCode.CONFIG_RETRIEVE_ALL_ERROR.getMessageDefinition(e.getClass().getName(), e.getMessage(), configStoreName),
                                          this.getClass().getName(),
                                          methodName, e);
        }
        return fileNames;
    }

    /**
     * Check whether the template string is valid.
     * @param templateString string to check
     * @return a falg indicating whether valid.
     */
    private boolean isTemplateValid(String templateString) {
        boolean isValid = true;
        StringTokenizer st = new StringTokenizer(templateString, INSERT_FOR_FILENAME_TEMPLATE);
        int tokenCount = st.countTokens();
        if (tokenCount == 0 || tokenCount > 2) {
            isValid = false;
        }
        // check for more than one insert in a segment- the logic does not handle this.
        while (st.hasMoreTokens()) {
            String token = st.nextToken();
            if (token.lastIndexOf("/") == -1) {
                isValid = false;
            }
        }

        return isValid;
    }

    /**
     * Close the config file
     */
    public void disconnect()
    {
        log.debug("Closing Config Store.");
    }
}
