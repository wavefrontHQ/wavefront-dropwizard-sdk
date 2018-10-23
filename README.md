# Wavefront Jersey SDK

This SDK provides support for reporting out of the box metrics and histograms from your Jersey based microservices application. That data is reported to Wavefront via proxy or direct ingestion. That data will help you understand how your application is performing in production.

## Usage
If you are using Maven, add following maven dependency to your pom.xml
```
<dependency>
    <groupId>com.wavefront</groupId>
    <artifactId>wavefront-jersey-sdk-java</artifactId>
    <version>0.9.0</version>
</dependency>
```

## Jersey Filter
We will be gathering http request/response metrics and histograms using Jersey filter.
See https://jersey.github.io/documentation/latest/filters-and-interceptors.html for more details on Jersey Filter. Wavefront has defined its own Jersey filter and the below steps will help you instantiate WavefrontJerseyFilter which you can use in your Jersey application.

### Application Tags
Before you configure the SDK you need to decide the metadata that you wish to emit for those out of the box metrics and histograms. Each and every application should have the application tag defined. If the name of your application is Ordering application, then you can put that as the value for that tag.
```java
    /* Set the name of your Jersey based application that you wish to monitor */
    String application = "OrderingApp";
```
Jersey based application is composed of microservices. Each and every microservice in your application should have the service tag defined.
```java
    /* Set the name of your service, 
     * for instance - 'inventory' service for your OrderingApp */
    String service = "inventory";
```

You can also define optional tags (cluster and shard).
```java
    /* Optional cluster field, set it to 'us-west-2', assuming
     * your app is running in 'us-west-2' cluster */
    String cluster = "us-west-2";

    /* Optional shard field, set it to 'secondary', assuming your 
     * application has 2 shards - primary and secondary */
    String shard = "secondary";
```

You can add optional custom tags for your application.
```java
    /* Optional custom tags map */
    Map<String, String> customTags = new HashMap<String, String>() {{
      put("location", "Oregon");
      put("env", "Staging");
    }};
```
You can define the above metadata in your application YAML config file.
Now create ApplicationTags instance using the above metatdata.
```java
    /* Create ApplicationTags instance using the above metadata */
    ApplicationTags applicationTags = new ApplicationTags.Builder(application, service).
        cluster(cluster).shard(shard).customTags(customTags).build();
```

### WavefrontSender
We need to instantiate WavefrontSender 
(i.e. either WavefrontProxyClient or WavefrontDirectIngestionClient)
Refer to this page (https://github.com/wavefrontHQ/wavefront-sdk-java#wavefrontsender)
to instantiate WavefrontProxyClient or WavefrontDirectIngestionClient.
<br />
<br />
**Note:** If you are using more than one Wavefront SDK (i.e. wavefront-opentracing-sdk-java, wavefront-dropwizard-metrics-sdk-java, wavefront-jersey-sdk-java, wavefront-grpc-sdk-java etc.) that requires you to instantiate WavefrontSender, then you should instantiate the WavefrontSender only once and share that sender instance across multiple SDKs inside the same JVM.
If the SDKs will be installed on different JVMs, then you would need to instantiate one WavefrontSender per JVM.

### WavefrontTracer
To enable sending tracing spans from the SDK, we need to instantiate a WavefrontTracer. Refer to this page (https://github.com/wavefrontHQ/wavefront-opentracing-sdk-java#tracer) for more details. 

### WavefrontJerseyReporter
```java

    /* Create WavefrontJerseyReporter.Builder using applicationTags. */
    WavefrontJerseyReporter.Builder builder = new WavefrontJerseyReporter.Builder(applicationTags);

    /* Set the source for your metrics and histograms */
    builder.withSource("mySource");

    /* Optionally change the reporting frequency to 30 seconds, defaults to 1 min */
    builder.reportingIntervalSeconds(30);

    /* Create a WavefrontJerseyReporter using ApplicationTags metadata and WavefronSender */
    WavefrontJerseyReporter wfJerseyReporter = new WavefrontJerseyReporter.
        Builder(applicationTags).build(wavefrontSender);
```

### Construct WavefrontJerseyFilter
```java
    /* Now create a Wavefront Jersey Filter which you can add to your 
    * Jersey based (Dropwizard, Springboot etc.) application  */
    WavefrontJerseyFilter wfJerseyFilter = new WavefrontJerseyFilter.Builder(
        wfJerseyReporter, applicationTags).build();
    
    /* If you want to send tracing data and have WavefrontTracer configured */
    WavefrontJerseyFilter wfJerseyFilter = new WavefrontJerseyFilter.Builder(
        wfJerseyReporter, applicationTags).withTracer(tracer).build();
```

### Starting and stopping the reporter
```java
    /* Once the reporter and filter is instantiated, start the reporter */
    wfJerseyReporter.start();

    /* Before shutting down your Jersey application, stop your reporter */
    wfJerseyReporter.stop();
```

## Out of the box metrics and histograms for your Jersey based application.
Let's say your have a RESTful HTTP GET API that returns all the fulfilled orders. Let's assume this API is defined in inventory service for your Ordering application.
Below is the API handler for the HTTP GET method.
```java
@ApiOperation(value = "Get all the fulfilled orders")
@GET
@Path("/orders/fulfilled")
public List<Order> getAllFulfilledOrders() {
   ...
}
```

Let's assume this HTTP handler is 
1) part of 'Ordering' application 
2) running inside 'Inventory' microservice 
3) deployed in 'us-west-1' cluster 
4) serviced by 'primary' shard 
5) on source = host-1 
6) this API returns HTTP 200 status code

When this API is invoked following entities (i.e. metrics and histograms) are reported directly from your application to Wavefront.

### Request Gauges
|Entity Name| Entity Type|source|application|cluster|service|shard|jersey.resource.class|jersey.resource.method|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|-----:|-----:|
|jersey.server.request.inventory.orders.fulfilled.GET.inflight|Gauge|host-1|Ordering|us-west-1|Inventory|primary|com.ordering.InventoryWebResource|getAllFulfilledOrders|
|jersey.server.total_requests.inflight|Gauge|host-1|Ordering|us-west-1|Inventory|primary|n/a|n/a|

### Granular Response related metrics
|Entity Name| Entity Type|source|application|cluster|service|shard|jersey.resource.class|jersey.resource.method|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|-----:|-----:|
|jersey.server.response.inventory.orders.fulfilled.GET.200.cumulative.count|Counter|host-1|Ordering|us-west-1|Inventory|primary|com.ordering.InventoryWebResource|getAllFulfilledOrders|
|jersey.server.response.inventory.orders.fulfilled.GET.200.aggregated_per_shard.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|Inventory|primary|com.ordering.InventoryWebResource|getAllFulfilledOrders|
|jersey.server.response.inventory.orders.fulfilled.GET.200.aggregated_per_service.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|Inventory|n/a|com.ordering.InventoryWebResource|getAllFulfilledOrders|
|jersey.server.response.inventory.orders.fulfilled.GET.200.aggregated_per_cluster.count|DeltaCounter|wavefront-provided|Ordering|us-west-1|n/a|n/a|com.ordering.InventoryWebResource|getAllFulfilledOrders|
|jersey.server.response.inventory.orders.fulfilled.GET.200.aggregated_per_appliation.count|DeltaCounter|wavefront-provided|Ordering|n/a|n/a|n/a|com.ordering.InventoryWebResource|getAllFulfilledOrders|

### Granular Response related histograms
|Entity Name| Entity Type|source|application|cluster|service|shard|jersey.resource.class|jersey.resource.method|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|-----:|-----:|
|jersey.server.response.inventory.orders.fulfilled.GET.200.latency|WavefrontHistogram|host-1|Ordering|us-west-1|Inventory|primary|com.ordering.InventoryWebResource|getAllFulfilledOrders|
|jersey.server.response.inventory.orders.fulfilled.GET.200.cpu_ns|WavefrontHistogram|host-1|Ordering|us-west-1|Inventory|primary|com.ordering.InventoryWebResource|getAllFulfilledOrders|

### Overall Response related metrics
This includes all the completed requests that returned a response (i.e. success + errors).

|Entity Name| Entity Type|source|application|cluster|service|shard|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|
|jersey.server.response.completed.aggregated_per_source.count|Counter|host-1|Ordering|us-west-1|Inventory|primary|
|jersey.server.response.completed.aggregated_per_shard.count|DeltaCounter|wavefont-provided|Ordering|us-west-1|Inventory|primary|
|jersey.server.response.completed.aggregated_per_service.count|DeltaCounter|wavefont-provided|Ordering|us-west-1|Inventory|n/a|
|jersey.server.response.completed.aggregated_per_cluster.count|DeltaCounter|wavefont-provided|Ordering|us-west-1|n/a|n/a|
|jersey.server.response.completed.aggregated_per_application.count|DeltaCounter|wavefont-provided|Ordering|n/a|n/a|n/a|

### Overall Error Response related metrics
This includes all the completed requests that resulted in an error response (that is HTTP status code of 4xx or 5xx).

|Entity Name| Entity Type|source|application|cluster|service|shard|
| ------------- |:-------------:| -----:|-----:|-----:|-----:|-----:|
|jersey.server.response.errors.aggregated_per_source.count|Counter|host-1|Ordering|us-west-1|Inventory|primary|
|jersey.server.response.errors.aggregated_per_shard.count|DeltaCounter|wavefont-provided|Ordering|us-west-1|Inventory|primary|
|jersey.server.response.errors.aggregated_per_service.count|DeltaCounter|wavefont-provided|Ordering|us-west-1|Inventory|n/a|
|jersey.server.response.errors.aggregated_per_cluster.count|DeltaCounter|wavefont-provided|Ordering|us-west-1|n/a|n/a|
|jersey.server.response.errors.aggregated_per_application.count|DeltaCounter|wavefont-provided|Ordering|n/a|n/a|n/a|

### Tracing Spans

Every span will have start time in millisec along with duration in millisec. The following table includes all the attributes of generated tracing span.  

| Span Tag Key          | Span Tag Value                         |
| --------------------- | -------------------------------------- |
| traceId               | 4a3dc181-d4ac-44bc-848b-133bb3811c31   |
| parent                | q908ddfe-4723-40a6-b1d3-1e85b60d9016   |
| followsFrom           | b768ddfe-4723-40a6-b1d3-1e85b60d9016   |
| spanId                | c908ddfe-4723-40a6-b1d3-1e85b60d9016   |
| component             | jersey-server                          |
| span.kind             | server                                 |
| application           | OrderingApp                            |
| service               | inventory                              |
| cluster               | us-west-2                              |
| shard                 | secondary                              |
| location              | Oregon (*custom tag)                   |
| env                   | Staging (*custom tag)                  |
| http.method           | GET                                    |
| http.url              | http://{SERVER_ADDR}/orders/fulfilled  |
| http.status_code      | 502                                    |
| error                 | True                                   |
| jersey.path           | "/orders/fulfilled"                    |
| jersey.resource.class | com.sample.ordering.InventryController |

