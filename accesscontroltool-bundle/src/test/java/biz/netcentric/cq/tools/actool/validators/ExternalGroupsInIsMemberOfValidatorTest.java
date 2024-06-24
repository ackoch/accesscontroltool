package biz.netcentric.cq.tools.actool.validators;

import static biz.netcentric.cq.tools.actool.configreader.YamlConfigurationMergerTest.getAcConfigurationForFile;
import static biz.netcentric.cq.tools.actool.configreader.YamlConfigurationMergerTest.getConfigurationMerger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import javax.jcr.Session;

import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import biz.netcentric.cq.tools.actool.configmodel.AcConfiguration;
import biz.netcentric.cq.tools.actool.validators.exceptions.InvalidExternalGroupUsageValidationException;

class ExternalGroupsInIsMemberOfValidatorTest {

    @Mock
    Session session;

    @Test
    public void testExternalIdSetCorrectlyOnRoleOnly() throws Exception {
        AcConfiguration acConfigurationForFile = getAcConfigurationForFile(getConfigurationMerger(), session,
                "externalIds/test-externalId-set-correctly-on-role-only.yaml");

        assertEquals(3, acConfigurationForFile.getAuthorizablesConfig().size());
    }

    @Test
    public void testExternalIdSetOnFragmentOverruledByConfigToBeAlllowed() throws Exception {
        AcConfiguration acConfigurationForFile = getAcConfigurationForFile(getConfigurationMerger(), session,
                "externalIds/test-externalId-set-on-fragment-overruled-by-config-to-be-allowed.yaml");

        assertEquals(3, acConfigurationForFile.getAuthorizablesConfig().size());
    }

    @Test
    public void testExternalIdSetOnFragmentInvalid() {
        assertThrows(InvalidExternalGroupUsageValidationException.class,
                () -> getAcConfigurationForFile(getConfigurationMerger(), session,
                        "externalIds/test-externalId-set-on-fragment-invalid.yaml"));
    }

}
