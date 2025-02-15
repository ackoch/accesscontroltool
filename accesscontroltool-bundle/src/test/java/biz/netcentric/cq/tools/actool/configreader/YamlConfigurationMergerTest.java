/*
 * (C) Copyright 2015 Netcentric AG.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package biz.netcentric.cq.tools.actool.configreader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.MockitoAnnotations.initMocks;

import java.io.IOException;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.ConfigurationPlugin;

import biz.netcentric.cq.tools.actool.configmodel.AcConfiguration;
import biz.netcentric.cq.tools.actool.configmodel.AuthorizableConfigBean;
import biz.netcentric.cq.tools.actool.configmodel.AuthorizablesConfig;
import biz.netcentric.cq.tools.actool.history.impl.PersistableInstallationLogger;
import biz.netcentric.cq.tools.actool.validators.ExternalGroupsInIsMemberOfValidator;
import biz.netcentric.cq.tools.actool.validators.exceptions.AcConfigBeanValidationException;
import biz.netcentric.cq.tools.actool.validators.impl.ObsoleteAuthorizablesValidatorImpl;

public class YamlConfigurationMergerTest {

    @Mock
    Session session;

    @BeforeEach
    public void setup() {
        initMocks(this);
    }

    @Test
    public void testMemberGroups() throws IOException, RepositoryException, AcConfigBeanValidationException {
        AcConfiguration acConfiguration = getAcConfigurationForFile(getConfigurationMerger(), session, "test-membergroups.yaml");
        final AuthorizablesConfig groups = acConfiguration.getAuthorizablesConfig();
        final AuthorizableConfigBean groupA = groups.getAuthorizableConfig("groupA");
        assertEquals(3, groupA.getIsMemberOf().length);
        final AuthorizableConfigBean groupB = groups.getAuthorizableConfig("groupB");
        assertEquals(2, groupB.getIsMemberOf().length);
        assertNotNull(groups.getAuthorizableConfig("groupC"));
        assertNotNull(groups.getAuthorizableConfig("groupD"));
    }

    @Test
    public void testEnsureIsMemberOfIsUsedWherePossible() throws RepositoryException, IOException, AcConfigBeanValidationException {
        AcConfiguration acConfiguration = getAcConfigurationForFile(getConfigurationMerger(), session, "test-membergroups.yaml");

        AuthorizableConfigBean groupAConfig = acConfiguration.getAuthorizablesConfig().getAuthorizableConfig("groupA");
        assertEquals("groupA", groupAConfig.getAuthorizableId());
        String[] members = groupAConfig.getIsMemberOf();

        assertArrayEquals(new String[]{"groupB", "groupC", "groupD"}, members, "check if groups have been copied over into isMemberOf field");
        //
        AuthorizableConfigBean groupBConfig = acConfiguration.getAuthorizablesConfig().getAuthorizableConfig("groupB");

        assertArrayEquals(new String[0], groupBConfig.getMembers(), "ensure that members that were added to isMemberOf are removed from original members arr");
    }

    @Test
    public void testReadInvalidYaml1() throws IOException, RepositoryException, AcConfigBeanValidationException {
        assertThrows(IllegalArgumentException.class,
                () -> getAcConfigurationForFile(getConfigurationMerger(), session, "test-invalid1.yaml"));
    }

    @Test
    public void testReadInvalidYaml2() throws IOException, RepositoryException, AcConfigBeanValidationException {
        assertThrows(AcConfigBeanValidationException.class,
                () -> getAcConfigurationForFile(getConfigurationMerger(), session, "test-invalid2.yaml"));
    }

    @Test
    public void testReadInvalidYaml3() throws IOException, RepositoryException, AcConfigBeanValidationException {
        assertThrows(AcConfigBeanValidationException.class,
                () -> getAcConfigurationForFile(getConfigurationMerger(), session, "test-invalid3.yaml"));
    }

    @Test()
    public void testReadEmptyYaml() throws IOException, RepositoryException, AcConfigBeanValidationException {
        getAcConfigurationForFile(getConfigurationMerger(), session, "test-empty.yaml");
    }

    @Test
    public void testConfigAdminInterpolatorFormat() {
        assertTrue(YamlConfigurationMerger.CONFIG_ADMIN_INTERPOLATOR_FORMAT.matcher("$[secret:db.password]").matches());
        assertTrue(YamlConfigurationMerger.CONFIG_ADMIN_INTERPOLATOR_FORMAT.matcher("$[env:PORT;default=8080]").matches());
        assertTrue(YamlConfigurationMerger.CONFIG_ADMIN_INTERPOLATOR_FORMAT.matcher("$[prop:my.property]").matches());
        assertTrue(YamlConfigurationMerger.CONFIG_ADMIN_INTERPOLATOR_FORMAT.matcher("$[env:PORT;type=Integer[];delimiter=,;default=8080,8081]").matches());
        assertFalse(YamlConfigurationMerger.CONFIG_ADMIN_INTERPOLATOR_FORMAT.matcher("${prop:my.property}").matches());
    }

    @Test
    public void testPlaceholdersWithoutInterpolationPlugin() throws IOException, RepositoryException, AcConfigBeanValidationException {
        AcConfiguration acConfiguration = getAcConfigurationForFile(getConfigurationMerger(), session, "test-placeholders.yaml");
        assertEquals("$[secret:password]", acConfiguration.getAuthorizablesConfig().getAuthorizableConfig("editor").getPassword());
    }

    @Test
    public void testPlaceholdersWithInterpolationPlugin() throws IOException, RepositoryException, AcConfigBeanValidationException {
        YamlConfigurationMerger yamlConfigMerger = getConfigurationMerger();
        yamlConfigMerger.interpolationPlugin = new ConfigurationPlugin() {
            // replace all placeholders with value "resolved"
            @Override
            public void modifyConfiguration(ServiceReference<?> reference, Dictionary<String, Object> properties) {
                // replace all placeholders
                String value = (String) properties.get(YamlConfigurationAdminPluginScalarConstructor.KEY);
                String newValue = value.replaceAll("\\$\\[(env|secret|prop):([^\\]]*)\\]", "resolved-$2");
                properties.put(YamlConfigurationAdminPluginScalarConstructor.KEY, newValue);
            }
        };
        AcConfiguration acConfiguration = getAcConfigurationForFile(yamlConfigMerger, session, "test-placeholders.yaml");
        assertEquals("resolved-password", acConfiguration.getAuthorizablesConfig().getAuthorizableConfig("editor").getPassword());
        assertEquals("RESOLVED-PASSWORD", acConfiguration.getAuthorizablesConfig().getAuthorizableConfig("editor2").getPassword());
    }

    public static AcConfiguration getAcConfigurationForFile(YamlConfigurationMerger merger, Session session, String testConfigFile)
            throws IOException, RepositoryException, AcConfigBeanValidationException {
        final String config = YamlConfigReaderTest.getTestConfigAsString(testConfigFile);
        final ConfigReader reader = new YamlConfigReader();
        final Map<String, String> configs = new HashMap<String, String>();
        configs.put("/etc/config", config);
        AcConfiguration acConfiguration = merger.getMergedConfigurations(configs, new PersistableInstallationLogger(), reader, session);
        return acConfiguration;
    }

    public static YamlConfigurationMerger getConfigurationMerger() {
        YamlConfigurationMerger merger = spy(new YamlConfigurationMerger());
        doReturn(null).when(merger).getAceBeanValidator(ArgumentMatchers.<Set<String>>any());
        merger.yamlMacroProcessor = new YamlMacroProcessorImpl();
        merger.obsoleteAuthorizablesValidator = new ObsoleteAuthorizablesValidatorImpl();
        merger.virtualGroupProcessor = new VirtualGroupProcessor();
        merger.testUserConfigsCreator = new TestUserConfigsCreator();
        merger.externalGroupsInIsMemberOfValidator = new ExternalGroupsInIsMemberOfValidator();
        return merger;
    }
}
