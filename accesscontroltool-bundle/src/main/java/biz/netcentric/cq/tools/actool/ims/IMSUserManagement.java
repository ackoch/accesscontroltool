package biz.netcentric.cq.tools.actool.ims;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.http.Consts;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.StatusLine;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.ServiceUnavailableRetryStrategy;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.osgi.services.HttpClientBuilderFactory;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import biz.netcentric.cq.tools.actool.configmodel.AuthorizableConfigBean;
import biz.netcentric.cq.tools.actool.externalusermanagement.ExternalGroupManagement;
import biz.netcentric.cq.tools.actool.ims.IMSUserManagement.Configuration;
import biz.netcentric.cq.tools.actool.ims.request.ActionCommand;
import biz.netcentric.cq.tools.actool.ims.request.AddMembershipStep;
import biz.netcentric.cq.tools.actool.ims.request.CreateGroupStep;
import biz.netcentric.cq.tools.actool.ims.request.UserGroupActionCommand;
import biz.netcentric.cq.tools.actool.ims.response.AccessToken;
import biz.netcentric.cq.tools.actool.ims.response.ActionCommandResponse;

/**
 * Managing Adobe IMS groups via the UMAPI.
 * 
 * @see <a href="https://adobe-apiplatform.github.io/umapi-documentation/en/">UMAPI Documentation</a>
 */
@Component(configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd=Configuration.class)
public class IMSUserManagement implements ExternalGroupManagement {

    @ObjectClassDefinition(name = "AC Tool Adobe IMS User Management", description = "Settings of the API for user management tasks (UMAPI) in the Adobe IMS")
    protected static @interface Configuration {
        @AttributeDefinition(name = "UMAPI Base URL", description = "UMAPI Endpoint Base URL (the part prior the organization id)")
        String umapiBaseUrl() default "https://usermanagement.adobe.io/v2/usermanagement/action/";
        @AttributeDefinition(name = "Organization ID", description = "The unique identifier for an organization. This is a string of the form A495E53@AdobeOrg where the prefix before the @ is a hexadecimal number. You can find this value as part of the URL path for the organization in the Adobe Admin Console or in the Adobe Developer Console for your User Management integration.")
        String organizationId();
        @AttributeDefinition(name = "Test Only", description = "If true, parameter syntactic and (limited) semantic checking is done, but the specified operations are not performed, so no user/group accounts or group memberships are created, changed, or deleted.")
        boolean isTestOnly();
        @AttributeDefinition(name = "IMS Token Endpoint URL", description = "The URL from which to retrieve the access token.")
        String imsTokenEndpointUrl() default "https://ims-na1.adobelogin.com/ims/token/v3";
        @AttributeDefinition(name = "Client ID", description = "The client ID exposed in the Adobe IO Console for the UMAPI integration used to authorize the session. Also used as \"X-Api-Key\" header value.")
        String clientId();
        @AttributeDefinition(name = "Client Secret", description = "The client secret exposed in the Adobe IO Console for the UMAPI integration used to authorize the session.", type = AttributeType.PASSWORD)
        String clientSecret();
        @AttributeDefinition(name = "OAuth Scopes", description = "Scopes for which the access token is requested.")
        String[] scopes() default { "openid","AdobeID","user_management_sdk" };
        @AttributeDefinition(name = "Connect Timeout", description = "The maximum time to establish the connection with the remote host in milliseconds.")
        int connectTimeout() default 2000;
        @AttributeDefinition(name = "Socket Timeout", description = "The time waiting for data – after establishing the connection; maximum time of inactivity between two data packets. Given in milliseconds.")
        int socketTimeout() default 10000;
        @AttributeDefinition(name = "AEM Product Profiles", description = "The given product profile names are automatically added to each synchronized IMS group. The given product profile names must exist for an AEM product!")
        String[] productProfiles() default {};
    }

    public static final Logger LOG = LoggerFactory.getLogger(IMSUserManagement.class);
    private static final int MAX_NUM_COMMANDS_PER_REQUEST = 10;

    private final Configuration config;
    private final CloseableHttpClient client;

    /**
     * Strategy evaluating the {@code retry-after} response header with status code 429.
     * This class is not thread safe (due to mutable state in {@code retryDelayInSeconds}).
     * Necessary due to <a href="https://adobe-apiplatform.github.io/umapi-documentation/en/api/ActionsRef.html#actionThrottle">throttling of UMAPI requests</a>.
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6585#section-4">RFC6585</a>
     * 
     */
    static final class TooManyRequestsRetryStrategy implements ServiceUnavailableRetryStrategy {
        private static final double DEFAULT_MULTIPLIER = 1.5; // increases each time by 50%
        private final int maxRetryCount;
        private final int defaultRetryDelayInSeconds;
        private long retryDelayInMilliseconds;
        private final Random random = new Random();
 
        public TooManyRequestsRetryStrategy(int maxRetryCount, int defaultRetryDelayInSeconds) {
            super();
            this.maxRetryCount = maxRetryCount;
            this.defaultRetryDelayInSeconds = defaultRetryDelayInSeconds;
            this.retryDelayInMilliseconds = defaultRetryDelayInSeconds * 1000l;
        }

        @Override
        public boolean retryRequest(HttpResponse response, int executionCount, HttpContext context) {
            retryDelayInMilliseconds = defaultRetryDelayInSeconds * 1000l;
            boolean shouldRetry = (executionCount <= maxRetryCount && response.getStatusLine().getStatusCode() == 429);
            if (shouldRetry) {
                Header retryAfterHeader = response.getFirstHeader("Retry-After");
                if (retryAfterHeader != null) {
                    // just using the retry-after as is does not work reliably (due to potential concurrent requests)
                    retryDelayInMilliseconds = Integer.parseInt(retryAfterHeader.getValue()) * 1000l;
                    LOG.info("Received 429 status with Retry-After header {}", retryAfterHeader.getValue());
                }
                // make it exponential because the retry-after is unreliable (particularly with multiple requests in parallel)
                retryDelayInMilliseconds *= Math.pow(DEFAULT_MULTIPLIER, executionCount);
                // always add some jitter between 0 and default delay in seconds
                long jitter= random.nextInt(defaultRetryDelayInSeconds) * 1000l;
                retryDelayInMilliseconds += jitter;
                LOG.info("Schedule retry no {} of {} in {} milliseconds (with jitter of {} ms) due to 429 response", executionCount, maxRetryCount, retryDelayInMilliseconds, jitter);
            }
            return shouldRetry;
        }

        @Override
        public long getRetryInterval() {
            return retryDelayInMilliseconds;
        } 
    }

    @Activate
    public IMSUserManagement(Configuration config, @Reference HttpClientBuilderFactory httpClientBuilderFactory) {
        this.config = config;
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(config.connectTimeout())
                .setConnectionRequestTimeout(config.socketTimeout())
                .setSocketTimeout(config.socketTimeout()).build();
        client = httpClientBuilderFactory.newBuilder()
                .setDefaultRequestConfig(requestConfig)
                .setServiceUnavailableRetryStrategy(new TooManyRequestsRetryStrategy(3, 5))
                .build();
    }
    
    @Deactivate
    public void deactivate() throws IOException {
        client.close();
    }

    private URI getUserManagementActionUrl() throws URISyntaxException {
        URI uri = new URI(config.umapiBaseUrl() + config.organizationId());
        if (config.isTestOnly()) {
            uri = new URI(uri.getScheme(), uri.getAuthority(),
                    uri.getPath(), "testOnly=true", uri.getFragment());
        }
        return uri;
    }

    @Override
    public String getLabel() {
        return "Adobe IMS";
    }

    @Override
    public void updateGroups(Collection<AuthorizableConfigBean> groupConfigs) throws IOException {
        List<ActionCommand> actionCommands = new LinkedList<>();
        for (AuthorizableConfigBean groupConfig : groupConfigs) {
            UserGroupActionCommand actionCommand = new UserGroupActionCommand(groupConfig.getAuthorizableId());
            CreateGroupStep createGroupStep = new CreateGroupStep();
            createGroupStep.description = groupConfig.getDescription();
            actionCommand.addStep(createGroupStep);
            // optionally maintain product profile memberships in the group as well
            if (config.productProfiles() != null && config.productProfiles().length > 0) {
                AddMembershipStep addMembershipStep = new AddMembershipStep();
                addMembershipStep.productProfileIds =  new HashSet<>(Arrays.asList(config.productProfiles()));
                actionCommand.addStep(addMembershipStep);
            }
            actionCommands.add(actionCommand);
        }
        // update in batches of 10 commands
        AtomicInteger counter = new AtomicInteger();
        final Collection<List<ActionCommand>> actionCommandsBatches = actionCommands.stream().collect(Collectors.groupingBy
                (it->counter.getAndIncrement() / MAX_NUM_COMMANDS_PER_REQUEST))
                .values();
        String token = getOAuthServer2ServerToken();
        for (List<ActionCommand> actionCommandBatch : actionCommandsBatches) {
            ActionCommandResponse response = sendActionCommand(token, actionCommandBatch);
            if (!response.errors.isEmpty()) {
                throw new IOException("Errors updating groups: " + response.errors + " for request " + getRequestInfo(response.associatedRequest));
            }
            if (!response.warnings.isEmpty()) {
                LOG.warn("Some warnings during updating groups with request {}", getRequestInfo(response.associatedRequest));
                response.warnings.stream().forEach(w -> LOG.warn("Warning updating a group: {}", w));
            }
        }
    }

    static String getRequestInfo(HttpRequest request) throws IOException {
        if (request == null) {
            return "Unknown";
        }
        StringBuilder requestInfo = new StringBuilder();
        requestInfo.append(request);
        if (request instanceof HttpEntityEnclosingRequest) {
            HttpEntity entity = ((HttpEntityEnclosingRequest) request).getEntity();
            ByteArrayOutputStream bs = new ByteArrayOutputStream();
            entity.writeTo(bs);
            requestInfo.append("\nwith payload:\n").append(new String(bs.toByteArray()));
        }
        return requestInfo.toString();
    }

    private ActionCommandResponse sendActionCommand(String token, Collection<ActionCommand> actions) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        HttpPost httpPost;
        try {
            httpPost = new HttpPost(getUserManagementActionUrl());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Could not create valid URI from configuration", e);
        }
        String jsonPayload = objectMapper.writeValueAsString(actions);
        httpPost.setEntity(new StringEntity(jsonPayload, ContentType.create("application/json"))); // must be without charset
        httpPost.setHeader("Authorization", "Bearer " + token);
        httpPost.setHeader("X-Api-Key", config.clientId());
        ResponseHandler<ActionCommandResponse> rh = new ResponseHandler<ActionCommandResponse>() {
            @Override
            public ActionCommandResponse handleResponse(
                    final HttpResponse response) throws IOException {
                StatusLine statusLine = response.getStatusLine();
                HttpEntity entity = response.getEntity();
                if (statusLine.getStatusCode() >= 300) {
                    throw new HttpResponseException(
                            statusLine.getStatusCode(),
                            statusLine.getReasonPhrase() + ", body:" + EntityUtils.toString(entity) + ", for request " + getRequestInfo(httpPost));
                }
                if (entity == null) {
                    throw new ClientProtocolException("Response contains no content for request" + getRequestInfo(httpPost));
                }
                ActionCommandResponse actionCommandResponse = objectMapper.readValue(entity.getContent(), ActionCommandResponse.class);
                actionCommandResponse.associatedRequest = httpPost;
                return actionCommandResponse;
            }
        };
        LOG.debug("Calling UMAPI via {}", httpPost);
        return client.execute(httpPost, rh);
    }

    /**
     * Requests an access token using the OAuth Server to Server authentication flow (OAuth 2.0 client credential grant).
     * It is valid for 24 hours.
     * @return the access token
     * @throws IOException 
     * @see <a href="https://adobe-apiplatform.github.io/umapi-documentation/en/UM_Authentication.html">OAuth Server to Server Authentication</a>
     */
    private String getOAuthServer2ServerToken() throws IOException {
        HttpPost httpPost = new HttpPost(config.imsTokenEndpointUrl());
        List<NameValuePair> params = new ArrayList<>();
        params.add(new BasicNameValuePair("client_id", config.clientId()));
        params.add(new BasicNameValuePair("client_secret", config.clientSecret()));
        params.add(new BasicNameValuePair("grant_type", "client_credentials"));
        params.add(new BasicNameValuePair("scope", String.join(",", config.scopes())));
        UrlEncodedFormEntity entity = new UrlEncodedFormEntity(params, Consts.UTF_8);
        httpPost.setEntity(entity);

        ResponseHandler<AccessToken> rh = new ResponseHandler<AccessToken>() {
            @Override
            public AccessToken handleResponse(
                    final HttpResponse response) throws IOException {
                StatusLine statusLine = response.getStatusLine();
                HttpEntity entity = response.getEntity();
                if (statusLine.getStatusCode() >= 300) {
                    throw new HttpResponseException(
                            statusLine.getStatusCode(),
                            statusLine.getReasonPhrase() + ", body:" + EntityUtils.toString(entity));
                }
                if (entity == null) {
                    throw new ClientProtocolException("Response contains no content");
                }
                ObjectMapper objectMapper = new ObjectMapper();
                ContentType contentType = ContentType.getOrDefault(entity);
                Charset charset = contentType.getCharset();
                try (Reader reader = new InputStreamReader(entity.getContent(), charset)) {
                    return objectMapper.readValue(reader, AccessToken.class);
                }
            }
        };
        AccessToken token = client.execute(httpPost, rh);
        return token.token;
    }
}
