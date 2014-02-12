/*
*
* Copyright 2013 Netflix, Inc.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
*/
package com.netflix.client;

import java.net.URI;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.IClientConfig;
import com.netflix.loadbalancer.AbstractLoadBalancer;
import com.netflix.loadbalancer.AvailabilityFilteringRule;
import com.netflix.loadbalancer.ILoadBalancer;
import com.netflix.loadbalancer.LoadBalancerStats;
import com.netflix.loadbalancer.Server;
import com.netflix.loadbalancer.ServerStats;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.Stopwatch;
import com.netflix.servo.monitor.Timer;
import com.netflix.utils.RxUtils;
import static com.netflix.client.LoadBalancerExecutor.CallableToObservable.toObsevableProvider;

/**
 * Abstract class that provides the integration of client with load balancers.
 * 
 * @author awang
 *
 */
public abstract class AbstractLoadBalancerAwareClient<S extends ClientRequest, T extends IResponse> 
extends LoadBalancerExecutor implements IClient<S, T>, IClientConfigAware {    
    
    private static final Logger logger = LoggerFactory.getLogger(AbstractLoadBalancerAwareClient.class);
    
    public AbstractLoadBalancerAwareClient(ILoadBalancer lb) {
        super(lb);
    }
    
    /**
     * Delegate to {@link #initWithNiwsConfig(IClientConfig)}
     * @param clientConfig
     */
    public AbstractLoadBalancerAwareClient(ILoadBalancer lb, IClientConfig clientConfig) {
        super(lb, clientConfig);        
    }
    
    /**
     * Determine if an exception should contribute to circuit breaker trip. If such exceptions happen consecutively
     * on a server, it will be deemed as circuit breaker tripped and enter into a time out when it will be
     * skipped by the {@link AvailabilityFilteringRule}, which is the default rule for load balancers.
     */
    @Deprecated
    protected boolean isCircuitBreakerException(Throwable e) {
        if (getErrorHandler() != null) {
            return getErrorHandler().isCircuitTrippingException(e);
        }
        return false;
    }
        
    /**
     * Determine if operation can be retried if an exception is thrown. For example, connect 
     * timeout related exceptions
     * are typically retriable.
     * 
     */
    @Deprecated
    protected boolean isRetriableException(Throwable e) {
        if (getErrorHandler() != null) {
            return getErrorHandler().isRetriableException(e, true);
        } 
        return false;
    }
    
    /**
     * Execute the request on single server after the final URI is calculated. This method takes care of
     * retries and update server stats.
     *  
     */
    protected T executeOnSingleServer(final S request, final IClientConfig requestConfig) throws ClientException {
        String host = request.getUri().getHost();
        Preconditions.checkNotNull(host);
        int port = request.getUri().getPort();
        Preconditions.checkArgument(port > 0, "port is undefined");
        ClientCallableProvider<T> callableProvider = new ClientCallableProvider<T>() {
            @Override
            public T executeOnServer(Server server) throws Exception {
                return execute(request, requestConfig);
            }
        };
        
        RequestSpecificRetryHandler<S> handler = getRequestSpecificRetryHandler(request, requestConfig);
        
        try {
            return RxUtils.getSingleValueWithRealErrorCause(retrySameServer(new Server(host, port), toObsevableProvider(callableProvider), handler));
        } catch (Exception e) {
            if (e instanceof ClientException) {
                throw (ClientException) e;
            } else {
                throw new ClientException(e);
            }
        }
    }

    public T executeWithLoadBalancer(S request) throws ClientException {
        return executeWithLoadBalancer(request, null);
    }

    /**
     * This method should be used when the caller wants to dispatch the request to a server chosen by
     * the load balancer, instead of specifying the server in the request's URI. 
     * It calculates the final URI by calling {@link #computeFinalUriWithLoadBalancer(ClientRequest)}
     * and then calls {@link #execute(ClientRequest)}.
     * 
     * @param request request to be dispatched to a server chosen by the load balancer. The URI can be a partial
     * URI which does not contain the host name or the protocol.
     */
    public T executeWithLoadBalancer(final S request, final IClientConfig requestConfig) throws ClientException {
        ClientCallableProvider<T> callableProvider = new ClientCallableProvider<T>() {
            @SuppressWarnings("unchecked")
            @Override
            public T executeOnServer(Server server) throws Exception {
                URI finalUri = reconstructURIWithServer(server, request.getUri());
                S requestForServer = (S) request.replaceUri(finalUri);
                return execute(requestForServer, requestConfig);
            }
        };
        RequestSpecificRetryHandler<S> handler = getRequestSpecificRetryHandler(request, requestConfig);
        
        try {
            return RxUtils.getSingleValueWithRealErrorCause(retryWithLoadBalancer(toObsevableProvider(callableProvider), request.getUri(), handler, request.getLoadBalancerKey()));
        } catch (Exception e) {
            if (e instanceof ClientException) {
                throw (ClientException) e;
            } else {
                throw new ClientException(e);
            }
        }
        
    }
    
    public abstract RequestSpecificRetryHandler<S> getRequestSpecificRetryHandler(S request, IClientConfig requestConfig);

    @Deprecated
    protected boolean isRetriable(S request) {
        if (request.isRetriable()) {
            return true;            
        } else {
            boolean retryOkayOnOperation = okToRetryOnAllOperations;
            IClientConfig overriddenClientConfig = request.getOverrideConfig();
            if (overriddenClientConfig != null) {
                retryOkayOnOperation = overriddenClientConfig.getPropertyAsBoolean(CommonClientConfigKey.RequestSpecificRetryOn, okToRetryOnAllOperations);
            }
            return retryOkayOnOperation;
        }
    }
    
}


