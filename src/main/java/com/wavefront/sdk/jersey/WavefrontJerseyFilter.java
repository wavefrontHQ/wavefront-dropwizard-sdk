package com.wavefront.sdk.jersey;

import com.wavefront.internal.reporter.SdkReporter;
import com.wavefront.internal_reporter_java.io.dropwizard.metrics5.MetricName;
import com.wavefront.sdk.common.Pair;
import com.wavefront.sdk.common.application.ApplicationTags;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.TextMap;
import io.opentracing.tag.Tags;
import io.opentracing.propagation.Format;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ExtendedUriInfo;
import org.glassfish.jersey.server.internal.routing.RoutingContext;

import javax.annotation.Nullable;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.core.MultivaluedMap;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Optional;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.Iterator;
import java.util.AbstractMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import jersey.repackaged.com.google.common.base.Preconditions;

import static com.wavefront.sdk.common.Constants.NULL_TAG_VAL;
import static com.wavefront.sdk.common.Constants.WAVEFRONT_PROVIDED_SOURCE;
import static com.wavefront.sdk.jersey.Constants.JERSEY_SERVER_COMPONENT;

/**
 * A filter to generate Wavefront metrics and histograms for Jersey API requests/responses.
 *
 * @author Sushant Dewan (sushant@wavefront.com).
 */
public class WavefrontJerseyFilter implements ContainerRequestFilter, ContainerResponseFilter {

  private final SdkReporter wfJerseyReporter;
  private final ApplicationTags applicationTags;
  private final ThreadLocal<Long> startTime = new ThreadLocal<>();
  private final ThreadLocal<Long> startTimeCpuNanos = new ThreadLocal<>();
  private final ConcurrentMap<MetricName, AtomicInteger> gauges = new ConcurrentHashMap<>();
  private final String PROPERTY_NAME = "com.wavefront.sdk.jersey.internal.SpanWrapper.activeSpanWrapper";
  @Nullable
  private final Tracer tracer;

  private WavefrontJerseyFilter(SdkReporter wfJerseyReporter, ApplicationTags applicationTags,
                                @Nullable Tracer tracer) {
    Preconditions.checkNotNull(wfJerseyReporter, "Invalid JerseyReporter");
    Preconditions.checkNotNull(applicationTags, "Invalid ApplicationTags");
    this.wfJerseyReporter = wfJerseyReporter;
    this.applicationTags = applicationTags;
    this.tracer = tracer;
  }

  public static final class Builder {

    private final SdkReporter wfJerseyReporter;
    private final ApplicationTags applicationTags;
    @Nullable
    private Tracer tracer;

    public Builder(SdkReporter wfJerseyReporter, ApplicationTags applicationTags) {
      this.wfJerseyReporter = wfJerseyReporter;
      this.applicationTags = applicationTags;
    }

    public Builder withTracer(Tracer tracer) {
      this.tracer = tracer;
      return this;
    }

    public WavefrontJerseyFilter build() {
      return new WavefrontJerseyFilter(wfJerseyReporter, applicationTags, tracer);
    }

  }

  @Override
  public void filter(ContainerRequestContext containerRequestContext) throws IOException {
    if (containerRequestContext instanceof ContainerRequest) {
      ContainerRequest request = (ContainerRequest) containerRequestContext;
      startTime.set(System.currentTimeMillis());
      startTimeCpuNanos.set(ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime());
      Optional<Pair<String, String>> optionalPair = MetricNameUtils.metricNameAndPath(request);
      if (!optionalPair.isPresent()) {
        return;
      }
      String requestMetricKey = optionalPair.get()._1;
      String finalMatchingPath = optionalPair.get()._2;
      ExtendedUriInfo uriInfo = request.getUriInfo();
      Pair<String, String> pair = getClassAndMethodName(uriInfo);
      String finalClassName = pair._1;
      String finalMethodName = pair._2;

      if (tracer != null) {
        Tracer.SpanBuilder spanBuilder = tracer.buildSpan(finalMethodName).
                withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER).
                withTag("jersey.resource.class", finalClassName).
                withTag("jersey.path", finalMatchingPath);
        SpanContext parentSpanContext = parentSpanContext(containerRequestContext);
        if (parentSpanContext != null) {
          spanBuilder.asChildOf(parentSpanContext);
        }
        Scope scope = spanBuilder.startActive(false);
        decorateRequest(containerRequestContext, scope.span());
        containerRequestContext.setProperty(PROPERTY_NAME, scope);
      }

       /* Gauges
       * 1) jersey.server.request.api.v2.alert.summary.GET.inflight
       * 2) jersey.server.total_requests.inflight
       */
       getGaugeValue(new MetricName(requestMetricKey + ".inflight",
          getCompleteTagsMap(finalClassName, finalMethodName))).incrementAndGet();
       getGaugeValue(new MetricName("total_requests.inflight",
          new HashMap<String, String>() {{
            put("cluster", applicationTags.getCluster() == null ? NULL_TAG_VAL :
                applicationTags.getCluster());
            put("service", applicationTags.getService());
            put("shard", applicationTags.getShard() == null ? NULL_TAG_VAL :
                applicationTags.getShard());
          }})).incrementAndGet();
    }
  }

  @Override
  public void filter(ContainerRequestContext containerRequestContext,
                     ContainerResponseContext containerResponseContext)
      throws IOException {
    if (tracer != null) {
      try {
        Scope scope = (Scope) containerRequestContext.getProperty(PROPERTY_NAME);
        if (scope != null) {
          decorateResponse(containerResponseContext, scope.span());
          scope.close();
          scope.span().finish();
        }
      } catch (ClassCastException ex) {
        // no valid scope found
      }
    }
    if (containerRequestContext instanceof ContainerRequest) {
      ContainerRequest request = (ContainerRequest) containerRequestContext;
      ExtendedUriInfo uriInfo = request.getUriInfo();

      Pair<String, String> pair = getClassAndMethodName(uriInfo);
      String finalClassName = pair._1;
      String finalMethodName = pair._2;

      Optional<Pair<String, String>> requestOptionalPair = MetricNameUtils.metricNameAndPath(request);
      if (!requestOptionalPair.isPresent()) {
        return;
      }
      String requestMetricKey = requestOptionalPair.get()._1;
      Optional<String> responseOptionalPair = MetricNameUtils.metricName(request, containerResponseContext);
      if (!responseOptionalPair.isPresent()) {
        return;
      }
      String responseMetricKey = responseOptionalPair.get();

      /* Gauges
       * 1) jersey.server.request.api.v2.alert.summary.GET.inflight
       * 2) jersey.server.total_requests.inflight
       */
      Map<String, String> completeTagsMap = getCompleteTagsMap(finalClassName, finalMethodName);

      /*
       * Okay to do map.get(key) as the key will definitely be present in the map during the
       * response phase.
       */
      gauges.get(new MetricName(requestMetricKey + ".inflight", completeTagsMap)).
          decrementAndGet();
      gauges.get(new MetricName("total_requests.inflight",
          new HashMap<String, String>() {{
            put("cluster", applicationTags.getCluster() == null ? NULL_TAG_VAL :
                applicationTags.getCluster());
            put("service", applicationTags.getService());
            put("shard", applicationTags.getShard() == null ? NULL_TAG_VAL :
                applicationTags.getShard());
          }})).decrementAndGet();

      // Response metrics and histograms below
      Map<String, String> aggregatedPerShardMap = new HashMap<String, String>() {{
        put("cluster", applicationTags.getCluster() == null ? NULL_TAG_VAL :
            applicationTags.getCluster());
        put("service", applicationTags.getService());
        put("shard", applicationTags.getShard() == null ? NULL_TAG_VAL :
            applicationTags.getShard());
        put("jersey.resource.class", finalClassName);
        put("jersey" + ".resource.method", finalMethodName);
        put("source", WAVEFRONT_PROVIDED_SOURCE);
      }};

      Map<String, String> overallAggregatedPerSourceMap = new HashMap<String, String>() {{
        put("cluster", applicationTags.getCluster() == null ? NULL_TAG_VAL :
            applicationTags.getCluster());
        put("service", applicationTags.getService());
        put("shard", applicationTags.getShard() == null ? NULL_TAG_VAL :
            applicationTags.getShard());
      }};

      Map<String, String> overallAggregatedPerShardMap = new HashMap<String, String>() {{
        put("cluster", applicationTags.getCluster() == null ? NULL_TAG_VAL :
            applicationTags.getCluster());
        put("service", applicationTags.getService());
        put("shard", applicationTags.getShard() == null ? NULL_TAG_VAL :
            applicationTags.getShard());
        put("source", WAVEFRONT_PROVIDED_SOURCE);
      }};

      Map<String, String> aggregatedPerServiceMap = new HashMap<String, String>() {{
        put("cluster", applicationTags.getCluster() == null ? NULL_TAG_VAL :
            applicationTags.getCluster());
        put("service", applicationTags.getService());
        put("jersey.resource.class", finalClassName);
        put("jersey" + ".resource.method", finalMethodName);
        put("source", WAVEFRONT_PROVIDED_SOURCE);
      }};

      Map<String, String> overallAggregatedPerServiceMap = new HashMap<String, String>() {{
        put("cluster", applicationTags.getCluster() == null ? NULL_TAG_VAL :
            applicationTags.getCluster());
        put("service", applicationTags.getService());
        put("source", WAVEFRONT_PROVIDED_SOURCE);
      }};

      Map<String, String> aggregatedPerClusterMap = new HashMap<String, String>() {{
        put("cluster", applicationTags.getCluster() == null ? NULL_TAG_VAL :
            applicationTags.getCluster());
        put("jersey.resource.class", finalClassName);
        put("jersey" + ".resource.method", finalMethodName);
        put("source", WAVEFRONT_PROVIDED_SOURCE);
      }};

      Map<String, String> overallAggregatedPerClusterMap = new HashMap<String, String>() {{
        put("cluster", applicationTags.getCluster() == null ? NULL_TAG_VAL :
            applicationTags.getCluster());
        put("source", WAVEFRONT_PROVIDED_SOURCE);
      }};

      Map<String, String> aggregatedPerApplicationMap = new HashMap<String, String>() {{
        put("jersey.resource.class", finalClassName);
        put("jersey" + ".resource.method", finalMethodName);
        put("source", WAVEFRONT_PROVIDED_SOURCE);
      }};

      Map<String, String> overallAggregatedPerApplicationMap = new HashMap<String, String>() {{
        put("source", WAVEFRONT_PROVIDED_SOURCE);
      }};

      /*
       * Granular response metrics
       * 1) jersey.server.response.api.v2.alert.summary.GET.200.cumulative.count (Counter)
       * 2) jersey.server.response.api.v2.alert.summary.GET.200.aggregated_per_shard.count (DeltaCounter)
       * 3) jersey.server.response.api.v2.alert.summary.GET.200.aggregated_per_service.count (DeltaCounter)
       * 4) jersey.server.response.api.v2.alert.summary.GET.200.aggregated_per_cluster.count (DeltaCounter)
       * 5) jersey.server.response.api.v2.alert.summary.GET.200.aggregated_per_application.count (DeltaCounter)
       */
      wfJerseyReporter.incrementCounter(new MetricName(responseMetricKey +
          ".cumulative", completeTagsMap));
      if (applicationTags.getShard() != null) {
        wfJerseyReporter.incrementDeltaCounter(new MetricName(responseMetricKey +
            ".aggregated_per_shard", aggregatedPerShardMap));
      }
      wfJerseyReporter.incrementDeltaCounter(new MetricName(responseMetricKey +
          ".aggregated_per_service", aggregatedPerServiceMap));
      if (applicationTags.getCluster() != null) {
        wfJerseyReporter.incrementDeltaCounter(new MetricName(responseMetricKey +
            ".aggregated_per_cluster", aggregatedPerClusterMap));
      }
      wfJerseyReporter.incrementDeltaCounter(new MetricName(responseMetricKey +
          ".aggregated_per_application", aggregatedPerApplicationMap));

     /*
       * Overall error response metrics
       * 1) <prefix>.response.errors.aggregated_per_source (Counter)
       * 2) <prefix>.response.errors.aggregated_per_shard (DeltaCounter)
       * 3) <prefix>.response.errors.aggregated_per_service (DeltaCounter)
       * 4) <prefix>.response.errors.aggregated_per_cluster (DeltaCounter)
       * 5) <prefix>.response.errors.aggregated_per_application (DeltaCounter)
       */
      if (isErrorStatusCode(containerResponseContext)) {
        wfJerseyReporter.incrementCounter(new MetricName("response.errors",
            completeTagsMap));
        wfJerseyReporter.incrementCounter(new MetricName(
            "response.errors.aggregated_per_source", overallAggregatedPerSourceMap));
        if (applicationTags.getShard() != null) {
          wfJerseyReporter.incrementDeltaCounter(new MetricName(
              "response.errors.aggregated_per_shard", overallAggregatedPerShardMap));
        }
        wfJerseyReporter.incrementDeltaCounter(new MetricName(
            "response.errors.aggregated_per_service", overallAggregatedPerServiceMap));
        if (applicationTags.getCluster() != null) {
          wfJerseyReporter.incrementDeltaCounter(new MetricName(
              "response.errors.aggregated_per_cluster", overallAggregatedPerClusterMap));
        }
        wfJerseyReporter.incrementDeltaCounter(new MetricName(
            "response.errors.aggregated_per_application", overallAggregatedPerApplicationMap));
      }

      /*
       * Overall response metrics
       * 1) jersey.server.response.completed.aggregated_per_source.count (Counter)
       * 2) jersey.server.response.completed.aggregated_per_shard.count (DeltaCounter)
       * 3) jersey.server.response.completed.aggregated_per_service.count (DeltaCounter)
       * 3) jersey.server.response.completed.aggregated_per_cluster.count (DeltaCounter)
       * 5) jersey.server.response.completed.aggregated_per_application.count (DeltaCounter)
       */
      wfJerseyReporter.incrementCounter(new MetricName("response.completed.aggregated_per_source",
          overallAggregatedPerSourceMap));
      if (applicationTags.getShard() != null) {
        wfJerseyReporter.incrementDeltaCounter(new MetricName("response" +
            ".completed.aggregated_per_shard", overallAggregatedPerShardMap));
      }
      wfJerseyReporter.incrementDeltaCounter(new MetricName("response" +
          ".completed.aggregated_per_service", overallAggregatedPerServiceMap));
      if (applicationTags.getCluster() != null) {
        wfJerseyReporter.incrementDeltaCounter(new MetricName("response" +
            ".completed.aggregated_per_cluster", overallAggregatedPerClusterMap));
      }
      wfJerseyReporter.incrementDeltaCounter(new MetricName("response" +
          ".completed.aggregated_per_application", overallAggregatedPerApplicationMap));

      /*
       * WavefrontHistograms
       * 1) jersey.server.response.api.v2.alert.summary.GET.200.latency
       * 2) jersey.server.response.api.v2.alert.summary.GET.200.cpu_ns
       */
      long cpuNanos = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() -
          startTimeCpuNanos.get();
      wfJerseyReporter.updateHistogram(new MetricName(responseMetricKey + ".cpu_ns",
          completeTagsMap), cpuNanos);

      long apiLatency = System.currentTimeMillis() - startTime.get();
      wfJerseyReporter.updateHistogram(new MetricName(responseMetricKey + ".latency",
              completeTagsMap), apiLatency);
    }
  }

  private Pair<String, String> getClassAndMethodName(ExtendedUriInfo uriInfo) {
    String className = "unknown";
    String methodName = "unknown";

    if (uriInfo != null) {
      Class clazz = ((RoutingContext) uriInfo).getResourceClass();
      if (clazz != null) {
        className = clazz.getCanonicalName();
      }
      Method method = ((RoutingContext) uriInfo).getResourceMethod();
      if (method != null) {
        methodName = method.getName();
      }
    }
    return Pair.of(className, methodName);
  }

  private AtomicInteger getGaugeValue(MetricName metricName) {
    return gauges.computeIfAbsent(metricName, key -> {
      final AtomicInteger toReturn = new AtomicInteger();
      wfJerseyReporter.registerGauge(key, toReturn);
      return toReturn;
    });
  }

  private Map<String, String> getCompleteTagsMap(String finalClassName, String finalMethodName) {
    return new HashMap<String, String>() {{
      put("cluster", applicationTags.getCluster() == null ? NULL_TAG_VAL :
          applicationTags.getCluster());
      put("service", applicationTags.getService());
      put("shard", applicationTags.getShard() == null ? NULL_TAG_VAL : applicationTags.getShard());
      put("jersey.resource.class", finalClassName);
      put("jersey" + ".resource.method", finalMethodName);
    }};
  }

  private SpanContext parentSpanContext(ContainerRequestContext requestContext) {
    Span activeSpan = tracer.activeSpan();
    if (activeSpan != null) {
      return activeSpan.context();
    } else {
      return tracer.extract(
              Format.Builtin.HTTP_HEADERS,
              new ServerHeadersExtractTextMap(requestContext.getHeaders())
      );
    }
  }

  private void decorateRequest(ContainerRequestContext requestContext, Span span) {
    Tags.COMPONENT.set(span, JERSEY_SERVER_COMPONENT);
    Tags.HTTP_METHOD.set(span, requestContext.getMethod());
    String urlStr = null;
    URL url;
    try {
      url = requestContext.getUriInfo().getRequestUri().toURL();
      urlStr = url.toString();
    } catch (MalformedURLException e) {
      // ignoring returning null
    }
    if (urlStr != null) {
      Tags.HTTP_URL.set(span, urlStr);
    }
  }

  private void decorateResponse(ContainerResponseContext responseContext, Span span) {
    Tags.HTTP_STATUS.set(span, responseContext.getStatus());
    if (isErrorStatusCode(responseContext)) {
      Tags.ERROR.set(span, true);
    }
  }

  private boolean isErrorStatusCode(ContainerResponseContext containerResponseContext) {
    int statusCode = containerResponseContext.getStatus();
    return statusCode >= 400 && statusCode <= 599;
  }

  public class ServerHeadersExtractTextMap implements TextMap {

    private final MultivaluedMap<String, String> headers;

    ServerHeadersExtractTextMap(MultivaluedMap<String, String> headers) {
      this.headers = headers;
    }

    @Override
    public Iterator<Map.Entry<String, String>> iterator() {
      return new MultivaluedMapFlatIterator<>(headers.entrySet());
    }

    @Override
    public void put(String key, String value) {
      throw new UnsupportedOperationException(
              ServerHeadersExtractTextMap.class.getName() +" should only be used with Tracer.extract()");
    }
  }

  public static final class MultivaluedMapFlatIterator<K, V> implements Iterator<Map.Entry<K, V>> {
    private final Iterator<Map.Entry<K, List<V>>> mapIterator;
    private Map.Entry<K, List<V>> mapEntry;
    private Iterator listIterator;

    MultivaluedMapFlatIterator(Set<Map.Entry<K, List<V>>> multiValuesEntrySet) {
      this.mapIterator = multiValuesEntrySet.iterator();
    }

    public boolean hasNext() {
      return this.listIterator != null && this.listIterator.hasNext() || this.mapIterator.hasNext();
    }

    public Map.Entry<K, V> next() {
      if (this.mapEntry == null || !this.listIterator.hasNext() && this.mapIterator.hasNext()) {
        this.mapEntry = this.mapIterator.next();
        this.listIterator = ((List)this.mapEntry.getValue()).iterator();
      }

      return this.listIterator.hasNext() ?
              new AbstractMap.SimpleImmutableEntry(this.mapEntry.getKey(), this.listIterator.next()) :
              new AbstractMap.SimpleImmutableEntry(this.mapEntry.getKey(), (Object)null);
    }

    public void remove() {
      throw new UnsupportedOperationException();
    }
  }
}
