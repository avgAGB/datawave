package datawave.security.cache;

import static datawave.webservice.query.exception.DatawaveErrorCode.UNKNOWN_SERVER_ERROR;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.annotation.security.DeclareRoles;
import javax.annotation.security.RolesAllowed;
import javax.annotation.security.RunAs;
import javax.ejb.LocalBean;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;

import datawave.configuration.ConfigurationEvent;
import datawave.configuration.DatawaveEmbeddedProjectStageHolder;
import datawave.configuration.RefreshLifecycle;
import datawave.security.authorization.DatawavePrincipal;
import datawave.security.util.DnUtils;
import datawave.webservice.common.cache.SharedCacheCoordinator;
import datawave.webservice.common.connection.AccumuloConnectionFactory;
import datawave.webservice.common.exception.DatawaveWebApplicationException;
import datawave.webservice.query.exception.QueryException;
import datawave.webservice.result.GenericResponse;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.shared.SharedCountListener;
import org.apache.curator.framework.recipes.shared.SharedCountReader;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.deltaspike.core.api.exclude.Exclude;
import org.apache.deltaspike.core.api.jmx.JmxManaged;
import org.apache.deltaspike.core.api.jmx.MBean;
import org.infinispan.AdvancedCache;
import org.infinispan.Cache;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.context.Flag;
import org.infinispan.filter.KeyValueFilter;
import org.infinispan.iteration.EntryIterable;
import org.infinispan.metadata.Metadata;
import org.jboss.security.AuthenticationManager;
import org.jboss.security.CacheableManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A service for managing the credentials cache. It should be noted that there are two caches: one for authorization service query responses and another for
 * calculated {@link DatawavePrincipal} objects. Most of the methods here operate on the cache containing {@link DatawavePrincipal}s.
 */
@Path("/Security/Admin/Credentials")
@LocalBean
@Startup
// tells the container to initialize on startup
@Singleton
// this is a singleton bean in the container
@RunAs("InternalUser")
@RolesAllowed({"JBossAdministrator", "Administrator", "SecurityUser", "InternalUser"})
@DeclareRoles({"JBossAdministrator", "Administrator", "SecurityUser", "InternalUser"})
@MBean
@Exclude(ifProjectStage = DatawaveEmbeddedProjectStageHolder.DatawaveEmbedded.class)
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
// transactions not supported directly by this bean
public class CredentialsCacheBean {
    protected Logger log = LoggerFactory.getLogger(getClass());
    private static final String FLUSH_PRINCIPALS_COUNTER = "flushPrincipals";
    
    @Resource(name = "java:jboss/jaas/datawave")
    private AuthenticationManager authManager;
    
    @Inject
    @PrincipalsCache
    private Cache<String,Principal> principalsCache;
    
    @Inject
    private SharedCacheCoordinator cacheCoordinator;
    
    @Inject
    private AccumuloConnectionFactory accumuloConnectionFactory;
    
    private Set<String> accumuloUserAuths = new HashSet<>();
    
    private Exception flushPrincipalsException;
    
    @PostConstruct
    protected void postConstruct() {
        try {
            retrieveAccumuloAuthorizations();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        
        // Watch for eviction requests coming from other web servers. If we get one, do a local eviction
        // as well. Note that this will push through to Accumulo, and there is a possible edge case. If
        // host one evicts and removes from accumulo, then host 2 gets a new request for the same DN and
        // repopulates but then host 2 gets the eviction notice, it will remove the info when it shouldn't
        // have. In the end, this shouldn't be a huge problem because this is just a cache and worst case
        // is that we re-query the authorization service when we don't need to. The likelihood of this
        // race condition is very small...
        cacheCoordinator.watchForEvictions(new SharedCacheCoordinator.EvictionCallback() {
            @Override
            public void evict(String dn) {
                evictInternal(dn, false);
            }
        });
        
        try {
            cacheCoordinator.registerCounter(FLUSH_PRINCIPALS_COUNTER, new SharedCountListener() {
                @Override
                public void stateChanged(CuratorFramework client, ConnectionState newState) {
                    // TODO Auto-generated method stub
                }
                
                @Override
                public void countHasChanged(SharedCountReader sharedCount, int newCount) throws Exception {
                    if (!cacheCoordinator.checkCounter(FLUSH_PRINCIPALS_COUNTER, newCount))
                        flushPrincipalsInternal(false);
                }
            });
        } catch (Exception e) {
            throw new RuntimeException("Unable to create shared counters: " + e.getMessage(), e);
        }
    }
    
    /**
     * Removes all entries from the credentials cache and the authorization service response cache. Invoking this method will guarantee that user authentication
     * will hit the authorization service (with one limitation--JBoss internally caches principlals for 30 minutes by default).
     *
     * @return a string indicating cache flush was successful
     */
    @GET
    @Path("/flushAll")
    @JmxManaged
    public String flushAll() {
        try {
            flushAllInternal(true);
            return "All credentials caches cleared.";
        } catch (Exception e) {
            GenericResponse<String> response = new GenericResponse<>();
            response.addException(new QueryException(UNKNOWN_SERVER_ERROR, e));
            throw new DatawaveWebApplicationException(e, response);
        }
    }
    
    private void flushAllInternal(boolean incrementCounter) throws Exception {
        flushPrincipalsInternal(incrementCounter);
    }
    
    /**
     * Removes all entries from the principals cache. Invoking this method will ensure that merged credentials and authorization service result translations are
     * applied the next time a principal is built. However, it does not cause the authorization service to be re-queried.
     *
     * @return a string indicating cache flush was successful
     */
    @GET
    @Path("/flushPrincipals")
    @JmxManaged
    public String flushPrincipals() {
        try {
            flushPrincipalsInternal(true);
            return "Credentials cache cleared.";
        } catch (Exception e) {
            GenericResponse<String> response = new GenericResponse<>();
            response.addException(new QueryException(UNKNOWN_SERVER_ERROR, e));
            throw new DatawaveWebApplicationException(e, response);
        }
    }
    
    private void flushPrincipalsInternal(boolean incrementCounter) throws Exception {
        AdvancedCache<String,Principal> advancedCache = principalsCache.getAdvancedCache();
        // If we're not incrementing the counter (i.e., this isn't the server that initiated the request),
        // then we'll ensure that when we clear the cache, we don't attempt to write through to the persistent
        // store. The advantage of doing this is that, if there are a large number of web servers, they do not
        // all attempt to clear the backing store at the same time.
        if (!incrementCounter) {
            advancedCache = advancedCache.withFlags(Flag.SKIP_CACHE_STORE, Flag.SKIP_CACHE_LOAD);
        }
        advancedCache.clear();
        flushAuthManager();
        if (incrementCounter) {
            cacheCoordinator.incrementCounter(FLUSH_PRINCIPALS_COUNTER);
        }
    }
    
    /**
     * Evicts {@code dn} from the principals cache. Any authorization services responses containing the DN are also flushed.
     *
     * @param dn
     *            the DN to evict from the cache
     *
     * @return a string indicating cache flush was successful
     */
    @GET
    @Path("/{dn}/evict")
    @Produces({"text/plain"})
    @JmxManaged
    public String evict(@PathParam("dn") String dn) {
        String removed = evictInternal(dn, true);
        return "Evicted " + removed + " from the credentials cache.";
    }
    
    public String evictInternal(String dn, boolean sendNotice) {
        String[] dns = DnUtils.splitProxiedSubjectIssuerDNs(dn);
        for (int i = 0; i < dns.length; ++i)
            dns[i] = DnUtils.normalizeDN(dns[i]);
        String combinedDN = DnUtils.buildProxiedDN(dns);
        
        // Remove the principal, as well as any others that involve this
        // one in its proxied entities chain.
        AdvancedCache<String,Principal> ignoreReturnCache = principalsCache.getAdvancedCache().withFlags(Flag.IGNORE_RETURN_VALUES);
        Principal removed = ignoreReturnCache.get(combinedDN);
        if (removed != null) {
            ignoreReturnCache.remove(combinedDN);
        }
        DnList otherDNs = listDNsMatching(dns[0]);
        if (otherDNs != null) {
            for (String otherDN : otherDNs.getDns()) {
                log.debug("Evicting {} due to partial match with {}. Original DN: {}", otherDN, dns[0], dn);
                ignoreReturnCache.remove(otherDN);
            }
        }
        
        // Remove principals from the JBoss cached authentication manager, if we have one in use.
        if (authManager instanceof CacheableManager) {
            @SuppressWarnings("unchecked")
            CacheableManager<?,Principal> cacheableManager = (CacheableManager<?,Principal>) authManager;
            Set<Principal> cachedKeys = cacheableManager.getCachedKeys();
            if (cachedKeys != null) {
                ArrayList<Principal> principalsToFlush = new ArrayList<>();
                for (Principal p : cacheableManager.getCachedKeys()) {
                    if (p.getName().contains(dns[0]))
                        principalsToFlush.add(p);
                }
                for (Principal p : principalsToFlush) {
                    log.debug("Evicting {} from the JBoss authentication cache. Original DN: {}", p, dn);
                    cacheableManager.flushCache(p);
                }
            }
        }
        
        if (sendNotice) {
            // Tell the rest of the cluster that we need to evict this DN
            try {
                cacheCoordinator.sendEvictMessage(dn);
            } catch (Exception e) {
                log.error("Unable to send eviction message to the rest of the cluster: {}", e.getMessage(), e);
            }
        }
        
        return (removed == null) ? "cached authorization responses for " + dn : removed.getName();
    }
    
    @JmxManaged
    public int numEntries() {
        return principalsCache.size();
    }
    
    /**
     * List DNs for principals stored in the cache. This list can include single DN values as well as values containing multiple DNs surrounded with {@code <>},
     * for when proxied entities were in use (via the X-ProxiedEntitiesChain and X-ProxiedIssuersChain headers).
     *
     * @param includeAll
     *            If true, then all enties are returned. Otherwise, only those entries in-memory on this server are returned. Default is false.
     *
     * @return a {@link DnList} containing DNs for principals stored in the cache.
     */
    @GET
    @Path("/listDNs")
    @Produces({"text/plain", "application/xml", "text/xml", "application/json", "text/html"})
    @JmxManaged
    public DnList listDNs(@QueryParam("includeAll") boolean includeAll) {
        AdvancedCache<String,Principal> advancedCache = principalsCache.getAdvancedCache();
        if (!includeAll) {
            advancedCache = advancedCache.withFlags(Flag.SKIP_CACHE_LOAD);
        }
        Set<String> keySet = advancedCache.keySet();
        Map<String,CacheEntry<String,Principal>> entries = advancedCache.getAllCacheEntries(keySet);
        // Call a second time to ensure entries loaded from the cache store are populated correctly
        if (includeAll) {
            entries = advancedCache.getAllCacheEntries(keySet);
        }
        return new DnList(entries);
    }
    
    /**
     * Lists all DNs contained in the principals cache containing the substring {@code substr}.
     *
     * @param substr
     *            the substring to search for in the principals cache
     * @return a {@link DnList} containing DNs for principals stored in the cache.
     */
    @GET
    @Path("/listMatching")
    @Produces({"text/plain", "application/xml", "text/xml", "application/json", "text/html"})
    @JmxManaged
    public DnList listDNsMatching(@QueryParam("substring") String substr) {
        substr = substr.toLowerCase();
        AdvancedCache<String,Principal> advancedCache = principalsCache.getAdvancedCache();
        final String substring = substr;
        final HashSet<String> matchingKeys = new HashSet<>();
        KeyValueFilter<String,Principal> kvFilter = new KeyValueFilter<String,Principal>() {
            @Override
            public boolean accept(String key, Principal value, Metadata metadata) {
                return key.toLowerCase().contains(substring);
            }
        };
        try (EntryIterable<String,Principal> iterable = advancedCache.filterEntries(kvFilter)) {
            for (Map.Entry<String,Principal> entry : iterable) {
                matchingKeys.add(entry.getKey());
            }
        }
        Map<String,CacheEntry<String,Principal>> entries = advancedCache.getAllCacheEntries(matchingKeys);
        return new DnList(entries);
    }
    
    /**
     * Retrieves the {@link DatawavePrincipal} for {@code dn} from the principals cache.
     *
     * @param dn
     *            the DN of the {@link DatawavePrincipal} to retrieve from the principals cache
     *
     * @return a string indicating cache flush was successful
     */
    @GET
    @Path("/{dn}/list")
    @Produces({"text/plain", "application/xml", "text/xml", "application/json", "text/html"})
    @JmxManaged
    public DatawavePrincipal list(@PathParam("dn") String dn) {
        return (DatawavePrincipal) principalsCache.get(dn);
    }
    
    private void flushAuthManager() {
        // Remove principals from the JBoss cached authentication manager, if we have one in use.
        if (authManager instanceof CacheableManager) {
            @SuppressWarnings("unchecked")
            CacheableManager<?,Principal> cacheableManager = (CacheableManager<?,Principal>) authManager;
            cacheableManager.flushCache();
        }
    }
    
    @SuppressWarnings("unused")
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    // transactions not supported directly by this bean
    public void onConfigurationUpdate(@Observes ConfigurationEvent event) {
        log.debug("Received a configuration update event. Re-querying Accumulo user authorizations and invalidating principals cache.");
        try {
            HashSet<String> oldAccumuloAuths = new HashSet<>(accumuloUserAuths);
            
            if (log.isTraceEnabled()) {
                log.trace("Received refresh event on 0x{}. Retrieving new Accumulo authorizations.", Integer.toHexString(System.identityHashCode(this)));
            }
            retrieveAccumuloAuthorizations();
            
            if (!accumuloUserAuths.equals(oldAccumuloAuths)) {
                // Flush the principals cache (and attempt to tell other web servers to do the same)
                // If there's a problem, however, do not fail the entire refresh event. Do that at the end.
                try {
                    flushPrincipals();
                } catch (Exception e) {
                    flushPrincipalsException = e;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    @SuppressWarnings("unused")
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void onRefreshComplete(@Observes RefreshLifecycle refreshLifecycle) {
        switch (refreshLifecycle) {
            case INITIATED:
                flushPrincipalsException = null;
                break;
            case COMPLETE:
                // Now that the refresh is complete, throw any flush principals exception that might have happened.
                // We want to let the rest of the refresh complete internally before throwing the error so that we
                // don't leave this server in an inconsistent state.
                if (flushPrincipalsException != null) {
                    throw new RuntimeException("Error flushing principals cache: " + flushPrincipalsException.getMessage(), flushPrincipalsException);
                }
                break;
        }
    }
    
    @GET
    @Path("/listAccumuloAuths")
    @Produces({"text/plain", "application/json"})
    @JmxManaged
    public Set<String> getAccumuloUserAuths() {
        return accumuloUserAuths;
    }
    
    @GET
    @Path("/reloadAccumuloAuths")
    @Produces({"application/xml", "text/xml", "application/json"})
    public GenericResponse<String> reloadAccumuloAuthorizations() {
        GenericResponse<String> response = new GenericResponse<>();
        try {
            retrieveAccumuloAuthorizations();
            response.setResult("Authorizations reloaded. Remember to flush the principals cache to ensure principals are reloaded with new auths applied.");
            return response;
        } catch (Exception e) {
            response.setResult("Unable to reload Accumulo authorizations.");
            throw new DatawaveWebApplicationException(e, response);
        }
    }
    
    private void retrieveAccumuloAuthorizations() throws Exception {
        Map<String,String> trackingMap = accumuloConnectionFactory.getTrackingMap(Thread.currentThread().getStackTrace());
        Connector c = accumuloConnectionFactory.getConnection(AccumuloConnectionFactory.Priority.ADMIN, trackingMap);
        try {
            Authorizations auths = c.securityOperations().getUserAuthorizations(c.whoami());
            HashSet<String> authSet = new HashSet<>();
            for (byte[] auth : auths.getAuthorizations()) {
                authSet.add(new String(auth).intern());
            }
            accumuloUserAuths = Collections.unmodifiableSet(authSet);
            log.debug("Accumulo User Authorizations: {}", accumuloUserAuths);
        } finally {
            accumuloConnectionFactory.returnConnection(c);
        }
    }
}